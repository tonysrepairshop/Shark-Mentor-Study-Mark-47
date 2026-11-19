package com.example.sharkmentor.ui.screens.notes.drawing

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * Supports 5 Pen Packages via long-press (Fountain, Ballpoint, Pencil, Brush, Highlighter),
 * Stroke/Area Erasers, 5 Geometric Shapes, Lasso, and 60-step Undo/Redo.
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
    val haptic = LocalHapticFeedback.current

    var showStrokeSettings by remember { mutableStateOf(false) }
    var showShapesRow by remember { mutableStateOf(false) }
    var showPenPackages by remember { mutableStateOf(false) }

    val activeTool = controller.activeTool
    val currentPenTool = if (activeTool.isPenTool) activeTool else controller.lastSelectedPenTool

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, onSurfaceColor.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 0. Expandable Pen Packages Picker Row (opened via long-press on Pen)
            AnimatedVisibility(
                visible = showPenPackages,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PenPackagePickerPanel(
                        controller = controller,
                        onDismiss = { showPenPackages = false }
                    )
                    HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.10f))
                }
            }

            // 1. Expandable Shape Picker Row
            AnimatedVisibility(
                visible = showShapesRow || activeTool.isShapeTool,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PenShapePickerPanel(controller = controller)
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
                    PenLassoActionsPanel(controller = controller)
                    HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.10f))
                }
            }

            // 3. Expandable Color, Thickness & (Highlighter-only) Opacity Panel
            AnimatedVisibility(
                visible = showStrokeSettings,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PenStrokeSettingsPanel(controller = controller)
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
                        showPenPackages = false
                        controller.selectTool(DrawingTool.NONE)
                    }
                ) { tint ->
                    PenToolGlyph(tool = DrawingTool.NONE, tint = tint)
                }

                // Unified Pen Tool Button (Tap: select pen or toggle packages if already active; Long-press: Pen Packages)
                StudioToolButton(
                    label = currentPenTool.shortLabel,
                    isSelected = activeTool.isPenTool || showPenPackages,
                    accentColor = if (currentPenTool == DrawingTool.HIGHLIGHTER) Color(0xFFFBC02D) else controller.penColor,
                    onClick = {
                        showShapesRow = false
                        if (activeTool.isPenTool) {
                            showPenPackages = !showPenPackages
                        } else {
                            showPenPackages = false
                            controller.selectActiveOrLastPen()
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showShapesRow = false
                        controller.selectActiveOrLastPen()
                        showPenPackages = !showPenPackages
                    }
                ) { tint ->
                    PenToolGlyph(tool = currentPenTool, tint = tint)
                }

                // Stroke Eraser
                StudioToolButton(
                    label = "Çizgi Sil",
                    isSelected = activeTool == DrawingTool.STROKE_ERASER,
                    accentColor = Color(0xFFEF4444),
                    onClick = {
                        showShapesRow = false
                        showPenPackages = false
                        controller.selectTool(DrawingTool.STROKE_ERASER)
                    }
                ) { tint ->
                    PenToolGlyph(tool = DrawingTool.STROKE_ERASER, tint = tint)
                }

                // Area / Point Eraser
                StudioToolButton(
                    label = "Alan Sil",
                    isSelected = activeTool == DrawingTool.AREA_ERASER,
                    accentColor = Color(0xFFEF4444),
                    onClick = {
                        showShapesRow = false
                        showPenPackages = false
                        controller.selectTool(DrawingTool.AREA_ERASER)
                    }
                ) { tint ->
                    PenToolGlyph(tool = DrawingTool.AREA_ERASER, tint = tint)
                }

                // Shapes Menu Toggle
                StudioToolButton(
                    label = "Şekil",
                    isSelected = activeTool.isShapeTool || showShapesRow,
                    accentColor = primaryColor,
                    onClick = {
                        showPenPackages = false
                        showShapesRow = !showShapesRow
                        if (!activeTool.isShapeTool && showShapesRow) {
                            controller.selectTool(DrawingTool.SHAPE_RECTANGLE)
                        }
                    }
                ) { tint ->
                    PenToolGlyph(tool = DrawingTool.SHAPE_RECTANGLE, tint = tint)
                }

                // Lasso Tool
                StudioToolButton(
                    label = "Kement",
                    isSelected = activeTool == DrawingTool.LASSO,
                    accentColor = Color(0xFF0288D1),
                    onClick = {
                        showShapesRow = false
                        showPenPackages = false
                        controller.selectTool(DrawingTool.LASSO)
                    }
                ) { tint ->
                    PenToolGlyph(tool = DrawingTool.LASSO, tint = tint)
                }

                // Stylus-Only / Palm Rejection Toggle
                StudioToolButton(
                    label = "Yalnızca Kalem",
                    isSelected = controller.stylusOnlyMode,
                    accentColor = Color(0xFF10B981),
                    onClick = { controller.toggleStylusOnlyMode() }
                ) { tint ->
                    PenPalmRejectionGlyph(tint = tint)
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
                    PenUndoGlyph(tint = tint)
                }

                // Redo (60 steps)
                IconButton(
                    onClick = { controller.redo() },
                    enabled = controller.canRedo,
                    modifier = Modifier.size(32.dp)
                ) {
                    val tint = if (controller.canRedo) onSurfaceColor else onSurfaceColor.copy(alpha = 0.3f)
                    PenRedoGlyph(tint = tint)
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
fun PenPackagePickerPanel(
    controller: DrawingController,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val onSurfaceColor = colorScheme.onSurface
    val primaryColor = colorScheme.primary
    val activeTool = controller.activeTool
    val currentPen = if (activeTool.isPenTool) activeTool else controller.lastSelectedPenTool

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Kalem Paketleri",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = onSurfaceColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawingTool.PEN_PACKAGES.forEach { pkg ->
                val isSelected = currentPen == pkg && activeTool.isPenTool
                val accent = if (pkg == DrawingTool.HIGHLIGHTER) Color(0xFFFBC02D) else primaryColor
                Surface(
                    onClick = {
                        controller.selectTool(pkg)
                        onDismiss?.invoke()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) accent.copy(alpha = 0.16f) else onSurfaceColor.copy(alpha = 0.04f),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) accent else onSurfaceColor.copy(alpha = 0.14f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PenToolGlyph(
                            tool = pkg,
                            tint = if (isSelected) accent else onSurfaceColor.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = pkg.displayName,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isSelected) accent else onSurfaceColor
                            )
                            Text(
                                text = pkg.packageDescription,
                                fontSize = 9.5.sp,
                                color = onSurfaceColor.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PenShapePickerPanel(
    controller: DrawingController,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val activeTool = controller.activeTool
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Şekil",
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
            label = "Elips",
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
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Kapat",
                    tint = onSurfaceColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
fun PenLassoActionsPanel(
    controller: DrawingController,
    modifier: Modifier = Modifier,
    showAskAiAction: Boolean = true,
    onDismiss: (() -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val onSurfaceColor = colorScheme.onSurface
    val primaryColor = colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                controller.hasSelection -> "${controller.selectedStrokeIds.size} seçili"
                controller.hasLassoRegion -> "Alan seçili"
                else -> "Alan çizin"
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = primaryColor
        )

        if (controller.hasActiveLasso) {
            if (showAskAiAction) {
                MiniActionPill(
                    label = "AI'ya Sor",
                    isPrimary = true,
                    onClick = { controller.requestLassoAskAi() }
                )
            }
            MiniActionPill("Kırp / SS") {
                controller.requestLassoScreenshot()
            }
        }
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
        }
        if (controller.hasActiveLasso) {
            MiniActionPill("Kapat") { controller.clearSelection() }
        }
        if (controller.hasSelection) {
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
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Kapat",
                    tint = onSurfaceColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
fun PenStrokeSettingsPanel(
    controller: DrawingController,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    LaunchedEffect(controller, context) {
        controller.bindPreferencesContext(context)
    }

    val colorScheme = MaterialTheme.colorScheme
    val onSurfaceColor = colorScheme.onSurface
    val primaryColor = colorScheme.primary
    val activeTool = controller.activeTool
    var showCustomColorDialog by remember { mutableStateOf(false) }
    val allSwatches = remember(controller.customPenColors) {
        (STUDIO_COLOR_PALETTE + controller.customPenColors).distinctBy { it.toArgb() }
    }

    if (showCustomColorDialog) {
        PenCustomColorDialog(
            initialColor = controller.penColor,
            onDismiss = { showCustomColorDialog = false },
            onColorSelected = { chosen ->
                controller.addCustomPenColor(chosen, context)
                controller.updatePenColor(chosen)
                if (controller.hasSelection) {
                    controller.recolorSelected(chosen)
                }
                showCustomColorDialog = false
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (onDismiss != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kalem Ayarları",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = onSurfaceColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                allSwatches.forEach { swatch ->
                    val isSelected = controller.penColor.toArgb() == swatch.toArgb()
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

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = primaryColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.4f)),
                modifier = Modifier.clickable { showCustomColorDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Renk Oluştur",
                        tint = primaryColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Renk",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (activeTool.isEraserTool) "Boyut" else "Kalınlık",
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceColor.copy(alpha = 0.75f),
                modifier = Modifier.width(64.dp)
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

        if (activeTool == DrawingTool.HIGHLIGHTER) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Opaklık",
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceColor.copy(alpha = 0.75f),
                    modifier = Modifier.width(64.dp)
                )
                Slider(
                    value = controller.alpha,
                    onValueChange = { controller.updateAlpha(it) },
                    valueRange = 0.15f..0.85f,
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
    }
}

@Composable
fun HsvColorWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onHueSaturationChanged: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
    wheelSize: Dp = 208.dp
) {
    val latestCallback = androidx.compose.runtime.rememberUpdatedState(onHueSaturationChanged)
    val wheelColors = remember {
        listOf(
            Color(0xFFFF0000), // 0 deg - Red
            Color(0xFFFFFF00), // 60 deg - Yellow
            Color(0xFF00FF00), // 120 deg - Green
            Color(0xFF00FFFF), // 180 deg - Cyan
            Color(0xFF0000FF), // 240 deg - Blue
            Color(0xFFFF00FF), // 300 deg - Magenta
            Color(0xFFFF0000)  // 360 deg - Red
        )
    }
    val selectedColor = remember(hue, saturation, brightness) {
        Color(
            AndroidColor.HSVToColor(
                floatArrayOf(
                    hue.coerceIn(0f, 360f),
                    saturation.coerceIn(0f, 1f),
                    brightness.coerceIn(0f, 1f)
                )
            )
        )
    }
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    fun updateFromPointer(pos: Offset, width: Float, height: Float) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = (minOf(width, height) * 0.5f).coerceAtLeast(1f)
        val dx = pos.x - cx
        val dy = pos.y - cy
        val angleDeg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()) + 360f) % 360f
        val sat = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        latestCallback.value(angleDeg, sat)
    }

    Canvas(
        modifier = modifier
            .size(wheelSize)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    updateFromPointer(down.position, size.width.toFloat(), size.height.toFloat())
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        change.consume()
                        updateFromPointer(change.position, size.width.toFloat(), size.height.toFloat())
                    }
                }
            }
    ) {
        val radius = size.minDimension * 0.5f - 6.dp.toPx()
        val center = Offset(size.width * 0.5f, size.height * 0.5f)

        // 1. Hue sweep gradient circle
        drawCircle(
            brush = Brush.sweepGradient(colors = wheelColors, center = center),
            radius = radius,
            center = center
        )

        // 2. White-to-transparent radial saturation gradient
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        // 3. Brightness darkening overlay
        val darkness = (1f - brightness.coerceIn(0f, 1f)).coerceIn(0f, 0.94f)
        if (darkness > 0.005f) {
            drawCircle(
                color = Color.Black.copy(alpha = darkness),
                radius = radius,
                center = center
            )
        }

        // 4. Crisp outer ring
        drawCircle(
            color = borderColor.copy(alpha = 0.65f),
            radius = radius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 5. Reticle thumb at active (hue, saturation)
        val angleRad = Math.toRadians(hue.coerceIn(0f, 360f).toDouble())
        val dist = saturation.coerceIn(0f, 1f) * radius
        val thumbCenter = Offset(
            x = center.x + (cos(angleRad) * dist).toFloat(),
            y = center.y + (sin(angleRad) * dist).toFloat()
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.30f),
            radius = 12.dp.toPx(),
            center = thumbCenter
        )
        drawCircle(
            color = Color.White,
            radius = 10.5.dp.toPx(),
            center = thumbCenter
        )
        drawCircle(
            color = selectedColor,
            radius = 7.5.dp.toPx(),
            center = thumbCenter
        )
    }
}

@Composable
fun PenCustomColorDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit,
    title: String = "Özel Renk Oluştur",
    confirmLabel: String = "Rengi Ekle",
    isHighlightMode: Boolean = false,
    highlightIntensity: Float = 0.60f
) {
    val initialHsv = remember(initialColor) {
        FloatArray(3).apply {
            AndroidColor.colorToHSV(initialColor.toArgb(), this)
        }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsv[0].coerceIn(0f, 360f)) }
    var saturation by remember(initialColor) {
        mutableFloatStateOf(initialHsv[1].coerceIn(0f, 1f).let { if (it < 0.01f && initialHsv[2] < 0.01f) 0f else it })
    }
    var brightness by remember(initialColor) { mutableFloatStateOf(initialHsv[2].coerceIn(0f, 1f)) }

    fun toHexString(color: Color): String {
        val rgb = color.toArgb() and 0xFFFFFF
        return String.format("#%06X", rgb)
    }

    var solidColor by remember(initialColor) { mutableStateOf(initialColor.copy(alpha = 1f)) }
    var hexInput by remember(initialColor) { mutableStateOf(toHexString(initialColor)) }

    fun syncFromHsv(h: Float, s: Float, v: Float) {
        val computed = Color(
            AndroidColor.HSVToColor(
                floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
            )
        )
        solidColor = computed
        hexInput = toHexString(computed)
    }

    val previewDisplayColor = if (isHighlightMode) {
        solidColor.copy(alpha = highlightIntensity.coerceIn(0.15f, 0.90f))
    } else {
        solidColor
    }
    val fullBrightnessColor = remember(hue, saturation) {
        Color(AndroidColor.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), 1f)))
    }

    val presets = remember {
        listOf(
            Color(0xFF0F172A), Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF10B981),
            Color(0xFFF59E0B), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF06B6D4)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = previewDisplayColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
                ) {
                    Text(
                        text = hexInput,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isHighlightMode || solidColor.luminance() > 0.52f) Color(0xFF0F172A) else Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Modern Interactive Color Wheel
                HsvColorWheel(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onHueSaturationChanged = { newH, newS ->
                        hue = newH
                        saturation = newS
                        syncFromHsv(hue, saturation, brightness)
                    },
                    wheelSize = 208.dp
                )

                // Modern Brightness Gradient Bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Parlaklık",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "%${(brightness * 100).roundToInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(Color(0xFF09090B), fullBrightnessColor)
                                    )
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                    CircleShape
                                )
                        )
                        Slider(
                            value = brightness,
                            onValueChange = {
                                brightness = it
                                syncFromHsv(hue, saturation, brightness)
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = solidColor,
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent
                            )
                        )
                    }
                }

                // Quick Preset Dots
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    presets.forEach { preset ->
                        val isMatch = preset.toArgb() == solidColor.toArgb()
                        Box(
                            modifier = Modifier
                                .size(if (isMatch) 28.dp else 24.dp)
                                .clip(CircleShape)
                                .background(if (isHighlightMode) preset.copy(alpha = highlightIntensity) else preset)
                                .border(
                                    width = if (isMatch) 2.dp else 1.dp,
                                    color = if (isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    val hsv = FloatArray(3)
                                    AndroidColor.colorToHSV(preset.toArgb(), hsv)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    brightness = hsv[2]
                                    solidColor = preset
                                    hexInput = toHexString(preset)
                                }
                        )
                    }
                }

                // Compact HEX Code Input
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { raw ->
                        hexInput = raw
                        val normalized = if (raw.startsWith("#")) raw.trim() else "#${raw.trim()}"
                        if (normalized.length == 7 || normalized.length == 9) {
                            val parsed = runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
                            if (parsed != null) {
                                val hsv = FloatArray(3)
                                AndroidColor.colorToHSV(parsed.toArgb(), hsv)
                                hue = hsv[0]
                                saturation = hsv[1]
                                brightness = hsv[2]
                                solidColor = parsed.copy(alpha = 1f)
                            }
                        }
                    },
                    label = { Text("HEX Kodu (#1E88E5)", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onColorSelected(previewDisplayColor) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

@Composable
fun PenToolGlyph(
    tool: DrawingTool,
    tint: Color,
    modifier: Modifier = Modifier.size(16.dp)
) {
    when {
        tool == DrawingTool.NONE -> {
            Canvas(modifier = modifier) {
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
        tool == DrawingTool.FOUNTAIN_PEN -> {
            Canvas(modifier = modifier) {
                val nib = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.84f)
                    lineTo(size.width * 0.34f, size.height * 0.44f)
                    lineTo(size.width * 0.64f, size.height * 0.16f)
                    lineTo(size.width * 0.84f, size.height * 0.36f)
                    lineTo(size.width * 0.56f, size.height * 0.66f)
                    close()
                }
                drawPath(nib, color = tint, style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round))
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.16f, size.height * 0.84f),
                    end = Offset(size.width * 0.48f, size.height * 0.52f),
                    strokeWidth = 1.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = tint,
                    radius = 1.3.dp.toPx(),
                    center = Offset(size.width * 0.50f, size.height * 0.50f)
                )
            }
        }
        tool == DrawingTool.BALLPOINT_PEN -> {
            Canvas(modifier = modifier) {
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.22f, size.height * 0.78f),
                    end = Offset(size.width * 0.74f, size.height * 0.26f),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.74f, size.height * 0.26f),
                    end = Offset(size.width * 0.86f, size.height * 0.14f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = tint,
                    radius = 1.4.dp.toPx(),
                    center = Offset(size.width * 0.17f, size.height * 0.83f)
                )
            }
        }
        tool == DrawingTool.PENCIL -> {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Kurşun Kalem",
                tint = tint,
                modifier = modifier
            )
        }
        tool == DrawingTool.BRUSH_PEN -> {
            Canvas(modifier = modifier) {
                // Handle shaft
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.48f, size.height * 0.52f),
                    end = Offset(size.width * 0.84f, size.height * 0.16f),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                // Tapered brush bristle belly
                val bristles = Path().apply {
                    moveTo(size.width * 0.46f, size.height * 0.46f)
                    quadraticTo(size.width * 0.22f, size.height * 0.48f, size.width * 0.14f, size.height * 0.86f)
                    quadraticTo(size.width * 0.52f, size.height * 0.78f, size.width * 0.54f, size.height * 0.54f)
                    close()
                }
                drawPath(bristles, color = tint)
            }
        }
        tool == DrawingTool.HIGHLIGHTER -> {
            Canvas(modifier = modifier) {
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
        tool == DrawingTool.TECHNICAL_PEN -> {
            Canvas(modifier = modifier) {
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
        tool == DrawingTool.UNDERLINE_MARKER -> {
            Canvas(modifier = modifier) {
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
        tool == DrawingTool.STROKE_ERASER -> {
            Canvas(modifier = modifier) {
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
        tool == DrawingTool.AREA_ERASER -> {
            Canvas(modifier = modifier) {
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
        tool.isShapeTool -> {
            Canvas(modifier = modifier) {
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
        tool == DrawingTool.LASSO -> {
            Canvas(modifier = modifier) {
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
    }
}

@Composable
fun PenPalmRejectionGlyph(
    tint: Color,
    modifier: Modifier = Modifier.size(16.dp)
) {
    Canvas(modifier = modifier) {
        val stylusPath = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.82f)
            lineTo(size.width * 0.28f, size.height * 0.56f)
            lineTo(size.width * 0.74f, size.height * 0.14f)
            lineTo(size.width * 0.86f, size.height * 0.26f)
            lineTo(size.width * 0.42f, size.height * 0.72f)
            close()
        }
        drawPath(
            path = stylusPath,
            color = tint,
            style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round)
        )
        drawCircle(
            color = tint,
            radius = 2.2.dp.toPx(),
            center = Offset(size.width * 0.78f, size.height * 0.78f)
        )
    }
}

@Composable
fun PenUndoGlyph(
    tint: Color,
    modifier: Modifier = Modifier.size(17.dp)
) {
    Canvas(modifier = modifier) {
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

@Composable
fun PenRedoGlyph(
    tint: Color,
    modifier: Modifier = Modifier.size(17.dp)
) {
    Canvas(modifier = modifier) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StudioToolButton(
    label: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    iconContent: @Composable (tint: Color) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary
    val effectiveAccent = if (kotlin.math.abs(accentColor.luminance() - surface.luminance()) < 0.22f) {
        primary
    } else {
        accentColor
    }
    val bgColor = if (isSelected) effectiveAccent.copy(alpha = 0.16f) else Color.Transparent
    val borderColor = if (isSelected) effectiveAccent else onSurface.copy(alpha = 0.12f)
    val contentTint = if (isSelected) effectiveAccent else onSurface.copy(alpha = 0.8f)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
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
            if (onLongClick != null) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Kalem Paketleri için uzun basın",
                    tint = contentTint.copy(alpha = 0.72f),
                    modifier = Modifier.size(13.dp)
                )
            }
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
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val fg = when {
        isDestructive -> colorScheme.error
        isPrimary -> colorScheme.onPrimary
        else -> colorScheme.onSecondaryContainer
    }
    val bg = when {
        isDestructive -> colorScheme.errorContainer.copy(alpha = 0.5f)
        isPrimary -> colorScheme.primary
        else -> colorScheme.secondaryContainer
    }
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
