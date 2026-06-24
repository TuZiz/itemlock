package ym.itemlock.listener

import ym.itemlock.bootstrap.ItemLockPlugin
import ym.itemlock.config.ItemLockConfig
import ym.itemlock.lang.ItemLockLang
import ym.itemlock.model.ScrollType
import ym.itemlock.service.ItemLockManager
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.InventoryType.SlotType
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
    private val lastKillWeapons = ConcurrentHashMap<UUID, WeaponHit>()
    private val projectileWeapons = ConcurrentHashMap<UUID, WeaponHit>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val item = event.item.itemStack
        val foreignOwner = manager.foreignOwnerName(item, player)
        if (foreignOwner != null && !manager.hasBypass(player)) {
            event.isCancelled = true
            deny(player, manager.replaceOwner(messages().cannotTake, foreignOwner))
            return
        }
        manager.prepareAutoBindIfEligible(item, player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (!managerConfig().protection.cancelDrop) {
            return
        }
        if (!manager.isBoundOrPending(event.itemDrop.itemStack)) {
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

        if (event.rawSlot == InventoryView.OUTSIDE && manager.isBoundOrPending(cursor)) {
            event.isCancelled = true
            deny(player, messages().cannotDrop)
            return
        }

        if (event.click == ClickType.LEFT && manager.scrollType(cursor) != null && !isAir(current)) {
            event.isCancelled = true
            applyScrollClick(player, event, current, cursor)
            return
        }

        if (isDropClick(event.click) && manager.isBoundOrPending(current)) {
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
            return
        }

        if (mayEquipArmor(event)) {
            plugin.runPlayerNextTick(player) {
                bindEquippedItems(player)
            }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArmorDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlots.any { rawSlot -> event.view.getSlotType(rawSlot) == SlotType.ARMOR }) {
            plugin.runPlayerNextTick(player) {
                bindEquippedItems(player)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        if (!managerConfig().protection.cancelHopperMove) {
            return
        }
        if (manager.isBoundOrPending(event.item)) {
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
            return
        }
        if (mayEquipByInteract(item)) {
            plugin.runPlayerNextTick(event.player) {
                bindEquippedItems(event.player)
            }
        }
        manager.bindPendingOnUse(item, event.player, ItemLockManager.BindAction.INTERACT)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val item = player.inventory.getItem(event.newSlot)
        if (shouldBlockForeign(player, item, moving = false)) {
            event.isCancelled = true
            deny(player, messages().cannotUse)
        } else {
            manager.prepareAutoBindIfEligible(item, player)
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
            return
        }
        plugin.runPlayerNextTick(player) {
            bindOffHandArmor(player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        cleanupExpired(lastKillWeapons)
        val damageWeapon = damageWeapon(event.damager) ?: return
        val player = damageWeapon.player
        val item = damageWeapon.item
        if (shouldBlockForeign(player, item, moving = false)) {
            event.isCancelled = true
            deny(player, messages().cannotUse)
            return
        }
        manager.prepareAutoBindIfEligible(item, player)
        val trackingKey = damageWeapon.itemKey ?: manager.trackingKey(item)
        if (trackingKey != null) {
            lastKillWeapons[event.entity.uniqueId] = WeaponHit(
                playerId = player.uniqueId,
                itemKey = trackingKey,
                at = System.currentTimeMillis()
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShootBow(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val projectile = event.projectile as? Projectile ?: return
        val weapon = heldWeaponFor(player, event.bow) ?: return
        trackProjectileWeapon(projectile, player, weapon)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        if (projectileWeapons.containsKey(projectile.uniqueId)) {
            return
        }
        val player = projectile.shooter as? Player ?: return
        val weapon = player.inventory.itemInMainHand
        if (isAir(weapon)) {
            return
        }
        trackProjectileWeapon(projectile, player, weapon)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKill(event: EntityDeathEvent) {
        val player = event.entity.killer ?: return
        val weapon = trackedKillWeapon(event.entity, player) ?: return
        manager.bindPendingOnUse(weapon, player, ItemLockManager.BindAction.KILL)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        manager.bindPendingOnUse(event.player.inventory.itemInMainHand, event.player, ItemLockManager.BindAction.BLOCK_BREAK)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (shouldBlockForeign(event.player, item, moving = false)) {
            event.isCancelled = true
            deny(event.player, messages().cannotUse)
            return
        }
        manager.bindPendingOnUse(item, event.player, ItemLockManager.BindAction.INTERACT)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        manager.scanInventoryForPendingBind(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        plugin.runPlayer(event.player) {
            manager.scanInventoryForPendingBind(event.player)
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
        val result = manager.markPendingBind(target, player)
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
            ItemLockManager.OperationResult.DENIED -> {
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
        val pending = manager.pendingBindingOf(target)
        if (binding == null && pending == null) {
            deny(player, text.scrollNotBound)
            return false
        }
        if (
            managerConfig().unbind.ownerOnlyScroll &&
            pending?.ownerUuid != null &&
            pending.ownerUuid != player.uniqueId &&
            !manager.hasBypass(player)
        ) {
            deny(player, manager.replaceOwner(text.notOwner, pending.ownerName))
            return false
        }
        if (
            managerConfig().unbind.ownerOnlyScroll &&
            binding != null &&
            binding.ownerUuid != player.uniqueId &&
            !manager.hasBypass(player)
        ) {
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
        return manager.foreignOwnerName(item, player) != null
    }

    private fun deny(player: Player, message: String) {
        val settings = managerConfig()
        manager.actionBar(player, message)
        manager.play(player, settings.sounds.failure)
    }

    private fun isDropClick(click: ClickType): Boolean {
        return click == ClickType.DROP || click == ClickType.CONTROL_DROP
    }

    private fun mayEquipArmor(event: InventoryClickEvent): Boolean {
        if (event.slotType == SlotType.ARMOR) {
            return true
        }
        if (event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT) {
            return currentItemCanEquip(event)
        }
        if (event.click == ClickType.NUMBER_KEY && event.rawSlot in 5..8) {
            return true
        }
        return event.clickedInventory?.type == InventoryType.PLAYER && event.slot in 36..39
    }

    private fun mayEquipByInteract(item: ItemStack): Boolean {
        val name = item.type.name
        return name.endsWith("_HELMET") ||
            name.endsWith("_CHESTPLATE") ||
            name.endsWith("_LEGGINGS") ||
            name.endsWith("_BOOTS") ||
            name == "ELYTRA" ||
            name == "SHIELD"
    }

    private fun bindEquippedItems(player: Player) {
        for (item in player.inventory.armorContents) {
            manager.bindPendingOnUse(item, player, ItemLockManager.BindAction.ARMOR_EQUIP)
        }
        manager.bindPendingOnUse(player.inventory.itemInOffHand, player, ItemLockManager.BindAction.ARMOR_EQUIP)
    }

    private fun bindOffHandArmor(player: Player) {
        manager.bindPendingOnUse(player.inventory.itemInOffHand, player, ItemLockManager.BindAction.ARMOR_EQUIP)
    }

    private fun currentItemCanEquip(event: InventoryClickEvent): Boolean {
        val current = event.currentItem ?: return false
        if (isAir(current)) {
            return false
        }
        return mayEquipByInteract(current)
    }

    private fun damageWeapon(entity: Entity): DamageWeapon? {
        val direct = entity as? Player
        if (direct != null) {
            return DamageWeapon(direct, direct.inventory.itemInMainHand, null)
        }

        val projectile = entity as? Projectile ?: return null
        val hit = projectileWeapons[projectile.uniqueId]
        val shooter = projectile.shooter as? Player ?: return null
        val tracked = hit?.takeIf { it.playerId == shooter.uniqueId && !isExpired(it) }
        if (tracked != null) {
            val item = findInventoryItem(shooter, tracked.itemKey) ?: return null
            return DamageWeapon(shooter, item, tracked.itemKey)
        }
        return DamageWeapon(shooter, shooter.inventory.itemInMainHand, null)
    }

    private fun heldWeaponFor(player: Player, eventWeapon: ItemStack?): ItemStack? {
        if (eventWeapon == null || isAir(eventWeapon)) {
            return null
        }
        val mainHand = player.inventory.itemInMainHand
        if (!isAir(mainHand) && mainHand.isSimilar(eventWeapon)) {
            return mainHand
        }
        val offHand = player.inventory.itemInOffHand
        if (!isAir(offHand) && offHand.isSimilar(eventWeapon)) {
            return offHand
        }
        return eventWeapon
    }

    private fun trackProjectileWeapon(projectile: Projectile, player: Player, weapon: ItemStack) {
        cleanupExpired(projectileWeapons)
        if (shouldBlockForeign(player, weapon, moving = false)) {
            return
        }
        manager.prepareAutoBindIfEligible(weapon, player)
        val trackingKey = manager.trackingKey(weapon) ?: return
        projectileWeapons[projectile.uniqueId] = WeaponHit(
            playerId = player.uniqueId,
            itemKey = trackingKey,
            at = System.currentTimeMillis()
        )
    }

    private fun trackedKillWeapon(entity: Entity, player: Player): ItemStack? {
        val hit = lastKillWeapons.remove(entity.uniqueId) ?: return null
        if (hit.playerId != player.uniqueId) {
            return null
        }
        if (isExpired(hit)) {
            return null
        }
        return findInventoryItem(player, hit.itemKey)
    }

    private fun isExpired(hit: WeaponHit): Boolean {
        return System.currentTimeMillis() - hit.at > KILL_WEAPON_TRACK_TTL_MS
    }

    private fun cleanupExpired(map: ConcurrentHashMap<UUID, WeaponHit>) {
        val now = System.currentTimeMillis()
        for ((id, hit) in map) {
            if (now - hit.at > KILL_WEAPON_TRACK_TTL_MS) {
                map.remove(id, hit)
            }
        }
    }

    private fun findInventoryItem(player: Player, itemKey: String): ItemStack? {
        for (item in player.inventory.contents) {
            if (manager.trackingKey(item) == itemKey) {
                return item
            }
        }
        for (item in player.inventory.armorContents) {
            if (manager.trackingKey(item) == itemKey) {
                return item
            }
        }
        val offHand = player.inventory.itemInOffHand
        if (manager.trackingKey(offHand) == itemKey) {
            return offHand
        }
        return null
    }

    private fun isAir(item: ItemStack?): Boolean {
        return item == null || item.type == Material.AIR || item.amount <= 0
    }

    private fun managerConfig(): ItemLockConfig.Settings = plugin.configService.settings()

    private fun messages(): ItemLockLang.MessageText = plugin.langService.text().messages

    private data class WeaponHit(
        val playerId: UUID,
        val itemKey: String,
        val at: Long
    )

    private data class DamageWeapon(
        val player: Player,
        val item: ItemStack,
        val itemKey: String?
    )

    private companion object {
        private const val KILL_WEAPON_TRACK_TTL_MS = 30_000L
    }
}
