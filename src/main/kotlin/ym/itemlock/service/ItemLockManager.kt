package ym.itemlock.service

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import ym.itemlock.bootstrap.ItemLockPlugin
import ym.itemlock.config.ItemLockConfig
import ym.itemlock.lang.ItemLockLang
import ym.itemlock.model.ScrollType
import ym.itemlock.storage.ItemLockStorage
import ym.itemlock.util.PaperRgb
import org.bukkit.Bukkit
import org.bukkit.ChatColor
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
    private val keyPendingBind = NamespacedKey(plugin, "pending_bind")
    private val keyPendingId = NamespacedKey(plugin, "pending_id")
    private val keyPendingOwnerUuid = NamespacedKey(plugin, "pending_owner_uuid")
    private val keyPendingOwnerName = NamespacedKey(plugin, "pending_owner_name")
    @Volatile
    private var loreMatcherCache: LoreMatcherCache? = null

    fun bindingOf(item: ItemStack?): BindingInfo? {
        if (isAir(item)) {
            return null
        }
        val meta = item!!.itemMeta ?: return null
        val container = meta.persistentDataContainer
        val ownerText = container.get(keyOwnerUuid, PersistentDataType.STRING)
        val ownerUuid = ownerText?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        val itemId = container.get(keyItemId, PersistentDataType.STRING)
        if (ownerUuid != null && itemId != null) {
            return BindingInfo(
                itemId = itemId,
                ownerUuid = ownerUuid,
                ownerName = container.get(keyOwnerName, PersistentDataType.STRING) ?: "Unknown",
                boundAt = container.get(keyBoundAt, PersistentDataType.LONG) ?: 0L,
                source = BindingSource.PDC
            )
        }
        return loreBindingOf(item, meta)
    }

    fun isBound(item: ItemStack?): Boolean = bindingOf(item) != null

    fun isOwnedBy(item: ItemStack?, player: Player): Boolean {
        val binding = bindingOf(item) ?: return true
        return isBindingOwnedBy(binding, player)
    }

    fun isBindingOwnedBy(binding: BindingInfo, player: Player): Boolean {
        return binding.ownerUuid == player.uniqueId || binding.ownerName.equals(player.name, ignoreCase = true)
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
        clearPendingKeys(container)
        val itemId = current?.takeIf { it.source == BindingSource.PDC }?.itemId ?: UUID.randomUUID().toString()
        val boundAt = current?.takeIf { it.source == BindingSource.PDC }?.boundAt?.takeIf { it > 0L }
            ?: System.currentTimeMillis()
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

    fun markPendingBind(item: ItemStack?, owner: Player? = null): OperationResult {
        if (isAir(item)) {
            return OperationResult.NO_ITEM
        }
        if (bindingOf(item) != null) {
            return OperationResult.ALREADY_BOUND
        }
        val current = pendingBindingOf(item)
        if (current != null) {
            if (
                owner != null &&
                current.ownerUuid != null &&
                current.ownerUuid != owner.uniqueId &&
                !hasBypass(owner)
            ) {
                return OperationResult.DENIED
            }
            if (owner != null && current.ownerUuid == null) {
                val meta = item!!.itemMeta ?: return OperationResult.NO_ITEM
                ensurePendingId(meta)
                writePendingOwner(meta, owner)
                item.itemMeta = meta
                return OperationResult.SUCCESS
            }
            return OperationResult.ALREADY_BOUND
        }
        val meta = item!!.itemMeta ?: return OperationResult.NO_ITEM
        val container = meta.persistentDataContainer
        container.set(keyPendingBind, PersistentDataType.BYTE, 1.toByte())
        ensurePendingId(meta)
        if (owner != null) {
            writePendingOwner(meta, owner)
        } else {
            container.remove(keyPendingOwnerUuid)
            container.remove(keyPendingOwnerName)
        }
        meta.lore = pendingLore(meta.lore, config.settings())
        item.itemMeta = meta
        return OperationResult.SUCCESS
    }

    fun pendingBindingOf(item: ItemStack?): PendingBindingInfo? {
        if (!isPendingBind(item)) {
            return null
        }
        val meta = item!!.itemMeta ?: return null
        val container = meta.persistentDataContainer
        val ownerText = container.get(keyPendingOwnerUuid, PersistentDataType.STRING)
        val ownerUuid = ownerText?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        return PendingBindingInfo(
            pendingId = container.get(keyPendingId, PersistentDataType.STRING),
            ownerUuid = ownerUuid,
            ownerName = container.get(keyPendingOwnerName, PersistentDataType.STRING) ?: "Unknown"
        )
    }

    fun isPendingBind(item: ItemStack?): Boolean {
        if (isAir(item)) {
            return false
        }
        val meta = item!!.itemMeta ?: return false
        val marker = meta.persistentDataContainer.get(keyPendingBind, PersistentDataType.BYTE)
        return marker?.toInt() == 1
    }

    fun isBoundOrPending(item: ItemStack?): Boolean {
        return bindingOf(item) != null || isPendingBind(item)
    }

    fun trackingKey(item: ItemStack?): String? {
        val binding = bindingOf(item)
        if (binding != null) {
            return "bound:${binding.itemId}"
        }
        val pending = pendingBindingOf(item)
        val pendingId = pending?.pendingId?.takeIf { it.isNotBlank() } ?: return null
        return "pending:$pendingId"
    }

    fun foreignOwnerName(item: ItemStack?, player: Player): String? {
        val binding = bindingOf(item)
        if (binding != null && !isBindingOwnedBy(binding, player)) {
            return binding.ownerName
        }
        return null
    }

    fun bindPendingOnUse(item: ItemStack?, owner: Player, action: BindAction, sendMessage: Boolean = true): Boolean {
        if (bindingOf(item) != null) {
            return false
        }
        if (!canBindOnAction(item, action)) {
            return false
        }
        val pending = pendingBindingOf(item)
        if (pending == null && !isAutomaticEligibleForAction(item, action)) {
            return false
        }
        val result = bindItem(item, owner)
        if (result == OperationResult.SUCCESS && sendMessage) {
            actionBar(owner, lang.text().messages.autoBound)
        }
        return result == OperationResult.SUCCESS
    }

    fun unbindItem(item: ItemStack?): OperationResult {
        val binding = bindingOf(item)
        val pending = pendingBindingOf(item)
        if (binding == null && pending == null) {
            return OperationResult.NOT_BOUND
        }
        val settings = config.settings()
        if (binding != null && item!!.type in settings.unbind.deniedMaterials) {
            return OperationResult.DENIED
        }

        val meta = item!!.itemMeta ?: return OperationResult.NO_ITEM
        val container = meta.persistentDataContainer
        container.remove(keyItemId)
        container.remove(keyOwnerUuid)
        container.remove(keyOwnerName)
        container.remove(keyBoundAt)
        clearPendingKeys(container)
        meta.lore = removeBindingLore(meta.lore, settings)
        item.itemMeta = meta
        if (binding?.source == BindingSource.PDC) {
            storage.recordUnbind(binding.itemId)
        }
        return OperationResult.SUCCESS
    }

    fun prepareAutoBindIfEligible(item: ItemStack?, owner: Player): Boolean {
        if (pendingBindingOf(item) != null) {
            return false
        }
        if (!isEligibleForAutoBind(item)) {
            return false
        }
        if (bindingOf(item) != null) {
            return false
        }
        return markPendingBind(item, owner) == OperationResult.SUCCESS
    }

    fun scanInventoryForPendingBind(player: Player): Int {
        val settings = config.settings()
        if (!settings.binding.automatic) {
            return 0
        }
        var changed = 0
        val inventory = player.inventory
        for (item in inventory.contents) {
            if (prepareAutoBindIfEligible(item, player)) {
                changed++
            }
        }
        for (item in inventory.armorContents) {
            if (prepareAutoBindIfEligible(item, player)) {
                changed++
            }
        }
        if (prepareAutoBindIfEligible(inventory.itemInOffHand, player)) {
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

    private fun isEligibleForAutoBind(item: ItemStack?): Boolean {
        val settings = config.settings()
        return settings.binding.automatic && shouldAutoBind(item, settings)
    }

    private fun isAutomaticEligibleForAction(item: ItemStack?, action: BindAction): Boolean {
        return isEligibleForAutoBind(item) && canBindOnAction(item, action)
    }

    private fun canBindOnAction(item: ItemStack?, action: BindAction): Boolean {
        if (isAir(item)) {
            return false
        }
        val material = item!!.type
        val rule = config.settings().binding.actionRules[action.configKey] ?: return false
        if (material in rule.excludedMaterials) {
            return false
        }
        if (material in rule.materials) {
            return true
        }
        return rule.types.any { token -> matchesType(material, token) }
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
        return settings.binding.defaultTypes.any { token ->
            matchesType(material, token)
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

    private fun loreBindingOf(item: ItemStack, meta: org.bukkit.inventory.meta.ItemMeta): BindingInfo? {
        val settings = config.settings().binding
        if (!settings.detectOwnerLore || settings.ownerLoreFormat.isBlank()) {
            return null
        }
        val lore = meta.lore ?: return null
        val ownerName = lore.asSequence()
            .mapNotNull { line -> ownerNameFromLore(line, settings.ownerLoreFormat) }
            .firstOrNull()
            ?: return null
        return BindingInfo(
            itemId = loreBindingItemId(item.type, ownerName),
            ownerUuid = ownerUuidFromName(ownerName),
            ownerName = ownerName,
            boundAt = 0L,
            source = BindingSource.LORE
        )
    }

    private fun ownerNameFromLore(line: String, format: String): String? {
        val matcher = loreMatcher(format) ?: return null
        val coloredMatch = matcher.colored?.matchEntire(line)
        if (coloredMatch != null) {
            return normalizeDetectedOwner(coloredMatch.groupValues.getOrNull(1))
        }
        val plainLine = ChatColor.stripColor(line) ?: line
        val plainMatch = matcher.plain?.matchEntire(plainLine)
        return normalizeDetectedOwner(plainMatch?.groupValues?.getOrNull(1))
    }

    private fun loreMatcher(format: String): LoreMatcherCache? {
        loreMatcherCache?.takeIf { it.format == format }?.let { return it }
        val coloredFormat = PaperRgb.color(format)
        val built = LoreMatcherCache(
            format = format,
            colored = ownerFormatRegex(coloredFormat),
            plain = ownerFormatRegex(ChatColor.stripColor(coloredFormat) ?: coloredFormat)
        )
        loreMatcherCache = built
        return built.takeIf { it.colored != null || it.plain != null }
    }

    private fun ownerFormatRegex(format: String): Regex? {
        val placeholder = Regex("(?i)%player%|\\{player}")
        val parts = placeholder.split(format, limit = 2)
        if (parts.size != 2) {
            return null
        }
        return Regex("^${Regex.escape(parts[0])}(.{1,48})${Regex.escape(parts[1])}$")
    }

    private fun normalizeDetectedOwner(value: String?): String? {
        val stripped = ChatColor.stripColor(value ?: "") ?: value ?: ""
        return stripped.trim().takeIf { it.isNotEmpty() }
    }

    private fun loreBindingItemId(material: Material, ownerName: String): String {
        val normalizedOwner = ownerName.lowercase(Locale.ROOT)
        return "lore:${material.name.lowercase(Locale.ROOT)}:$normalizedOwner"
    }

    private fun ownerUuidFromName(ownerName: String): UUID {
        Bukkit.getPlayerExact(ownerName)?.let { return it.uniqueId }
        val key = "ItemLock:lore-owner:${ownerName.lowercase(Locale.ROOT)}"
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
    }

    private fun matchesType(material: Material, token: String): Boolean {
        val name = material.name.uppercase(Locale.ROOT)
        return when (token) {
            "ALL" -> true
            "WEAPON", "WEAPONS" -> isWeapon(name)
            "ARMOR", "ARMORS" -> isArmor(name)
            "TOOL", "TOOLS" -> isTool(name)
            "INTERACT_TOOL", "INTERACT_TOOLS" -> isInteractTool(name)
            "MISC", "MISC_ITEM", "MISC_ITEMS" -> isMiscInteractionItem(name)
            "SWORD", "SWORDS" -> name.endsWith("_SWORD")
            "PICKAXE", "PICKAXES" -> name.endsWith("_PICKAXE")
            "AXE", "AXES" -> name.endsWith("_AXE")
            "SHOVEL", "SHOVELS" -> name.endsWith("_SHOVEL")
            "HOE", "HOES" -> name.endsWith("_HOE")
            "BOW", "BOWS" -> name == "BOW" || name == "CROSSBOW"
            "TRIDENT", "TRIDENTS" -> name == "TRIDENT"
            "SHEAR", "SHEARS" -> name == "SHEARS"
            "FISHING_ROD", "FISHING_RODS" -> name == "FISHING_ROD"
            "FLINT_AND_STEEL" -> name == "FLINT_AND_STEEL"
            "ELYTRA" -> name == "ELYTRA"
            "SHIELD", "SHIELDS" -> name == "SHIELD"
            else -> name == token
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

    private fun isBlockBreakTool(name: String): Boolean {
        return name.endsWith("_PICKAXE") ||
            name.endsWith("_AXE") ||
            name.endsWith("_SHOVEL") ||
            name.endsWith("_HOE") ||
            name == "SHEARS"
    }

    private fun isInteractTool(name: String): Boolean {
        return name == "FISHING_ROD" ||
            name == "FLINT_AND_STEEL" ||
            name.endsWith("_HOE")
    }

    private fun isMiscInteractionItem(name: String): Boolean {
        return !isWeapon(name) && !isArmor(name) && !isBlockBreakTool(name)
    }

    private fun boundLore(oldLore: List<String>?, ownerName: String, settings: ItemLockConfig.Settings): List<String> {
        val lore = removeBindingLore(oldLore, settings).toMutableList()
        lore.add(lang.text().binding.loreFormat.replace("{player}", ownerName))
        return lore
    }

    private fun pendingLore(oldLore: List<String>?, settings: ItemLockConfig.Settings): List<String> {
        val lore = removeBindingLore(oldLore, settings).toMutableList()
        lore.add(lang.text().binding.pendingLoreFormat)
        return lore
    }

    private fun removeBindingLore(oldLore: List<String>?, settings: ItemLockConfig.Settings): List<String> {
        if (oldLore.isNullOrEmpty()) {
            return emptyList()
        }
        val text = lang.text().binding
        return oldLore.filterNot { line ->
            line.contains(text.loreMarker, ignoreCase = true) ||
                line.contains(text.pendingLoreMarker, ignoreCase = true)
        }
    }

    private fun clearPendingKeys(container: org.bukkit.persistence.PersistentDataContainer) {
        container.remove(keyPendingBind)
        container.remove(keyPendingId)
        container.remove(keyPendingOwnerUuid)
        container.remove(keyPendingOwnerName)
    }

    private fun writePendingOwner(meta: org.bukkit.inventory.meta.ItemMeta, owner: Player) {
        val container = meta.persistentDataContainer
        container.set(keyPendingOwnerUuid, PersistentDataType.STRING, owner.uniqueId.toString())
        container.set(keyPendingOwnerName, PersistentDataType.STRING, owner.name)
    }

    private fun ensurePendingId(meta: org.bukkit.inventory.meta.ItemMeta) {
        val container = meta.persistentDataContainer
        if (container.get(keyPendingId, PersistentDataType.STRING).isNullOrBlank()) {
            container.set(keyPendingId, PersistentDataType.STRING, UUID.randomUUID().toString())
        }
    }

    private fun isAir(item: ItemStack?): Boolean {
        return item == null || item.type == Material.AIR || item.amount <= 0
    }

    data class BindingInfo(
        val itemId: String,
        val ownerUuid: UUID,
        val ownerName: String,
        val boundAt: Long,
        val source: BindingSource
    )

    data class PendingBindingInfo(
        val pendingId: String?,
        val ownerUuid: UUID?,
        val ownerName: String
    )

    enum class OperationResult {
        SUCCESS,
        NO_ITEM,
        ALREADY_BOUND,
        NOT_BOUND,
        DENIED
    }

    enum class BindingSource {
        PDC,
        LORE
    }

    private data class LoreMatcherCache(
        val format: String,
        val colored: Regex?,
        val plain: Regex?
    )

    enum class BindAction {
        BLOCK_BREAK,
        ARMOR_EQUIP,
        KILL,
        INTERACT;

        val configKey: String
            get() = when (this) {
                BLOCK_BREAK -> ItemLockConfig.ACTION_BLOCK_BREAK
                ARMOR_EQUIP -> ItemLockConfig.ACTION_ARMOR_EQUIP
                KILL -> ItemLockConfig.ACTION_KILL
                INTERACT -> ItemLockConfig.ACTION_INTERACT
            }
    }
}
