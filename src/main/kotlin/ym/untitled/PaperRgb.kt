package ym.untitled

import org.bukkit.ChatColor

object PaperRgb {

    private val ampHex = Regex("(?i)&#([0-9a-f]{6})")
    private val angleHex = Regex("(?i)<#([0-9a-f]{6})>")

    fun color(value: String): String {
        var output = angleHex.replace(value) { toLegacyHex(it.groupValues[1]) }
        output = ampHex.replace(output) { toLegacyHex(it.groupValues[1]) }
        return ChatColor.translateAlternateColorCodes('&', output)
    }

    fun colorList(values: List<String>): List<String> {
        return values.map { color(it) }
    }

    private fun toLegacyHex(hex: String): String {
        val builder = StringBuilder("§x")
        for (char in hex) {
            builder.append('§').append(char.lowercaseChar())
        }
        return builder.toString()
    }
}
