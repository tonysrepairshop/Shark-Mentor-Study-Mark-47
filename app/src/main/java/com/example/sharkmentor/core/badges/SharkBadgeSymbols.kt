package com.example.sharkmentor.core.badges

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class BadgeCategory(
    val title: String,
    val shortTitle: String,
    val emoji: String,
    val accentColorHex: Long
) {
    MENTORSHIP("Mentorluk & Odak", "Mentorluk", "", 0xFF0284C7),
    MATH_GEO("Matematik & Geometri", "Mat / Geo", "", 0xFF4F46E5),
    PHYSICS("Fizik", "Fizik", "", 0xFF0369A1),
    CHEMISTRY("Kimya", "Kimya", "", 0xFF7C3AED),
    BIOLOGY("Biyoloji", "Biyoloji", "", 0xFF15803D),
    TURKISH_LIT("Türkçe & Edebiyat", "Türkçe / Edb", "", 0xFFBE185D),
    SOCIAL_LANG("Sosyal & Yabancı Dil", "Sosyal / Dil", "", 0xFF0F766E)
}

/**
 * Renders a badge icon inside a vibrant gradient circle.
 * When [SharkBadgeDef.symbolText] is non-blank (e.g. "f'(x)", "∫", "lim", "△", "sin θ",
 * "p⃗", "Ω", "λ", "pH", "C₆H₆", "ATP", "DNA", "¶", "1453", "36°N", "Φ"), renders a crisp
 * typographic/mathematical symbol; otherwise falls back to [SharkBadgeDef.icon].
 */
@Composable
fun SharkBadgeSymbolIcon(
    badge: SharkBadgeDef,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    iconSize: Dp = (size.value * 0.6f).dp,
    fontSize: TextUnit = TextUnit.Unspecified,
    tier: BadgeTier? = null,
    isLocked: Boolean = false
) {
    val startColor = if (isLocked) {
        Color(badge.primaryColorHex).copy(alpha = 0.38f)
    } else {
        Color(badge.primaryColorHex)
    }
    val endColor = if (isLocked) {
        Color(badge.secondaryColorHex).copy(alpha = 0.38f)
    } else {
        Color(badge.secondaryColorHex)
    }
    val symbol = badge.symbolText.trim()

    val resolvedFontSize = if (fontSize != TextUnit.Unspecified) {
        fontSize
    } else {
        val len = symbol.length
        val scale = when {
            len <= 1 -> 0.52f
            len == 2 -> 0.43f
            len == 3 -> 0.35f
            len == 4 -> 0.30f
            else -> 0.26f
        }
        (size.value * scale).coerceAtLeast(7.5f).sp
    }

    val useMathSerif = symbol.any { ch ->
        ch in listOf('∫', '∑', '∏', '√', '∞', '∂', 'Δ', 'Ω', 'λ', 'θ', 'ω', 'τ', 'ν', 'α', 'β', 'γ', 'Φ', 'f', 'x')
    }

    val baseModifier = modifier
        .size(size)
        .clip(CircleShape)
        .background(Brush.linearGradient(listOf(startColor, endColor)))
        .let { mod ->
            if (tier != null) {
                mod.border(
                    width = if (size >= 32.dp) 1.5.dp else 1.dp,
                    color = Color(tier.secondaryColorHex).copy(alpha = if (isLocked) 0.35f else 0.75f),
                    shape = CircleShape
                )
            } else {
                mod
            }
        }

    Box(
        modifier = baseModifier,
        contentAlignment = Alignment.Center
    ) {
        if (symbol.isNotEmpty()) {
            Text(
                text = symbol,
                color = if (isLocked) Color.White.copy(alpha = 0.72f) else Color.White,
                style = TextStyle(
                    fontSize = resolvedFontSize,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = if (useMathSerif) FontFamily.Serif else FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                    letterSpacing = if (symbol.length >= 4) (-0.4).sp else (-0.15).sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        } else {
            Icon(
                imageVector = badge.icon,
                contentDescription = badge.title,
                tint = if (isLocked) Color.White.copy(alpha = 0.72f) else Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
