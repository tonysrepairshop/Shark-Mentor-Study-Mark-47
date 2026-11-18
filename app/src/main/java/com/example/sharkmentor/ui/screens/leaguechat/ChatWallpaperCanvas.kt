package com.example.sharkmentor.ui.screens.leaguechat

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.data.AppThemeMode
import com.example.sharkmentor.core.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ChatWallpaperStyle(
    val id: String,
    val title: String,
    val subtitle: String,
    val emoji: String
) {
    SADE_TEMA(
        id = "sade_tema",
        title = "Sade Tema",
        subtitle = "Temiz ve odak dostu zemin",
        emoji = ""
    ),
    KARELI_MATEMATIK(
        id = "kareli_matematik",
        title = "Kareli Matematik Defteri",
        subtitle = "Klasik kareli defter çizgileri",
        emoji = ""
    ),
    NOKTALI_PLANLAYICI(
        id = "noktali_planlayici",
        title = "Noktalı Planlayıcı",
        subtitle = "Minimal nokta matrisi",
        emoji = ""
    ),
    GEOMETRIK_CIZIMLER(
        id = "geometrik_cizimler",
        title = "Geometrik Çizimler",
        subtitle = "Üçgen, altıgen ve açı desenleri",
        emoji = ""
    ),
    OKYANUS_DALGALARI(
        id = "okyanus_dalgalari",
        title = "Okyanus Dalgaları",
        subtitle = "Shark derin deniz dalgaları",
        emoji = ""
    ),
    FORMUL_SEMBOLLER(
        id = "formul_semboller",
        title = "Formül & Semboller",
        subtitle = "∑ ∫ π Δ √ ∞ bilimsel semboller",
        emoji = ""
    );

    companion object {
        fun fromId(id: String?): ChatWallpaperStyle =
            entries.firstOrNull { it.id == id } ?: OKYANUS_DALGALARI
    }
}

object ChatWallpaperPrefs {
    private const val PREFS_NAME = "shark_chat_prefs"
    private const val KEY_WALLPAPER_STYLE = "chat_wallpaper_style"

    private val _styleFlow = MutableStateFlow(ChatWallpaperStyle.OKYANUS_DALGALARI)
    private var initialized = false

    fun observeStyle(context: Context): StateFlow<ChatWallpaperStyle> {
        if (!initialized) {
            val savedId = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_WALLPAPER_STYLE, null)
            _styleFlow.value = ChatWallpaperStyle.fromId(savedId)
            initialized = true
        }
        return _styleFlow.asStateFlow()
    }

    fun setStyle(context: Context, style: ChatWallpaperStyle) {
        initialized = true
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WALLPAPER_STYLE, style.id)
            .apply()
        _styleFlow.value = style
    }
}

/**
 * Zero-network vector chat wallpaper offering 6 study patterns whose colors are
 * derived at low opacity from the active [Theme.kt] [MaterialTheme.colorScheme]
 * (Dark/Light, 8 ThemePalettes, and Zen Mode).
 */
@Composable
fun ChatWallpaperCanvas(
    styleOverride: ChatWallpaperStyle? = null,
    isMiniaturePreview: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themePrefs = remember(context) {
        runCatching { ThemePreferences.initialize(context.applicationContext) }.getOrNull()
    }

    val themeModeFlow = remember(themePrefs) { themePrefs?.themeMode ?: MutableStateFlow(AppThemeMode.SYSTEM) }
    val zenModeFlow = remember(themePrefs) { themePrefs?.isZenMode ?: MutableStateFlow(false) }
    val selectedStyleFlow = remember(context) { ChatWallpaperPrefs.observeStyle(context) }

    val themeMode by themeModeFlow.collectAsStateWithLifecycle()
    val isZenMode by zenModeFlow.collectAsStateWithLifecycle()
    val savedStyle by selectedStyleFlow.collectAsStateWithLifecycle()
    val activeStyle = styleOverride ?: savedStyle

    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val colorScheme = MaterialTheme.colorScheme
    val baseBg = colorScheme.background
    val surfaceBg = colorScheme.surface
    val primaryColor = colorScheme.primary
    val subtleColor = colorScheme.onSurfaceVariant

    val gradientColors = if (isZenMode) {
        listOf(baseBg, surfaceBg, baseBg)
    } else if (isDark) {
        listOf(
            baseBg,
            primaryColor.copy(alpha = 0.08f),
            surfaceBg.copy(alpha = 0.94f),
            baseBg
        )
    } else {
        listOf(
            baseBg,
            primaryColor.copy(alpha = 0.06f),
            colorScheme.surfaceContainer.copy(alpha = 0.72f),
            baseBg
        )
    }

    val primaryAlpha = when {
        isMiniaturePreview && isZenMode -> 0.14f
        isMiniaturePreview -> 0.22f
        isZenMode -> 0.055f
        isDark -> 0.11f
        else -> 0.095f
    }
    val secondaryAlpha = when {
        isMiniaturePreview -> primaryAlpha * 0.75f
        isZenMode -> 0.04f
        isDark -> 0.08f
        else -> 0.07f
    }

    val symbolPaint = remember(primaryColor, primaryAlpha, isMiniaturePreview) {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
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

            val motifPrimary = primaryColor.copy(alpha = primaryAlpha)
            val motifSecondary = subtleColor.copy(alpha = secondaryAlpha)

            when (activeStyle) {
                ChatWallpaperStyle.SADE_TEMA -> {
                    // Clean soft radial glow with ultra-minimal subtle accent rings
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = primaryAlpha * 0.95f),
                                Color.Transparent
                            ),
                            center = Offset(w * 0.82f, h * 0.18f),
                            radius = w * 0.65f
                        ),
                        radius = w * 0.65f,
                        center = Offset(w * 0.82f, h * 0.18f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                subtleColor.copy(alpha = secondaryAlpha * 0.75f),
                                Color.Transparent
                            ),
                            center = Offset(w * 0.18f, h * 0.78f),
                            radius = w * 0.55f
                        ),
                        radius = w * 0.55f,
                        center = Offset(w * 0.18f, h * 0.78f)
                    )
                }

                ChatWallpaperStyle.KARELI_MATEMATIK -> {
                    // Math graph notebook: fine squares + emphasized 5th grid lines + left margin line
                    val minorStep = if (isMiniaturePreview) 16.dp.toPx() else 24.dp.toPx()
                    var col = 0
                    var gx = 0f
                    while (gx <= w) {
                        val isMajor = (col % 5 == 0)
                        drawLine(
                            color = if (isMajor) motifPrimary.copy(alpha = primaryAlpha * 1.1f) else motifSecondary.copy(alpha = secondaryAlpha * 0.7f),
                            start = Offset(gx, 0f),
                            end = Offset(gx, h),
                            strokeWidth = if (isMajor) 1.5f else 0.9f
                        )
                        gx += minorStep
                        col++
                    }
                    var row = 0
                    var gy = 0f
                    while (gy <= h) {
                        val isMajor = (row % 5 == 0)
                        drawLine(
                            color = if (isMajor) motifPrimary.copy(alpha = primaryAlpha * 1.1f) else motifSecondary.copy(alpha = secondaryAlpha * 0.7f),
                            start = Offset(0f, gy),
                            end = Offset(w, gy),
                            strokeWidth = if (isMajor) 1.5f else 0.9f
                        )
                        gy += minorStep
                        row++
                    }
                    // Subtle vertical margin rule like a study notebook
                    val marginX = w * 0.085f
                    drawLine(
                        color = primaryColor.copy(alpha = primaryAlpha * 1.35f),
                        start = Offset(marginX, 0f),
                        end = Offset(marginX, h),
                        strokeWidth = 2.2f
                    )
                }

                ChatWallpaperStyle.NOKTALI_PLANLAYICI -> {
                    // Bullet-journal dot planner grid with subtle anchor nodes every 4th intersection
                    val step = if (isMiniaturePreview) 14.dp.toPx() else 22.dp.toPx()
                    val baseR = if (isMiniaturePreview) 1.5f else 2.2f
                    var r = 0
                    var y = step * 0.5f
                    while (y < h) {
                        var c = 0
                        var x = step * 0.5f
                        while (x < w) {
                            val isAnchor = (r % 4 == 0 && c % 4 == 0)
                            drawCircle(
                                color = if (isAnchor) motifPrimary else motifSecondary,
                                radius = if (isAnchor) baseR * 1.55f else baseR,
                                center = Offset(x, y)
                            )
                            x += step
                            c++
                        }
                        y += step
                        r++
                    }
                }

                ChatWallpaperStyle.GEOMETRIK_CIZIMLER -> {
                    // Geometric triangles, hexagons, circles, and right-angle markers
                    val cell = if (isMiniaturePreview) 54.dp.toPx() else 110.dp.toPx()
                    var row = 0
                    var cy = cell * 0.5f
                    while (cy < h + cell) {
                        var col = 0
                        var cx = cell * 0.5f + if (row % 2 == 1) cell * 0.35f else 0f
                        while (cx < w + cell) {
                            val shapeType = (row + col) % 3
                            when (shapeType) {
                                0 -> {
                                    // Inscribed triangle + circle
                                    val r = cell * 0.3f
                                    drawCircle(
                                        color = motifSecondary,
                                        radius = r,
                                        center = Offset(cx, cy),
                                        style = Stroke(width = 1.6f)
                                    )
                                    val tri = Path().apply {
                                        for (i in 0 until 3) {
                                            val ang = (-PI / 2.0) + i * (2.0 * PI / 3.0)
                                            val px = cx + (cos(ang) * r).toFloat()
                                            val py = cy + (sin(ang) * r).toFloat()
                                            if (i == 0) moveTo(px, py) else lineTo(px, py)
                                        }
                                        close()
                                    }
                                    drawPath(tri, color = motifPrimary, style = Stroke(width = 1.8f))
                                }
                                1 -> {
                                    // Regular hexagon
                                    val r = cell * 0.28f
                                    val hex = Path().apply {
                                        for (i in 0 until 6) {
                                            val ang = i * (PI / 3.0)
                                            val px = cx + (cos(ang) * r).toFloat()
                                            val py = cy + (sin(ang) * r).toFloat()
                                            if (i == 0) moveTo(px, py) else lineTo(px, py)
                                        }
                                        close()
                                    }
                                    drawPath(hex, color = motifPrimary, style = Stroke(width = 1.7f))
                                }
                                else -> {
                                    // Right triangle with angle arc
                                    val s = cell * 0.26f
                                    val rightTri = Path().apply {
                                        moveTo(cx - s, cy + s)
                                        lineTo(cx - s, cy - s)
                                        lineTo(cx + s, cy + s)
                                        close()
                                    }
                                    drawPath(rightTri, color = motifSecondary, style = Stroke(width = 1.7f))
                                    drawArc(
                                        color = motifPrimary,
                                        startAngle = 270f,
                                        sweepAngle = 90f,
                                        useCenter = false,
                                        topLeft = Offset(cx - s - 10f, cy + s - 10f),
                                        size = Size(20f, 20f),
                                        style = Stroke(width = 1.4f)
                                    )
                                }
                            }
                            cx += cell * 1.15f
                            col++
                        }
                        cy += cell
                        row++
                    }
                }

                ChatWallpaperStyle.OKYANUS_DALGALARI -> {
                    // Flowing ocean wave ribbons + sonar arcs
                    val waveCount = if (isMiniaturePreview) 4 else 6
                    for (waveIdx in 0 until waveCount) {
                        val baseY = h * (0.14f + waveIdx * (0.76f / waveCount))
                        val amplitude = (if (isMiniaturePreview) 7f else 18f) + waveIdx * 3f
                        val path = Path().apply {
                            moveTo(0f, baseY)
                            var x = 0f
                            val step = if (isMiniaturePreview) 8f else 18f
                            while (x <= w + step) {
                                val angle = (x / w) * 2.0 * PI * 1.85 + waveIdx * 0.85
                                val y = baseY + (sin(angle) * amplitude).toFloat()
                                lineTo(x, y)
                                x += step
                            }
                        }
                        drawPath(
                            path = path,
                            color = if (waveIdx % 2 == 0) motifPrimary else motifSecondary,
                            style = Stroke(width = if (isMiniaturePreview) 1.6f else 2.4f, cap = StrokeCap.Round)
                        )
                    }
                    drawCircle(
                        color = motifSecondary,
                        radius = w * 0.28f,
                        center = Offset(w * 0.86f, h * 0.14f),
                        style = Stroke(width = 1.8f)
                    )
                    drawCircle(
                        color = motifPrimary,
                        radius = w * 0.16f,
                        center = Offset(w * 0.12f, h * 0.84f),
                        style = Stroke(width = 1.6f)
                    )
                }

                ChatWallpaperStyle.FORMUL_SEMBOLLER -> {
                    // Mathematical & science symbols + delicate parabola arc at low opacity
                    val symbols = listOf("∑", "∫", "π", "Δ", "√", "∞", "f'(x)", "θ", "λ", "α", "lim", "H₂O")
                    val stepX = if (isMiniaturePreview) 36.dp.toPx() else 88.dp.toPx()
                    val stepY = if (isMiniaturePreview) 30.dp.toPx() else 84.dp.toPx()
                    val textSizePx = if (isMiniaturePreview) 11.sp.toPx() else 20.sp.toPx()
                    symbolPaint.textSize = textSizePx

                    // Subtle coordinate parabola behind symbols
                    val para = Path().apply {
                        moveTo(w * 0.1f, h * 0.25f)
                        quadraticTo(w * 0.5f, h * 0.92f, w * 0.9f, h * 0.25f)
                    }
                    drawPath(
                        path = para,
                        color = motifSecondary.copy(alpha = secondaryAlpha * 0.6f),
                        style = Stroke(width = 1.6f, cap = StrokeCap.Round)
                    )

                    drawIntoCanvas { canvas ->
                        val nc = canvas.nativeCanvas
                        var r = 0
                        var y = stepY * 0.65f
                        while (y < h) {
                            var c = 0
                            var x = stepX * 0.5f + if (r % 2 == 1) stepX * 0.42f else 0f
                            while (x < w) {
                                val symIdx = (r * 5 + c * 3) % symbols.size
                                val sym = symbols[symIdx]
                                val isPrim = (r + c) % 2 == 0
                                symbolPaint.color = (if (isPrim) motifPrimary else motifSecondary).toArgb()
                                val angleDeg = (((r + c) % 5) - 2) * 7f
                                nc.save()
                                nc.rotate(angleDeg, x, y)
                                nc.drawText(sym, x, y, symbolPaint)
                                nc.restore()
                                x += stepX
                                c++
                            }
                            y += stepY
                            r++
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWallpaperPickerSheet(
    currentStyle: ChatWallpaperStyle,
    onSelectStyle: (ChatWallpaperStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sohbet Duvar Kağıdı",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Temana uyumlu 6 desenden birini seç",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ChatWallpaperStyle.entries.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { style ->
                        val isSelected = (style == currentStyle)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    onSelectStyle(style)
                                    onDismiss()
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(68.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    ChatWallpaperCanvas(
                                        styleOverride = style,
                                        isMiniaturePreview = true,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isSelected) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .size(22.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Seçili",
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                Text(
                                    text = style.title,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = style.subtitle,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
