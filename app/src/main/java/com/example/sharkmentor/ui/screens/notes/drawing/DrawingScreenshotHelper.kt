package com.example.sharkmentor.ui.screens.notes.drawing

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.Window
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.NoteEntity
import com.example.sharkmentor.data.repository.NoteRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class CapturedCropResult(
    val rectBitmap: Bitmap,
    val polygonBitmap: Bitmap?
)

data class ProjectedIntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Helper utilities for freeform Lasso region screenshots via direct bitmap rendering or
 * [LayoutCoordinates]-projected [PixelCopy], Note saving ([NoteRepositoryImpl]),
 * Gallery export ([MediaStore]), and sharing ([FileProvider]).
 */
object DrawingScreenshotHelper {

    private const val NOTE_CROPS_DIR = "lasso_crops"
    private const val SHARED_CROPS_DIR = "shared_crops"

    tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    /**
     * Pure JVM-testable window coordinate intersection for Lasso PixelCopy.
     * Takes the already-projected window bounds of the lasso (`localToWindow` of all corners),
     * intersects them with the visible clipped bounds of the canvas (`boundsInWindow`) and
     * the DecorView window rectangle `[0..windowWidth, 0..windowHeight]`.
     *
     * Unlike the old `canvasWindowBounds.top + localBounds.top` formula, this remains 100% accurate
     * when the canvas is scrolled above/below the viewport or transformed with zoom/pan.
     */
    fun projectLocalBoundsToWindow(
        projectedLeft: Float,
        projectedTop: Float,
        projectedRight: Float,
        projectedBottom: Float,
        visibleClipLeft: Float,
        visibleClipTop: Float,
        visibleClipRight: Float,
        visibleClipBottom: Float,
        windowWidth: Int,
        windowHeight: Int
    ): ProjectedIntRect? {
        if (windowWidth <= 0 || windowHeight <= 0) return null
        if (visibleClipRight - visibleClipLeft <= 1f || visibleClipBottom - visibleClipTop <= 1f) return null

        val pMinX = min(projectedLeft, projectedRight)
        val pMaxX = max(projectedLeft, projectedRight)
        val pMinY = min(projectedTop, projectedBottom)
        val pMaxY = max(projectedTop, projectedBottom)

        val clipMinX = max(0f, visibleClipLeft)
        val clipMinY = max(0f, visibleClipTop)
        val clipMaxX = min(windowWidth.toFloat(), visibleClipRight)
        val clipMaxY = min(windowHeight.toFloat(), visibleClipBottom)
        if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) return null

        val left = floor(max(pMinX, clipMinX)).toInt().coerceIn(0, windowWidth - 1)
        val top = floor(max(pMinY, clipMinY)).toInt().coerceIn(0, windowHeight - 1)
        val right = ceil(min(pMaxX, clipMaxX)).toInt().coerceIn(0, windowWidth)
        val bottom = ceil(min(pMaxY, clipMaxY)).toInt().coerceIn(0, windowHeight)

        if (right - left < 4 || bottom - top < 4) return null
        return ProjectedIntRect(left, top, right, bottom)
    }

    /**
     * Pure JVM-testable mapping from local Compose canvas coordinates (`[0..canvasWidth, 0..canvasHeight]`)
     * directly to pixel coordinates on an underlying backing bitmap (e.g., PDF rendered page bitmap).
     */
    fun mapLocalBoundsToBitmapRect(
        localLeft: Float,
        localTop: Float,
        localRight: Float,
        localBottom: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): ProjectedIntRect? {
        if (canvasWidth <= 1f || canvasHeight <= 1f || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val scaleX = bitmapWidth.toFloat() / canvasWidth
        val scaleY = bitmapHeight.toFloat() / canvasHeight

        val minX = min(localLeft, localRight).coerceIn(0f, canvasWidth)
        val maxX = max(localLeft, localRight).coerceIn(0f, canvasWidth)
        val minY = min(localTop, localBottom).coerceIn(0f, canvasHeight)
        val maxY = max(localTop, localBottom).coerceIn(0f, canvasHeight)

        val left = floor(minX * scaleX).toInt().coerceIn(0, bitmapWidth - 1)
        val top = floor(minY * scaleY).toInt().coerceIn(0, bitmapHeight - 1)
        val right = ceil(maxX * scaleX).toInt().coerceIn(0, bitmapWidth)
        val bottom = ceil(maxY * scaleY).toInt().coerceIn(0, bitmapHeight)

        if (right - left < 4 || bottom - top < 4) return null
        return ProjectedIntRect(left, top, right, bottom)
    }

    /**
     * Draws a list of [DrawingStroke]s directly onto an Android [Canvas], mapping local canvas
     * coordinates into target bitmap/PDF coordinates via [scaleX], [scaleY], and optional
     * [translateLocalX]/[translateLocalY] origin offset.
     */
    fun drawStrokesOnAndroidCanvas(
        canvas: Canvas,
        strokes: List<DrawingStroke>,
        scaleX: Float,
        scaleY: Float,
        translateLocalX: Float = 0f,
        translateLocalY: Float = 0f,
        pressureSensitivityEnabled: Boolean = true
    ) {
        if (strokes.isEmpty() || scaleX <= 0f || scaleY <= 0f) return
        val saveCount = canvas.save()
        try {
            canvas.scale(scaleX, scaleY)
            if (translateLocalX != 0f || translateLocalY != 0f) {
                canvas.translate(-translateLocalX, -translateLocalY)
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isDither = true
            }
            val path = Path()

            for (stroke in strokes) {
                val pts = stroke.points
                if (pts.isEmpty()) continue

                val effAlpha = if (stroke.tool == DrawingTool.PENCIL) {
                    (stroke.alpha * 0.78f).coerceIn(0.15f, 0.92f)
                } else {
                    stroke.alpha.coerceIn(0.05f, 1.0f)
                }
                val alphaByte = (effAlpha * 255f).roundToInt().coerceIn(0, 255)
                paint.color = (stroke.colorArgb and 0x00FFFFFF) or (alphaByte shl 24)
                val baseWidth = stroke.strokeWidth.coerceAtLeast(1f)
                paint.strokeWidth = baseWidth
                paint.strokeCap = when (stroke.tool) {
                    DrawingTool.HIGHLIGHTER -> Paint.Cap.SQUARE
                    else -> Paint.Cap.ROUND
                }
                paint.strokeJoin = when (stroke.tool) {
                    DrawingTool.TECHNICAL_PEN -> Paint.Join.MITER
                    else -> Paint.Join.ROUND
                }

                if (stroke.shapeKind != ShapeKind.NONE && pts.size >= 2) {
                    paint.style = Paint.Style.STROKE
                    val start = pts.first()
                    val end = pts.last()
                    val left = min(start.x, end.x)
                    val right = max(start.x, end.x)
                    val top = min(start.y, end.y)
                    val bottom = max(start.y, end.y)

                    when (stroke.shapeKind) {
                        ShapeKind.LINE -> {
                            canvas.drawLine(start.x, start.y, end.x, end.y, paint)
                        }
                        ShapeKind.ARROW -> {
                            canvas.drawLine(start.x, start.y, end.x, end.y, paint)
                            val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                            val headLength = (baseWidth * 4.2f).coerceIn(18f, 48f)
                            val wingAngle = PI / 6.0
                            val w1x = (end.x - headLength * cos(angle - wingAngle)).toFloat()
                            val w1y = (end.y - headLength * sin(angle - wingAngle)).toFloat()
                            val w2x = (end.x - headLength * cos(angle + wingAngle)).toFloat()
                            val w2y = (end.y - headLength * sin(angle + wingAngle)).toFloat()
                            path.reset()
                            path.moveTo(w1x, w1y)
                            path.lineTo(end.x, end.y)
                            path.lineTo(w2x, w2y)
                            canvas.drawPath(path, paint)
                        }
                        ShapeKind.RECTANGLE -> {
                            canvas.drawRect(left, top, right, bottom, paint)
                        }
                        ShapeKind.ELLIPSE -> {
                            canvas.drawOval(RectF(left, top, right, bottom), paint)
                        }
                        ShapeKind.TRIANGLE -> {
                            path.reset()
                            if (pts.size >= 3) {
                                path.moveTo(pts[0].x, pts[0].y)
                                path.lineTo(pts[1].x, pts[1].y)
                                path.lineTo(pts[2].x, pts[2].y)
                            } else {
                                path.moveTo((left + right) * 0.5f, top)
                                path.lineTo(right, bottom)
                                path.lineTo(left, bottom)
                            }
                            path.close()
                            canvas.drawPath(path, paint)
                        }
                        ShapeKind.NONE -> Unit
                    }
                    continue
                }

                if (pts.size == 1) {
                    paint.style = Paint.Style.FILL
                    val p0 = pts[0]
                    canvas.drawCircle(p0.x, p0.y, (baseWidth * 0.5f).coerceAtLeast(0.6f), paint)
                    continue
                }

                paint.style = Paint.Style.STROKE
                val usePressure = pressureSensitivityEnabled && stroke.pressureSensitive &&
                    (stroke.tool == DrawingTool.FOUNTAIN_PEN ||
                        stroke.tool == DrawingTool.TECHNICAL_PEN ||
                        stroke.tool == DrawingTool.BRUSH_PEN ||
                        stroke.tool == DrawingTool.PENCIL ||
                        stroke.tool == DrawingTool.BALLPOINT_PEN)

                if (usePressure) {
                    var prevAnchorX = pts[0].x
                    var prevAnchorY = pts[0].y
                    for (i in 1 until pts.size) {
                        val prev = pts[i - 1]
                        val curr = pts[i]
                        val midX = if (i == pts.lastIndex) curr.x else (prev.x + curr.x) * 0.5f
                        val midY = if (i == pts.lastIndex) curr.y else (prev.y + curr.y) * 0.5f
                        val avgP = ((prev.pressure + curr.pressure) * 0.5f).coerceIn(0.04f, 1.95f)
                        val pFactor = when (stroke.tool) {
                            DrawingTool.BRUSH_PEN -> (0.14f + 2.05f * avgP).coerceIn(0.14f, 2.85f)
                            DrawingTool.BALLPOINT_PEN -> (0.84f + 0.26f * avgP).coerceIn(0.75f, 1.32f)
                            DrawingTool.PENCIL -> (0.55f + 0.72f * avgP).coerceIn(0.45f, 1.65f)
                            else -> (0.16f + 1.50f * avgP).coerceIn(0.16f, 2.40f)
                        }
                        paint.strokeWidth = (baseWidth * pFactor).coerceAtLeast(0.8f)
                        path.reset()
                        path.moveTo(prevAnchorX, prevAnchorY)
                        path.quadTo(prev.x, prev.y, midX, midY)
                        canvas.drawPath(path, paint)
                        prevAnchorX = midX
                        prevAnchorY = midY
                    }
                } else {
                    path.reset()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val prev = pts[i - 1]
                        val curr = pts[i]
                        val midX = (prev.x + curr.x) * 0.5f
                        val midY = (prev.y + curr.y) * 0.5f
                        path.quadTo(prev.x, prev.y, midX, midY)
                    }
                    path.lineTo(pts.last().x, pts.last().y)
                    canvas.drawPath(path, paint)
                }
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /**
     * Captures the exact Lasso region directly from a backing content [sourceBitmap] (such as a rendered
     * PDF page) plus the page's committed [strokes], bypassing screen-space [PixelCopy] completely.
     * Guarantees 0px drift regardless of scroll offset, zoom scale, pan, or overlapping toolbars,
     * and preserves full native bitmap resolution for crisp AI Vision reading.
     */
    suspend fun captureDirectBitmapRegion(
        sourceBitmap: Bitmap,
        strokes: List<DrawingStroke>,
        canvasWidth: Float,
        canvasHeight: Float,
        localBounds: DrawingBounds,
        lassoPolygon: List<DrawingPoint>,
        pressureSensitivityEnabled: Boolean = true
    ): CapturedCropResult? = withContext(Dispatchers.Default) {
        if (sourceBitmap.isRecycled || canvasWidth <= 1f || canvasHeight <= 1f) return@withContext null

        val srcProj = mapLocalBoundsToBitmapRect(
            localLeft = localBounds.left,
            localTop = localBounds.top,
            localRight = localBounds.right,
            localBottom = localBounds.bottom,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            bitmapWidth = sourceBitmap.width,
            bitmapHeight = sourceBitmap.height
        ) ?: return@withContext null

        val (writableSource, shouldRecycleWritable) = runCatching {
            prepareWritableBitmap(sourceBitmap)
        }.getOrElse { return@withContext null }

        try {
            val cropW = srcProj.width
            val cropH = srcProj.height
            val rectBitmap = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rectBitmap)
            canvas.drawColor(Color.WHITE)

            val srcRect = Rect(srcProj.left, srcProj.top, srcProj.right, srcProj.bottom)
            val dstRect = Rect(0, 0, cropW, cropH)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(writableSource, srcRect, dstRect, bmpPaint)

            val scaleX = writableSource.width.toFloat() / canvasWidth
            val scaleY = writableSource.height.toFloat() / canvasHeight
            val originLocalX = srcProj.left.toFloat() / scaleX
            val originLocalY = srcProj.top.toFloat() / scaleY

            if (strokes.isNotEmpty()) {
                val visibleStrokes = strokes.filter { it.boundingBox().intersects(localBounds.inflate(32f)) }
                drawStrokesOnAndroidCanvas(
                    canvas = canvas,
                    strokes = visibleStrokes,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    translateLocalX = originLocalX,
                    translateLocalY = originLocalY,
                    pressureSensitivityEnabled = pressureSensitivityEnabled
                )
            }

            val polygonBitmap = if (lassoPolygon.size >= 3) {
                val mappedPolygon = lassoPolygon.map { pt ->
                    DrawingPoint(
                        x = (pt.x - originLocalX) * scaleX,
                        y = (pt.y - originLocalY) * scaleY,
                        pressure = pt.pressure
                    )
                }
                createPolygonMaskedBitmap(rectBitmap, mappedPolygon, 0f, 0f)
            } else {
                null
            }

            CapturedCropResult(
                rectBitmap = rectBitmap,
                polygonBitmap = polygonBitmap
            )
        } catch (_: Throwable) {
            null
        } finally {
            if (shouldRecycleWritable && !writableSource.isRecycled) {
                writableSource.recycle()
            }
        }
    }

    /**
     * Captures the exact on-screen pixels inside [localBounds] from [window] using live
     * [LayoutCoordinates.localToWindow] projection + hardware-accelerated [PixelCopy.request].
     * Accurately handles scrolled canvases whose top lies off-screen (negative window Y) as well as
     * zoom/pan transforms.
     */
    suspend fun captureLassoRegion(
        window: Window,
        canvasCoordinates: LayoutCoordinates,
        localBounds: DrawingBounds,
        lassoPolygon: List<DrawingPoint>
    ): CapturedCropResult? {
        if (!canvasCoordinates.isAttached) return null
        val decorView = window.decorView
        val decorW = decorView.width
        val decorH = decorView.height
        if (decorW <= 0 || decorH <= 0) return null

        val tl = canvasCoordinates.localToWindow(Offset(localBounds.left, localBounds.top))
        val tr = canvasCoordinates.localToWindow(Offset(localBounds.right, localBounds.top))
        val br = canvasCoordinates.localToWindow(Offset(localBounds.right, localBounds.bottom))
        val bl = canvasCoordinates.localToWindow(Offset(localBounds.left, localBounds.bottom))

        val projLeft = min(min(tl.x, tr.x), min(br.x, bl.x))
        val projRight = max(max(tl.x, tr.x), max(br.x, bl.x))
        val projTop = min(min(tl.y, tr.y), min(br.y, bl.y))
        val projBottom = max(max(tl.y, tr.y), max(br.y, bl.y))

        val visibleBounds = canvasCoordinates.boundsInWindow()
        val projected = projectLocalBoundsToWindow(
            projectedLeft = projLeft,
            projectedTop = projTop,
            projectedRight = projRight,
            projectedBottom = projBottom,
            visibleClipLeft = visibleBounds.left,
            visibleClipTop = visibleBounds.top,
            visibleClipRight = visibleBounds.right,
            visibleClipBottom = visibleBounds.bottom,
            windowWidth = decorW,
            windowHeight = decorH
        ) ?: return null

        val srcRect = Rect(projected.left, projected.top, projected.right, projected.bottom)
        val rawBitmap = Bitmap.createBitmap(projected.width, projected.height, Bitmap.Config.ARGB_8888)

        val copySuccess = suspendCancellableCoroutine<Boolean> { cont ->
            val handler = Handler(Looper.getMainLooper())
            try {
                PixelCopy.request(
                    window,
                    srcRect,
                    rawBitmap,
                    { copyResult ->
                        if (cont.isActive) {
                            cont.resume(copyResult == PixelCopy.SUCCESS)
                        }
                    },
                    handler
                )
            } catch (_: Throwable) {
                if (cont.isActive) {
                    cont.resume(false)
                }
            }
        }

        if (!copySuccess) {
            rawBitmap.recycle()
            return null
        }

        val polygonBitmap = if (lassoPolygon.size >= 3) {
            val projectedPolygon = lassoPolygon.map { pt ->
                val winPt = canvasCoordinates.localToWindow(Offset(pt.x, pt.y))
                DrawingPoint(
                    x = winPt.x - projected.left.toFloat(),
                    y = winPt.y - projected.top.toFloat(),
                    pressure = pt.pressure
                )
            }
            withContext(Dispatchers.Default) {
                createPolygonMaskedBitmap(rawBitmap, projectedPolygon, 0f, 0f)
            }
        } else {
            null
        }

        return CapturedCropResult(
            rectBitmap = rawBitmap,
            polygonBitmap = polygonBitmap
        )
    }

    private fun createPolygonMaskedBitmap(
        source: Bitmap,
        polygon: List<DrawingPoint>,
        originX: Float,
        originY: Float
    ): Bitmap? {
        if (polygon.size < 3) return null
        return runCatching {
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val path = Path().apply {
                moveTo(polygon[0].x - originX, polygon[0].y - originY)
                for (i in 1 until polygon.size) {
                    lineTo(polygon[i].x - originX, polygon[i].y - originY)
                }
                close()
            }
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            canvas.drawPath(path, maskPaint)

            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(source, 0f, 0f, bitmapPaint)
            out
        }.getOrNull()
    }

    private fun prepareWritableBitmap(bitmap: Bitmap): Pair<Bitmap, Boolean> {
        if (bitmap.isRecycled) {
            error("Görsel artık bellekte değil")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Donanım bitmap'i dönüştürülemedi")
            copy to true
        } else {
            bitmap to false
        }
    }

    /**
     * Saves the cropped bitmap to internal app storage as a compact JPEG (<= 1440px, 82% quality)
     * and inserts a new [NoteEntity] via [NoteRepositoryImpl] using `[PDF_PAGE:...]` so Notes render it natively.
     * Avoids multi-megabyte uncompressed PNG bloat on limited-storage devices.
     */
    suspend fun saveCropToNotes(
        context: Context,
        bitmap: Bitmap,
        noteTitlePrefix: String = "Kement Kırpımı"
    ): Result<NoteEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val (writableBitmap, shouldRecycleWritable) = prepareWritableBitmap(bitmap)
            var finalBitmap: Bitmap? = null
            try {
                val maxSide = maxOf(writableBitmap.width, writableBitmap.height)
                val targetW: Int
                val targetH: Int
                if (maxSide > 1440) {
                    val ratio = 1440f / maxSide.toFloat()
                    targetW = (writableBitmap.width * ratio).toInt().coerceAtLeast(1)
                    targetH = (writableBitmap.height * ratio).toInt().coerceAtLeast(1)
                } else {
                    targetW = writableBitmap.width.coerceAtLeast(1)
                    targetH = writableBitmap.height.coerceAtLeast(1)
                }

                // Composite onto white so freeform polygon crops have a clean background in JPEG
                val composited = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                finalBitmap = composited
                val canvas = Canvas(composited)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val destRect = Rect(0, 0, targetW, targetH)
                canvas.drawBitmap(writableBitmap, null, destRect, paint)

                val dir = File(appContext.filesDir, NOTE_CROPS_DIR).apply {
                    if (!exists()) mkdirs()
                }
                val imageFile = File(dir, "crop_${UUID.randomUUID()}.jpg")
                FileOutputStream(imageFile).use { out ->
                    if (!composited.compress(Bitmap.CompressFormat.JPEG, 82, out)) {
                        error("Görsel dosyaya yazılamadı")
                    }
                    out.flush()
                }

                val now = System.currentTimeMillis()
                val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now))
                val note = NoteEntity(
                    id = UUID.randomUUID().toString(),
                    title = "$noteTitlePrefix ($timestamp)",
                    content = "[PDF_PAGE:${imageFile.absolutePath}]\n\nKement ile kırpılan görsel (${composited.width}×${composited.height} px)",
                    colorHex = "#F8FAFC",
                    isPinned = false,
                    createdAt = now,
                    updatedAt = now
                )
                val db = SharkMentorDatabase.getDatabase(appContext)
                val repo = NoteRepositoryImpl(db.noteDao(), appContext)
                repo.insertOrUpdateNote(note)
                note
            } finally {
                if (finalBitmap != null && !finalBitmap.isRecycled) {
                    finalBitmap.recycle()
                }
                if (shouldRecycleWritable && !writableBitmap.isRecycled) {
                    writableBitmap.recycle()
                }
            }
        }
    }

    /**
     * Exports the cropped bitmap as PNG to the device Gallery across Android 9-16 (API 28-36).
     */
    @Suppress("DEPRECATION")
    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            val fileName = "SharkMentor_Kirpim_${System.currentTimeMillis()}.png"
            val (exportBitmap, shouldRecycleCopy) = prepareWritableBitmap(bitmap)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val primaryCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val baseTitle = fileName.substringBeforeLast('.')

                    fun tryInsert(collection: Uri, relativePath: String): Uri? {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.TITLE, baseTitle)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        return runCatching { resolver.insert(collection, values) }.getOrNull()
                    }

                    val uri = tryInsert(primaryCollection, "${Environment.DIRECTORY_PICTURES}/SharkMentor")
                        ?: tryInsert(primaryCollection, Environment.DIRECTORY_PICTURES)
                        ?: tryInsert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES)
                        ?: error("Galeri kaydı oluşturulamadı")

                    try {
                        val outStream = resolver.openOutputStream(uri, "w")
                            ?: resolver.openOutputStream(uri)
                            ?: error("Galeri yazma akışı açılamadı")
                        outStream.use { out ->
                            if (!exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                                error("PNG sıkıştırma başarısız oldu")
                            }
                            out.flush()
                        }

                        val doneValues = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        resolver.update(uri, doneValues, null, null)
                        uri
                    } catch (t: Throwable) {
                        runCatching { resolver.delete(uri, null, null) }
                        throw t
                    }
                } else {
                    val hasWritePermission = ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasWritePermission) {
                        error("Depolama izni verilmedi")
                    }

                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val targetDir = File(picturesDir, "SharkMentor").apply {
                        if (!exists() && !mkdirs()) {
                            // Fallback directly into Pictures if subfolder creation fails
                            picturesDir.mkdirs()
                        }
                    }
                    val actualDir = if (targetDir.exists() && targetDir.canWrite()) targetDir else picturesDir
                    val outFile = File(actualDir, fileName)
                    FileOutputStream(outFile).use { out ->
                        if (!exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            error("PNG sıkıştırma başarısız oldu")
                        }
                        out.flush()
                    }

                    val legacyValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.TITLE, fileName.substringBeforeLast('.'))
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.DATA, outFile.absolutePath)
                        put(MediaStore.Images.Media.SIZE, outFile.length())
                    }
                    val insertedUri = runCatching {
                        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, legacyValues)
                    }.getOrNull()

                    MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(outFile.absolutePath),
                        arrayOf("image/png"),
                        null
                    )
                    insertedUri ?: Uri.fromFile(outFile)
                }
            } finally {
                if (shouldRecycleCopy && !exportBitmap.isRecycled) {
                    exportBitmap.recycle()
                }
            }
        }
    }

    /**
     * Writes the cropped bitmap into cache and shares it via [FileProvider] (`${applicationId}.fileprovider`).
     */
    suspend fun shareBitmap(
        context: Context,
        bitmap: Bitmap
    ): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val uri = withContext(Dispatchers.IO) {
            val (exportBitmap, shouldRecycleCopy) = prepareWritableBitmap(bitmap)
            try {
                val cacheDir = File(appContext.cacheDir, SHARED_CROPS_DIR).apply {
                    if (!exists()) mkdirs()
                    listFiles()?.forEach { oldFile ->
                        if (System.currentTimeMillis() - oldFile.lastModified() > 60L * 60L * 1000L) {
                            oldFile.delete()
                        }
                    }
                }
                val file = File(cacheDir, "sharkmentor_crop_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    if (!exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        error("Paylaşım görseli oluşturulamadı")
                    }
                    out.flush()
                }
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file
                )
            } finally {
                if (shouldRecycleCopy && !exportBitmap.isRecycled) {
                    exportBitmap.recycle()
                }
            }
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Kırpılan Görseli Paylaş").apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }
}
