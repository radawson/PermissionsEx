/*
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.stellardrift.permissionsex.fabric

import ca.stellardrift.permissionsex.BaseDirectoryScope
import ca.stellardrift.permissionsex.ImplementationInterface
import ca.stellardrift.permissionsex.PermissionsEx
import ca.stellardrift.permissionsex.PermissionsEx.GLOBAL_CONTEXT
import ca.stellardrift.permissionsex.PermissionsEx.SUBJECTS_DEFAULTS
import ca.stellardrift.permissionsex.PermissionsEx.SUBJECTS_USER
import ca.stellardrift.permissionsex.config.FilePermissionsExConfiguration
import ca.stellardrift.permissionsex.hikariconfig.createHikariDataSource
import ca.stellardrift.permissionsex.logging.TranslatableLogger
import ca.stellardrift.permissionsex.smartertext.CallbackController
import ca.stellardrift.permissionsex.util.MinecraftProfile
import ca.stellardrift.permissionsex.util.Translations.t
import ca.stellardrift.permissionsex.util.castMap
import ca.stellardrift.permissionsex.util.command.CommandSpec
import com.google.common.collect.Iterables
import com.mojang.authlib.Agent
import com.mojang.authlib.GameProfile
import com.mojang.authlib.ProfileLookupCallback
import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.server.ServerStartCallback
import net.fabricmc.fabric.api.event.server.ServerStopCallback
import net.fabricmc.fabric.api.registry.CommandRegistry
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.ModContainer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import ninja.leaping.configurate.hocon.HoconConfigurationLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.Supplier
import javax.sql.DataSource

private const val MOD_ID: String = "permissionsex"
object PermissionsExMod : ImplementationInterface, ModInitializer {

    val callbackController = CallbackController()
    val manager: PermissionsEx<*>
    get() {
        val temp = _manager
        if (temp != null) {
            return temp
        } else {
            throw IllegalStateException("PermissionsEx has not yet been initialized!")
        }
    }
    private var _manager: PermissionsEx<*>? = null
    private lateinit var container: ModContainer
    private lateinit var dataDir: Path
    lateinit var server: MinecraftServer private set

    private val _logger = TranslatableLogger.forLogger(LoggerFactory.getLogger(MOD_ID))
    private val exec = Executors.newCachedThreadPool()
    private val commands = mutableSetOf<Supplier<Set<CommandSpec>>>()


    override fun onInitialize() {
        this.dataDir = FabricLoader.getInstance().configDirectory.toPath().resolve(MOD_ID)
        this.container = FabricLoader.getInstance().getModContainer(MOD_ID)
            .orElseThrow { IllegalStateException("Mod container for PermissionsEx was not available in init!") }
        logger.prefix = "[${container.metadata.name}] "

        logger.info(t("Loaded mod v%s", container.metadata.version.friendlyString))
        ServerStartCallback.EVENT.register(ServerStartCallback {init(it) })
        ServerStopCallback.EVENT.register(ServerStopCallback {  shutdown(it) })
        CommandRegistry.INSTANCE.register(true) {
            tryRegisterCommands(it)
        }
        registerWorldEdit()
    }

    private fun init(gameInstance: MinecraftServer) {
        this.server = gameInstance
        Files.createDirectories(dataDir)

        val loader = HoconConfigurationLoader.builder()
            .setPath(dataDir.resolve("$MOD_ID.conf"))
            .build()

        try {
            _manager = PermissionsEx(FilePermissionsExConfiguration.fromLoader(loader), this)
        } catch (e: Exception) {
            logger.error(t("Unable to enable PEX"), e)
            server.stop(false)
            return
        }
        tryRegisterCommands()

        manager.registerContextDefinitions(WorldContextDefinition,
            DimensionContextDefinition,
            RemoteIpContextDefinition,
            LocalIpContextDefinition,
            LocalHostContextDefinition,
            LocalPortContextDefinition)
        manager.getSubjects(SUBJECTS_USER).typeInfo = UserSubjectTypeDefinition()
        manager.getSubjects(SUBJECTS_DEFAULTS).transientData().update(SUBJECTS_SYSTEM) {
            it.setDefaultValue(GLOBAL_CONTEXT, 1)
        }
        tryRegisterCommands()
        logger.info(t("v%s successfully enabled! Welcome!", container.metadata.version))
    }

    private fun shutdown(server: MinecraftServer) {
        val manager = _manager
        if (manager != null) {
            manager.close()
            _manager = null
        }
        this.exec.shutdown()
        try {
            this.exec.awaitTermination(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            logger.error(t("Unable to shut down PermissionsEx executor in 10 seconds, remaining tasks will be killed"))
            this.exec.shutdownNow()
        }
    }

    fun handlePlayerJoin(player: ServerPlayerEntity) {
        manager.getSubjects(SUBJECTS_USER).get(player.uuidAsString).thenAccept {
            // Update name option
            it.data().cache.isRegistered(it.identifier.value).thenAccept {isReg ->
                if (isReg) {
                    it.data().update {data ->
                        data.setOption(GLOBAL_CONTEXT, "name", player.name.asString())
                    }
                }
            }

            // Add listener to re-send command tree on a permission update
            it.registerListener { newSubj ->
                newSubj.associatedObject.castMap<ServerPlayerEntity> {
                    server.playerManager.sendCommandTree(this)
                }
            }
        }
    }

    fun handlePlayerQuit(player: ServerPlayerEntity) {
        callbackController.clearOwnedBy(player.uuid)
        _manager?.getSubjects(SUBJECTS_USER)?.uncache(player.uuidAsString)
    }

    override fun getBaseDirectory(scope: BaseDirectoryScope): Path {
        return when (scope) {
            BaseDirectoryScope.CONFIG -> dataDir
            BaseDirectoryScope.JAR -> FabricLoader.getInstance().gameDirectory.toPath().resolve("mods")
            BaseDirectoryScope.SERVER -> FabricLoader.getInstance().gameDirectory.toPath()
            BaseDirectoryScope.WORLDS -> server.levelStorage.savesDirectory
        }
    }

    override fun getLogger(): TranslatableLogger {
        return _logger
    }

    override fun getDataSourceForURL(url: String): DataSource {
        return createHikariDataSource(url, dataDir)
    }

    override fun getAsyncExecutor(): Executor {
        return exec
    }

    override fun registerCommands(commandSupplier: Supplier<Set<CommandSpec>>) {
        synchronized (commands) {
                commands.add(commandSupplier)
                tryRegisterCommands()
        }
    }

    private fun tryRegisterCommands(possibleDispatch: CommandDispatcher<ServerCommandSource>? = null) {
        val dispatcher = if (possibleDispatch == null && this::server.isInitialized) {
            server.commandManager.dispatcher
        } else {
            possibleDispatch
        }
        if (dispatcher != null && _manager != null) {
            synchronized(commands) {
                commands.forEach {
                    it.get().forEach { cmd ->
                        registerCommand(cmd, dispatcher)
                    }
                }
                commands.clear() // TODO: Remove if we stop re-creating the PEX instance
            }
        }
    }

    override fun getImplementationCommands(): Set<CommandSpec> {
        return setOf(callbackController.createCommand(manager))
    }

    override fun getVersion(): String {
        return container.metadata.version.friendlyString
    }

    override fun lookupMinecraftProfilesByName(
        namesIter: Iterable<String>,
        action: Function<MinecraftProfile, CompletableFuture<Void>>
    ): CompletableFuture<Int> {
        val futures = mutableListOf<CompletableFuture<Void>>()
        return CompletableFuture.supplyAsync(Supplier {
            val names = Iterables.toArray(namesIter, String::class.java)
            val state = CountDownLatch(names.size)
            val callback = PEXProfileLookupCallback(state, action, futures)
            this.server.gameProfileRepo.findProfilesByNames(names, Agent.MINECRAFT, callback)
            state.await()
            futures.size
        }, asyncExecutor).thenCombine(CompletableFuture.allOf(*futures.toTypedArray())) { count, _ -> count }
    }

}

internal class PEXProfileLookupCallback(private val state: CountDownLatch, private val action: Function<MinecraftProfile, CompletableFuture<Void>>, val futures: MutableList<CompletableFuture<Void>>): ProfileLookupCallback {
    override fun onProfileLookupSucceeded(profile: GameProfile) {
        try {
            futures.add(action.apply(profile as MinecraftProfile))
        } finally {
            state.countDown()
        }
    }

    override fun onProfileLookupFailed(profile: GameProfile, exception: java.lang.Exception) {
        state.countDown()
        PermissionsExMod.logger.error(t("Unable to resolve profile %s due to %s", profile, exception.message), exception)
    }

}
