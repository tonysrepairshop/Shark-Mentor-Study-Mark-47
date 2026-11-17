package com.example.sharkmentor.core.util

import androidx.compose.ui.graphics.Color

enum class PaperPattern(val title: String, val description: String) {
    PLAIN("Düz", "Sade ve pürüzsüz"),
    RULED("Çizgili", "Klasik defter satırları"),
    GRID("Kareli", "Düzenli ızgara"),
    DOTTED("Noktalı", "Ajanda noktaları");

    companion object {
        fun fromName(name: String?): PaperPattern {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PLAIN
        }
    }
}

data class PaperTint(
    val title: String,
    val hex: String,
    val lightColor: Color,
    val darkColor: Color
) {
    companion object {
        val Options = listOf(
            PaperTint("Varsayılan", "#F8FAFC", Color(0xFFF8FAFC), Color(0xFF121212)),
            PaperTint("Beyaz", "#FFFFFF", Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
            PaperTint("Parşömen", "#FDF6E2", Color(0xFFFDF6E2), Color(0xFFFDF6E2)),
            PaperTint("Fildişi", "#FFFDF5", Color(0xFFFFFDF5), Color(0xFFFFFDF5)),
            PaperTint("Pastel Nane", "#F0FDF4", Color(0xFFF0FDF4), Color(0xFFF0FDF4)),
            PaperTint("Pastel Gül", "#FAF5FF", Color(0xFFFAF5FF), Color(0xFFFAF5FF)),
            PaperTint("Gece Kağıdı", "#0F172A", Color(0xFF0F172A), Color(0xFF0F172A))
        )

        fun parseColor(hex: String, isDark: Boolean): Color? {
            val clean = hex.substringBefore("|").trim()
            if (clean.equals("#FFFFFF", ignoreCase = true)) {
                return Color(0xFFFFFFFF)
            }
            if (clean.equals("#0F172A", ignoreCase = true) || clean.equals("#1E293B", ignoreCase = true)) {
                return Color(0xFF0F172A)
            }
            val match = Options.firstOrNull { it.hex.equals(clean, ignoreCase = true) }
            if (match != null) {
                return if (match.hex.equals("#F8FAFC", ignoreCase = true)) {
                    if (isDark) match.darkColor else match.lightColor
                } else {
                    match.lightColor
                }
            }
            return runCatching {
                Color(android.graphics.Color.parseColor(clean))
            }.getOrNull()
        }
    }
}

object NoteStyleParser {
    fun getTintHex(colorHex: String?): String {
        if (colorHex.isNullOrBlank()) return "#F8FAFC"
        return colorHex.substringBefore("|").ifBlank { "#F8FAFC" }
    }

    fun getPattern(colorHex: String?): PaperPattern {
        if (colorHex.isNullOrBlank()) return PaperPattern.PLAIN
        val patternStr = colorHex.substringAfter("|", "PLAIN")
        return PaperPattern.fromName(patternStr)
    }

    fun encode(tintHex: String, pattern: PaperPattern): String {
        val clean = tintHex.substringBefore("|").ifBlank { "#F8FAFC" }
        return "$clean|${pattern.name}"
    }
}
