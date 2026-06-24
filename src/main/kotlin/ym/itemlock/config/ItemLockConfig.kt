package ym.itemlock.config

import ym.itemlock.bootstrap.ItemLockPlugin
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

class ItemLockConfig(
    private val plugin: ItemLockPlugin,
    private val executor: Executor
) {

    private val settingsRef = AtomicReference(Settings.defaults())
    private val configPath = plugin.dataFolder.toPath().resolve("config.yml")

    fun settings(): Settings = settingsRef.get()

    fun reloadAsync(): CompletableFuture<Settings> {
        return CompletableFuture.supplyAsync({
            Files.createDirectories(configPath.parent)
            if (Files.notExists(configPath)) {
                Files.write(configPath, DEFAULT_CONFIG.toByteArray(StandardCharsets.UTF_8))
            }

            val config = Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use {
                YamlConfiguration.loadConfiguration(it)
            }
            val explicitRootKeys = config.getKeys(false).toSet()
            val defaults = YamlConfiguration()
            defaults.loadFromString(DEFAULT_CONFIG)
            config.setDefaults(defaults)

            val settings = Settings(
                binding = BindingSettings(
                    automatic = config.getBoolean("binding.automatic", false),
                    defaultTypes = config.getStringList("binding.default-types")
                        .map { normalizeToken(it) }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                    explicitMaterials = parseMaterials(config.getStringList("binding.explicit-materials")),
                    excludedMaterials = parseMaterials(config.getStringList("binding.excluded-materials")),
                    bindAlreadyBoundItems = config.getBoolean("binding.bind-already-bound-items", false)
                ),
                unbind = UnbindSettings(
                    deniedMaterials = parseMaterials(config.getStringList("unbind.denied-materials")),
                    ownerOnlyScroll = config.getBoolean("unbind.owner-only-scroll", true)
                ),
                bindScroll = parseScrollSettings(config, "bind-scroll", Material.PAPER),
                unbindScroll = parseScrollSettings(config, "unbind-scroll", Material.PAPER).let { unbind ->
                    if (explicitRootKeys.contains("unbind-scroll")) {
                        unbind
                    } else {
                        parseScrollSettings(config, "scroll", Material.PAPER)
                    }
                },
                protection = ProtectionSettings(
                    cancelDrop = config.getBoolean("protection.cancel-drop", true),
                    cancelHopperMove = config.getBoolean("protection.cancel-hopper-move", true),
                    blockForeignMove = config.getBoolean("protection.block-foreign-move", true),
                    blockForeignUse = config.getBoolean("protection.block-foreign-use", true),
                    keepBoundOnDeath = config.getBoolean("protection.keep-bound-on-death", true)
                ),
                sounds = SoundSettings(
                    success = parseSound(config.getString("sounds.success"), Sound.ENTITY_PLAYER_LEVELUP),
                    failure = parseSound(config.getString("sounds.failure"), Sound.BLOCK_NOTE_BLOCK_BASS)
                )
            )
            settingsRef.set(settings)
            settings
        }, executor)
    }

    private fun parseScrollSettings(config: YamlConfiguration, path: String, fallbackMaterial: Material): ScrollSettings {
        return ScrollSettings(
            material = parseMaterial(config.getString("$path.material"), fallbackMaterial),
            customModelData = config.getInt("$path.custom-model-data", 0),
            matchByDisplay = config.getBoolean("$path.match-by-display", true)
        )
    }

    private fun parseMaterials(names: List<String>): Set<Material> {
        return names.mapNotNull { parseMaterialOrNull(it) }.toSet()
    }

    private fun parseMaterialOrNull(name: String?): Material? {
        if (name.isNullOrBlank()) {
            return null
        }
        return Material.matchMaterial(normalizeToken(name))
    }

    private fun parseMaterial(name: String?, fallback: Material): Material {
        return parseMaterialOrNull(name) ?: fallback
    }

    private fun parseSound(name: String?, fallback: Sound): Sound {
        if (name.isNullOrBlank()) {
            return fallback
        }
        return try {
            Sound.valueOf(normalizeToken(name))
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }

    private fun normalizeToken(value: String): String {
        return value.trim().uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
    }

    data class Settings(
        val binding: BindingSettings,
        val unbind: UnbindSettings,
        val bindScroll: ScrollSettings,
        val unbindScroll: ScrollSettings,
        val protection: ProtectionSettings,
        val sounds: SoundSettings
    ) {
        companion object {
            fun defaults(): Settings {
                return Settings(
                    binding = BindingSettings(
                        automatic = false,
                        defaultTypes = setOf("WEAPONS", "ARMOR", "TOOLS"),
                        explicitMaterials = emptySet(),
                        excludedMaterials = emptySet(),
                        bindAlreadyBoundItems = false
                    ),
                    unbind = UnbindSettings(emptySet(), ownerOnlyScroll = true),
                    bindScroll = ScrollSettings(
                        material = Material.PAPER,
                        customModelData = 0,
                        matchByDisplay = true
                    ),
                    unbindScroll = ScrollSettings(
                        material = Material.PAPER,
                        customModelData = 0,
                        matchByDisplay = true
                    ),
                    protection = ProtectionSettings(
                        cancelDrop = true,
                        cancelHopperMove = true,
                        blockForeignMove = true,
                        blockForeignUse = true,
                        keepBoundOnDeath = true
                    ),
                    sounds = SoundSettings(Sound.ENTITY_PLAYER_LEVELUP, Sound.BLOCK_NOTE_BLOCK_BASS)
                )
            }
        }
    }

    data class BindingSettings(
        val automatic: Boolean,
        val defaultTypes: Set<String>,
        val explicitMaterials: Set<Material>,
        val excludedMaterials: Set<Material>,
        val bindAlreadyBoundItems: Boolean
    )

    data class UnbindSettings(
        val deniedMaterials: Set<Material>,
        val ownerOnlyScroll: Boolean
    )

    data class ScrollSettings(
        val material: Material,
        val customModelData: Int,
        val matchByDisplay: Boolean
    )

    data class ProtectionSettings(
        val cancelDrop: Boolean,
        val cancelHopperMove: Boolean,
        val blockForeignMove: Boolean,
        val blockForeignUse: Boolean,
        val keepBoundOnDeath: Boolean
    )

    data class SoundSettings(
        val success: Sound,
        val failure: Sound
    )

    companion object {
        private val DEFAULT_CONFIG = """
# ItemLock 功能配置
# 所有玩家可见文本请在 lang/zh_cn.yml 中修改；该文件只放功能开关、材料和声音。

# 自动灵魂绑定规则。默认关闭；关闭时所有物品必须先使用绑定卷轴。
binding:
  # true 时，玩家获得符合规则的物品会先标记为待绑定；第一次对应交互后才绑定主人。
  # false 时，不会自动标记，只有绑定卷轴能让物品进入待绑定状态。
  automatic: false
  # 自动绑定启用后才生效的物品分类：
  # WEAPONS = 剑、弓、弩、三叉戟，击杀实体后绑定
  # ARMOR = 盔甲、鞘翅、盾牌，穿戴后绑定
  # TOOLS = 镐、斧、锹、锄、剪刀、鱼竿、打火石，破坏方块或实际交互后绑定
  # ALL = 所有非空气物品
  default-types:
    - WEAPONS
    - ARMOR
    - TOOLS
  # 自动绑定启用后，额外强制自动绑定的 Bukkit Material 名称，例如 DIAMOND_HOE。
  explicit-materials: []
  # 自动绑定启用后，从自动绑定中排除的 Bukkit Material 名称，优先级高于 default-types 和 explicit-materials。
  excluded-materials: []
  # true 时，管理员重复绑定已绑定物品会覆盖原所有者；false 时保留原绑定。
  bind-already-bound-items: false

# 解绑规则。
unbind:
  # true 时，解绑卷轴只能由绑定所有者使用；拥有 itemlock.bypass 权限的玩家不受限制。
  owner-only-scroll: true
  # 禁止使用卷轴解绑的 Bukkit Material 名称。
  denied-materials: []

# 绑定卷轴物品识别配置。
# 获取方式由你自行接商店、掉落、礼包或其他插件；/itemlock scroll bind 可生成标准绑定卷轴。
bind-scroll:
  # 卷轴基础物品类型。
  material: PAPER
  # 自定义模型数据；0 表示不设置。
  custom-model-data: 0
  # true 时，没有插件 PDC 标记但名称匹配语言文件 bind-scroll.name 的物品也会被识别为绑定卷轴。
  match-by-display: true

# 解绑卷轴物品识别配置。
# 获取方式由你自行接商店、掉落、礼包或其他插件；/itemlock scroll unbind 可生成标准解绑卷轴。
unbind-scroll:
  # 卷轴基础物品类型。
  material: PAPER
  # 自定义模型数据；0 表示不设置。
  custom-model-data: 0
  # true 时，没有插件 PDC 标记但名称匹配语言文件 unbind-scroll.name 的物品也会被识别为解绑卷轴。
  match-by-display: true

# 保护行为开关。
protection:
  # 禁止玩家丢出已绑定物品。
  cancel-drop: true
  # 禁止漏斗等容器自动移动已绑定物品。
  cancel-hopper-move: true
  # 禁止玩家移动、拿取其他玩家的绑定物品。
  block-foreign-move: true
  # 禁止玩家使用、攻击、放置其他玩家的绑定物品。
  block-foreign-use: true
  # 玩家死亡时，属于自己的灵魂绑定物品默认不会进入死亡掉落列表，并会在重生后归还。
  keep-bound-on-death: true

# 音效配置，填写 Bukkit Sound 枚举名。
sounds:
  # 卷轴解绑成功音效。
  success: ENTITY_PLAYER_LEVELUP
  # 操作失败或被保护阻止时的提示音效。
  failure: BLOCK_NOTE_BLOCK_BASS
""".trimIndent()
    }
}
