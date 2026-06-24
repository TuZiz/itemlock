package ym.untitled

import org.bukkit.Bukkit
import org.bukkit.command.PluginCommand
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ItemLockPlugin : JavaPlugin() {

    lateinit var ioExecutor: ExecutorService
        private set

    lateinit var configService: ItemLockConfig
        private set

    lateinit var langService: ItemLockLang
        private set

    lateinit var storage: ItemLockStorage
        private set

    lateinit var scheduler: ItemLockScheduler
        private set

    lateinit var manager: ItemLockManager
        private set

    private var listener: ItemLockListener? = null
    private var command: ItemLockCommand? = null

    override fun onEnable() {
        ioExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ItemLock-IO").apply { isDaemon = true }
        }

        configService = ItemLockConfig(this, ioExecutor)
        langService = ItemLockLang(this, ioExecutor)
        storage = ItemLockStorage(this, ioExecutor)
        scheduler = ItemLockScheduler(this)
        manager = ItemLockManager(this, configService, langService, storage)

        val configFuture = configService.reloadAsync()
        val langFuture = langService.reloadAsync()
        val storageFuture = storage.loadAsync()

        CompletableFuture.allOf(configFuture, langFuture, storageFuture).whenComplete { _, error ->
            scheduler.runGlobal {
                if (!isEnabled) {
                    return@runGlobal
                }
                if (error != null) {
                    logger.warning("ItemLock asynchronous startup finished with warnings: ${error.message}")
                }
                registerRuntime()
                val platform = if (scheduler.isFolia()) "Folia" else "Spigot/Paper"
                logger.info("ItemLock enabled for $platform. Loaded ${storage.recordCount()} tracked item records.")
            }
        }
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        storage.flushAsync()
        if (::ioExecutor.isInitialized) {
            ioExecutor.shutdown()
        }
    }

    fun runSync(task: () -> Unit) {
        scheduler.runGlobal(task)
    }

    fun runPlayer(player: org.bukkit.entity.Player, task: () -> Unit) {
        scheduler.runPlayer(player, task)
    }

    fun runSender(sender: org.bukkit.command.CommandSender, task: () -> Unit) {
        scheduler.runSender(sender, task)
    }

    private fun registerRuntime() {
        if (listener != null) {
            return
        }

        val createdListener = ItemLockListener(this, manager)
        listener = createdListener
        server.pluginManager.registerEvents(createdListener, this)

        val createdCommand = ItemLockCommand(this, manager, configService)
        command = createdCommand
        getCommandOrWarn("itemlock")?.let { pluginCommand: PluginCommand ->
            pluginCommand.setExecutor(createdCommand)
            pluginCommand.tabCompleter = createdCommand
        }
    }

    private fun getCommandOrWarn(name: String): PluginCommand? {
        val pluginCommand = getCommand(name)
        if (pluginCommand == null) {
            logger.warning("Command '$name' is missing from plugin.yml.")
        }
        return pluginCommand
    }
}
