package com.example.sharkmentor.ui.screens.notes.drawing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingScreenshotHelper.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val QUICK_LASSO_COLORS = listOf(
    Color(0xFF0F172A),
    Color(0xFFE53935),
    Color(0xFF1E88E5),
    Color(0xFF43A047),
    Color(0xFFFB8C00),
    Color(0xFF8E24AA),
    Color(0xFFFFEB3B),
    Color(0xFF00ACC1)
)

/**
 * Interactive drawing & annotation overlay that works both over the Rich Note Editor
 * and over individual PDF Reader pages.
 *
 * Supports:
 * - Freehand pens with automatic Scribble-to-Erase (zigzag >= 3 sharp reversals)
 * - Free-region Lasso selection & PixelCopy screenshot cropping (Notes / Gallery / Share)
 * - Pass-through when [controller.activeTool] == [DrawingTool.NONE]
 */
@Composable
fun DrawingCanvasOverlay(
    controller: DrawingController,
    modifier: Modifier = Modifier,
    onTwoFingerScroll: ((deltaY: Float, deltaX: Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val window = remember(context) { context.findActivity()?.window }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var canvasWindowBounds by remember { mutableStateOf<Rect?>(null) }

    val inProgressPoints = remember { mutableStateListOf<DrawingPoint>() }
    var eraserCursorPos by remember { mutableStateOf<Offset?>(null) }
    var isMovingLassoSelection by remember { mutableStateOf(false) }
    var showLassoColorRow by remember { mutableStateOf(false) }

    var isCapturingScreenshot by remember { mutableStateOf(false) }
    var capturedCrop by remember { mutableStateOf<CapturedCropResult?>(null) }
    var usePolygonCrop by remember { mutableStateOf(true) }
    var isSavingCrop by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var dialogStatusMessage by remember { mutableStateOf<String?>(null) }
    var pendingGalleryBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun performGallerySave(targetBitmap: Bitmap) {
        if (isSavingCrop) return
        isSavingCrop = true
        coroutineScope.launch {
            val result = DrawingScreenshotHelper.saveBitmapToGallery(context, targetBitmap)
            isSavingCrop = false
            val msg = if (result.isSuccess) {
                "Galeriye kaydedildi"
            } else {
                val detail = result.exceptionOrNull()?.localizedMessage?.takeIf { it.isNotBlank() }
                if (detail != null) "Galeriye kaydedilemedi: $detail" else "Galeriye kaydedilemedi"
            }
            dialogStatusMessage = msg
            feedbackMessage = msg
        }
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingGalleryBitmap
        pendingGalleryBitmap = null
        if (granted && pending != null) {
            performGallerySave(pending)
        } else {
            val msg = "Galeriye kaydetmek için depolama izni gerekiyor"
            dialogStatusMessage = msg
            feedbackMessage = msg
        }
    }

    // Auto-clear subtle toast/feedback pill
    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            delay(2200L)
            feedbackMessage = null
        }
    }

    fun triggerLassoScreenshot() {
        val win = window
        val winBounds = canvasWindowBounds
        val selBounds = controller.selectionBounds()
        if (win == null || winBounds == null || selBounds == null || isCapturingScreenshot) {
            if (win == null || winBounds == null) {
                feedbackMessage = "Ekran bölgesi henüz hazır değil"
            }
            return
        }
        val polygonSnapshot = controller.lassoPolygon.toList()
        coroutineScope.launch {
            isCapturingScreenshot = true
            try {
                // Wait 2 UI frames so dashed lasso border & action bar disappear before PixelCopy
                withFrameNanos { }
                withFrameNanos { }
                delay(40L)

                val result = DrawingScreenshotHelper.captureLassoRegion(
                    window = win,
                    canvasWindowBounds = winBounds,
                    localBounds = selBounds,
                    lassoPolygon = polygonSnapshot
                )
                if (result != null) {
                    dialogStatusMessage = null
                    usePolygonCrop = result.polygonBitmap != null
                    capturedCrop = result
                } else {
                    feedbackMessage = "Ekran görüntüsü alınamadı"
                }
            } finally {
                isCapturingScreenshot = false
            }
        }
    }

    // Listen to toolbar screenshot triggers
    LaunchedEffect(controller.screenshotRequestCounter) {
        if (controller.screenshotRequestCounter > 0) {
            triggerLassoScreenshot()
        }
    }

    LaunchedEffect(controller, context) {
        controller.bindPreferencesContext(context)
    }

    // Adapt strokes if loaded from a different canvas width (e.g. orientation change or PDF resize)
    LaunchedEffect(canvasSize.width, controller.strokes.size) {
        val currentW = canvasSize.width.toFloat()
        val currentH = canvasSize.height.toFloat()
        if (currentW > 10f && controller.strokes.isNotEmpty()) {
            val needsScale = controller.strokes.any {
                it.canvasWidth > 10f && abs(it.canvasWidth - currentW) > 4f
            }
            if (needsScale) {
                val rescaled = controller.strokes.map { stroke ->
                    if (stroke.canvasWidth > 10f && abs(stroke.canvasWidth - currentW) > 4f) {
                        val scale = currentW / stroke.canvasWidth
                        stroke.copy(
                            strokeWidth = (stroke.strokeWidth * scale).coerceIn(1f, 60f),
                            points = stroke.points.map { pt ->
                                DrawingPoint(pt.x * scale, pt.y * scale, pt.pressure)
                            },
                            canvasWidth = currentW,
                            canvasHeight = if (currentH > 10f) currentH else stroke.canvasHeight * scale
                        )
                    } else {
                        stroke
                    }
                }
                controller.setInitialStrokes(rescaled)
            }
        }
    }

    LaunchedEffect(controller.hasSelection) {
        if (!controller.hasSelection) {
            showLassoColorRow = false
        }
    }

    var lastStylusActivityMs by remember { mutableLongStateOf(0L) }
    val currentOnTwoFingerScroll by rememberUpdatedState(onTwoFingerScroll)

    val activeTool = controller.activeTool
    val stylusTrackerModifier = if (activeTool == DrawingTool.NONE) {
        Modifier
    } else {
        Modifier.pointerInput(activeTool) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val hasStylus = event.changes.any {
                        it.type == PointerType.Stylus || it.type == PointerType.Eraser
                    }
                    if (hasStylus) {
                        lastStylusActivityMs = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    val pointerModifier = if (activeTool == DrawingTool.NONE) {
        Modifier
    } else {
        Modifier.pointerInput(
            activeTool,
            controller.penColor,
            controller.strokeWidth,
            controller.alpha,
            controller.eraserRadius,
            controller.stylusOnlyMode,
            controller.smartPalmRejectionEnabled,
            controller.pressureSensitivityEnabled,
            onTwoFingerScroll != null
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                    lastStylusActivityMs = SystemClock.elapsedRealtime()
                }

                val shouldRejectTouch = down.type == PointerType.Touch && (
                    controller.stylusOnlyMode ||
                        (controller.smartPalmRejectionEnabled &&
                            SystemClock.elapsedRealtime() - lastStylusActivityMs < 1500L)
                    )

                var activeDown: PointerInputChange = down
                if (shouldRejectTouch) {
                    down.consume()
                    var promotedStylus: PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }
                        if (pressedChanges.isEmpty()) break

                        val stylusPressed = pressedChanges.firstOrNull {
                            it.type == PointerType.Stylus || it.type == PointerType.Eraser
                        }
                        if (stylusPressed != null) {
                            lastStylusActivityMs = SystemClock.elapsedRealtime()
                            stylusPressed.consume()
                            promotedStylus = stylusPressed
                            break
                        }

                        val touchPressedCount = pressedChanges.count { it.type == PointerType.Touch }
                        val scrollCallback = currentOnTwoFingerScroll
                        if (touchPressedCount >= 2 && scrollCallback != null) {
                            runTwoFingerScrollLoop(event, scrollCallback)
                            break
                        }

                        event.changes.forEach { it.consume() }
                    }

                    if (promotedStylus == null) {
                        return@awaitEachGesture
                    }
                    activeDown = promotedStylus
                } else {
                    down.consume()
                }

                val effectiveTool = if (activeDown.type == PointerType.Eraser) {
                    DrawingTool.STROKE_ERASER
                } else {
                    activeTool
                }

                val gestureStartMs = SystemClock.elapsedRealtime()
                var lastSampleTimeMs = gestureStartMs
                val startX = activeDown.position.x
                val startY = activeDown.position.y
                val initialPressure = resolvePointerPressure(
                    change = activeDown,
                    prevPoint = null,
                    prevTimeMs = gestureStartMs,
                    nowTimeMs = gestureStartMs,
                    enabled = controller.pressureSensitivityEnabled
                )
                val startPoint = DrawingPoint(startX, startY, initialPressure)

                inProgressPoints.clear()
                var cancelledByMultiTouch = false

                when {
                    effectiveTool.isEraserTool -> {
                        controller.beginEraserGesture()
                        eraserCursorPos = activeDown.position
                        if (effectiveTool == DrawingTool.STROKE_ERASER) {
                            controller.eraseStrokeAt(startX, startY, controller.eraserRadius)
                        } else {
                            controller.eraseAreaAt(startX, startY, controller.eraserRadius)
                        }
                    }
                    effectiveTool == DrawingTool.LASSO -> {
                        val selBounds = controller.selectionBounds()?.inflate(18f)
                        if (selBounds != null && selBounds.contains(startX, startY)) {
                            isMovingLassoSelection = true
                            controller.beginMoveSelection()
                        } else {
                            isMovingLassoSelection = false
                            controller.clearSelection()
                            inProgressPoints.add(startPoint)
                        }
                    }
                    effectiveTool.isShapeTool || effectiveTool == DrawingTool.UNDERLINE_MARKER -> {
                        inProgressPoints.add(startPoint)
                        inProgressPoints.add(startPoint)
                    }
                    else -> {
                        // Freehand pen / highlighter / technical pen
                        inProgressPoints.add(startPoint)
                    }
                }

                while (true) {
                    val event = awaitPointerEvent()
                    val pressedChanges = event.changes.filter { it.pressed }
                    val pressedCount = pressedChanges.size

                    val hasStylusInEvent = event.changes.any {
                        it.type == PointerType.Stylus || it.type == PointerType.Eraser
                    }
                    if (hasStylusInEvent) {
                        lastStylusActivityMs = SystemClock.elapsedRealtime()
                    }

                    if (activeDown.type == PointerType.Touch &&
                        controller.smartPalmRejectionEnabled &&
                        inProgressPoints.size <= 6 &&
                        hasStylusInEvent
                    ) {
                        cancelledByMultiTouch = true
                        inProgressPoints.clear()
                        eraserCursorPos = null
                        if (effectiveTool.isEraserTool) {
                            controller.endEraserGesture()
                        } else if (isMovingLassoSelection) {
                            isMovingLassoSelection = false
                        }
                        event.changes.forEach { it.consume() }
                        break
                    }

                    if (activeDown.type == PointerType.Touch &&
                        pressedCount >= 2 &&
                        inProgressPoints.size <= 6 &&
                        !isMovingLassoSelection
                    ) {
                        // Multi-touch gesture (e.g. 2-finger scroll or pinch zoom): abort stroke preview
                        cancelledByMultiTouch = true
                        inProgressPoints.clear()
                        eraserCursorPos = null
                        if (effectiveTool.isEraserTool) {
                            controller.endEraserGesture()
                        }
                        val scrollCallback = currentOnTwoFingerScroll
                        if (scrollCallback != null) {
                            runTwoFingerScrollLoop(event, scrollCallback)
                        }
                        break
                    }

                    val change = event.changes.firstOrNull { it.id == activeDown.id }
                    if (change == null || !change.pressed) {
                        break
                    }

                    val pos = change.position
                    val delta = change.positionChange()
                    val nowMs = SystemClock.elapsedRealtime()
                    change.consume()

                    when {
                        effectiveTool.isEraserTool -> {
                            eraserCursorPos = pos
                            if (effectiveTool == DrawingTool.STROKE_ERASER) {
                                controller.eraseStrokeAt(pos.x, pos.y, controller.eraserRadius)
                            } else {
                                controller.eraseAreaAt(pos.x, pos.y, controller.eraserRadius)
                            }
                        }
                        effectiveTool == DrawingTool.LASSO -> {
                            if (isMovingLassoSelection) {
                                controller.moveSelectionBy(delta.x, delta.y)
                            } else {
                                val last = inProgressPoints.lastOrNull()
                                val current = DrawingPoint(pos.x, pos.y, 1.0f)
                                if (last == null || last.distanceTo(current) >= 3f) {
                                    inProgressPoints.add(current)
                                }
                            }
                        }
                        effectiveTool == DrawingTool.UNDERLINE_MARKER -> {
                            val first = inProgressPoints.firstOrNull() ?: startPoint
                            // Snap to clean horizontal underline unless dragged steeply (> 25 deg)
                            val dx = abs(pos.x - first.x)
                            val dy = abs(pos.y - first.y)
                            val endPt = if (dx > dy * 2.0f) {
                                DrawingPoint(pos.x, first.y, 1.0f)
                            } else {
                                DrawingPoint(pos.x, pos.y, 1.0f)
                            }
                            if (inProgressPoints.size >= 2) {
                                inProgressPoints[1] = endPt
                            } else {
                                inProgressPoints.add(endPt)
                            }
                        }
                        effectiveTool.isShapeTool -> {
                            val current = DrawingPoint(pos.x, pos.y, 1.0f)
                            if (inProgressPoints.size >= 2) {
                                inProgressPoints[1] = current
                            } else {
                                inProgressPoints.add(current)
                            }
                        }
                        else -> {
                            val last = inProgressPoints.lastOrNull()
                            val pointPressure = resolvePointerPressure(
                                change = change,
                                prevPoint = last,
                                prevTimeMs = lastSampleTimeMs,
                                nowTimeMs = nowMs,
                                enabled = controller.pressureSensitivityEnabled
                            )
                            val current = DrawingPoint(pos.x, pos.y, pointPressure)
                            if (last == null || last.distanceTo(current) >= 2.2f) {
                                inProgressPoints.add(current)
                                lastSampleTimeMs = nowMs
                            }
                        }
                    }
                }

                // Gesture finished
                eraserCursorPos = null
                if (!cancelledByMultiTouch) {
                    when {
                        effectiveTool.isEraserTool -> {
                            controller.endEraserGesture()
                        }
                        effectiveTool == DrawingTool.LASSO -> {
                            if (isMovingLassoSelection) {
                                controller.endMoveSelection()
                                isMovingLassoSelection = false
                            } else if (inProgressPoints.size >= 3) {
                                controller.selectByLassoPolygon(inProgressPoints.toList())
                            }
                            inProgressPoints.clear()
                        }
                        effectiveTool.isShapeTool || effectiveTool == DrawingTool.UNDERLINE_MARKER -> {
                            if (inProgressPoints.size >= 2) {
                                val p0 = inProgressPoints.first()
                                val p1 = inProgressPoints.last()
                                if (p0.distanceTo(p1) >= 4f) {
                                    controller.addStroke(
                                        DrawingStroke(
                                            tool = effectiveTool,
                                            shapeKind = effectiveTool.toShapeKind(),
                                            colorArgb = controller.penColor.toArgb(),
                                            strokeWidth = controller.strokeWidth,
                                            alpha = controller.alpha,
                                            points = listOf(p0, p1),
                                            canvasWidth = canvasSize.width.toFloat().coerceAtLeast(1f),
                                            canvasHeight = canvasSize.height.toFloat().coerceAtLeast(1f),
                                            pressureSensitive = false
                                        )
                                    )
                                }
                            }
                            inProgressPoints.clear()
                        }
                        effectiveTool.isPenTool -> {
                            if (inProgressPoints.isNotEmpty()) {
                                val gestureDurationMs = SystemClock.elapsedRealtime() - gestureStartMs
                                val rawPoints = inProgressPoints.toList()
                                val isFreehandPen = effectiveTool == DrawingTool.FOUNTAIN_PEN ||
                                    effectiveTool == DrawingTool.TECHNICAL_PEN ||
                                    effectiveTool == DrawingTool.HIGHLIGHTER

                                val erasedByScribble = if (
                                    isFreehandPen &&
                                    controller.strokes.isNotEmpty() &&
                                    ScribbleDetector.isScribbleGesture(rawPoints, gestureDurationMs)
                                ) {
                                    controller.eraseStrokesByScribble(rawPoints)
                                } else {
                                    0
                                }

                                if (erasedByScribble > 0) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    feedbackMessage = "Karalama ile $erasedByScribble çizim silindi"
                                } else {
                                    val finalPoints = if (rawPoints.size == 1) {
                                        val p = rawPoints.first()
                                        listOf(p, DrawingPoint(p.x + 0.5f, p.y + 0.5f, p.pressure))
                                    } else {
                                        rawPoints
                                    }
                                    controller.addStroke(
                                        DrawingStroke(
                                            tool = effectiveTool,
                                            shapeKind = ShapeKind.NONE,
                                            colorArgb = controller.penColor.toArgb(),
                                            strokeWidth = controller.strokeWidth,
                                            alpha = controller.alpha,
                                            points = finalPoints,
                                            canvasWidth = canvasSize.width.toFloat().coerceAtLeast(1f),
                                            canvasHeight = canvasSize.height.toFloat().coerceAtLeast(1f),
                                            pressureSensitive = controller.pressureSensitivityEnabled
                                        )
                                    )
                                }
                            }
                            inProgressPoints.clear()
                        }
                        else -> {
                            inProgressPoints.clear()
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .then(stylusTrackerModifier)
            .onSizeChanged { canvasSize = it }
            .onGloballyPositioned { coords -> canvasWindowBounds = coords.boundsInWindow() }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(pointerModifier)
        ) {
            val selectedIds = controller.selectedStrokeIds

            // 1. Draw all committed strokes
            for (stroke in controller.strokes) {
                val isSelected = !isCapturingScreenshot && stroke.id in selectedIds
                if (isSelected) {
                    // Draw subtle selection halo behind selected stroke
                    drawStrokeElement(
                        stroke = stroke.copy(
                            colorArgb = Color(0xFF00B0FF).toArgb(),
                            strokeWidth = stroke.strokeWidth + 6f,
                            alpha = 0.28f
                        ),
                        pressureSensitivityEnabled = controller.pressureSensitivityEnabled && stroke.pressureSensitive
                    )
                }
                drawStrokeElement(
                    stroke = stroke,
                    pressureSensitivityEnabled = controller.pressureSensitivityEnabled && stroke.pressureSensitive
                )
            }

            // 2. Draw active in-progress stroke / shape / lasso
            if (inProgressPoints.isNotEmpty() && !isCapturingScreenshot) {
                if (activeTool == DrawingTool.LASSO) {
                    drawLassoLoop(inProgressPoints)
                } else if (activeTool.isPenTool || activeTool.isShapeTool) {
                    val previewStroke = DrawingStroke(
                        id = "preview",
                        tool = activeTool,
                        shapeKind = activeTool.toShapeKind(),
                        colorArgb = controller.penColor.toArgb(),
                        strokeWidth = controller.strokeWidth,
                        alpha = controller.alpha,
                        points = inProgressPoints.toList(),
                        pressureSensitive = controller.pressureSensitivityEnabled
                    )
                    drawStrokeElement(
                        stroke = previewStroke,
                        pressureSensitivityEnabled = controller.pressureSensitivityEnabled
                    )
                }
            }

            // 3. Draw Lasso selection bounding box (and free-region polygon contour) when not capturing
            if (!isCapturingScreenshot) {
                if (controller.lassoPolygon.size >= 3 && !controller.hasSelection) {
                    drawLassoLoop(controller.lassoPolygon)
                }

                val selBounds = controller.selectionBounds()
                if (selBounds != null) {
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    val topLeft = Offset(selBounds.left, selBounds.top)
                    val rectSize = Size(selBounds.width, selBounds.height)

                    if (controller.hasSelection || controller.lassoPolygon.size < 3) {
                        drawRect(
                            color = Color(0xFF0288D1).copy(alpha = 0.08f),
                            topLeft = topLeft,
                            size = rectSize
                        )
                    }
                    drawRect(
                        color = Color(0xFF0288D1),
                        topLeft = topLeft,
                        size = rectSize,
                        style = Stroke(width = 2.dp.toPx(), pathEffect = dashEffect)
                    )

                    val handleRadius = 4.5.dp.toPx()
                    val corners = listOf(
                        Offset(selBounds.left, selBounds.top),
                        Offset(selBounds.right, selBounds.top),
                        Offset(selBounds.right, selBounds.bottom),
                        Offset(selBounds.left, selBounds.bottom)
                    )
                    for (corner in corners) {
                        drawCircle(color = Color.White, radius = handleRadius + 1.5f, center = corner)
                        drawCircle(color = Color(0xFF0288D1), radius = handleRadius, center = corner)
                    }
                }
            }

            // 4. Draw Eraser cursor circle
            eraserCursorPos?.let { cursor ->
                val r = controller.eraserRadius
                drawCircle(
                    color = Color(0xFFEF4444).copy(alpha = 0.18f),
                    radius = r,
                    center = cursor
                )
                drawCircle(
                    color = Color(0xFFEF4444).copy(alpha = 0.75f),
                    radius = r,
                    center = cursor,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // Floating Contextual Action Bar above/below Lasso Selection (strokes OR free region)
        val selBounds = controller.selectionBounds()
        if (selBounds != null && canvasSize.width > 0 && !isCapturingScreenshot) {
            val maxBarWidthDp = with(density) { (canvasSize.width.toFloat() - 16f).coerceAtLeast(160f).toDp() }
            val estBarWidthPx = with(density) {
                if (controller.hasSelection) 330.dp.toPx() else 210.dp.toPx()
            }.coerceAtMost((canvasSize.width - 16f).coerceAtLeast(120f))
            val barHeightPx = with(density) { if (showLassoColorRow) 84.dp.toPx() else 46.dp.toPx() }
            val targetX = (selBounds.centerX - estBarWidthPx * 0.5f)
                .coerceIn(8f, (canvasSize.width - estBarWidthPx - 8f).coerceAtLeast(8f))
            val aboveY = selBounds.top - barHeightPx - with(density) { 10.dp.toPx() }
            val targetY = if (aboveY >= 8f) {
                aboveY
            } else {
                (selBounds.bottom + with(density) { 10.dp.toPx() })
                    .coerceAtMost((canvasSize.height - barHeightPx - 8f).coerceAtLeast(8f))
            }

            Surface(
                modifier = Modifier
                    .zIndex(10f)
                    .offset { IntOffset(targetX.roundToInt(), targetY.roundToInt()) }
                    .widthIn(max = maxBarWidthDp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LassoActionChip(
                            label = "Ekran Görüntüsü",
                            icon = Icons.Default.Crop,
                            isHighlighted = true,
                            onClick = { triggerLassoScreenshot() }
                        )
                        if (controller.hasSelection) {
                            LassoActionChip(
                                label = "Kes",
                                onClick = { controller.cutSelected() }
                            )
                            LassoActionChip(
                                label = "Kopyala",
                                onClick = { controller.copySelected() }
                            )
                        }
                        if (controller.hasClipboard) {
                            LassoActionChip(
                                label = "Yapıştır",
                                onClick = { controller.pasteClipboard() }
                            )
                        }
                        if (controller.hasSelection) {
                            IconButton(
                                onClick = { showLassoColorRow = !showLassoColorRow },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Renk Değiştir",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            IconButton(
                                onClick = { controller.deleteSelected() },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Seçimi Sil",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = { controller.clearSelection() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Seçimi Kapat",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (showLassoColorRow && controller.hasSelection) {
                        Row(
                            modifier = Modifier
                                .padding(top = 4.dp, bottom = 2.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QUICK_LASSO_COLORS.forEach { swatch ->
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                            CircleShape
                                        )
                                        .clickable {
                                            controller.recolorSelected(swatch)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Subtle Feedback Pill (Scribble-to-Erase / Screenshot Saved)
        AnimatedVisibility(
            visible = feedbackMessage != null && !isCapturingScreenshot,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        ) {
            feedbackMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.inversePrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                }
            }
        }

        // Cropped Screenshot Preview & Export Dialog
        capturedCrop?.let { crop ->
            val activeBitmap = if (usePolygonCrop && crop.polygonBitmap != null) {
                crop.polygonBitmap
            } else {
                crop.rectBitmap
            }
            val dismissPreview = {
                capturedCrop = null
                dialogStatusMessage = null
                pendingGalleryBitmap = null
            }

            Dialog(onDismissRequest = { if (!isSavingCrop) dismissPreview() }) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 14.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Kırpılan Alan",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${activeBitmap.width} × ${activeBitmap.height} px",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                            IconButton(
                                onClick = { if (!isSavingCrop) dismissPreview() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Kapat"
                                )
                            }
                        }

                        if (crop.polygonBitmap != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = usePolygonCrop,
                                    onClick = { usePolygonCrop = true },
                                    label = { Text("Serbest Kement", fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = !usePolygonCrop,
                                    onClick = { usePolygonCrop = false },
                                    label = { Text("Dikdörtgen", fontSize = 12.sp) }
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 280.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = activeBitmap.asImageBitmap(),
                                contentDescription = "Kırpılan ekran görüntüsü",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        dialogStatusMessage?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Button(
                            onClick = {
                                if (isSavingCrop) return@Button
                                isSavingCrop = true
                                coroutineScope.launch {
                                    val result = DrawingScreenshotHelper.saveCropToNotes(context, activeBitmap)
                                    isSavingCrop = false
                                    if (result.isSuccess) {
                                        feedbackMessage = "Notlara eklendi"
                                        dismissPreview()
                                    } else {
                                        val detail = result.exceptionOrNull()?.localizedMessage?.takeIf { it.isNotBlank() }
                                        dialogStatusMessage = if (detail != null) "Not kaydedilemedi: $detail" else "Not kaydedilemedi"
                                    }
                                }
                            },
                            enabled = !isSavingCrop,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSavingCrop) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.StickyNote2,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Notlara Ekle", fontWeight = FontWeight.SemiBold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (isSavingCrop) return@OutlinedButton
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        pendingGalleryBitmap = activeBitmap
                                        galleryPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    } else {
                                        performGallerySave(activeBitmap)
                                    }
                                },
                                enabled = !isSavingCrop,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Galeriye Kaydet", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    if (isSavingCrop) return@OutlinedButton
                                    coroutineScope.launch {
                                        val result = DrawingScreenshotHelper.shareBitmap(context, activeBitmap)
                                        if (result.isFailure) {
                                            dialogStatusMessage = "Paylaşım başlatılamadı"
                                        }
                                    }
                                },
                                enabled = !isSavingCrop,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Paylaş", fontSize = 12.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { if (!isSavingCrop) dismissPreview() },
                                enabled = !isSavingCrop
                            ) {
                                Text("Kapat")
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.runTwoFingerScrollLoop(
    initialEvent: PointerEvent,
    onTwoFingerScroll: (deltaY: Float, deltaX: Float) -> Unit
) {
    var scrollEvent = initialEvent
    while (true) {
        val activePointers = scrollEvent.changes.filter { it.pressed }
        if (activePointers.isEmpty()) break
        if (activePointers.size >= 2) {
            var sumDx = 0f
            var sumDy = 0f
            for (ptr in activePointers) {
                val delta = ptr.positionChange()
                sumDx += delta.x
                sumDy += delta.y
                ptr.consume()
            }
            val count = activePointers.size.toFloat()
            val avgDx = sumDx / count
            val avgDy = sumDy / count
            if (avgDy != 0f || avgDx != 0f) {
                onTwoFingerScroll(avgDy, avgDx)
            }
        } else {
            activePointers.forEach { it.consume() }
        }
        scrollEvent = awaitPointerEvent()
    }
}

@Composable
private fun LassoActionChip(
    label: String,
    icon: ImageVector? = null,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = if (isHighlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    }
    val fgColor = if (isHighlighted) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = fgColor
            )
        }
    }
}

/**
 * Normalizes stylus or touch pressure from [PointerInputChange]:
 * - [PointerType.Stylus]: directly reads high-precision hardware pressure in `0.05f..1.5f`
 *   (falls back to `0.65f` if device reports `0.0f`).
 * - [PointerType.Touch]: uses hardware touch pressure when varied, or falls back to
 *   velocity-based natural taper when the touchscreen reports a constant `1.0f`.
 */
internal fun resolvePointerPressure(
    change: PointerInputChange,
    prevPoint: DrawingPoint?,
    prevTimeMs: Long,
    nowTimeMs: Long,
    enabled: Boolean
): Float {
    if (!enabled) return 1.0f
    val raw = change.pressure
    if (change.type == PointerType.Stylus) {
        val normalized = if (raw <= 0.001f) 0.65f else raw.coerceIn(0.05f, 1.5f)
        val prevP = prevPoint?.pressure ?: normalized
        return (prevP * 0.35f + normalized * 0.65f).coerceIn(0.05f, 1.5f)
    }

    // Touch or other pointer types
    if (raw > 0.01f && abs(raw - 1.0f) > 0.015f) {
        val clamped = raw.coerceIn(0.15f, 1.4f)
        val prevP = prevPoint?.pressure ?: clamped
        return (prevP * 0.45f + clamped * 0.55f).coerceIn(0.15f, 1.4f)
    }

    // Velocity-based natural taper when touch reports constant 1.0f
    if (prevPoint == null) return 0.78f
    val dist = kotlin.math.hypot(change.position.x - prevPoint.x, change.position.y - prevPoint.y)
    val dt = (nowTimeMs - prevTimeMs).coerceAtLeast(4L).toFloat()
    val speedPxPerMs = dist / dt
    val velocityPressure = (1.06f - (speedPxPerMs * 0.14f)).coerceIn(0.55f, 1.12f)
    return (prevPoint.pressure * 0.60f + velocityPressure * 0.40f).coerceIn(0.45f, 1.25f)
}

/**
 * Renders a single [DrawingStroke] (either freehand pen/highlighter or geometric shape) onto [DrawScope].
 */
fun DrawScope.drawStrokeElement(
    stroke: DrawingStroke,
    pressureSensitivityEnabled: Boolean = stroke.pressureSensitive
) {
    val pts = stroke.points
    if (pts.isEmpty()) return

    val paintColor = stroke.composeColor
    val widthPx = stroke.strokeWidth.coerceAtLeast(1f)
    val cap = when (stroke.tool) {
        DrawingTool.HIGHLIGHTER -> StrokeCap.Square
        DrawingTool.TECHNICAL_PEN -> StrokeCap.Round
        else -> StrokeCap.Round
    }
    val join = when (stroke.tool) {
        DrawingTool.TECHNICAL_PEN -> StrokeJoin.Miter
        else -> StrokeJoin.Round
    }

    if (stroke.shapeKind != ShapeKind.NONE && pts.size >= 2) {
        val start = Offset(pts.first().x, pts.first().y)
        val end = Offset(pts.last().x, pts.last().y)
        val left = min(start.x, end.x)
        val right = max(start.x, end.x)
        val top = min(start.y, end.y)
        val bottom = max(start.y, end.y)
        val w = (right - left).coerceAtLeast(1f)
        val h = (bottom - top).coerceAtLeast(1f)

        when (stroke.shapeKind) {
            ShapeKind.LINE -> {
                drawLine(
                    color = paintColor,
                    start = start,
                    end = end,
                    strokeWidth = widthPx,
                    cap = cap
                )
            }
            ShapeKind.ARROW -> {
                drawLine(
                    color = paintColor,
                    start = start,
                    end = end,
                    strokeWidth = widthPx,
                    cap = StrokeCap.Round
                )
                val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                val headLength = (widthPx * 4.2f).coerceIn(18f, 48f)
                val wingAngle = PI / 6.0
                val wing1 = Offset(
                    x = (end.x - headLength * cos(angle - wingAngle)).toFloat(),
                    y = (end.y - headLength * sin(angle - wingAngle)).toFloat()
                )
                val wing2 = Offset(
                    x = (end.x - headLength * cos(angle + wingAngle)).toFloat(),
                    y = (end.y - headLength * sin(angle + wingAngle)).toFloat()
                )
                val arrowHeadPath = Path().apply {
                    moveTo(wing1.x, wing1.y)
                    lineTo(end.x, end.y)
                    lineTo(wing2.x, wing2.y)
                }
                drawPath(
                    path = arrowHeadPath,
                    color = paintColor,
                    style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            ShapeKind.RECTANGLE -> {
                drawRect(
                    color = paintColor,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = widthPx, cap = cap, join = join)
                )
            }
            ShapeKind.ELLIPSE -> {
                drawOval(
                    color = paintColor,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = widthPx, cap = cap, join = join)
                )
            }
            ShapeKind.TRIANGLE -> {
                val triPath = Path().apply {
                    moveTo((left + right) * 0.5f, top)
                    lineTo(right, bottom)
                    lineTo(left, bottom)
                    close()
                }
                drawPath(
                    path = triPath,
                    color = paintColor,
                    style = Stroke(width = widthPx, cap = cap, join = StrokeJoin.Round)
                )
            }
            ShapeKind.NONE -> {}
        }
        return
    }

    // Freehand stroke rendering with smooth quadratic Bezier curves
    if (pts.size == 1) {
        val p0 = pts[0]
        val pFactor = if (pressureSensitivityEnabled) {
            (0.35f + 0.95f * p0.pressure.coerceIn(0.05f, 1.5f)).coerceIn(0.30f, 1.80f)
        } else {
            1.0f
        }
        drawCircle(
            color = paintColor,
            radius = (widthPx * pFactor * 0.5f).coerceAtLeast(0.75f),
            center = Offset(p0.x, p0.y)
        )
        return
    }

    val isPressurePen = pressureSensitivityEnabled &&
        stroke.pressureSensitive &&
        (stroke.tool == DrawingTool.FOUNTAIN_PEN || stroke.tool == DrawingTool.TECHNICAL_PEN)

    if (isPressurePen) {
        var prevAnchorX = pts[0].x
        var prevAnchorY = pts[0].y
        val segPath = Path()
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val curr = pts[i]
            val endX = if (i == pts.lastIndex) curr.x else (prev.x + curr.x) * 0.5f
            val endY = if (i == pts.lastIndex) curr.y else (prev.y + curr.y) * 0.5f

            val pPrev2 = pts[(i - 2).coerceAtLeast(0)].pressure.coerceIn(0.05f, 1.5f)
            val pPrev = prev.pressure.coerceIn(0.05f, 1.5f)
            val pCurr = curr.pressure.coerceIn(0.05f, 1.5f)
            val smoothedPressure = pPrev2 * 0.25f + pPrev * 0.50f + pCurr * 0.25f

            // Taper endpoints gently so pen entry/exit looks like a natural nib
            val edgeTaper = when {
                pts.size >= 5 && i == 1 -> 0.82f
                pts.size >= 5 && i == pts.lastIndex -> 0.78f
                else -> 1.0f
            }
            val widthI = (widthPx * (0.35f + 0.95f * smoothedPressure) * edgeTaper)
                .coerceIn(max(0.75f, widthPx * 0.28f), widthPx * 1.85f)

            segPath.reset()
            segPath.moveTo(prevAnchorX, prevAnchorY)
            segPath.quadraticTo(prev.x, prev.y, endX, endY)
            drawPath(
                path = segPath,
                color = paintColor,
                style = Stroke(
                    width = widthI,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            prevAnchorX = endX
            prevAnchorY = endY
        }
        return
    }

    // Highlighter or constant-width stroke
    val effectiveColor = if (pressureSensitivityEnabled && stroke.pressureSensitive && stroke.tool == DrawingTool.HIGHLIGHTER) {
        var sumP = 0f
        for (pt in pts) sumP += pt.pressure.coerceIn(0.2f, 1.4f)
        val avgP = sumP / pts.size.toFloat()
        val modulatedAlpha = (stroke.alpha * (0.78f + 0.26f * avgP)).coerceIn(0.08f, 0.85f)
        Color(stroke.colorArgb).copy(alpha = modulatedAlpha)
    } else {
        paintColor
    }

    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val curr = pts[i]
            val midX = (prev.x + curr.x) * 0.5f
            val midY = (prev.y + curr.y) * 0.5f
            quadraticTo(prev.x, prev.y, midX, midY)
        }
        val last = pts.last()
        lineTo(last.x, last.y)
    }

    drawPath(
        path = path,
        color = effectiveColor,
        style = Stroke(
            width = widthPx,
            cap = cap,
            join = join
        )
    )
}

private fun DrawScope.drawLassoLoop(points: List<DrawingPoint>) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
        close()
    }
    drawPath(
        path = path,
        color = Color(0xFF0288D1).copy(alpha = 0.10f)
    )
    drawPath(
        path = path,
        color = Color(0xFF0288D1),
        style = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
        )
    )
}
