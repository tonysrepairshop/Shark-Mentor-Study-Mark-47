package com.example.sharkmentor.ui.screens.notes.drawing

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Reactive SharedPreferences store for Notes & PDF drawing studio preferences.
 * Edited exclusively from Settings -> "Not Özellikleri" and observed live by DrawingCanvasOverlay.
 */
object NoteDrawingPreferences {
    private const val PREFS_NAME = "shark_drawing_studio_prefs"
    private const val KEY_PRESSURE_SENSITIVITY = "pressure_sensitivity_enabled"
    private const val KEY_PRESSURE_STRENGTH = "pressure_sensitivity_strength"
    private const val KEY_SCRIBBLE_TO_ERASE = "scribble_to_erase_enabled"
    private const val KEY_AUTO_SHAPE_RECOGNITION = "auto_shape_recognition_enabled"

    @Volatile
    private var loaded = false

    private val _pressureSensitivityEnabled = MutableStateFlow(true)
    val pressureSensitivityEnabled: StateFlow<Boolean> = _pressureSensitivityEnabled.asStateFlow()

    private val _pressureSensitivityStrength = MutableStateFlow(1.15f)
    val pressureSensitivityStrength: StateFlow<Float> = _pressureSensitivityStrength.asStateFlow()

    private val _scribbleToEraseEnabled = MutableStateFlow(true)
    val scribbleToEraseEnabled: StateFlow<Boolean> = _scribbleToEraseEnabled.asStateFlow()

    private val _autoShapeRecognitionEnabled = MutableStateFlow(true)
    val autoShapeRecognitionEnabled: StateFlow<Boolean> = _autoShapeRecognitionEnabled.asStateFlow()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                _pressureSensitivityEnabled.value = prefs.getBoolean(KEY_PRESSURE_SENSITIVITY, true)
                _pressureSensitivityStrength.value = prefs.getFloat(KEY_PRESSURE_STRENGTH, 1.15f).coerceIn(0.5f, 1.8f)
                _scribbleToEraseEnabled.value = prefs.getBoolean(KEY_SCRIBBLE_TO_ERASE, true)
                _autoShapeRecognitionEnabled.value = prefs.getBoolean(KEY_AUTO_SHAPE_RECOGNITION, true)
            }
            loaded = true
        }
    }

    fun setPressureSensitivityEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _pressureSensitivityEnabled.value = enabled
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PRESSURE_SENSITIVITY, enabled)
                .apply()
        }
    }

    fun setPressureSensitivityStrength(context: Context, strength: Float) {
        ensureLoaded(context)
        val clamped = strength.coerceIn(0.5f, 1.8f)
        _pressureSensitivityStrength.value = clamped
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_PRESSURE_STRENGTH, clamped)
                .apply()
        }
    }

    fun setScribbleToEraseEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _scribbleToEraseEnabled.value = enabled
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SCRIBBLE_TO_ERASE, enabled)
                .apply()
        }
    }

    fun setAutoShapeRecognitionEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _autoShapeRecognitionEnabled.value = enabled
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_SHAPE_RECOGNITION, enabled)
                .apply()
        }
    }
}

/**
 * Available tools in the Notes & PDF Annotation Drawing Studio.
 */
enum class DrawingTool(
    val displayName: String,
    val defaultWidth: Float,
    val defaultAlpha: Float
) {
    NONE("Metin / Gezinme", 4f, 1.0f),

    // Kalem Paketleri (Kalem simgesine uzun basınca seçilir)
    FOUNTAIN_PEN("Dolma Kalem", 4.5f, 1.0f),
    BALLPOINT_PEN("Tükenmez Kalem", 3.2f, 1.0f),
    PENCIL("Kurşun Kalem", 3.4f, 0.86f),
    BRUSH_PEN("Fırça Kalem", 8.0f, 1.0f),
    HIGHLIGHTER("Fosforlu Kalem", 20f, 0.38f),

    // Eski kayıtlı çizimlerle geriye dönük uyumluluk
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

    val shortLabel: String
        get() = when (this) {
            FOUNTAIN_PEN -> "Dolma"
            BALLPOINT_PEN -> "Tükenmez"
            PENCIL -> "Kurşun"
            BRUSH_PEN -> "Fırça"
            HIGHLIGHTER -> "Fosforlu"
            TECHNICAL_PEN -> "Teknik"
            UNDERLINE_MARKER -> "Alt Çizgi"
            else -> displayName
        }

    val packageDescription: String
        get() = when (this) {
            FOUNTAIN_PEN -> "Basınca duyarlı kaligrafik uç"
            BALLPOINT_PEN -> "Net ve dengeli günlük yazım"
            PENCIL -> "Mat grafit dokulu çizim ucu"
            BRUSH_PEN -> "Geniş aralıklı vurgulu fırça"
            HIGHLIGHTER -> "Yarı saydam vurgu kalemi"
            else -> displayName
        }

    val isPenTool: Boolean
        get() = this == FOUNTAIN_PEN || this == BALLPOINT_PEN || this == PENCIL ||
            this == BRUSH_PEN || this == HIGHLIGHTER || this == TECHNICAL_PEN ||
            this == UNDERLINE_MARKER

    val isPrimaryPenPackage: Boolean
        get() = this in PEN_PACKAGES

    val supportsAutoShape: Boolean
        get() = this == FOUNTAIN_PEN || this == BALLPOINT_PEN || this == PENCIL ||
            this == BRUSH_PEN || this == TECHNICAL_PEN

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

    companion object {
        val PEN_PACKAGES: List<DrawingTool> = listOf(
            FOUNTAIN_PEN,
            BALLPOINT_PEN,
            PENCIL,
            BRUSH_PEN,
            HIGHLIGHTER
        )
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
    val y: Float,
    val pressure: Float = 1.0f
) {
    fun distanceTo(other: DrawingPoint): Float = hypot(x - other.x, y - other.y)
    fun offset(dx: Float, dy: Float): DrawingPoint = DrawingPoint(x + dx, y + dy, pressure)
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
    val canvasHeight: Float = 1920f,
    val pressureSensitive: Boolean = true
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
                                y = a.y + (b.y - a.y) * t,
                                pressure = a.pressure + (b.pressure - a.pressure) * t
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
                val (v0, v1, v2) = if (points.size >= 3) {
                    Triple(points[0], points[1], points[2])
                } else {
                    Triple(
                        DrawingPoint((left + right) * 0.5f, top),
                        DrawingPoint(right, bottom),
                        DrawingPoint(left, bottom)
                    )
                }
                buildList {
                    addAll(interpolateSegment(v0, v1, stepPx))
                    addAll(interpolateSegment(v1, v2, stepPx))
                    addAll(interpolateSegment(v2, v0, stepPx))
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
    context: Context? = null,
    private val onStrokesChanged: (List<DrawingStroke>) -> Unit = {}
) {
    private var appContext: Context? = context?.applicationContext

    var strokes by mutableStateOf<List<DrawingStroke>>(emptyList())
        private set

    var activeTool by mutableStateOf(DrawingTool.NONE)
        private set

    var lastSelectedPenTool by mutableStateOf(DrawingTool.FOUNTAIN_PEN)
        private set

    var penColor by mutableStateOf(Color(0xFF1E88E5))
    var strokeWidth by mutableFloatStateOf(DrawingTool.FOUNTAIN_PEN.defaultWidth)
    var alpha by mutableFloatStateOf(DrawingTool.FOUNTAIN_PEN.defaultAlpha)
    var eraserRadius by mutableFloatStateOf(22f)
    var stylusOnlyMode by mutableStateOf(false)
        private set
    var smartPalmRejectionEnabled by mutableStateOf(true)
    var pressureSensitivityEnabled by mutableStateOf(NoteDrawingPreferences.pressureSensitivityEnabled.value)
    var pressureSensitivityStrength by mutableFloatStateOf(NoteDrawingPreferences.pressureSensitivityStrength.value)
    var scribbleToEraseEnabled by mutableStateOf(NoteDrawingPreferences.scribbleToEraseEnabled.value)
    var autoShapeRecognitionEnabled by mutableStateOf(NoteDrawingPreferences.autoShapeRecognitionEnabled.value)
    var customPenColors by mutableStateOf<List<Color>>(emptyList())
        private set

    init {
        appContext?.let { bindPreferencesContext(it) }
    }

    fun bindPreferencesContext(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        NoteDrawingPreferences.ensureLoaded(ctx)
        syncFromNotePreferences()
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_DRAWING_STUDIO, Context.MODE_PRIVATE)
            val rawColors = prefs.getString(KEY_CUSTOM_PEN_COLORS, null).orEmpty()
            if (rawColors.isNotBlank()) {
                val parsed = rawColors.split(",")
                    .mapNotNull { token -> token.trim().toIntOrNull()?.let { Color(it) } }
                    .distinctBy { it.toArgb() }
                    .take(16)
                if (parsed.isNotEmpty()) {
                    customPenColors = parsed
                }
            }
        }
    }

    fun syncFromNotePreferences() {
        pressureSensitivityEnabled = NoteDrawingPreferences.pressureSensitivityEnabled.value
        pressureSensitivityStrength = NoteDrawingPreferences.pressureSensitivityStrength.value
        scribbleToEraseEnabled = NoteDrawingPreferences.scribbleToEraseEnabled.value
        autoShapeRecognitionEnabled = NoteDrawingPreferences.autoShapeRecognitionEnabled.value
    }

    fun addCustomPenColor(color: Color, context: Context? = null) {
        val updated = (listOf(color) + customPenColors.filterNot { it.toArgb() == color.toArgb() })
            .distinctBy { it.toArgb() }
            .take(16)
        customPenColors = updated
        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) {
            appContext = ctx
            runCatching {
                ctx.getSharedPreferences(PREFS_DRAWING_STUDIO, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CUSTOM_PEN_COLORS, updated.joinToString(",") { it.toArgb().toString() })
                    .apply()
            }
        }
    }

    fun updatePressureSensitivity(enabled: Boolean, context: Context? = null) {
        pressureSensitivityEnabled = enabled
        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) {
            appContext = ctx
            NoteDrawingPreferences.setPressureSensitivityEnabled(ctx, enabled)
        }
    }

    fun togglePressureSensitivity(context: Context? = null) {
        updatePressureSensitivity(!pressureSensitivityEnabled, context)
    }

    // Per-tool saved settings so switching between Pen packages preserves user preferences
    private val toolWidths = mutableMapOf(
        DrawingTool.FOUNTAIN_PEN to DrawingTool.FOUNTAIN_PEN.defaultWidth,
        DrawingTool.BALLPOINT_PEN to DrawingTool.BALLPOINT_PEN.defaultWidth,
        DrawingTool.PENCIL to DrawingTool.PENCIL.defaultWidth,
        DrawingTool.BRUSH_PEN to DrawingTool.BRUSH_PEN.defaultWidth,
        DrawingTool.HIGHLIGHTER to DrawingTool.HIGHLIGHTER.defaultWidth,
        DrawingTool.TECHNICAL_PEN to DrawingTool.TECHNICAL_PEN.defaultWidth,
        DrawingTool.UNDERLINE_MARKER to DrawingTool.UNDERLINE_MARKER.defaultWidth
    )
    private val toolAlphas = mutableMapOf(
        DrawingTool.FOUNTAIN_PEN to DrawingTool.FOUNTAIN_PEN.defaultAlpha,
        DrawingTool.BALLPOINT_PEN to DrawingTool.BALLPOINT_PEN.defaultAlpha,
        DrawingTool.PENCIL to DrawingTool.PENCIL.defaultAlpha,
        DrawingTool.BRUSH_PEN to DrawingTool.BRUSH_PEN.defaultAlpha,
        DrawingTool.HIGHLIGHTER to DrawingTool.HIGHLIGHTER.defaultAlpha,
        DrawingTool.TECHNICAL_PEN to DrawingTool.TECHNICAL_PEN.defaultAlpha,
        DrawingTool.UNDERLINE_MARKER to DrawingTool.UNDERLINE_MARKER.defaultAlpha
    )
    private val toolColors = mutableMapOf(
        DrawingTool.FOUNTAIN_PEN to Color(0xFF1E88E5),
        DrawingTool.BALLPOINT_PEN to Color(0xFF0F172A),
        DrawingTool.PENCIL to Color(0xFF475569),
        DrawingTool.BRUSH_PEN to Color(0xFF1E88E5),
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

    // Lasso Selection, Free-Region Crop & Clipboard State
    var selectedStrokeIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var lassoPolygon by mutableStateOf<List<DrawingPoint>>(emptyList())
        private set

    var lassoRegionBounds by mutableStateOf<DrawingBounds?>(null)
        private set

    var clipboardStrokes by mutableStateOf<List<DrawingStroke>>(emptyList())
        private set

    var screenshotRequestCounter by mutableIntStateOf(0)
        private set

    var askAiRequestCounter by mutableIntStateOf(0)
        private set

    private var preMoveSnapshot: List<DrawingStroke>? = null
    private var hasMovedSelectionInGesture: Boolean = false

    val isDrawingActive: Boolean
        get() = activeTool != DrawingTool.NONE

    val hasSelection: Boolean
        get() = selectedStrokeIds.isNotEmpty()

    val hasLassoRegion: Boolean
        get() = lassoRegionBounds != null && lassoPolygon.size >= 3

    val hasActiveLasso: Boolean
        get() = hasSelection || hasLassoRegion

    val hasClipboard: Boolean
        get() = clipboardStrokes.isNotEmpty()

    fun toggleStylusOnlyMode() {
        stylusOnlyMode = !stylusOnlyMode
    }

    fun updateStylusOnlyMode(enabled: Boolean) {
        stylusOnlyMode = enabled
    }

    fun requestLassoScreenshot() {
        if (selectionBounds() != null) {
            screenshotRequestCounter++
        }
    }

    fun requestLassoAskAi() {
        if (selectionBounds() != null) {
            askAiRequestCounter++
        }
    }

    fun selectTool(tool: DrawingTool) {
        // Save current tool settings
        if (activeTool.isPenTool) {
            toolWidths[activeTool] = strokeWidth
            toolAlphas[activeTool] = alpha
            toolColors[activeTool] = penColor
        }
        activeTool = tool
        if (tool.isPrimaryPenPackage) {
            lastSelectedPenTool = tool
        }
        if (tool != DrawingTool.LASSO) {
            clearSelection()
        }
        when {
            tool.isPenTool -> {
                strokeWidth = toolWidths[tool] ?: tool.defaultWidth
                alpha = if (tool == DrawingTool.HIGHLIGHTER) {
                    toolAlphas[tool] ?: tool.defaultAlpha
                } else {
                    tool.defaultAlpha
                }
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

    fun selectActiveOrLastPen() {
        selectTool(lastSelectedPenTool)
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
        clearSelection()
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
        clearSelection()
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

    /**
     * Scribble-to-Erase: deletes all existing strokes intersecting [scribblePoints]
     * in one batch while recording a single Undo snapshot.
     * Returns the count of erased strokes.
     */
    fun eraseStrokesByScribble(
        scribblePoints: List<DrawingPoint>,
        hitPadding: Float = 16f
    ): Int {
        if (strokes.isEmpty() || scribblePoints.size < 2) return 0

        val rawBounds = computePointsBounds(scribblePoints) ?: return 0
        val inflatedBounds = rawBounds.inflate(hitPadding)
        val denseScribble = interpolatePolyline(scribblePoints, stepPx = 10f)
        if (denseScribble.isEmpty()) return 0

        val remaining = ArrayList<DrawingStroke>(strokes.size)
        val removedIds = mutableSetOf<String>()

        for (stroke in strokes) {
            if (!stroke.boundingBox().intersects(inflatedBounds)) {
                remaining.add(stroke)
                continue
            }

            val sampledStroke = stroke.toSampledPolyline(stepPx = 8f)
            val hitRadius = hitPadding + stroke.strokeWidth * 0.5f
            val isHit = sampledStroke.any { sp ->
                denseScribble.any { qp -> sp.distanceTo(qp) <= hitRadius }
            }

            if (isHit) {
                removedIds.add(stroke.id)
            } else {
                remaining.add(stroke)
            }
        }

        if (removedIds.isEmpty()) return 0

        pushUndoSnapshot()
        selectedStrokeIds = selectedStrokeIds - removedIds
        updateStrokesAndNotify(remaining)
        return removedIds.size
    }

    // ── Lasso Selection & Manipulation ───────────────────────────────────────

    fun clearSelection() {
        selectedStrokeIds = emptySet()
        lassoPolygon = emptyList()
        lassoRegionBounds = null
    }

    fun selectionBounds(): DrawingBounds? {
        if (selectedStrokeIds.isNotEmpty()) {
            val selected = strokes.filter { it.id in selectedStrokeIds }
            if (selected.isNotEmpty()) {
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
        }
        return lassoRegionBounds
    }

    /**
     * Selects all strokes inside [lassoPolygon] AND retains the drawn lasso polygon + bounding box
     * as an active lasso region even when zero strokes lie inside (for PDF/note screenshot cropping).
     */
    fun selectByLassoPolygon(lassoPolygon: List<DrawingPoint>) {
        if (lassoPolygon.size < 3) {
            clearSelection()
            return
        }

        val polyBounds = computePointsBounds(lassoPolygon)
        if (polyBounds == null) {
            clearSelection()
            return
        }

        val matchedIds = mutableSetOf<String>()
        for (stroke in strokes) {
            val sampled = stroke.toSampledPolyline(stepPx = 10f)
            if (sampled.isEmpty()) continue
            val insideCount = sampled.count { pt -> isPointInPolygon(pt, lassoPolygon) }
            val bbox = stroke.boundingBox()
            val centerPt = DrawingPoint(bbox.centerX, bbox.centerY)
            if (insideCount > 0 && (insideCount >= max(1, sampled.size / 4) || isPointInPolygon(centerPt, lassoPolygon))) {
                matchedIds.add(stroke.id)
            }
        }

        val isMeaningfulRegion = polyBounds.width >= 16f && polyBounds.height >= 16f
        if (!isMeaningfulRegion && matchedIds.isEmpty()) {
            clearSelection()
            return
        }

        this.lassoPolygon = lassoPolygon
        this.lassoRegionBounds = polyBounds
        this.selectedStrokeIds = matchedIds
    }

    fun beginMoveSelection() {
        preMoveSnapshot = if (selectedStrokeIds.isNotEmpty()) strokes else null
        hasMovedSelectionInGesture = false
    }

    fun moveSelectionBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        if (selectedStrokeIds.isEmpty() && lassoRegionBounds == null) return

        if (selectedStrokeIds.isNotEmpty()) {
            hasMovedSelectionInGesture = true
            strokes = strokes.map { stroke ->
                if (stroke.id in selectedStrokeIds) stroke.translated(dx, dy) else stroke
            }
        }
        if (lassoPolygon.isNotEmpty()) {
            lassoPolygon = lassoPolygon.map { it.offset(dx, dy) }
        }
        lassoRegionBounds?.let { b ->
            lassoRegionBounds = DrawingBounds(
                left = b.left + dx,
                top = b.top + dy,
                right = b.right + dx,
                bottom = b.bottom + dy
            )
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
        clearSelection()
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
        clearSelection()
        updateStrokesAndNotify(remaining)
    }

    fun pasteClipboard(offsetX: Float = 28f, offsetY: Float = 28f) {
        if (clipboardStrokes.isEmpty()) return
        pushUndoSnapshot()
        val pasted = clipboardStrokes.map { it.withNewIdentity(dx = offsetX, dy = offsetY) }
        // Advance clipboard slightly so repeated pastes cascade nicely
        clipboardStrokes = pasted.map { it.withNewIdentity() }
        val updated = strokes + pasted
        lassoPolygon = emptyList()
        lassoRegionBounds = null
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
        private const val PREFS_DRAWING_STUDIO = "shark_drawing_studio_prefs"
        private const val KEY_PRESSURE_SENSITIVITY = "pressure_sensitivity_enabled"
        private const val KEY_CUSTOM_PEN_COLORS = "custom_pen_colors_csv"

        private fun computePointsBounds(points: List<DrawingPoint>): DrawingBounds? {
            if (points.isEmpty()) return null
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
            return DrawingBounds(minX, minY, maxX, maxY)
        }

        private fun interpolatePolyline(
            points: List<DrawingPoint>,
            stepPx: Float
        ): List<DrawingPoint> {
            if (points.isEmpty()) return emptyList()
            if (points.size == 1) return points
            val out = ArrayList<DrawingPoint>(points.size * 2)
            out.add(points.first())
            for (i in 0 until points.lastIndex) {
                val a = points[i]
                val b = points[i + 1]
                val dist = a.distanceTo(b)
                if (dist > stepPx) {
                    val steps = (dist / stepPx).toInt().coerceIn(1, 100)
                    for (s in 1..steps) {
                        val t = s.toFloat() / steps.toFloat()
                        out.add(
                            DrawingPoint(
                                x = a.x + (b.x - a.x) * t,
                                y = a.y + (b.y - a.y) * t,
                                pressure = a.pressure + (b.pressure - a.pressure) * t
                            )
                        )
                    }
                } else {
                    out.add(b)
                }
            }
            return out
        }

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

/**
 * Heuristic detector for rapid back-and-forth zigzag / scribble-to-erase gestures.
 * Triggers when at least [MIN_SHARP_REVERSALS] sharp turns (>= 110 degrees) happen
 * inside a compact bounding box within a short gesture duration.
 */
object ScribbleDetector {
    private const val MIN_POINTS = 6
    private const val MIN_DURATION_MS = 50L
    private const val MAX_DURATION_MS = 2600L
    private const val MIN_DIAGONAL_PX = 24f
    private const val MIN_PATH_LENGTH_PX = 75f
    private const val MAX_WIDTH_PX = 460f
    private const val MAX_HEIGHT_PX = 380f
    private const val MIN_LENGTH_TO_DIAG_RATIO = 2.0f
    private const val RDP_EPSILON_PX = 5.5f
    private const val MIN_SEGMENT_LEN_PX = 10f
    // cos(110°) = -0.34202014; angle >= 110° means dot product <= SHARP_TURN_COS_THRESHOLD
    private const val SHARP_TURN_COS_THRESHOLD = -0.342f
    private const val MIN_SHARP_REVERSALS = 3

    fun isScribbleGesture(
        points: List<DrawingPoint>,
        durationMs: Long
    ): Boolean {
        if (points.size < MIN_POINTS) return false
        if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) return false

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var totalLength = 0f

        for (i in points.indices) {
            val p = points[i]
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
            if (i > 0) {
                totalLength += points[i - 1].distanceTo(p)
            }
        }

        val width = maxX - minX
        val height = maxY - minY
        val diagonal = hypot(width, height)

        if (diagonal < MIN_DIAGONAL_PX || totalLength < MIN_PATH_LENGTH_PX) return false
        if (width > MAX_WIDTH_PX || height > MAX_HEIGHT_PX) return false
        if ((totalLength / diagonal) < MIN_LENGTH_TO_DIAG_RATIO) return false

        val simplified = simplifyDouglasPeucker(points, RDP_EPSILON_PX)
        val filtered = ArrayList<DrawingPoint>(simplified.size)
        for (pt in simplified) {
            val prev = filtered.lastOrNull()
            if (prev == null || prev.distanceTo(pt) >= MIN_SEGMENT_LEN_PX) {
                filtered.add(pt)
            }
        }
        if (filtered.size < 4) return false

        var sharpReversals = 0
        for (i in 1 until filtered.lastIndex) {
            val a = filtered[i - 1]
            val b = filtered[i]
            val c = filtered[i + 1]

            val v1x = b.x - a.x
            val v1y = b.y - a.y
            val v2x = c.x - b.x
            val v2y = c.y - b.y

            val len1 = hypot(v1x, v1y)
            val len2 = hypot(v2x, v2y)
            if (len1 < MIN_SEGMENT_LEN_PX || len2 < MIN_SEGMENT_LEN_PX) continue

            val dot = ((v1x * v2x) + (v1y * v2y)) / (len1 * len2)
            if (dot <= SHARP_TURN_COS_THRESHOLD) {
                sharpReversals++
                if (sharpReversals >= MIN_SHARP_REVERSALS) {
                    return true
                }
            }
        }

        return false
    }

    private fun simplifyDouglasPeucker(
        points: List<DrawingPoint>,
        epsilon: Float
    ): List<DrawingPoint> {
        if (points.size <= 2) return points
        var maxDist = 0f
        var index = 0
        val start = points.first()
        val end = points.last()

        for (i in 1 until points.lastIndex) {
            val d = perpendicularDistance(points[i], start, end)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }

        return if (maxDist > epsilon) {
            val left = simplifyDouglasPeucker(points.subList(0, index + 1), epsilon)
            val right = simplifyDouglasPeucker(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(
        point: DrawingPoint,
        lineStart: DrawingPoint,
        lineEnd: DrawingPoint
    ): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.0001f) return point.distanceTo(lineStart)
        val cross = abs(dy * point.x - dx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x)
        return cross / hypot(dx, dy)
    }
}
