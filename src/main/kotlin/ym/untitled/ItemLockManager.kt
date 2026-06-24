package ym.untitled

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Locale
import java.util.UUID

class ItemLockManager(
    private val plugin: ItemLockPlugin,
    private val config: ItemLockConfig,
    private val lang: ItemLockLang,
    private val storage: ItemLockStorage
) {

    private val keyItemId = NamespacedKey(plugin, "item_id")
    private val keyOwnerUuid = NamespacedKey(plugin, "owner_uuid")
    private val keyOwnerName = NamespacedKey(plugin, "owner_name")
    private val keyBoundAt = NamespacedKey(plugin, "bound_at")
    private val keyScroll = NamespacedKey(plugin, "unbind_scroll")
    private val keyScrollType = NamespacedKey(plugin, "scroll_type")

    fun bindingOf(item: ItemStack?): BindingInfo? {
        if (isAir(item)) {
            return null
        }
        val meta = item!!.itemMeta ?: return null
        val container = meta.persistentDataContainer
        val ownerText = container.get(keyOwnerUuid, PersistentDataType.STRING) ?: return null
        val ownerUuid = runCatching { UUID.fromString(ownerText) }.getOrNull() ?: return null
        val itemId = container.get(keyItemId, PersistentDataType.STRING) ?: return null
        return BindingInfo(
            itemId = itemId,
            ownerUuid = ownerUuid,
            ownerName = container.get(keyOwnerName, PersistentDataType.STRING) ?: "Unknown",
            boundAt = container.get(keyBoundAt, PersistentDataType.LONG) ?: 0L
        )
    }

    fun isBound(item: ItemStack?): Boolean = bindingOf(item) != null

    fun isOwnedBy(item: ItemStack?, player: Player): Boolean {
        val binding = bindingOf(item) ?: return true
        return binding.ownerUuid == player.uniqueId
    }

    fun hasBypass(player: Player): Boolean = player.hasPermission("itemlock.bypass")

    fun bindItem(item: ItemStack?, owner: Player, notifyStorage: Boolean = true): OperationResult {
        if (isAir(item)) {
            return OperationResult.NO_ITEM
        }
        val current = bindingOf(item)
        val settings = config.settings()
        if (current != null && !settings.binding.bindAlreadyBoundItems) {
            return OperationResult.ALREADY_BOUND
        }

        val meta = item!!.itemMeta ?: return OperationResult.NO_ITEM
        val container = meta.persistentDataContainer
        val itemId = current?.itemId ?: UUID.randomUUID().toString()
        val boundAt = current?.boundAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        container.set(keyItemId, PersistentDataType.STRING, itemId)
        container.set(keyOwnerUuid, PersistentDataType.STRING, owner.uniqueId.toString())
        container.set(keyOwnerName, PersistentDataType.STRING, owner.name)
        container.set(keyBoundAt, PersistentDataType.LONG, boundAt)
        meta.lore = boundLore(meta.lore, owner.name, settings)
        item.itemMeta = meta

        if (notifyStorage) {
            storage.recordBind(itemId, owner.uniqueId, owner.name, item.type.name, boundAt)
        }
        return OperationResult.SUCCESS
    }

    fun unbindItem(item: ItemStack?): OperationResult {
        val binding = bindingOf(item) ?: return OperationResult.NOT_BOUND
        val settings = config.settings()
        if (item!!.type in settings.unbind.deniedMaterials) {
            return OperationResult.DENIED
        }

        val meta = item.itemMeta ?: return OperationResult.NO_ITEM
        val container = meta.persistentDataContainer
        container.remove(keyItemId)
        container.remove(keyOwnerUuid)
        container.remove(keyOwnerName)
        container.remove(keyBoundAt)
        meta.lore = removeBindingLore(meta.lore, settings)
        item.itemMeta = meta
        storage.recordUnbind(binding.itemId)
        return OperationResult.SUCCESS
    }

    fun bindIfEligible(item: ItemStack?, owner: Player, sendMessage: Boolean = false): Boolean {
        val settings = config.settings()
        if (!settings.binding.automatic || !shouldAutoBind(item, settings)) {
            return false
        }
        if (bindingOf(item) != null) {
            return false
        }
        val result = bindItem(item, owner)
        if (result == OperationResult.SUCCESS && sendMessage) {
            actionBar(owner, lang.text().messages.autoBound)
        }
        return result == OperationResult.SUCCESS
    }

    fun scanInventoryForAutoBind(player: Player): Int {
        val settings = config.settings()
        if (!settings.binding.automatic) {
            return 0
        }
        var changed = 0
        val inventory = player.inventory
        for (item in inventory.contents) {
            if (bindIfEligible(item, player)) {
                changed++
            }
        }
        for (item in inventory.armorContents) {
            if (bindIfEligible(item, player)) {
                changed++
            }
        }
        if (bindIfEligible(inventory.itemInOffHand, player)) {
            changed++
        }
        return changed
    }

    fun createScroll(amount: Int, type: ScrollType = ScrollType.UNBIND): ItemStack {
        val settings = scrollSettings(type)
        val text = scrollText(type)
        val item = ItemStack(settings.material, amount.coerceIn(1, 64))
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(text.name)
            meta.lore = text.lore
            if (settings.customModelData > 0) {
                meta.setCustomModelData(settings.customModelData)
            }
            meta.persistentDataContainer.set(keyScrollType, PersistentDataType.STRING, type.id)
            if (type == ScrollType.UNBIND) {
                meta.persistentDataContainer.set(keyScroll, PersistentDataType.BYTE, 1.toByte())
            }
            item.itemMeta = meta
        }
        return item
    }

    fun isScroll(item: ItemStack?): Boolean {
        return scrollType(item) != null
    }

    fun scrollType(item: ItemStack?): ScrollType? {
        if (isAir(item)) {
            return null
        }
        item!!
        val meta = item.itemMeta ?: return null

        val explicitType = meta.persistentDataContainer.get(keyScrollType, PersistentDataType.STRING)
        if (explicitType != null) {
            return ScrollType.values().firstOrNull { type ->
                type.id.equals(explicitType, ignoreCase = true) && item.type == scrollSettings(type).material
            }
        }

        val legacyUnbindMarker = meta.persistentDataContainer.get(keyScroll, PersistentDataType.BYTE)
        if (legacyUnbindMarker != null && legacyUnbindMarker.toInt() == 1 && item.type == scrollSettings(ScrollType.UNBIND).material) {
            return ScrollType.UNBIND
        }

        for (type in ScrollType.values()) {
            val settings = scrollSettings(type)
            if (item.type != settings.material || !settings.matchByDisplay) {
                continue
            }
            if (meta.hasDisplayName() && meta.displayName == scrollText(type).name) {
                return type
            }
        }
        return null
    }

    fun consumeOne(item: ItemStack?): ItemStack {
        if (isAir(item)) {
            return ItemStack(Material.AIR)
        }
        val copy = item!!.clone()
        copy.amount = copy.amount - 1
        if (copy.amount <= 0) {
            return ItemStack(Material.AIR)
        }
        return copy
    }

    fun message(player: Player, raw: String) {
        player.sendMessage(lang.text().messages.prefix + raw)
    }

    fun actionBar(player: Player, raw: String) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(raw))
    }

    fun play(player: Player, sound: Sound) {
        player.playSound(player.location, sound, 0.8f, 1.2f)
    }

    fun replace(raw: String, player: Player): String {
        return raw.replace("{player}", player.name)
    }

    fun replaceOwner(raw: String, ownerName: String): String {
        return raw.replace("{owner}", ownerName)
    }

    private fun shouldAutoBind(item: ItemStack?, settings: ItemLockConfig.Settings): Boolean {
        if (isAir(item)) {
            return false
        }
        val material = item!!.type
        if (material in settings.binding.excludedMaterials) {
            return false
        }
        if (material in settings.binding.explicitMaterials) {
            return true
        }
        val name = material.name.uppercase(Locale.ROOT)
        return settings.binding.defaultTypes.any { token ->
            when (token) {
                "ALL" -> true
                "WEAPON", "WEAPONS" -> isWeapon(name)
                "ARMOR", "ARMORS" -> isArmor(name)
                "TOOL", "TOOLS" -> isTool(name)
                "SWORD", "SWORDS" -> name.endsWith("_SWORD")
                "AXE", "AXES" -> name.endsWith("_AXE")
                "BOW", "BOWS" -> name == "BOW" || name == "CROSSBOW"
                else -> name == token
            }
        }
    }

    private fun scrollSettings(type: ScrollType): ItemLockConfig.ScrollSettings {
        val settings = config.settings()
        return when (type) {
            ScrollType.BIND -> settings.bindScroll
            ScrollType.UNBIND -> settings.unbindScroll
        }
    }

    private fun scrollText(type: ScrollType): ItemLockLang.ScrollText {
        val text = lang.text()
        return when (type) {
            ScrollType.BIND -> text.bindScroll
            ScrollType.UNBIND -> text.unbindScroll
        }
    }

    private fun isWeapon(name: String): Boolean {
        return name.endsWith("_SWORD") || name == "BOW" || name == "CROSSBOW" || name == "TRIDENT"
    }

    private fun isArmor(name: String): Boolean {
        return name.endsWith("_HELMET") ||
            name.endsWith("_CHESTPLATE") ||
            name.endsWith("_LEGGINGS") ||
            name.endsWith("_BOOTS") ||
            name == "ELYTRA" ||
            name == "SHIELD"
    }

    private fun isTool(name: String): Boolean {
        return name.endsWith("_PICKAXE") ||
            name.endsWith("_AXE") ||
            name.endsWith("_SHOVEL") ||
            name.endsWith("_HOE") ||
            name == "SHEARS" ||
            name == "FISHING_ROD" ||
            name == "FLINT_AND_STEEL"
    }

    private fun boundLore(oldLore: List<String>?, ownerName: String, settings: ItemLockConfig.Settings): List<String> {
        val lore = removeBindingLore(oldLore, settings).toMutableList()
        lore.add(lang.text().binding.loreFormat.replace("{player}", ownerName))
        return lore
    }

    private fun removeBindingLore(oldLore: List<String>?, settings: ItemLockConfig.Settings): List<String> {
        if (oldLore.isNullOrEmpty()) {
            return emptyList()
        }
        val marker = lang.text().binding.loreMarker
        return oldLore.filterNot { line -> line.contains(marker, ignoreCase = true) }
    }

    private fun isAir(item: ItemStack?): Boolean {
        return item == null || item.type == Material.AIR || item.amount <= 0
    }

    data class BindingInfo(
        val itemId: String,
        val ownerUuid: UUID,
        val ownerName: String,
        val boundAt: Long
    )

    enum class OperationResult {
        SUCCESS,
        NO_ITEM,
        ALREADY_BOUND,
        NOT_BOUND,
        DENIED
    }
}
