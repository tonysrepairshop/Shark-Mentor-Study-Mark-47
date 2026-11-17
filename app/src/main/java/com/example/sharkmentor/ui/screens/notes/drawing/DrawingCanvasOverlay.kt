package com.example.sharkmentor.ui.screens.notes.drawing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * When [controller.activeTool] == [DrawingTool.NONE], pointer events pass straight through
 * to underlying text fields or scrollable/zoomable containers.
 */
@Composable
fun DrawingCanvasOverlay(
    controller: DrawingController,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val inProgressPoints = remember { mutableStateListOf<DrawingPoint>() }
    var eraserCursorPos by remember { mutableStateOf<Offset?>(null) }
    var isMovingLassoSelection by remember { mutableStateOf(false) }
    var showLassoColorRow by remember { mutableStateOf(false) }

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
                                DrawingPoint(pt.x * scale, pt.y * scale)
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

    val activeTool = controller.activeTool
    val pointerModifier = if (activeTool == DrawingTool.NONE) {
        Modifier
    } else {
        Modifier.pointerInput(activeTool, controller.penColor, controller.strokeWidth, controller.alpha, controller.eraserRadius) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()

                val startX = down.position.x
                val startY = down.position.y
                val startPoint = DrawingPoint(startX, startY)

                inProgressPoints.clear()
                var cancelledByMultiTouch = false

                when {
                    activeTool.isEraserTool -> {
                        controller.beginEraserGesture()
                        eraserCursorPos = down.position
                        if (activeTool == DrawingTool.STROKE_ERASER) {
                            controller.eraseStrokeAt(startX, startY, controller.eraserRadius)
                        } else {
                            controller.eraseAreaAt(startX, startY, controller.eraserRadius)
                        }
                    }
                    activeTool == DrawingTool.LASSO -> {
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
                    activeTool.isShapeTool || activeTool == DrawingTool.UNDERLINE_MARKER -> {
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
                    val pressedCount = event.changes.count { it.pressed }
                    if (pressedCount > 1 && inProgressPoints.size <= 5 && !isMovingLassoSelection) {
                        // Multi-touch gesture (e.g. 2-finger pinch zoom): abort stroke preview
                        cancelledByMultiTouch = true
                        inProgressPoints.clear()
                        eraserCursorPos = null
                        break
                    }

                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                    if (change == null || !change.pressed) {
                        break
                    }

                    val pos = change.position
                    val delta = change.positionChange()
                    change.consume()

                    when {
                        activeTool.isEraserTool -> {
                            eraserCursorPos = pos
                            if (activeTool == DrawingTool.STROKE_ERASER) {
                                controller.eraseStrokeAt(pos.x, pos.y, controller.eraserRadius)
                            } else {
                                controller.eraseAreaAt(pos.x, pos.y, controller.eraserRadius)
                            }
                        }
                        activeTool == DrawingTool.LASSO -> {
                            if (isMovingLassoSelection) {
                                controller.moveSelectionBy(delta.x, delta.y)
                            } else {
                                val last = inProgressPoints.lastOrNull()
                                val current = DrawingPoint(pos.x, pos.y)
                                if (last == null || last.distanceTo(current) >= 3f) {
                                    inProgressPoints.add(current)
                                }
                            }
                        }
                        activeTool == DrawingTool.UNDERLINE_MARKER -> {
                            val first = inProgressPoints.firstOrNull() ?: startPoint
                            // Snap to clean horizontal underline unless dragged steeply (> 25 deg)
                            val dx = abs(pos.x - first.x)
                            val dy = abs(pos.y - first.y)
                            val endPt = if (dx > dy * 2.0f) {
                                DrawingPoint(pos.x, first.y)
                            } else {
                                DrawingPoint(pos.x, pos.y)
                            }
                            if (inProgressPoints.size >= 2) {
                                inProgressPoints[1] = endPt
                            } else {
                                inProgressPoints.add(endPt)
                            }
                        }
                        activeTool.isShapeTool -> {
                            val current = DrawingPoint(pos.x, pos.y)
                            if (inProgressPoints.size >= 2) {
                                inProgressPoints[1] = current
                            } else {
                                inProgressPoints.add(current)
                            }
                        }
                        else -> {
                            val last = inProgressPoints.lastOrNull()
                            val current = DrawingPoint(pos.x, pos.y)
                            if (last == null || last.distanceTo(current) >= 2.2f) {
                                inProgressPoints.add(current)
                            }
                        }
                    }
                }

                // Gesture finished
                eraserCursorPos = null
                if (!cancelledByMultiTouch) {
                    when {
                        activeTool.isEraserTool -> {
                            controller.endEraserGesture()
                        }
                        activeTool == DrawingTool.LASSO -> {
                            if (isMovingLassoSelection) {
                                controller.endMoveSelection()
                                isMovingLassoSelection = false
                            } else if (inProgressPoints.size >= 3) {
                                controller.selectByLassoPolygon(inProgressPoints.toList())
                            }
                            inProgressPoints.clear()
                        }
                        activeTool.isShapeTool || activeTool == DrawingTool.UNDERLINE_MARKER -> {
                            if (inProgressPoints.size >= 2) {
                                val p0 = inProgressPoints.first()
                                val p1 = inProgressPoints.last()
                                if (p0.distanceTo(p1) >= 4f) {
                                    controller.addStroke(
                                        DrawingStroke(
                                            tool = activeTool,
                                            shapeKind = activeTool.toShapeKind(),
                                            colorArgb = controller.penColor.toArgb(),
                                            strokeWidth = controller.strokeWidth,
                                            alpha = controller.alpha,
                                            points = listOf(p0, p1),
                                            canvasWidth = canvasSize.width.toFloat().coerceAtLeast(1f),
                                            canvasHeight = canvasSize.height.toFloat().coerceAtLeast(1f)
                                        )
                                    )
                                }
                            }
                            inProgressPoints.clear()
                        }
                        activeTool.isPenTool -> {
                            if (inProgressPoints.isNotEmpty()) {
                                val finalPoints = if (inProgressPoints.size == 1) {
                                    val p = inProgressPoints.first()
                                    listOf(p, DrawingPoint(p.x + 0.5f, p.y + 0.5f))
                                } else {
                                    inProgressPoints.toList()
                                }
                                controller.addStroke(
                                    DrawingStroke(
                                        tool = activeTool,
                                        shapeKind = ShapeKind.NONE,
                                        colorArgb = controller.penColor.toArgb(),
                                        strokeWidth = controller.strokeWidth,
                                        alpha = controller.alpha,
                                        points = finalPoints,
                                        canvasWidth = canvasSize.width.toFloat().coerceAtLeast(1f),
                                        canvasHeight = canvasSize.height.toFloat().coerceAtLeast(1f)
                                    )
                                )
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
            .onSizeChanged { canvasSize = it }
            .then(pointerModifier)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val selectedIds = controller.selectedStrokeIds

            // 1. Draw all committed strokes
            for (stroke in controller.strokes) {
                val isSelected = stroke.id in selectedIds
                if (isSelected) {
                    // Draw subtle selection halo behind selected stroke
                    drawStrokeElement(
                        stroke = stroke.copy(
                            colorArgb = Color(0xFF00B0FF).toArgb(),
                            strokeWidth = stroke.strokeWidth + 6f,
                            alpha = 0.28f
                        )
                    )
                }
                drawStrokeElement(stroke)
            }

            // 2. Draw active in-progress stroke / shape / lasso
            if (inProgressPoints.isNotEmpty()) {
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
                        points = inProgressPoints.toList()
                    )
                    drawStrokeElement(previewStroke)
                }
            }

            // 3. Draw Lasso selection bounding box with corner handles
            val selBounds = controller.selectionBounds()
            if (selBounds != null) {
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                val topLeft = Offset(selBounds.left, selBounds.top)
                val rectSize = Size(selBounds.width, selBounds.height)

                drawRect(
                    color = Color(0xFF0288D1).copy(alpha = 0.08f),
                    topLeft = topLeft,
                    size = rectSize
                )
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

        // Floating Contextual Action Bar above/below Lasso Selection
        val selBounds = controller.selectionBounds()
        if (selBounds != null && canvasSize.width > 0) {
            val barWidthPx = with(density) { 290.dp.toPx() }
            val barHeightPx = with(density) { if (showLassoColorRow) 84.dp.toPx() else 44.dp.toPx() }
            val targetX = (selBounds.centerX - barWidthPx * 0.5f)
                .coerceIn(8f, (canvasSize.width - barWidthPx - 8f).coerceAtLeast(8f))
            val aboveY = selBounds.top - barHeightPx - with(density) { 10.dp.toPx() }
            val targetY = if (aboveY >= 8f) {
                aboveY
            } else {
                (selBounds.bottom + with(density) { 10.dp.toPx() })
                    .coerceAtMost((canvasSize.height - barHeightPx - 8f).coerceAtLeast(8f))
            }

            Surface(
                modifier = Modifier.offset { IntOffset(targetX.roundToInt(), targetY.roundToInt()) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        LassoActionChip(
                            label = "Kes",
                            onClick = { controller.cutSelected() }
                        )
                        LassoActionChip(
                            label = "Kopyala",
                            onClick = { controller.copySelected() }
                        )
                        if (controller.hasClipboard) {
                            LassoActionChip(
                                label = "Yapıştır",
                                onClick = { controller.pasteClipboard() }
                            )
                        }
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
                        IconButton(
                            onClick = { controller.clearSelection() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Seçimi Kaldır",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (showLassoColorRow) {
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
    }
}

@Composable
private fun LassoActionChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Renders a single [DrawingStroke] (either freehand pen/highlighter or geometric shape) onto [DrawScope].
 */
fun DrawScope.drawStrokeElement(stroke: DrawingStroke) {
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
        drawCircle(
            color = paintColor,
            radius = widthPx * 0.5f,
            center = Offset(pts[0].x, pts[0].y)
        )
        return
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
        color = paintColor,
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
