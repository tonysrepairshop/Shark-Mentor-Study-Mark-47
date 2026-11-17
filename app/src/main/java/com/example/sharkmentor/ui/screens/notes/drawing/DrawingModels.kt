package com.example.sharkmentor.ui.screens.notes.drawing

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Available tools in the Notes & PDF Annotation Drawing Studio.
 */
enum class DrawingTool(
    val displayName: String,
    val defaultWidth: Float,
    val defaultAlpha: Float
) {
    NONE("Metin / Gezinme", 4f, 1.0f),

    // Kalemler
    FOUNTAIN_PEN("Dolma Kalem", 4.5f, 1.0f),
    HIGHLIGHTER("Fosforlu Kalem", 20f, 0.38f),
    TECHNICAL_PEN("İnce Teknik Kalem", 1.8f, 1.0f),
    UNDERLINE_MARKER("Alt Çizgi", 3.5f, 0.90f),

    // Silgiler
    STROKE_ERASER("Çizgi Silgisi", 22f, 1.0f),
    AREA_ERASER("Alan Silgisi", 20f, 1.0f),

    // Özel Şekiller
    SHAPE_LINE("Çizgi", 4.0f, 1.0f),
    SHAPE_ARROW("Ok", 4.0f, 1.0f),
    SHAPE_RECTANGLE("Dikdörtgen", 4.0f, 1.0f),
    SHAPE_ELLIPSE("Daire / Elips", 4.0f, 1.0f),
    SHAPE_TRIANGLE("Üçgen", 4.0f, 1.0f),

    // Kement (Lasso)
    LASSO("Kement Seçim", 2.5f, 1.0f);

    val isPenTool: Boolean
        get() = this == FOUNTAIN_PEN || this == HIGHLIGHTER || this == TECHNICAL_PEN || this == UNDERLINE_MARKER

    val isEraserTool: Boolean
        get() = this == STROKE_ERASER || this == AREA_ERASER

    val isShapeTool: Boolean
        get() = this == SHAPE_LINE || this == SHAPE_ARROW || this == SHAPE_RECTANGLE ||
            this == SHAPE_ELLIPSE || this == SHAPE_TRIANGLE

    fun toShapeKind(): ShapeKind = when (this) {
        SHAPE_LINE -> ShapeKind.LINE
        SHAPE_ARROW -> ShapeKind.ARROW
        SHAPE_RECTANGLE -> ShapeKind.RECTANGLE
        SHAPE_ELLIPSE -> ShapeKind.ELLIPSE
        SHAPE_TRIANGLE -> ShapeKind.TRIANGLE
        UNDERLINE_MARKER -> ShapeKind.LINE
        else -> ShapeKind.NONE
    }
}

enum class ShapeKind {
    NONE,
    LINE,
    ARROW,
    RECTANGLE,
    ELLIPSE,
    TRIANGLE
}

@Immutable
data class DrawingPoint(
    val x: Float,
    val y: Float
) {
    fun distanceTo(other: DrawingPoint): Float = hypot(x - other.x, y - other.y)
    fun offset(dx: Float, dy: Float): DrawingPoint = DrawingPoint(x + dx, y + dy)
}

@Immutable
data class DrawingBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom

    fun intersects(other: DrawingBounds): Boolean =
        left <= other.right && right >= other.left &&
            top <= other.bottom && bottom >= other.top

    fun inflate(padding: Float): DrawingBounds = DrawingBounds(
        left = left - padding,
        top = top - padding,
        right = right + padding,
        bottom = bottom + padding
    )
}

/**
 * Immutable representation of a drawn stroke or geometric shape.
 */
@Immutable
data class DrawingStroke(
    val id: String = UUID.randomUUID().toString(),
    val tool: DrawingTool = DrawingTool.FOUNTAIN_PEN,
    val shapeKind: ShapeKind = ShapeKind.NONE,
    val colorArgb: Int = 0xFF1E88E5.toInt(),
    val strokeWidth: Float = 4.5f,
    val alpha: Float = 1.0f,
    val points: List<DrawingPoint> = emptyList(),
    val canvasWidth: Float = 1080f,
    val canvasHeight: Float = 1920f
) {
    val composeColor: Color
        get() = Color(colorArgb).copy(alpha = alpha.coerceIn(0.05f, 1.0f))

    fun translated(dx: Float, dy: Float): DrawingStroke = copy(
        points = points.map { it.offset(dx, dy) }
    )

    fun withNewIdentity(dx: Float = 0f, dy: Float = 0f): DrawingStroke = copy(
        id = UUID.randomUUID().toString(),
        points = if (dx == 0f && dy == 0f) points else points.map { it.offset(dx, dy) }
    )

    fun boundingBox(): DrawingBounds {
        if (points.isEmpty()) return DrawingBounds(0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val halfStroke = strokeWidth * 0.6f + 4f
        return DrawingBounds(
            left = minX - halfStroke,
            top = minY - halfStroke,
            right = maxX + halfStroke,
            bottom = maxY + halfStroke
        )
    }

    /**
     * Densifies this stroke or geometric shape into a list of polyline points
     * for accurate Lasso selection and Area Eraser splitting.
     */
    fun toSampledPolyline(stepPx: Float = 8f): List<DrawingPoint> {
        if (points.isEmpty()) return emptyList()
        if (shapeKind == ShapeKind.NONE) {
            if (points.size == 1) return points
            val out = ArrayList<DrawingPoint>(points.size * 2)
            out.add(points.first())
            for (i in 0 until points.lastIndex) {
                val a = points[i]
                val b = points[i + 1]
                val dist = a.distanceTo(b)
                if (dist > stepPx) {
                    val steps = (dist / stepPx).toInt().coerceIn(1, 120)
                    for (s in 1..steps) {
                        val t = s.toFloat() / steps.toFloat()
                        out.add(
                            DrawingPoint(
                                x = a.x + (b.x - a.x) * t,
                                y = a.y + (b.y - a.y) * t
                            )
                        )
                    }
                } else {
                    out.add(b)
                }
            }
            return out
        }

        val start = points.first()
        val end = points.last()
        val left = min(start.x, end.x)
        val right = max(start.x, end.x)
        val top = min(start.y, end.y)
        val bottom = max(start.y, end.y)

        return when (shapeKind) {
            ShapeKind.LINE -> interpolateSegment(start, end, stepPx)
            ShapeKind.ARROW -> {
                val mainLine = interpolateSegment(start, end, stepPx).toMutableList()
                val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                val headLen = (strokeWidth * 4.2f).coerceIn(18f, 48f)
                val wingAngle = PI / 6.0
                val wing1 = DrawingPoint(
                    x = (end.x - headLen * cos(angle - wingAngle)).toFloat(),
                    y = (end.y - headLen * sin(angle - wingAngle)).toFloat()
                )
                val wing2 = DrawingPoint(
                    x = (end.x - headLen * cos(angle + wingAngle)).toFloat(),
                    y = (end.y - headLen * sin(angle + wingAngle)).toFloat()
                )
                mainLine.addAll(interpolateSegment(wing1, end, stepPx))
                mainLine.addAll(interpolateSegment(end, wing2, stepPx))
                mainLine
            }
            ShapeKind.RECTANGLE -> {
                val tl = DrawingPoint(left, top)
                val tr = DrawingPoint(right, top)
                val br = DrawingPoint(right, bottom)
                val bl = DrawingPoint(left, bottom)
                buildList {
                    addAll(interpolateSegment(tl, tr, stepPx))
                    addAll(interpolateSegment(tr, br, stepPx))
                    addAll(interpolateSegment(br, bl, stepPx))
                    addAll(interpolateSegment(bl, tl, stepPx))
                }
            }
            ShapeKind.TRIANGLE -> {
                val topVertex = DrawingPoint((left + right) * 0.5f, top)
                val br = DrawingPoint(right, bottom)
                val bl = DrawingPoint(left, bottom)
                buildList {
                    addAll(interpolateSegment(topVertex, br, stepPx))
                    addAll(interpolateSegment(br, bl, stepPx))
                    addAll(interpolateSegment(bl, topVertex, stepPx))
                }
            }
            ShapeKind.ELLIPSE -> {
                val cx = (left + right) * 0.5f
                val cy = (top + bottom) * 0.5f
                val rx = (right - left) * 0.5f
                val ry = (bottom - top) * 0.5f
                val circumference = (2.0 * PI * max(rx, ry)).toFloat()
                val segments = (circumference / stepPx).toInt().coerceIn(24, 180)
                buildList(segments + 1) {
                    for (i in 0..segments) {
                        val theta = (2.0 * PI * i) / segments
                        add(
                            DrawingPoint(
                                x = cx + (rx * cos(theta)).toFloat(),
                                y = cy + (ry * sin(theta)).toFloat()
                            )
                        )
                    }
                }
            }
            ShapeKind.NONE -> points
        }
    }

    companion object {
        private fun interpolateSegment(
            a: DrawingPoint,
            b: DrawingPoint,
            stepPx: Float
        ): List<DrawingPoint> {
            val dist = a.distanceTo(b)
            val steps = (dist / stepPx).toInt().coerceIn(1, 200)
            return (0..steps).map { s ->
                val t = s.toFloat() / steps.toFloat()
                DrawingPoint(
                    x = a.x + (b.x - a.x) * t,
                    y = a.y + (b.y - a.y) * t
                )
            }
        }
    }
}

/**
 * Stateful controller managing strokes, 60-step Undo/Redo history, Pens, Erasers, Shapes, and Lasso operations.
 */
@Stable
class DrawingController(
    val maxHistorySteps: Int = 60,
    private val onStrokesChanged: (List<DrawingStroke>) -> Unit = {}
) {
    var strokes by mutableStateOf<List<DrawingStroke>>(emptyList())
        private set

    var activeTool by mutableStateOf(DrawingTool.NONE)
        private set

    var penColor by mutableStateOf(Color(0xFF1E88E5))
    var strokeWidth by mutableFloatStateOf(DrawingTool.FOUNTAIN_PEN.defaultWidth)
    var alpha by mutableFloatStateOf(DrawingTool.FOUNTAIN_PEN.defaultAlpha)
    var eraserRadius by mutableFloatStateOf(22f)

    // Per-tool saved settings so switching between Fountain Pen, Highlighter, and Technical Pen preserves user preferences
    private val toolWidths = mutableMapOf(
        DrawingTool.FOUNTAIN_PEN to DrawingTool.FOUNTAIN_PEN.defaultWidth,
        DrawingTool.HIGHLIGHTER to DrawingTool.HIGHLIGHTER.defaultWidth,
        DrawingTool.TECHNICAL_PEN to DrawingTool.TECHNICAL_PEN.defaultWidth,
        DrawingTool.UNDERLINE_MARKER to DrawingTool.UNDERLINE_MARKER.defaultWidth
    )
    private val toolAlphas = mutableMapOf(
        DrawingTool.FOUNTAIN_PEN to DrawingTool.FOUNTAIN_PEN.defaultAlpha,
        DrawingTool.HIGHLIGHTER to DrawingTool.HIGHLIGHTER.defaultAlpha,
        DrawingTool.TECHNICAL_PEN to DrawingTool.TECHNICAL_PEN.defaultAlpha,
        DrawingTool.UNDERLINE_MARKER to DrawingTool.UNDERLINE_MARKER.defaultAlpha
    )
    private val toolColors = mutableMapOf(
        DrawingTool.FOUNTAIN_PEN to Color(0xFF1E88E5),
        DrawingTool.HIGHLIGHTER to Color(0xFFFFEB3B),
        DrawingTool.TECHNICAL_PEN to Color(0xFF0F172A),
        DrawingTool.UNDERLINE_MARKER to Color(0xFFE53935)
    )

    // Undo / Redo stacks (minimum 50 steps required; defaults to 60)
    private val undoStack = ArrayDeque<List<DrawingStroke>>()
    private val redoStack = ArrayDeque<List<DrawingStroke>>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // Lasso Selection & Clipboard State
    var selectedStrokeIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var clipboardStrokes by mutableStateOf<List<DrawingStroke>>(emptyList())
        private set

    private var preMoveSnapshot: List<DrawingStroke>? = null
    private var hasMovedSelectionInGesture: Boolean = false

    val isDrawingActive: Boolean
        get() = activeTool != DrawingTool.NONE

    val hasSelection: Boolean
        get() = selectedStrokeIds.isNotEmpty()

    val hasClipboard: Boolean
        get() = clipboardStrokes.isNotEmpty()

    fun selectTool(tool: DrawingTool) {
        // Save current tool settings
        if (activeTool.isPenTool) {
            toolWidths[activeTool] = strokeWidth
            toolAlphas[activeTool] = alpha
            toolColors[activeTool] = penColor
        }
        activeTool = tool
        if (tool != DrawingTool.LASSO) {
            clearSelection()
        }
        when {
            tool.isPenTool -> {
                strokeWidth = toolWidths[tool] ?: tool.defaultWidth
                alpha = toolAlphas[tool] ?: tool.defaultAlpha
                penColor = toolColors[tool] ?: penColor
            }
            tool.isShapeTool -> {
                if (alpha < 0.5f) alpha = 1.0f
                if (strokeWidth > 14f) strokeWidth = 4.0f
            }
            tool.isEraserTool -> {
                eraserRadius = tool.defaultWidth
            }
            else -> {}
        }
    }

    fun updatePenColor(color: Color) {
        penColor = color
        if (activeTool.isPenTool) {
            toolColors[activeTool] = color
        }
    }

    fun updateStrokeWidth(width: Float) {
        val clamped = width.coerceIn(1f, 42f)
        strokeWidth = clamped
        if (activeTool.isPenTool) {
            toolWidths[activeTool] = clamped
        } else if (activeTool.isEraserTool) {
            eraserRadius = clamped.coerceIn(8f, 48f)
        }
    }

    fun updateAlpha(newAlpha: Float) {
        val clamped = newAlpha.coerceIn(0.12f, 1.0f)
        alpha = clamped
        if (activeTool.isPenTool) {
            toolAlphas[activeTool] = clamped
        }
    }

    /**
     * Loads initial strokes (e.g. from JSON sidecar) without polluting Undo history.
     */
    fun setInitialStrokes(loaded: List<DrawingStroke>) {
        strokes = loaded
        undoStack.clear()
        redoStack.clear()
        selectedStrokeIds = emptySet()
        syncHistoryFlags()
    }

    private fun pushUndoSnapshot(snapshot: List<DrawingStroke> = strokes) {
        undoStack.addLast(snapshot)
        while (undoStack.size > maxHistorySteps) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        syncHistoryFlags()
    }

    private fun syncHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun updateStrokesAndNotify(newStrokes: List<DrawingStroke>) {
        strokes = newStrokes
        onStrokesChanged(newStrokes)
    }

    fun addStroke(stroke: DrawingStroke) {
        if (stroke.points.isEmpty()) return
        pushUndoSnapshot()
        updateStrokesAndNotify(strokes + stroke)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.removeLast()
        redoStack.addLast(strokes)
        while (redoStack.size > maxHistorySteps) {
            redoStack.removeFirst()
        }
        selectedStrokeIds = selectedStrokeIds.intersect(previous.map { it.id }.toSet())
        syncHistoryFlags()
        updateStrokesAndNotify(previous)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(strokes)
        while (undoStack.size > maxHistorySteps) {
            undoStack.removeFirst()
        }
        selectedStrokeIds = selectedStrokeIds.intersect(next.map { it.id }.toSet())
        syncHistoryFlags()
        updateStrokesAndNotify(next)
    }

    fun clearAll() {
        if (strokes.isEmpty()) return
        pushUndoSnapshot()
        selectedStrokeIds = emptySet()
        updateStrokesAndNotify(emptyList())
    }

    // ── Eraser Operations ────────────────────────────────────────────────────

    private var eraserSnapshotPushed = false

    fun beginEraserGesture() {
        eraserSnapshotPushed = false
    }

    fun endEraserGesture() {
        if (eraserSnapshotPushed) {
            onStrokesChanged(strokes)
        }
        eraserSnapshotPushed = false
    }

    /**
     * Stroke Eraser: removes any whole stroke whose sampled points fall within [radius] of (x, y).
     */
    fun eraseStrokeAt(x: Float, y: Float, radius: Float = eraserRadius): Boolean {
        if (strokes.isEmpty()) return false
        val touchPoint = DrawingPoint(x, y)
        val remaining = ArrayList<DrawingStroke>(strokes.size)
        var removedAny = false

        for (stroke in strokes) {
            val hitRadius = radius + stroke.strokeWidth * 0.5f
            val box = stroke.boundingBox().inflate(radius)
            if (!box.contains(x, y)) {
                remaining.add(stroke)
                continue
            }
            val sampled = stroke.toSampledPolyline(stepPx = 6f)
            val isHit = sampled.any { pt -> pt.distanceTo(touchPoint) <= hitRadius }
            if (isHit) {
                removedAny = true
            } else {
                remaining.add(stroke)
            }
        }

        if (removedAny) {
            if (!eraserSnapshotPushed) {
                pushUndoSnapshot()
                eraserSnapshotPushed = true
            }
            strokes = remaining
        }
        return removedAny
    }

    /**
     * Area / Point Eraser: removes individual points within [radius] of (x, y),
     * splitting strokes or shapes into sub-strokes where erased.
     */
    fun eraseAreaAt(x: Float, y: Float, radius: Float = eraserRadius): Boolean {
        if (strokes.isEmpty()) return false
        val touchPoint = DrawingPoint(x, y)
        val nextStrokes = ArrayList<DrawingStroke>(strokes.size + 4)
        var modified = false

        for (stroke in strokes) {
            val hitRadius = radius + stroke.strokeWidth * 0.4f
            val box = stroke.boundingBox().inflate(radius)
            if (!box.contains(x, y)) {
                nextStrokes.add(stroke)
                continue
            }

            val densePoints = stroke.toSampledPolyline(stepPx = 6f)
            val anyHit = densePoints.any { it.distanceTo(touchPoint) <= hitRadius }
            if (!anyHit) {
                nextStrokes.add(stroke)
                continue
            }

            modified = true
            // Split densePoints into contiguous surviving segments outside hitRadius
            var currentSegment = ArrayList<DrawingPoint>()
            for (pt in densePoints) {
                if (pt.distanceTo(touchPoint) > hitRadius) {
                    currentSegment.add(pt)
                } else {
                    if (currentSegment.size >= 2) {
                        nextStrokes.add(
                            stroke.copy(
                                id = UUID.randomUUID().toString(),
                                shapeKind = ShapeKind.NONE,
                                tool = if (stroke.tool.isShapeTool) DrawingTool.FOUNTAIN_PEN else stroke.tool,
                                points = currentSegment
                            )
                        )
                    }
                    currentSegment = ArrayList()
                }
            }
            if (currentSegment.size >= 2) {
                nextStrokes.add(
                    stroke.copy(
                        id = UUID.randomUUID().toString(),
                        shapeKind = ShapeKind.NONE,
                        tool = if (stroke.tool.isShapeTool) DrawingTool.FOUNTAIN_PEN else stroke.tool,
                        points = currentSegment
                    )
                )
            }
        }

        if (modified) {
            if (!eraserSnapshotPushed) {
                pushUndoSnapshot()
                eraserSnapshotPushed = true
            }
            strokes = nextStrokes
        }
        return modified
    }

    // ── Lasso Selection & Manipulation ───────────────────────────────────────

    fun clearSelection() {
        selectedStrokeIds = emptySet()
    }

    fun selectionBounds(): DrawingBounds? {
        if (selectedStrokeIds.isEmpty()) return null
        val selected = strokes.filter { it.id in selectedStrokeIds }
        if (selected.isEmpty()) return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (s in selected) {
            val b = s.boundingBox()
            if (b.left < minX) minX = b.left
            if (b.top < minY) minY = b.top
            if (b.right > maxX) maxX = b.right
            if (b.bottom > maxY) maxY = b.bottom
        }
        return DrawingBounds(minX, minY, maxX, maxY).inflate(6f)
    }

    /**
     * Selects all strokes that have points inside [lassoPolygon] (ray-casting algorithm).
     */
    fun selectByLassoPolygon(lassoPolygon: List<DrawingPoint>) {
        if (lassoPolygon.size < 3) {
            selectedStrokeIds = emptySet()
            return
        }

        val matchedIds = mutableSetOf<String>()
        for (stroke in strokes) {
            val sampled = stroke.toSampledPolyline(stepPx = 10f)
            if (sampled.isEmpty()) continue
            val insideCount = sampled.count { pt -> isPointInPolygon(pt, lassoPolygon) }
            // Select if at least 25% of sampled points or any endpoint/center is inside the lasso loop
            val bbox = stroke.boundingBox()
            val centerPt = DrawingPoint(bbox.centerX, bbox.centerY)
            if (insideCount > 0 && (insideCount >= max(1, sampled.size / 4) || isPointInPolygon(centerPt, lassoPolygon))) {
                matchedIds.add(stroke.id)
            }
        }
        selectedStrokeIds = matchedIds
    }

    fun beginMoveSelection() {
        preMoveSnapshot = strokes
        hasMovedSelectionInGesture = false
    }

    fun moveSelectionBy(dx: Float, dy: Float) {
        if (selectedStrokeIds.isEmpty() || (dx == 0f && dy == 0f)) return
        hasMovedSelectionInGesture = true
        strokes = strokes.map { stroke ->
            if (stroke.id in selectedStrokeIds) stroke.translated(dx, dy) else stroke
        }
    }

    fun endMoveSelection() {
        val snapshot = preMoveSnapshot
        if (hasMovedSelectionInGesture && snapshot != null) {
            pushUndoSnapshot(snapshot)
            onStrokesChanged(strokes)
        }
        preMoveSnapshot = null
        hasMovedSelectionInGesture = false
    }

    fun deleteSelected() {
        if (selectedStrokeIds.isEmpty()) return
        pushUndoSnapshot()
        val remaining = strokes.filterNot { it.id in selectedStrokeIds }
        selectedStrokeIds = emptySet()
        updateStrokesAndNotify(remaining)
    }

    fun copySelected() {
        if (selectedStrokeIds.isEmpty()) return
        clipboardStrokes = strokes
            .filter { it.id in selectedStrokeIds }
            .map { it.withNewIdentity() }
    }

    fun cutSelected() {
        if (selectedStrokeIds.isEmpty()) return
        clipboardStrokes = strokes
            .filter { it.id in selectedStrokeIds }
            .map { it.withNewIdentity() }
        pushUndoSnapshot()
        val remaining = strokes.filterNot { it.id in selectedStrokeIds }
        selectedStrokeIds = emptySet()
        updateStrokesAndNotify(remaining)
    }

    fun pasteClipboard(offsetX: Float = 28f, offsetY: Float = 28f) {
        if (clipboardStrokes.isEmpty()) return
        pushUndoSnapshot()
        val pasted = clipboardStrokes.map { it.withNewIdentity(dx = offsetX, dy = offsetY) }
        // Advance clipboard slightly so repeated pastes cascade nicely
        clipboardStrokes = pasted.map { it.withNewIdentity() }
        val updated = strokes + pasted
        selectedStrokeIds = pasted.map { it.id }.toSet()
        activeTool = DrawingTool.LASSO
        updateStrokesAndNotify(updated)
    }

    fun recolorSelected(newColor: Color) {
        if (selectedStrokeIds.isEmpty()) return
        pushUndoSnapshot()
        val argb = newColor.toArgb()
        val updated = strokes.map { stroke ->
            if (stroke.id in selectedStrokeIds) stroke.copy(colorArgb = argb) else stroke
        }
        penColor = newColor
        updateStrokesAndNotify(updated)
    }

    companion object {
        private fun isPointInPolygon(point: DrawingPoint, polygon: List<DrawingPoint>): Boolean {
            var inside = false
            var j = polygon.lastIndex
            for (i in polygon.indices) {
                val pi = polygon[i]
                val pj = polygon[j]
                val intersects = ((pi.y > point.y) != (pj.y > point.y)) &&
                    (point.x < (pj.x - pi.x) * (point.y - pi.y) / ((pj.y - pi.y).let { if (it == 0f) 0.0001f else it }) + pi.x)
                if (intersects) inside = !inside
                j = i
            }
            return inside
        }
    }
}
