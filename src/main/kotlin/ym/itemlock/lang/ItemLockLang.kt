package ym.itemlock.lang

import ym.itemlock.bootstrap.ItemLockPlugin
import ym.itemlock.util.PaperRgb
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

class ItemLockLang(
    private val plugin: ItemLockPlugin,
    private val executor: Executor
) {

    private val textRef = AtomicReference(Text.defaults())
    private val langPath = plugin.dataFolder.toPath().resolve("lang").resolve("zh_cn.yml")

    fun text(): Text = textRef.get()

    fun reloadAsync(): CompletableFuture<Text> {
        return CompletableFuture.supplyAsync({
            Files.createDirectories(langPath.parent)
            if (Files.notExists(langPath)) {
                val bytes = plugin.getResource("lang/zh_cn.yml")?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.isNotEmpty()) {
                    Files.write(langPath, bytes)
                }
            }

            val lang = Files.newBufferedReader(langPath, StandardCharsets.UTF_8).use {
                YamlConfiguration.loadConfiguration(it)
            }
            val defaults = loadBundledDefaults()
            lang.setDefaults(defaults)

            val text = Text(
                binding = BindingText(
                    loreFormat = color(lang, "binding.lore-format"),
                    loreMarker = color(lang, "binding.lore-marker"),
                    pendingLoreFormat = color(lang, "binding.pending-lore-format"),
                    pendingLoreMarker = color(lang, "binding.pending-lore-marker")
                ),
                bindScroll = ScrollText(
                    name = color(lang, "bind-scroll.name"),
                    lore = PaperRgb.colorList(lang.getStringList("bind-scroll.lore"))
                ),
                unbindScroll = ScrollText(
                    name = colorWithFallback(lang, "unbind-scroll.name", "scroll.name"),
                    lore = PaperRgb.colorList(listWithFallback(lang, "unbind-scroll.lore", "scroll.lore"))
                ),
                messages = MessageText(
                    prefix = color(lang, "messages.prefix"),
                    noPermission = color(lang, "messages.no-permission"),
                    playerOnly = color(lang, "messages.player-only"),
                    noItem = color(lang, "messages.no-item"),
                    bindSuccess = color(lang, "messages.bind-success"),
                    unbindSuccess = color(lang, "messages.unbind-success"),
                    alreadyBound = color(lang, "messages.already-bound"),
                    notBound = color(lang, "messages.not-bound"),
                    notOwner = color(lang, "messages.not-owner"),
                    cannotDrop = color(lang, "messages.cannot-drop"),
                    cannotUse = color(lang, "messages.cannot-use"),
                    cannotTake = color(lang, "messages.cannot-take"),
                    scrollNotBound = color(lang, "messages.scroll-not-bound"),
                    scrollAlreadyBound = color(lang, "messages.scroll-already-bound"),
                    scrollDenied = color(lang, "messages.scroll-denied"),
                    scrollSuccess = color(lang, "messages.scroll-success"),
                    bindScrollSuccess = color(lang, "messages.bind-scroll-success"),
                    scrollGive = color(lang, "messages.scroll-give"),
                    bindScrollLabel = color(lang, "messages.bind-scroll-label"),
                    unbindScrollLabel = color(lang, "messages.unbind-scroll-label"),
                    reloadStarted = color(lang, "messages.reload-started"),
                    reloadDone = color(lang, "messages.reload-done"),
                    reloadFailed = color(lang, "messages.reload-failed"),
                    autoBound = color(lang, "messages.auto-bound"),
                    helpBind = color(lang, "messages.help-bind"),
                    helpUnbind = color(lang, "messages.help-unbind"),
                    helpScroll = color(lang, "messages.help-scroll"),
                    helpReload = color(lang, "messages.help-reload")
                )
            )
            textRef.set(text)
            text
        }, executor)
    }

    private fun color(lang: YamlConfiguration, path: String): String {
        return PaperRgb.color(lang.getString(path, path) ?: path)
    }

    private fun colorWithFallback(lang: YamlConfiguration, path: String, fallbackPath: String): String {
        return PaperRgb.color(lang.getString(path) ?: lang.getString(fallbackPath, path) ?: path)
    }

    private fun listWithFallback(lang: YamlConfiguration, path: String, fallbackPath: String): List<String> {
        val current = lang.getStringList(path)
        if (current.isNotEmpty()) {
            return current
        }
        return lang.getStringList(fallbackPath)
    }

    private fun loadBundledDefaults(): YamlConfiguration {
        val defaults = YamlConfiguration()
        plugin.getResource("lang/zh_cn.yml")?.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                defaults.load(reader)
            }
        }
        return defaults
    }

    data class Text(
        val binding: BindingText,
        val bindScroll: ScrollText,
        val unbindScroll: ScrollText,
        val messages: MessageText
    ) {
        companion object {
            fun defaults(): Text {
                return Text(
                    binding = BindingText(
                        PaperRgb.color("&#BFC7D5◆ &#7AD7FF灵魂绑定 &#6B7280· &#FFFFFF{player}"),
                        "灵魂绑定",
                        PaperRgb.color("&#BFC7D5◆ &#F8D66D待灵魂绑定 &#6B7280· &#FFFFFF首次使用后绑定主人"),
                        "待灵魂绑定"
                    ),
                    bindScroll = ScrollText(
                        PaperRgb.color("&#B58CFF绑定卷轴"),
                        PaperRgb.colorList(
                            listOf(
                                "&#BFC7D5左键拿起后覆盖到未绑定装备上",
                                "&#BFC7D5成功后消耗 &#FFFFFF1 &#BFC7D5张并标记为待绑定。",
                                "",
                                "&#F8D66D所有物品必须先使用绑定卷轴",
                                "&#F8D66D目标物品实际使用一次后绑定主人"
                            )
                        )
                    ),
                    unbindScroll = ScrollText(
                        PaperRgb.color("&#7AD7FF解绑卷轴"),
                        PaperRgb.colorList(
                            listOf(
                                "&#BFC7D5左键拿起后覆盖到已绑定装备上",
                                "&#BFC7D5成功后消耗 &#FFFFFF1 &#BFC7D5张并移除绑定。"
                            )
                        )
                    ),
                    messages = MessageText.defaults()
                )
            }
        }
    }

    data class BindingText(
        val loreFormat: String,
        val loreMarker: String,
        val pendingLoreFormat: String,
        val pendingLoreMarker: String
    )

    data class ScrollText(
        val name: String,
        val lore: List<String>
    )

    data class MessageText(
        val prefix: String,
        val noPermission: String,
        val playerOnly: String,
        val noItem: String,
        val bindSuccess: String,
        val unbindSuccess: String,
        val alreadyBound: String,
        val notBound: String,
        val notOwner: String,
        val cannotDrop: String,
        val cannotUse: String,
        val cannotTake: String,
        val scrollNotBound: String,
        val scrollAlreadyBound: String,
        val scrollDenied: String,
        val scrollSuccess: String,
        val bindScrollSuccess: String,
        val scrollGive: String,
        val bindScrollLabel: String,
        val unbindScrollLabel: String,
        val reloadStarted: String,
        val reloadDone: String,
        val reloadFailed: String,
        val autoBound: String,
        val helpBind: String,
        val helpUnbind: String,
        val helpScroll: String,
        val helpReload: String
    ) {
        companion object {
            fun defaults(): MessageText {
                return MessageText(
                    prefix = PaperRgb.color("&#2F3542[&#7AD7FFItemLock&#2F3542] "),
                    noPermission = PaperRgb.color("&#FF6B6B你没有权限执行该操作。"),
                    playerOnly = PaperRgb.color("&#FF6B6B该指令只能由玩家执行。"),
                    noItem = PaperRgb.color("&#FF6B6B请先拿着需要操作的物品。"),
                    bindSuccess = PaperRgb.color("&#72E06A物品已灵魂绑定给 &#FFFFFF{player}&#72E06A。"),
                    unbindSuccess = PaperRgb.color("&#72E06A物品已解除灵魂绑定。"),
                    alreadyBound = PaperRgb.color("&#F8D66D该物品已经绑定。"),
                    notBound = PaperRgb.color("&#F8D66D该物品没有绑定。"),
                    notOwner = PaperRgb.color("&#FF6B6B该物品属于 &#FFFFFF{owner}&#FF6B6B，无法操作。"),
                    cannotDrop = PaperRgb.color("&#FF6B6B灵魂绑定物品不能丢出。"),
                    cannotUse = PaperRgb.color("&#FF6B6B你不能使用其他玩家的灵魂绑定物品。"),
                    cannotTake = PaperRgb.color("&#FF6B6B你不能取走其他玩家的灵魂绑定物品。"),
                    scrollNotBound = PaperRgb.color("&#F8D66D目标物品没有绑定，不会消耗卷轴。"),
                    scrollAlreadyBound = PaperRgb.color("&#F8D66D目标物品已经绑定或等待绑定，不会消耗卷轴。"),
                    scrollDenied = PaperRgb.color("&#FF6B6B该物品不允许使用卷轴解绑。"),
                    scrollSuccess = PaperRgb.color("&#72E06A解绑成功，已消耗 &#FFFFFF1 &#72E06A张解绑卷轴。"),
                    bindScrollSuccess = PaperRgb.color("&#72E06A绑定卷轴已生效，实际使用一次后会绑定主人。"),
                    scrollGive = PaperRgb.color("&#72E06A已给予 &#FFFFFF{player} &#72E06Ax{amount} {scroll}。"),
                    bindScrollLabel = PaperRgb.color("&#B58CFF绑定卷轴"),
                    unbindScrollLabel = PaperRgb.color("&#7AD7FF解绑卷轴"),
                    reloadStarted = PaperRgb.color("&#BFC7D5正在异步重载 ItemLock 配置与语言..."),
                    reloadDone = PaperRgb.color("&#72E06AItemLock 配置与语言已重载。"),
                    reloadFailed = PaperRgb.color("&#FF6B6B重载失败：&#FFFFFF{error}"),
                    autoBound = PaperRgb.color("&#BFC7D5绑定卷轴已完成灵魂绑定。"),
                    helpBind = PaperRgb.color("&#66C7FF/{label} bind &#BFC7D5- 绑定手中物品"),
                    helpUnbind = PaperRgb.color("&#66C7FF/{label} unbind &#BFC7D5- 解绑手中物品"),
                    helpScroll = PaperRgb.color("&#66C7FF/{label} scroll [bind|unbind] [数量] [玩家] &#BFC7D5- 获取绑定/解绑卷轴"),
                    helpReload = PaperRgb.color("&#66C7FF/{label} reload &#BFC7D5- 异步重载配置与语言")
                )
            }
        }
    }
}
