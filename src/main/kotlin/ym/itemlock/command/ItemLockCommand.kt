package ym.itemlock.command

import ym.itemlock.bootstrap.ItemLockPlugin
import ym.itemlock.config.ItemLockConfig
import ym.itemlock.lang.ItemLockLang
import ym.itemlock.model.ScrollType
import ym.itemlock.service.ItemLockManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ItemLockCommand(
    private val plugin: ItemLockPlugin,
    private val manager: ItemLockManager,
    private val config: ItemLockConfig
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "bind" -> bind(sender)
            "unbind" -> unbind(sender)
            "scroll" -> scroll(sender, args)
            "reload" -> reload(sender)
            else -> help(sender, label)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size == 1) {
            return listOf("bind", "unbind", "scroll", "reload")
                .filter { it.startsWith(args[0], ignoreCase = true) && canUse(sender, permissionFor(it)) }
                .toMutableList()
        }
        if (args.size == 2 && args[0].equals("scroll", ignoreCase = true)) {
            return listOf("bind", "unbind", "1", "8", "16", "32", "64")
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .toMutableList()
        }
        if (args.size == 3 && args[0].equals("scroll", ignoreCase = true)) {
            val suggestions = if (isScrollType(args[1])) {
                listOf("1", "8", "16", "32", "64") + Bukkit.getOnlinePlayers().map { it.name }
            } else {
                Bukkit.getOnlinePlayers().map { it.name }
            }
            return suggestions
                .filter { it.startsWith(args[2], ignoreCase = true) }
                .toMutableList()
        }
        if (args.size == 4 && args[0].equals("scroll", ignoreCase = true) && isScrollType(args[1])) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[3], ignoreCase = true) }
                .toMutableList()
        }
        return mutableListOf()
    }

    private fun bind(sender: CommandSender) {
        if (!canUse(sender, "itemlock.bind")) {
            send(sender, messages().noPermission)
            return
        }
        val player = sender as? Player
        if (player == null) {
            send(sender, messages().playerOnly)
            return
        }
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR || item.amount <= 0) {
            send(sender, messages().noItem)
            return
        }
        when (manager.bindItem(item, player)) {
            ItemLockManager.OperationResult.SUCCESS -> send(player, manager.replace(messages().bindSuccess, player))
            ItemLockManager.OperationResult.ALREADY_BOUND -> send(player, messages().alreadyBound)
            else -> send(player, messages().noItem)
        }
    }

    private fun unbind(sender: CommandSender) {
        if (!canUse(sender, "itemlock.unbind")) {
            send(sender, messages().noPermission)
            return
        }
        val player = sender as? Player
        if (player == null) {
            send(sender, messages().playerOnly)
            return
        }
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR || item.amount <= 0) {
            send(sender, messages().noItem)
            return
        }
        when (manager.unbindItem(item)) {
            ItemLockManager.OperationResult.SUCCESS -> send(player, messages().unbindSuccess)
            ItemLockManager.OperationResult.NOT_BOUND -> send(player, messages().notBound)
            ItemLockManager.OperationResult.DENIED -> send(player, messages().scrollDenied)
            else -> send(player, messages().noItem)
        }
    }

    private fun scroll(sender: CommandSender, args: Array<out String>) {
        if (!canUse(sender, "itemlock.scroll")) {
            send(sender, messages().noPermission)
            return
        }
        val request = parseScrollRequest(args)
        val target = request.playerName?.let { Bukkit.getPlayerExact(it) } ?: sender as? Player
        if (target == null) {
            send(sender, messages().playerOnly)
            return
        }
        plugin.runPlayer(target) {
            target.inventory.addItem(manager.createScroll(request.amount, request.type)).values.forEach { leftover ->
                target.world.dropItemNaturally(target.location, leftover)
            }
            val targetName = target.name
            val scrollLabel = when (request.type) {
                ScrollType.BIND -> messages().bindScrollLabel
                ScrollType.UNBIND -> messages().unbindScrollLabel
            }
            plugin.runSender(sender) {
                send(
                    sender,
                    messages().scrollGive
                        .replace("{player}", targetName)
                        .replace("{amount}", request.amount.toString())
                        .replace("{scroll}", scrollLabel)
                )
            }
        }
    }

    private fun reload(sender: CommandSender) {
        if (!canUse(sender, "itemlock.reload")) {
            send(sender, messages().noPermission)
            return
        }
        send(sender, messages().reloadStarted)
        val configFuture = config.reloadAsync()
        val langFuture = plugin.langService.reloadAsync()
        java.util.concurrent.CompletableFuture.allOf(configFuture, langFuture).whenComplete { _, error ->
            plugin.runSender(sender) {
                if (error != null) {
                    send(sender, messages().reloadFailed.replace("{error}", error.message ?: "unknown"))
                } else {
                    send(sender, messages().reloadDone)
                }
            }
        }
    }

    private fun help(sender: CommandSender, label: String) {
        send(sender, messages().helpBind.replace("{label}", label))
        send(sender, messages().helpUnbind.replace("{label}", label))
        if (canUse(sender, "itemlock.scroll")) {
            send(sender, messages().helpScroll.replace("{label}", label))
        }
        if (canUse(sender, "itemlock.reload")) {
            send(sender, messages().helpReload.replace("{label}", label))
        }
    }

    private fun send(sender: CommandSender, message: String) {
        sender.sendMessage(messages().prefix + message)
    }

    private fun messages(): ItemLockLang.MessageText = plugin.langService.text().messages

    private fun parseScrollRequest(args: Array<out String>): ScrollRequest {
        var index = 1
        val type = scrollTypeOf(args.getOrNull(index)).also {
            if (it != null) {
                index++
            }
        } ?: ScrollType.UNBIND

        val first = args.getOrNull(index)
        val amount = first?.toIntOrNull()?.coerceIn(1, 64) ?: 1
        val playerName = if (first?.toIntOrNull() != null) {
            args.getOrNull(index + 1)
        } else {
            first
        }
        return ScrollRequest(type, amount, playerName)
    }

    private fun scrollTypeOf(value: String?): ScrollType? {
        return when (value?.lowercase()) {
            "bind", "binding", "绑定" -> ScrollType.BIND
            "unbind", "unbinding", "remove", "解绑" -> ScrollType.UNBIND
            else -> null
        }
    }

    private fun isScrollType(value: String?): Boolean = scrollTypeOf(value) != null

    private data class ScrollRequest(
        val type: ScrollType,
        val amount: Int,
        val playerName: String?
    )

    private fun canUse(sender: CommandSender, permission: String): Boolean {
        return sender.hasPermission(permission)
    }

    private fun permissionFor(subCommand: String): String {
        return when (subCommand.lowercase()) {
            "bind" -> "itemlock.bind"
            "unbind" -> "itemlock.unbind"
            "scroll" -> "itemlock.scroll"
            "reload" -> "itemlock.reload"
            else -> "itemlock.command"
        }
    }
}
