package com.example.sharkmentor.ui.screens.notes.drawing

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.Window
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
import kotlin.math.roundToInt

data class CapturedCropResult(
    val rectBitmap: Bitmap,
    val polygonBitmap: Bitmap?
)

/**
 * Helper utilities for freeform Lasso region screenshots via [PixelCopy],
 * Note saving ([NoteRepositoryImpl]), Gallery export ([MediaStore]), and sharing ([FileProvider]).
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
     * Captures the exact on-screen pixels inside [localBounds] (relative to [canvasWindowBounds])
     * from [window] using hardware-accelerated [PixelCopy.request].
     * Also builds an optional freeform polygon cutout if [lassoPolygon] has >= 3 vertices.
     */
    suspend fun captureLassoRegion(
        window: Window,
        canvasWindowBounds: androidx.compose.ui.geometry.Rect,
        localBounds: DrawingBounds,
        lassoPolygon: List<DrawingPoint>
    ): CapturedCropResult? {
        val decorView = window.decorView
        val decorW = decorView.width
        val decorH = decorView.height
        if (decorW <= 0 || decorH <= 0) return null
        if (canvasWindowBounds.width <= 1f || canvasWindowBounds.height <= 1f) return null

        val minWinX = canvasWindowBounds.left.roundToInt().coerceIn(0, decorW - 1)
        val minWinY = canvasWindowBounds.top.roundToInt().coerceIn(0, decorH - 1)
        val maxWinX = canvasWindowBounds.right.roundToInt().coerceIn(minWinX + 1, decorW)
        val maxWinY = canvasWindowBounds.bottom.roundToInt().coerceIn(minWinY + 1, decorH)

        val srcLeft = (canvasWindowBounds.left + localBounds.left).roundToInt().coerceIn(minWinX, maxWinX - 1)
        val srcTop = (canvasWindowBounds.top + localBounds.top).roundToInt().coerceIn(minWinY, maxWinY - 1)
        val srcRight = (canvasWindowBounds.left + localBounds.right).roundToInt().coerceIn(srcLeft + 1, maxWinX)
        val srcBottom = (canvasWindowBounds.top + localBounds.bottom).roundToInt().coerceIn(srcTop + 1, maxWinY)

        val cropW = srcRight - srcLeft
        val cropH = srcBottom - srcTop
        if (cropW < 4 || cropH < 4) return null

        val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
        val rawBitmap = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)

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
            val originX = srcLeft.toFloat() - canvasWindowBounds.left
            val originY = srcTop.toFloat() - canvasWindowBounds.top
            withContext(Dispatchers.Default) {
                createPolygonMaskedBitmap(rawBitmap, lassoPolygon, originX, originY)
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

    /**
     * Saves the cropped bitmap to internal app storage and inserts a new [NoteEntity]
     * via [NoteRepositoryImpl] using the standard `[PDF_PAGE:...]` image tag so Notes render it natively.
     */
    suspend fun saveCropToNotes(
        context: Context,
        bitmap: Bitmap,
        noteTitlePrefix: String = "Kement Kırpımı"
    ): Result<NoteEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val dir = File(appContext.filesDir, NOTE_CROPS_DIR).apply {
                if (!exists()) mkdirs()
            }
            val imageFile = File(dir, "crop_${UUID.randomUUID()}.png")
            FileOutputStream(imageFile).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    error("Görsel dosyaya yazılamadı")
                }
            }

            val now = System.currentTimeMillis()
            val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now))
            val note = NoteEntity(
                id = UUID.randomUUID().toString(),
                title = "$noteTitlePrefix ($timestamp)",
                content = "[PDF_PAGE:${imageFile.absolutePath}]\n\nKement ile kırpılan ekran görüntüsü (${bitmap.width}×${bitmap.height} px)",
                colorHex = "#F8FAFC",
                isPinned = false,
                createdAt = now,
                updatedAt = now
            )
            val db = SharkMentorDatabase.getDatabase(appContext)
            val repo = NoteRepositoryImpl(db.noteDao(), appContext)
            repo.insertOrUpdateNote(note)
            note
        }
    }

    /**
     * Exports the cropped bitmap as PNG to the device Gallery (`Pictures/SharkMentor`) via [MediaStore].
     */
    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            val fileName = "SharkMentor_Kirpim_${System.currentTimeMillis()}.png"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SharkMentor")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Galeri kaydı oluşturulamadı")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        error("PNG sıkıştırma başarısız oldu")
                    }
                } ?: error("Galeri yazma akışı açılamadı")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, doneValues, null, null)
                }
                uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
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
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    error("Paylaşım görseli oluşturulamadı")
                }
            }
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
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
