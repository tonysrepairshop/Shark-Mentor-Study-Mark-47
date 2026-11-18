package com.example.sharkmentor.ui.screens.leaguechat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.data.AppThemeMode
import com.example.sharkmentor.core.data.ThemePalette
import com.example.sharkmentor.core.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zero-network vector chat wallpaper reacting dynamically to [ThemePalette],
 * dark/light [AppThemeMode], and Zen Mode.
 */
@Composable
fun ChatWallpaperCanvas(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themePrefs = remember(context) {
        runCatching { ThemePreferences.initialize(context.applicationContext) }.getOrNull()
    }

    val paletteFlow = remember(themePrefs) { themePrefs?.palette ?: MutableStateFlow(ThemePalette.OCEAN) }
    val themeModeFlow = remember(themePrefs) { themePrefs?.themeMode ?: MutableStateFlow(AppThemeMode.SYSTEM) }
    val zenModeFlow = remember(themePrefs) { themePrefs?.isZenMode ?: MutableStateFlow(false) }

    val palette by paletteFlow.collectAsStateWithLifecycle()
    val themeMode by themeModeFlow.collectAsStateWithLifecycle()
    val isZenMode by zenModeFlow.collectAsStateWithLifecycle()

    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val baseBg = MaterialTheme.colorScheme.background
    val primaryAccent = if (isZenMode) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    } else {
        Color(palette.primaryHex)
    }
    val secondaryAccent = MaterialTheme.colorScheme.secondary

    val gradientColors = if (isZenMode) {
        listOf(Color(0xFF000000), Color(0xFF0A0A0A))
    } else if (isDark) {
        listOf(
            baseBg,
            Color(palette.bgHex).copy(alpha = 0.92f),
            baseBg
        )
    } else {
        listOf(
            baseBg,
            primaryAccent.copy(alpha = 0.05f),
            baseBg
        )
    }

    val strokeAlpha = when {
        isZenMode -> 0.045f
        isDark -> 0.095f
        else -> 0.075f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradientColors))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val motifColor = primaryAccent.copy(alpha = strokeAlpha)
            val subtleColor = secondaryAccent.copy(alpha = strokeAlpha * 0.7f)

            if (isZenMode) {
                // Ultra-minimal calm focus dots
                val step = (w / 7f).coerceAtLeast(64f)
                var y = step * 0.6f
                while (y < h) {
                    var x = step * 0.5f
                    while (x < w) {
                        drawCircle(
                            color = motifColor,
                            radius = 2.2f,
                            center = Offset(x, y)
                        )
                        x += step
                    }
                    y += step
                }
                return@Canvas
            }

            when (palette) {
                ThemePalette.OCEAN -> {
                    // Flowing deep-sea waves + subtle sonar rings
                    for (waveIdx in 0 until 5) {
                        val baseY = h * (0.16f + waveIdx * 0.18f)
                        val amplitude = 18f + waveIdx * 4f
                        val path = Path().apply {
                            moveTo(0f, baseY)
                            var x = 0f
                            while (x <= w + 24f) {
                                val angle = (x / w) * 2.0 * PI * 1.8 + waveIdx * 0.9
                                val y = baseY + (sin(angle) * amplitude).toFloat()
                                lineTo(x, y)
                                x += 20f
                            }
                        }
                        drawPath(
                            path = path,
                            color = motifColor,
                            style = Stroke(width = 2.4f, cap = StrokeCap.Round)
                        )
                    }
                    drawCircle(
                        color = subtleColor,
                        radius = w * 0.28f,
                        center = Offset(w * 0.85f, h * 0.14f),
                        style = Stroke(width = 2f)
                    )
                    drawCircle(
                        color = subtleColor,
                        radius = w * 0.18f,
                        center = Offset(w * 0.12f, h * 0.82f),
                        style = Stroke(width = 2f)
                    )
                }

                ThemePalette.FOREST -> {
                    // Topographic hills & minimal evergreen spires
                    for (layer in 0 until 4) {
                        val yBase = h * (0.24f + layer * 0.2f)
                        val path = Path().apply {
                            moveTo(0f, yBase + 32f)
                            quadraticTo(w * 0.25f, yBase - 36f, w * 0.52f, yBase + 14f)
                            quadraticTo(w * 0.8f, yBase + 46f, w, yBase - 22f)
                        }
                        drawPath(
                            path = path,
                            color = motifColor,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                        )
                    }
                    val treeXs = listOf(w * 0.14f, w * 0.78f, w * 0.42f)
                    val treeYs = listOf(h * 0.2f, h * 0.52f, h * 0.84f)
                    treeXs.indices.forEach { i ->
                        val cx = treeXs[i]
                        val cy = treeYs[i]
                        val tri = Path().apply {
                            moveTo(cx, cy - 28f)
                            lineTo(cx - 18f, cy + 16f)
                            lineTo(cx + 18f, cy + 16f)
                            close()
                        }
                        drawPath(tri, color = subtleColor, style = Stroke(width = 2f))
                    }
                }

                ThemePalette.RAINY -> {
                    // Crisp diagonal rain streaks + gentle puddle ripples
                    val spacing = 92f
                    var startX = -h * 0.3f
                    var count = 0
                    while (startX < w + h * 0.3f) {
                        val topY = (count % 3) * 80f
                        val len = 64f + (count % 4) * 28f
                        drawLine(
                            color = motifColor,
                            start = Offset(startX, topY),
                            end = Offset(startX - len * 0.35f, topY + len),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = subtleColor,
                            start = Offset(startX + 20f, h * 0.48f + (count % 3) * 60f),
                            end = Offset(startX + 20f - len * 0.35f, h * 0.48f + (count % 3) * 60f + len),
                            strokeWidth = 1.8f,
                            cap = StrokeCap.Round
                        )
                        startX += spacing
                        count++
                    }
                    drawOval(
                        color = motifColor,
                        topLeft = Offset(w * 0.55f, h * 0.74f),
                        size = Size(140f, 38f),
                        style = Stroke(width = 2f)
                    )
                }

                ThemePalette.COFFEE -> {
                    // Warm aromatic steam ribbons & subtle latte rings
                    for (i in 0 until 4) {
                        val x = w * (0.18f + i * 0.22f)
                        val steam = Path().apply {
                            moveTo(x, h * 0.85f)
                            cubicTo(
                                x - 34f, h * 0.64f,
                                x + 38f, h * 0.42f,
                                x - 14f, h * 0.16f
                            )
                        }
                        drawPath(
                            path = steam,
                            color = motifColor,
                            style = Stroke(width = 2.6f, cap = StrokeCap.Round)
                        )
                    }
                    drawCircle(
                        color = subtleColor,
                        radius = w * 0.22f,
                        center = Offset(w * 0.82f, h * 0.22f),
                        style = Stroke(width = 2f)
                    )
                }

                ThemePalette.DREAMS, ThemePalette.SAKURA -> {
                    // Floating orbital arcs & constellation/blossom dots
                    for (r in 1..4) {
                        drawCircle(
                            color = motifColor,
                            radius = (w * 0.15f) * r,
                            center = Offset(w * 0.5f, h * 0.48f),
                            style = Stroke(width = 1.6f)
                        )
                    }
                    for (i in 0 until 14) {
                        val angle = i * (2.0 * PI / 14.0)
                        val rad = w * (0.2f + (i % 3) * 0.11f)
                        val px = w * 0.5f + (cos(angle) * rad).toFloat()
                        val py = h * 0.48f + (sin(angle) * rad).toFloat()
                        drawCircle(
                            color = subtleColor,
                            radius = if (i % 2 == 0) 6f else 3.5f,
                            center = Offset(px, py)
                        )
                    }
                }

                ThemePalette.AUTUMN -> {
                    // Warm curved leaf veins & horizon arcs
                    for (i in 0 until 5) {
                        val y = h * (0.18f + i * 0.16f)
                        drawArc(
                            color = motifColor,
                            startAngle = 190f,
                            sweepAngle = 160f,
                            useCenter = false,
                            topLeft = Offset(-w * 0.1f, y),
                            size = Size(w * 1.2f, 120f),
                            style = Stroke(width = 2.4f, cap = StrokeCap.Round)
                        )
                    }
                }

                ThemePalette.SOOTY -> {
                    // Clean geometric desk grid & subtle bathymetric arcs
                    val step = (w / 5f).coerceAtLeast(84f)
                    var gx = step * 0.5f
                    while (gx < w) {
                        drawLine(
                            color = motifColor.copy(alpha = strokeAlpha * 0.55f),
                            start = Offset(gx, 0f),
                            end = Offset(gx, h),
                            strokeWidth = 1f
                        )
                        gx += step
                    }
                    var gy = step * 0.5f
                    while (gy < h) {
                        drawLine(
                            color = motifColor.copy(alpha = strokeAlpha * 0.55f),
                            start = Offset(0f, gy),
                            end = Offset(w, gy),
                            strokeWidth = 1f
                        )
                        gy += step
                    }
                    drawCircle(
                        color = subtleColor,
                        radius = w * 0.34f,
                        center = Offset(w * 0.88f, h * 0.18f),
                        style = Stroke(width = 2f)
                    )
                }
            }
        }
    }
}
