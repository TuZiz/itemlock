package ym.untitled

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ItemLockListener(
    private val plugin: ItemLockPlugin,
    private val manager: ItemLockManager
) : Listener {

    private val deathKeptItems = ConcurrentHashMap<UUID, MutableList<ItemStack>>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val item = event.item.itemStack
        val binding = manager.bindingOf(item)
        if (binding != null && !manager.isOwnedBy(item, player) && !manager.hasBypass(player)) {
            event.isCancelled = true
            deny(player, manager.replaceOwner(messages().cannotTake, binding.ownerName))
            return
        }
        manager.bindIfEligible(item, player, sendMessage = true)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (!managerConfig().protection.cancelDrop) {
            return
        }
        if (!manager.isBound(event.itemDrop.itemStack)) {
            return
        }
        event.isCancelled = true
        deny(event.player, messages().cannotDrop)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val cursor = event.cursor
        val current = event.currentItem

        if (event.rawSlot == InventoryView.OUTSIDE && manager.isBound(cursor)) {
            event.isCancelled = true
            deny(player, messages().cannotDrop)
            return
        }

        if (event.click == ClickType.LEFT && manager.scrollType(cursor) != null && !isAir(current)) {
            event.isCancelled = true
            applyScrollClick(player, event, current, cursor)
            return
        }

        if (isDropClick(event.click) && manager.isBound(current)) {
            event.isCancelled = true
            deny(player, messages().cannotDrop)
            return
        }

        if (event.click == ClickType.NUMBER_KEY) {
            val hotbarItem = player.inventory.getItem(event.hotbarButton)
            if (shouldBlockForeign(player, hotbarItem, moving = true)) {
                event.isCancelled = true
                deny(player, messages().cannotTake)
                return
            }
        }

        if (shouldBlockForeign(player, current, moving = true)) {
            event.isCancelled = true
            deny(player, messages().cannotTake)
            return
        }

        if (shouldBlockForeign(player, cursor, moving = true)) {
            event.isCancelled = true
            deny(player, messages().cannotTake)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val cursor = event.oldCursor
        if (manager.scrollType(cursor) == null || event.rawSlots.size != 1) {
            return
        }
        val rawSlot = event.rawSlots.first()
        val target = event.view.getItem(rawSlot)
        if (isAir(target)) {
            return
        }
        event.isCancelled = true
        plugin.runPlayer(player) {
            applyScrollDrag(player, event.view, rawSlot, cursor)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        if (!managerConfig().protection.cancelHopperMove) {
            return
        }
        if (manager.isBound(event.item)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        if (!managerConfig().protection.keepBoundOnDeath || event.keepInventory) {
            return
        }
        val player = event.entity
        val kept = mutableListOf<ItemStack>()
        val iterator = event.drops.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val binding = manager.bindingOf(item) ?: continue
            if (binding.ownerUuid != player.uniqueId) {
                continue
            }
            kept.add(item.clone())
            iterator.remove()
        }
        if (kept.isNotEmpty()) {
            deathKeptItems.compute(player.uniqueId) { _, existing ->
                val list = existing ?: mutableListOf()
                list.addAll(kept)
                list
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        val kept = deathKeptItems.remove(event.player.uniqueId) ?: return
        plugin.runPlayer(event.player) {
            val leftovers = event.player.inventory.addItem(*kept.toTypedArray())
            leftovers.values.forEach { leftover ->
                event.player.world.dropItemNaturally(event.player.location, leftover)
            }
            event.player.updateInventory()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val kept = deathKeptItems.remove(event.player.uniqueId) ?: return
        plugin.runPlayer(event.player) {
            kept.forEach { item ->
                event.player.world.dropItemNaturally(event.player.location, item)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val item = event.item ?: return
        if (shouldBlockForeign(event.player, item, moving = false)) {
            event.isCancelled = true
            deny(event.player, messages().cannotUse)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val item = player.inventory.getItem(event.newSlot)
        if (shouldBlockForeign(player, item, moving = false)) {
            event.isCancelled = true
            deny(player, messages().cannotUse)
        } else {
            manager.bindIfEligible(item, player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (shouldBlockForeign(player, event.mainHandItem, moving = false) ||
            shouldBlockForeign(player, event.offHandItem, moving = false)
        ) {
            event.isCancelled = true
            deny(player, messages().cannotUse)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val item = player.inventory.itemInMainHand
        if (shouldBlockForeign(player, item, moving = false)) {
            event.isCancelled = true
            deny(player, messages().cannotUse)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (shouldBlockForeign(event.player, item, moving = false)) {
            event.isCancelled = true
            deny(event.player, messages().cannotUse)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        manager.scanInventoryForAutoBind(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        plugin.runPlayer(event.player) {
            manager.scanInventoryForAutoBind(event.player)
        }
    }

    private fun applyScrollClick(player: Player, event: InventoryClickEvent, target: ItemStack?, cursor: ItemStack?) {
        val result = tryApplyScroll(player, target, cursor)
        if (result) {
            event.currentItem = target
            event.cursor = manager.consumeOne(cursor)
            player.updateInventory()
        }
    }

    private fun applyScrollDrag(player: Player, view: InventoryView, rawSlot: Int, cursor: ItemStack?) {
        val target = view.getItem(rawSlot)
        val result = tryApplyScroll(player, target, cursor)
        if (result) {
            view.setItem(rawSlot, target)
            player.setItemOnCursor(manager.consumeOne(cursor))
            player.updateInventory()
        }
    }

    private fun tryApplyScroll(player: Player, target: ItemStack?, scroll: ItemStack?): Boolean {
        val text = messages()
        return when (manager.scrollType(scroll)) {
            ScrollType.BIND -> tryApplyBindScroll(player, target, text)
            ScrollType.UNBIND -> tryApplyUnbindScroll(player, target, text)
            null -> false
        }
    }

    private fun tryApplyBindScroll(player: Player, target: ItemStack?, text: ItemLockLang.MessageText): Boolean {
        if (manager.bindingOf(target) != null) {
            deny(player, text.scrollAlreadyBound)
            return false
        }
        val result = manager.bindItem(target, player)
        return when (result) {
            ItemLockManager.OperationResult.SUCCESS -> {
                manager.play(player, managerConfig().sounds.success)
                manager.actionBar(player, text.bindScrollSuccess)
                true
            }
            ItemLockManager.OperationResult.ALREADY_BOUND -> {
                deny(player, text.scrollAlreadyBound)
                false
            }
            else -> {
                deny(player, text.noItem)
                false
            }
        }
    }

    private fun tryApplyUnbindScroll(player: Player, target: ItemStack?, text: ItemLockLang.MessageText): Boolean {
        val binding = manager.bindingOf(target)
        if (binding == null) {
            deny(player, text.scrollNotBound)
            return false
        }
        if (managerConfig().unbind.ownerOnlyScroll && binding.ownerUuid != player.uniqueId && !manager.hasBypass(player)) {
            deny(player, manager.replaceOwner(text.notOwner, binding.ownerName))
            return false
        }
        val result = manager.unbindItem(target)
        return when (result) {
            ItemLockManager.OperationResult.SUCCESS -> {
                manager.play(player, managerConfig().sounds.success)
                manager.actionBar(player, text.scrollSuccess)
                true
            }
            ItemLockManager.OperationResult.DENIED -> {
                deny(player, text.scrollDenied)
                false
            }
            else -> {
                deny(player, text.scrollNotBound)
                false
            }
        }
    }

    private fun shouldBlockForeign(player: Player, item: ItemStack?, moving: Boolean): Boolean {
        val settings = managerConfig()
        if (manager.hasBypass(player)) {
            return false
        }
        if (moving && !settings.protection.blockForeignMove) {
            return false
        }
        if (!moving && !settings.protection.blockForeignUse) {
            return false
        }
        return manager.bindingOf(item)?.ownerUuid?.let { it != player.uniqueId } == true
    }

    private fun deny(player: Player, message: String) {
        val settings = managerConfig()
        manager.actionBar(player, message)
        manager.play(player, settings.sounds.failure)
    }

    private fun isDropClick(click: ClickType): Boolean {
        return click == ClickType.DROP || click == ClickType.CONTROL_DROP
    }

    private fun isAir(item: ItemStack?): Boolean {
        return item == null || item.type == Material.AIR || item.amount <= 0
    }

    private fun managerConfig(): ItemLockConfig.Settings = plugin.configService.settings()

    private fun messages(): ItemLockLang.MessageText = plugin.langService.text().messages
}
