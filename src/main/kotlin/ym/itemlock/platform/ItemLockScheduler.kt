package ym.itemlock.platform

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class ItemLockScheduler(private val plugin: JavaPlugin) {

    private val folia = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    fun isFolia(): Boolean = folia

    fun runGlobal(task: () -> Unit) {
        if (!folia) {
            if (Bukkit.isPrimaryThread()) {
                task()
            } else {
                plugin.server.scheduler.runTask(plugin, Runnable { task() })
            }
            return
        }

        val scheduler = runCatching {
            Bukkit::class.java.getMethod("getGlobalRegionScheduler").invoke(null)
        }.getOrNull()

        val execute = scheduler?.javaClass?.methods?.firstOrNull { method ->
            method.name == "execute" && method.parameterTypes.size == 2
        }

        if (scheduler != null && execute != null) {
            execute.invoke(scheduler, plugin, Runnable { task() })
        } else {
            plugin.logger.warning("Folia global scheduler was not found; running task inline as a last resort.")
            task()
        }
    }

    fun runPlayer(player: Player, task: () -> Unit) {
        if (!folia) {
            runGlobal(task)
            return
        }

        val scheduler = player.javaClass.methods
            .firstOrNull { it.name == "getScheduler" && it.parameterTypes.isEmpty() }
            ?.invoke(player)

        val execute = scheduler?.javaClass?.methods?.firstOrNull { method ->
            method.name == "execute" && method.parameterTypes.size == 4
        }

        if (scheduler != null && execute != null) {
            execute.invoke(
                scheduler,
                plugin,
                Runnable { task() },
                Runnable { },
                1L
            )
        } else {
            plugin.logger.warning("Folia entity scheduler was not found for ${player.name}; running task through global scheduler.")
            runGlobal(task)
        }
    }

    fun runPlayerNextTick(player: Player, task: () -> Unit) {
        if (!folia) {
            plugin.server.scheduler.runTask(plugin, Runnable { task() })
            return
        }

        val scheduler = player.javaClass.methods
            .firstOrNull { it.name == "getScheduler" && it.parameterTypes.isEmpty() }
            ?.invoke(player)

        val execute = scheduler?.javaClass?.methods?.firstOrNull { method ->
            method.name == "execute" && method.parameterTypes.size == 4
        }

        if (scheduler != null && execute != null) {
            execute.invoke(
                scheduler,
                plugin,
                Runnable { task() },
                Runnable { },
                1L
            )
        } else {
            plugin.logger.warning("Folia entity scheduler was not found for ${player.name}; running next-tick task through global scheduler.")
            runGlobal(task)
        }
    }

    fun runSender(sender: CommandSender, task: () -> Unit) {
        val player = sender as? Player
        if (player != null) {
            runPlayer(player, task)
        } else {
            runGlobal(task)
        }
    }
}
