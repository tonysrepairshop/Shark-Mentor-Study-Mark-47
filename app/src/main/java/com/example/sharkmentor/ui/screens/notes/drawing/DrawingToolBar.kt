package com.example.sharkmentor.ui.screens.notes.drawing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

val STUDIO_COLOR_PALETTE = listOf(
    Color(0xFF0F172A), // Deep Ink Black
    Color(0xFF1E88E5), // Royal Blue
    Color(0xFFE53935), // Crimson Red
    Color(0xFF43A047), // Emerald Green
    Color(0xFFFB8C00), // Sunset Orange
    Color(0xFF8E24AA), // Amethyst Purple
    Color(0xFFFFEB3B), // Highlighter Yellow
    Color(0xFF00ACC1), // Cyan Teal
    Color(0xFFD81B60), // Magenta Pink
    Color(0xFF6D4C41), // Warm Brown
    Color(0xFF546E7A), // Slate Grey
    Color(0xFFFFFFFF)  // Pure White
)

/**
 * Full-featured modular Drawing & Annotation Studio Toolbar.
 * Supports Fountain Pen, Highlighter, Technical Pen, Underline, Stroke/Area Erasers,
 * 5 Geometric Shapes (Line, Arrow, Rectangle, Ellipse, Triangle), Lasso (Move/Cut/Copy/Paste/Delete/Recolor),
 * and 60-step Undo/Redo.
 */
@Composable
fun DrawingStudioBar(
    controller: DrawingController,
    modifier: Modifier = Modifier,
    isCompactMode: Boolean = false,
    onCloseStudio: (() -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val surfaceColor = colorScheme.surface
    val onSurfaceColor = colorScheme.onSurface
    val primaryColor = colorScheme.primary

    var showStrokeSettings by remember { mutableStateOf(false) }
    var showShapesRow by remember { mutableStateOf(false) }

    val activeTool = controller.activeTool

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, onSurfaceColor.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. Expandable Shape Picker Row
            AnimatedVisibility(
                visible = showShapesRow || activeTool.isShapeTool,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Şekiller:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor.copy(alpha = 0.7f)
                        )
                        ShapeToolChip(
                            label = "Çizgi",
                            tool = DrawingTool.SHAPE_LINE,
                            isSelected = activeTool == DrawingTool.SHAPE_LINE,
                            onClick = { controller.selectTool(DrawingTool.SHAPE_LINE) }
                        )
                        ShapeToolChip(
                            label = "Ok",
                            tool = DrawingTool.SHAPE_ARROW,
                            isSelected = activeTool == DrawingTool.SHAPE_ARROW,
                            onClick = { controller.selectTool(DrawingTool.SHAPE_ARROW) }
                        )
                        ShapeToolChip(
                            label = "Dikdörtgen",
                            tool = DrawingTool.SHAPE_RECTANGLE,
                            isSelected = activeTool == DrawingTool.SHAPE_RECTANGLE,
                            onClick = { controller.selectTool(DrawingTool.SHAPE_RECTANGLE) }
                        )
                        ShapeToolChip(
                            label = "Daire/Elips",
                            tool = DrawingTool.SHAPE_ELLIPSE,
                            isSelected = activeTool == DrawingTool.SHAPE_ELLIPSE,
                            onClick = { controller.selectTool(DrawingTool.SHAPE_ELLIPSE) }
                        )
                        ShapeToolChip(
                            label = "Üçgen",
                            tool = DrawingTool.SHAPE_TRIANGLE,
                            isSelected = activeTool == DrawingTool.SHAPE_TRIANGLE,
                            onClick = { controller.selectTool(DrawingTool.SHAPE_TRIANGLE) }
                        )
                    }
                    HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.10f))
                }
            }

            // 2. Expandable Lasso Controls Row
            AnimatedVisibility(
                visible = activeTool == DrawingTool.LASSO,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (controller.hasSelection) {
                                "${controller.selectedStrokeIds.size} nesne seçildi:"
                            } else {
                                "Kement: Seçmek için etrafını çizin"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryColor
                        )

                        if (controller.hasSelection) {
                            MiniActionPill("Kes") { controller.cutSelected() }
                            MiniActionPill("Kopyala") { controller.copySelected() }
                        }
                        if (controller.hasClipboard) {
                            MiniActionPill("Yapıştır") { controller.pasteClipboard() }
                        }
                        if (controller.hasSelection) {
                            MiniActionPill(
                                label = "Sil",
                                isDestructive = true,
                                onClick = { controller.deleteSelected() }
                            )
                            MiniActionPill("Seçimi Kaldır") { controller.clearSelection() }

                            Spacer(modifier = Modifier.width(4.dp))
                            STUDIO_COLOR_PALETTE.take(7).forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(1.dp, onSurfaceColor.copy(alpha = 0.3f), CircleShape)
                                        .clickable { controller.recolorSelected(color) }
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.10f))
                }
            }

            // 3. Expandable Color, Thickness & Opacity Panel
            AnimatedVisibility(
                visible = showStrokeSettings,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Color Swatches Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        STUDIO_COLOR_PALETTE.forEach { swatch ->
                            val isSelected = controller.penColor == swatch
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 28.dp else 24.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        controller.updatePenColor(swatch)
                                        if (controller.hasSelection) {
                                            controller.recolorSelected(swatch)
                                        }
                                    }
                            )
                        }
                    }

                    // Thickness Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (activeTool.isEraserTool) "Silgi Boyutu" else "Kalınlık",
                            style = MaterialTheme.typography.labelSmall,
                            color = onSurfaceColor.copy(alpha = 0.75f),
                            modifier = Modifier.width(72.dp)
                        )
                        val currentWidthValue = if (activeTool.isEraserTool) controller.eraserRadius else controller.strokeWidth
                        Slider(
                            value = currentWidthValue,
                            onValueChange = { controller.updateStrokeWidth(it) },
                            valueRange = if (activeTool.isEraserTool) 8f..48f else 1f..36f,
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor
                            )
                        )
                        Text(
                            text = "${currentWidthValue.roundToInt()} px",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor,
                            modifier = Modifier.width(38.dp)
                        )
                    }

                    // Opacity Slider (for pens & shapes)
                    if (!activeTool.isEraserTool) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Opaklık",
                                style = MaterialTheme.typography.labelSmall,
                                color = onSurfaceColor.copy(alpha = 0.75f),
                                modifier = Modifier.width(72.dp)
                            )
                            Slider(
                                value = controller.alpha,
                                onValueChange = { controller.updateAlpha(it) },
                                valueRange = 0.15f..1.0f,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = primaryColor,
                                    activeTrackColor = primaryColor
                                )
                            )
                            Text(
                                text = "%${(controller.alpha * 100).roundToInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor,
                                modifier = Modifier.width(38.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.10f))
                }
            }

            // 4. Main Horizontal Studio Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Navigation / Text mode
                StudioToolButton(
                    label = if (isCompactMode) "Gezin" else "Metin",
                    isSelected = activeTool == DrawingTool.NONE,
                    accentColor = primaryColor,
                    onClick = {
                        showShapesRow = false
                        controller.selectTool(DrawingTool.NONE)
                    }
                ) { tint ->
                    Canvas(modifier = Modifier.size(16.dp)) {
                        // Cursor / pointer icon
                        val path = Path().apply {
                            moveTo(size.width * 0.25f, size.height * 0.15f)
                            lineTo(size.width * 0.25f, size.height * 0.85f)
                            lineTo(size.width * 0.48f, size.height * 0.65f)
                            lineTo(size.width * 0.78f, size.height * 0.65f)
                            close()
                        }
                        drawPath(path, color = tint, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round))
                    }
                }

                // Fountain Pen
                StudioToolButton(
                    label = "Dolma",
                    isSelected = activeTool == DrawingTool.FOUNTAIN_PEN,
                    accentColor = controller.penColor,
                    onClick = {
                        showShapesRow = false
                        controller.selectTool(DrawingTool.FOUNTAIN_PEN)
                    }
                ) { tint ->
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Dolma Kalem",
                        tint = tint,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Highlighter
                StudioToolButton(
                    label = "Fosforlu",
                    isSelected = activeTool == DrawingTool.HIGHLIGHTER,
                    accentColor = Color(0xFFFBC02D),
                    onClick = {
                        showShapesRow = false
                        controller.selectTool(DrawingTool.HIGHLIGHTER)
                    }
                ) { tint ->
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawRect(
                            color = tint.copy(alpha = 0.45f),
                            topLeft = Offset(size.width * 0.1f, size.height * 0.55f),
                            size = Size(size.width * 0.8f, size.height * 0.3f)
                        )
                        drawLine(
                            color = tint,
                            start = Offset(size.width * 0.25f, size.height * 0.7f),
                            end = Offset(size.width * 0.75f, size.height * 0.2f),
                            strokeWidth = 2.6.dp.toPx(),
                            cap = StrokeCap.Square
                        )
                    }
                }

                // Technical Fine Pen
                StudioToolButton(
                    label = "Teknik",
                    isSelected = activeTool == DrawingTool.TECHNICAL_PEN,
                    accentColor = controller.penColor,
                    onClick = {
                        showShapesRow = false
                        controller.selectTool(DrawingTool.TECHNICAL_PEN)
                    }
                ) { tint ->
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawLine(
                            color = tint,
                            start = Offset(size.width * 0.2f, size.height * 0.8f),
                            end = Offset(size.width * 0.8f, size.height * 0.2f),
                            strokeWidth = 1.4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawCircle(
                            color = tint,
                            radius = 1.6.dp.toPx(),
                            center = Offset(size.width * 0.8f, size.height * 0.2f)
                        )
                    }
                }

                // Underline Marker
                StudioToolButton(
                    label = "Alt Çizgi",
                    isSelected = activeTool == DrawingTool.UNDERLINE_MARKER,
                    accentColor = controller.penColor,
                    onClick = {
                        showShapesRow = false
                        controller.selectTool(DrawingTool.UNDERLINE_MARKER)
                    }
                ) { tint ->
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawLine(
                            color = tint,
                            start = Offset(size.width * 0.12f, size.height * 0.78f),
                            end = Offset(size.width * 0.88f, size.height * 0.78f),
                            strokeWidth = 2.4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = tint.copy(alpha = 0.6f),
                            start = Offset(size.width * 0.25f, size.height * 0.35f),
                            end = Offset(size.width * 0.75f, size.height * 0.35f),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Stroke Eraser
                StudioToolButton(
                    label = "Çizgi Sil",
                    isSelected = activeTool == DrawingTool.STROKE_ERASER,
                    accentColor = Color(0xFFEF4444),
                    onClick = {
                        showShapesRow = false
                        controller.selectTool(DrawingTool.STROKE_ERASER)
                    }
                ) { tint ->
                    Canvas(modifier = Modifier.size(16.dp)) {
                        val path = Path().apply {
                            moveTo(size.width * 0.2f, size.height * 0.65f)
                            lineTo(size.width * 0.55f, size.height * 0.2f)
                            lineTo(size.width * 0.85f, size.height * 0.45f)
                            lineTo(size.width * 0.5f, size.height * 0.85f)
                            close()
                        }
                        drawPath(path, color = tint, style = Stroke(width = 1.7.dp.toPx(), join = StrokeJoin.Round))
                    }
                }

                // Area / Point Eraser
                StudioToolButton(
                    label = "Alan Sil",
                    isSelected = activeTool == DrawingTool.AREA_ERASER,
                    accentColor = Color(0xFFEF4444),
                    onClick = {
                        showShapesRow = false
                        controller.selectTool(DrawingTool.AREA_ERASER)
                    }
                ) { tint ->
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawCircle(
                            color = tint,
                            radius = size.minDimension * 0.38f,
                            style = Stroke(
                                width = 1.7.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                            )
                        )
                    }
                }

                // Shapes Menu Toggle
                StudioToolButton(
                    label = "Şekil",
                    isSelected = activeTool.isShapeTool || showShapesRow,
                    accentColor = primaryColor,
                    onClick = {
                        showShapesRow = !showShapesRow
                        if (!activeTool.isShapeTool && showShapesRow) {
                            controller.selectTool(DrawingTool.SHAPE_RECTANGLE)
                        }
                    }
                ) { tint ->
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawRect(
                            color = tint,
                            topLeft = Offset(size.width * 0.15f, size.height * 0.2f),
                            size = Size(size.width * 0.48f, size.height * 0.48f),
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                        drawCircle(
                            color = tint,
                            radius = size.minDimension * 0.26f,
                            center = Offset(size.width * 0.66f, size.height * 0.66f),
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                    }
                }

                // Lasso Tool
                StudioToolButton(
                    label = "Kement",
                    isSelected = activeTool == DrawingTool.LASSO,
                    accentColor = Color(0xFF0288D1),
                    onClick = {
                        showShapesRow = false
                        controller.selectTool(DrawingTool.LASSO)
                    }
                ) { tint ->
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawOval(
                            color = tint,
                            topLeft = Offset(size.width * 0.12f, size.height * 0.18f),
                            size = Size(size.width * 0.76f, size.height * 0.60f),
                            style = Stroke(
                                width = 1.7.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                            )
                        )
                    }
                }

                VerticalDivider(
                    modifier = Modifier
                        .height(26.dp)
                        .padding(horizontal = 2.dp),
                    color = onSurfaceColor.copy(alpha = 0.16f)
                )

                // Color & Thickness Settings Button
                IconButton(
                    onClick = { showStrokeSettings = !showStrokeSettings },
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (showStrokeSettings) Icons.Default.Tune else Icons.Default.Palette,
                            contentDescription = "Kalem Renk ve Kalınlık Ayarları",
                            tint = if (showStrokeSettings) primaryColor else onSurfaceColor,
                            modifier = Modifier.size(19.dp)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(controller.penColor)
                                .border(1.dp, surfaceColor, CircleShape)
                        )
                    }
                }

                // Undo (60 steps)
                IconButton(
                    onClick = { controller.undo() },
                    enabled = controller.canUndo,
                    modifier = Modifier.size(32.dp)
                ) {
                    val tint = if (controller.canUndo) onSurfaceColor else onSurfaceColor.copy(alpha = 0.3f)
                    Canvas(modifier = Modifier.size(17.dp)) {
                        val path = Path().apply {
                            moveTo(size.width * 0.42f, size.height * 0.22f)
                            lineTo(size.width * 0.18f, size.height * 0.45f)
                            lineTo(size.width * 0.42f, size.height * 0.68f)
                        }
                        drawPath(path, color = tint, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawArc(
                            color = tint,
                            startAngle = 180f,
                            sweepAngle = 165f,
                            useCenter = false,
                            topLeft = Offset(size.width * 0.20f, size.height * 0.32f),
                            size = Size(size.width * 0.65f, size.height * 0.50f),
                            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                // Redo (60 steps)
                IconButton(
                    onClick = { controller.redo() },
                    enabled = controller.canRedo,
                    modifier = Modifier.size(32.dp)
                ) {
                    val tint = if (controller.canRedo) onSurfaceColor else onSurfaceColor.copy(alpha = 0.3f)
                    Canvas(modifier = Modifier.size(17.dp)) {
                        val path = Path().apply {
                            moveTo(size.width * 0.58f, size.height * 0.22f)
                            lineTo(size.width * 0.82f, size.height * 0.45f)
                            lineTo(size.width * 0.58f, size.height * 0.68f)
                        }
                        drawPath(path, color = tint, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawArc(
                            color = tint,
                            startAngle = 195f,
                            sweepAngle = 165f,
                            useCenter = false,
                            topLeft = Offset(size.width * 0.15f, size.height * 0.32f),
                            size = Size(size.width * 0.65f, size.height * 0.50f),
                            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                // Clear All
                if (controller.strokes.isNotEmpty()) {
                    IconButton(
                        onClick = { controller.clearAll() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Çizimleri Temizle",
                            tint = colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                if (onCloseStudio != null) {
                    IconButton(
                        onClick = onCloseStudio,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Çizim Stüdyosunu Kapat",
                            tint = onSurfaceColor.copy(alpha = 0.65f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioToolButton(
    label: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    iconContent: @Composable (tint: Color) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val effectiveAccent = if (accentColor == Color.White) primary else accentColor
    val bgColor = if (isSelected) effectiveAccent.copy(alpha = 0.16f) else Color.Transparent
    val borderColor = if (isSelected) effectiveAccent else onSurface.copy(alpha = 0.12f)
    val contentTint = if (isSelected) effectiveAccent else onSurface.copy(alpha = 0.8f)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            iconContent(contentTint)
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentTint
            )
        }
    }
}

@Composable
private fun ShapeToolChip(
    label: String,
    tool: DrawingTool,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) primary.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(1.dp, if (isSelected) primary else onSurface.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val tint = if (isSelected) primary else onSurface.copy(alpha = 0.8f)
            Canvas(modifier = Modifier.size(13.dp)) {
                when (tool) {
                    DrawingTool.SHAPE_LINE -> {
                        drawLine(tint, Offset(2f, size.height - 2f), Offset(size.width - 2f, 2f), strokeWidth = 2f)
                    }
                    DrawingTool.SHAPE_ARROW -> {
                        drawLine(tint, Offset(2f, size.height - 2f), Offset(size.width - 2f, 2f), strokeWidth = 2f)
                        drawLine(tint, Offset(size.width - 2f, 2f), Offset(size.width * 0.45f, 2f), strokeWidth = 2f)
                        drawLine(tint, Offset(size.width - 2f, 2f), Offset(size.width - 2f, size.height * 0.55f), strokeWidth = 2f)
                    }
                    DrawingTool.SHAPE_RECTANGLE -> {
                        drawRect(tint, Offset(2f, 3f), Size(size.width - 4f, size.height - 6f), style = Stroke(width = 2f))
                    }
                    DrawingTool.SHAPE_ELLIPSE -> {
                        drawOval(tint, Offset(2f, 2f), Size(size.width - 4f, size.height - 4f), style = Stroke(width = 2f))
                    }
                    DrawingTool.SHAPE_TRIANGLE -> {
                        val p = Path().apply {
                            moveTo(size.width * 0.5f, 2f)
                            lineTo(size.width - 2f, size.height - 2f)
                            lineTo(2f, size.height - 2f)
                            close()
                        }
                        drawPath(p, tint, style = Stroke(width = 2f, join = StrokeJoin.Round))
                    }
                    else -> {}
                }
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = tint
            )
        }
    }
}

@Composable
private fun MiniActionPill(
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val fg = if (isDestructive) colorScheme.error else colorScheme.onSecondaryContainer
    val bg = if (isDestructive) colorScheme.errorContainer.copy(alpha = 0.5f) else colorScheme.secondaryContainer
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bg
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
