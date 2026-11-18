package com.example.sharkmentor.ui.screens.leaguechat

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingCanvasOverlay
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingController
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingStroke
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

val QUESTION_ANNOTATOR_COLORS = listOf(
    Color(0xFFE53935), // Kırmızı Kalem
    Color(0xFF1E88E5), // Mavi Kalem
    Color(0xFF43A047), // Yeşil Kalem
    Color(0xFFFFEB3B), // Sarı Fosforlu
    Color(0xFF8E24AA), // Mor Kalem
    Color(0xFF0F172A)  // Siyah Kalem
)

/**
 * Holds the question bitmap, canvas viewport dimensions, and [DrawingController]
 * so the caller can export a composite [Bitmap] (question image + drawn solution strokes)
 * via [exportAnnotatedBitmap].
 */
@Stable
class QuestionAnnotatorState(
    val drawingController: DrawingController = DrawingController(maxHistorySteps = 60).apply {
        selectTool(DrawingTool.FOUNTAIN_PEN)
        penColor = Color(0xFFE53935)
        strokeWidth = 4.5f
        alpha = 1.0f
    }
) {
    var sourceBitmap by mutableStateOf<Bitmap?>(null)
    var isLoadingImage by mutableStateOf(false)
    var imageLoadError by mutableStateOf<String?>(null)
    var canvasWidthPx by mutableIntStateOf(0)
    var canvasHeightPx by mutableIntStateOf(0)

    val hasAnnotations: Boolean
        get() = drawingController.strokes.isNotEmpty()

    fun loadBitmap(bitmap: Bitmap) {
        sourceBitmap = bitmap
        isLoadingImage = false
        imageLoadError = null
    }

    /**
     * Composites the loaded [sourceBitmap] (or a clean white canvas if no image was loaded)
     * together with all strokes in [drawingController] into a single [Bitmap].
     * Returns null only if there is neither a source bitmap nor any drawn strokes.
     */
    fun exportAnnotatedBitmap(): Bitmap? {
        val baseBmp = sourceBitmap?.takeIf { !it.isRecycled }
        val strokesSnapshot = drawingController.strokes.toList()
        if (baseBmp == null && strokesSnapshot.isEmpty()) {
            return null
        }

        val viewW = canvasWidthPx.coerceAtLeast(1)
        val viewH = canvasHeightPx.coerceAtLeast(1)

        val outW: Int
        val outH: Int
        if (baseBmp != null) {
            val maxDim = 1440
            val scale = if (baseBmp.width > maxDim || baseBmp.height > maxDim) {
                maxDim.toFloat() / max(baseBmp.width, baseBmp.height).toFloat()
            } else {
                1f
            }
            outW = (baseBmp.width * scale).roundToInt().coerceIn(320, 1440)
            outH = (baseBmp.height * scale).roundToInt().coerceIn(320, 1440)
        } else {
            outW = viewW.coerceIn(480, 1280)
            outH = viewH.coerceIn(480, 1280)
        }

        return runCatching {
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(output)
            canvas.drawColor(AndroidColor.WHITE)

            if (baseBmp != null) {
                val destRect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
                val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(baseBmp, null, destRect, bmpPaint)
            }

            if (strokesSnapshot.isNotEmpty()) {
                val scaleX = outW.toFloat() / viewW.toFloat()
                val scaleY = outH.toFloat() / viewH.toFloat()
                val avgScale = (scaleX + scaleY) * 0.5f

                // Render strokes onto a separate layer so AREA_ERASER (PorterDuff.Mode.CLEAR)
                // only erases ink and never punches holes in the question image!
                val layerBounds = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
                val saveCount = canvas.saveLayer(layerBounds, null)
                for (stroke in strokesSnapshot) {
                    renderStrokeToAndroidCanvas(
                        canvas = canvas,
                        stroke = stroke,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        strokeScale = avgScale
                    )
                }
                canvas.restoreToCount(saveCount)
            }
            output
        }.getOrNull()
    }

    private fun renderStrokeToAndroidCanvas(
        canvas: AndroidCanvas,
        stroke: DrawingStroke,
        scaleX: Float,
        scaleY: Float,
        strokeScale: Float
    ) {
        val pts = stroke.points
        if (pts.isEmpty()) return

        val effectiveAlpha = when (stroke.tool) {
            DrawingTool.HIGHLIGHTER -> (stroke.alpha * 0.38f).coerceIn(0.10f, 0.55f)
            else -> stroke.alpha.coerceIn(0.10f, 1.0f)
        }
        val baseColorInt = Color(stroke.colorArgb).copy(alpha = effectiveAlpha).toArgb()
        val baseWidth = (stroke.strokeWidth * strokeScale).coerceAtLeast(1.5f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = baseColorInt
            strokeWidth = baseWidth
            strokeCap = if (stroke.tool == DrawingTool.HIGHLIGHTER || stroke.tool == DrawingTool.UNDERLINE_MARKER) {
                Paint.Cap.SQUARE
            } else {
                Paint.Cap.ROUND
            }
            strokeJoin = Paint.Join.ROUND
            if (stroke.tool == DrawingTool.TECHNICAL_PEN) {
                pathEffect = DashPathEffect(floatArrayOf(12f * strokeScale, 8f * strokeScale), 0f)
            }
            if (stroke.tool == DrawingTool.AREA_ERASER) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                color = AndroidColor.TRANSPARENT
            }
        }

        if (stroke.tool.isShapeTool && pts.size >= 2) {
            val startX = pts.first().x * scaleX
            val startY = pts.first().y * scaleY
            val endX = pts.last().x * scaleX
            val endY = pts.last().y * scaleY
            when (stroke.tool) {
                DrawingTool.SHAPE_LINE -> {
                    canvas.drawLine(startX, startY, endX, endY, paint)
                }
                DrawingTool.SHAPE_ARROW -> {
                    canvas.drawLine(startX, startY, endX, endY, paint)
                    val angle = atan2(endY - startY, endX - startX)
                    val headLen = (baseWidth * 4.2f).coerceIn(14f, 44f)
                    val wingAngle = Math.toRadians(28.0).toFloat()
                    val x1 = endX - headLen * cos(angle - wingAngle)
                    val y1 = endY - headLen * sin(angle - wingAngle)
                    val x2 = endX - headLen * cos(angle + wingAngle)
                    val y2 = endY - headLen * sin(angle + wingAngle)
                    canvas.drawLine(endX, endY, x1, y1, paint)
                    canvas.drawLine(endX, endY, x2, y2, paint)
                }
                DrawingTool.SHAPE_RECTANGLE -> {
                    canvas.drawRect(
                        min(startX, endX),
                        min(startY, endY),
                        max(startX, endX),
                        max(startY, endY),
                        paint
                    )
                }
                DrawingTool.SHAPE_ELLIPSE -> {
                    canvas.drawOval(
                        RectF(
                            min(startX, endX),
                            min(startY, endY),
                            max(startX, endX),
                            max(startY, endY)
                        ),
                        paint
                    )
                }
                DrawingTool.SHAPE_TRIANGLE -> {
                    val left = min(startX, endX)
                    val right = max(startX, endX)
                    val top = min(startY, endY)
                    val bottom = max(startY, endY)
                    val path = AndroidPath().apply {
                        moveTo((left + right) * 0.5f, top)
                        lineTo(right, bottom)
                        lineTo(left, bottom)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
                else -> {}
            }
            return
        }

        if (stroke.tool == DrawingTool.UNDERLINE_MARKER && pts.size >= 2) {
            val startX = pts.first().x * scaleX
            val startY = pts.first().y * scaleY
            val endX = pts.last().x * scaleX
            canvas.drawLine(startX, startY, endX, startY, paint)
            return
        }

        if (pts.size == 1) {
            val pt = pts.first()
            val pFactor = if (stroke.pressureSensitive) {
                (0.35f + 0.95f * pt.pressure.coerceIn(0.05f, 1.5f)).coerceIn(0.35f, 1.75f)
            } else 1.0f
            val dotPaint = Paint(paint).apply {
                style = Paint.Style.FILL
            }
            canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, (baseWidth * pFactor) * 0.5f, dotPaint)
            return
        }

        val hasVariablePressure = stroke.pressureSensitive &&
            (stroke.tool == DrawingTool.FOUNTAIN_PEN || stroke.tool == DrawingTool.TECHNICAL_PEN) &&
            pts.any { kotlin.math.abs(it.pressure - 1.0f) > 0.03f }

        if (hasVariablePressure) {
            var smoothedP = pts.first().pressure.coerceIn(0.05f, 1.5f)
            var prevX = pts.first().x * scaleX
            var prevY = pts.first().y * scaleY
            for (i in 1 until pts.size) {
                val prev = pts[i - 1]
                val curr = pts[i]
                smoothedP = smoothedP * 0.6f + curr.pressure.coerceIn(0.05f, 1.5f) * 0.4f
                val widthFactor = (0.35f + 0.95f * smoothedP).coerceIn(0.35f, 1.75f)
                paint.strokeWidth = (baseWidth * widthFactor).coerceAtLeast(1.0f)
                val midX = ((prev.x + curr.x) * 0.5f) * scaleX
                val midY = ((prev.y + curr.y) * 0.5f) * scaleY
                val segPath = AndroidPath().apply {
                    moveTo(prevX, prevY)
                    quadTo(prev.x * scaleX, prev.y * scaleY, midX, midY)
                }
                canvas.drawPath(segPath, paint)
                prevX = midX
                prevY = midY
            }
            val last = pts.last()
            canvas.drawLine(prevX, prevY, last.x * scaleX, last.y * scaleY, paint)
        } else {
            val path = AndroidPath().apply {
                moveTo(pts.first().x * scaleX, pts.first().y * scaleY)
                for (i in 1 until pts.size) {
                    val prev = pts[i - 1]
                    val curr = pts[i]
                    val midX = ((prev.x + curr.x) * 0.5f) * scaleX
                    val midY = ((prev.y + curr.y) * 0.5f) * scaleY
                    quadTo(prev.x * scaleX, prev.y * scaleY, midX, midY)
                }
                val last = pts.last()
                lineTo(last.x * scaleX, last.y * scaleY)
            }
            canvas.drawPath(path, paint)
        }
    }
}

@Composable
fun rememberQuestionAnnotatorState(): QuestionAnnotatorState {
    val context = LocalContext.current
    return remember {
        QuestionAnnotatorState().also { state ->
            state.drawingController.bindPreferencesContext(context)
        }
    }
}

/**
 * Interactive Question Image Annotator with pressure-sensitive pen overlay and
 * a movable/collapsible floating pen bar (Red, Blue, Green, Yellow Highlighter, Eraser, Undo/Redo, Clear).
 */
@Composable
fun QuestionImageAnnotator(
    imageUrl: String,
    annotatorState: QuestionAnnotatorState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val surfaceColor = colorScheme.surface
    val onSurfaceColor = colorScheme.onSurface
    val primaryColor = colorScheme.primary
    val controller = annotatorState.drawingController

    var isToolbarCollapsed by remember { mutableStateOf(false) }
    var showPressureSettings by remember { mutableStateOf(false) }
    var toolbarOffset by remember { mutableStateOf(Offset(12f, 12f)) }

    // Load question bitmap via Coil so we can both display it and composite annotations onto it
    LaunchedEffect(imageUrl) {
        if (imageUrl.isBlank()) {
            annotatorState.sourceBitmap = null
            annotatorState.isLoadingImage = false
            annotatorState.imageLoadError = null
            return@LaunchedEffect
        }
        annotatorState.isLoadingImage = true
        annotatorState.imageLoadError = null
        val loadedBmp = withContext(Dispatchers.IO) {
            runCatching {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    (result.drawable as? BitmapDrawable)?.bitmap
                } else {
                    null
                }
            }.getOrNull()
        }
        annotatorState.sourceBitmap = loadedBmp
        annotatorState.isLoadingImage = false
        if (loadedBmp == null) {
            annotatorState.imageLoadError = "Soru görseli yüklenemedi; beyaz çözüm alanı üzerinde çizim yapabilirsiniz."
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, onSurfaceColor.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
    ) {
        val viewportWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    annotatorState.canvasWidthPx = size.width
                    annotatorState.canvasHeightPx = size.height
                }
        ) {
            val bmp = annotatorState.sourceBitmap
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Soru Görseli",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (annotatorState.isLoadingImage) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Soru görseli yükleniyor...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475569)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = annotatorState.imageLoadError
                            ?: "Çözümünüzü bu alan üzerine kalemle çizebilirsiniz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Interactive Pressure-Sensitive Drawing Canvas Overlay
            DrawingCanvasOverlay(
                controller = controller,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Movable & Collapsible Floating Question Pen Toolbar
        val maxDragX = (viewportWidthPx - 160f).coerceAtLeast(0f)
        val maxDragY = (viewportHeightPx - 72f).coerceAtLeast(0f)
        val clampedX = toolbarOffset.x.coerceIn(0f, maxDragX)
        val clampedY = toolbarOffset.y.coerceIn(0f, maxDragY)

        if (isToolbarCollapsed) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = surfaceColor.copy(alpha = 0.95f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.2.dp, primaryColor.copy(alpha = 0.6f)),
                modifier = Modifier
                    .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                    .pointerInput(viewportWidthPx, viewportHeightPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            toolbarOffset = Offset(
                                x = (toolbarOffset.x + dragAmount.x).coerceIn(0f, maxDragX),
                                y = (toolbarOffset.y + dragAmount.y).coerceIn(0f, maxDragY)
                            )
                        }
                    }
                    .clickable { isToolbarCollapsed = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(controller.penColor)
                    )
                    Text(
                        text = "Kalem Araçları",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surfaceColor.copy(alpha = 0.96f),
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, onSurfaceColor.copy(alpha = 0.14f)),
                modifier = Modifier
                    .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                    .padding(end = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Drag Handle
                        Text(
                            text = "⋮⋮",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor.copy(alpha = 0.55f),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .pointerInput(viewportWidthPx, viewportHeightPx) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        toolbarOffset = Offset(
                                            x = (toolbarOffset.x + dragAmount.x).coerceIn(0f, maxDragX),
                                            y = (toolbarOffset.y + dragAmount.y).coerceIn(0f, maxDragY)
                                        )
                                    }
                                }
                        )

                        // Fountain Pen
                        val isPenSelected = controller.activeTool == DrawingTool.FOUNTAIN_PEN
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPenSelected) primaryColor.copy(alpha = 0.18f) else Color.Transparent,
                            border = if (isPenSelected) BorderStroke(1.dp, primaryColor) else null,
                            modifier = Modifier.clickable {
                                controller.selectTool(DrawingTool.FOUNTAIN_PEN)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Kalem",
                                    tint = if (isPenSelected) primaryColor else onSurfaceColor,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Kalem",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPenSelected) primaryColor else onSurfaceColor
                                )
                            }
                        }

                        // Yellow Highlighter
                        val isHighlighterSelected = controller.activeTool == DrawingTool.HIGHLIGHTER
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isHighlighterSelected) Color(0xFFF59E0B).copy(alpha = 0.22f) else Color.Transparent,
                            border = if (isHighlighterSelected) BorderStroke(1.dp, Color(0xFFF59E0B)) else null,
                            modifier = Modifier.clickable {
                                controller.selectTool(DrawingTool.HIGHLIGHTER)
                                controller.updatePenColor(Color(0xFFFFEB3B))
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BorderColor,
                                    contentDescription = "Fosforlu",
                                    tint = if (isHighlighterSelected) Color(0xFFD97706) else onSurfaceColor,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Fosforlu",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isHighlighterSelected) Color(0xFFD97706) else onSurfaceColor
                                )
                            }
                        }

                        // Stroke Eraser
                        val isEraserSelected = controller.activeTool.isEraserTool
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isEraserSelected) colorScheme.error.copy(alpha = 0.16f) else Color.Transparent,
                            border = if (isEraserSelected) BorderStroke(1.dp, colorScheme.error) else null,
                            modifier = Modifier.clickable {
                                controller.selectTool(DrawingTool.STROKE_ERASER)
                            }
                        ) {
                            Text(
                                text = "Silgi",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isEraserSelected) colorScheme.error else onSurfaceColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Color Swatches (Red, Blue, Green, Yellow, Purple, Black)
                        QUESTION_ANNOTATOR_COLORS.forEach { swatch ->
                            val isSelected = controller.penColor == swatch && !controller.activeTool.isEraserTool
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 24.dp else 20.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        if (controller.activeTool == DrawingTool.NONE || controller.activeTool.isEraserTool) {
                                            controller.selectTool(DrawingTool.FOUNTAIN_PEN)
                                        }
                                        controller.updatePenColor(swatch)
                                    }
                            )
                        }

                        // Undo / Redo / Clear
                        TextButton(
                            onClick = { controller.undo() },
                            enabled = controller.canUndo,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("↶ Geri", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        TextButton(
                            onClick = { controller.redo() },
                            enabled = controller.canRedo,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("↷ İleri", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { controller.clearAll() },
                            enabled = controller.strokes.isNotEmpty(),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Temizle",
                                tint = if (controller.strokes.isNotEmpty()) colorScheme.error else onSurfaceColor.copy(alpha = 0.35f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        // Pressure sensitivity & stroke settings button
                        IconButton(
                            onClick = { showPressureSettings = !showPressureSettings },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Basınç Hassasiyeti",
                                tint = if (showPressureSettings) primaryColor else onSurfaceColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        // Collapse to floating pill
                        IconButton(
                            onClick = { isToolbarCollapsed = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Küçült",
                                tint = onSurfaceColor.copy(alpha = 0.65f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    if (showPressureSettings) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Basınç Hassasiyeti:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = onSurfaceColor
                            )
                            Switch(
                                checked = controller.pressureSensitivityEnabled,
                                onCheckedChange = { enabled ->
                                    controller.updatePressureSensitivity(enabled, context)
                                },
                                modifier = Modifier.height(22.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primaryColor
                                )
                            )
                            Text(
                                text = if (controller.pressureSensitivityEnabled) "Aktif" else "Kapalı",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (controller.pressureSensitivityEnabled) primaryColor else onSurfaceColor.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
