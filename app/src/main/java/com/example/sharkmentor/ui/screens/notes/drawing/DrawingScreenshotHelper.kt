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
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.Window
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
            val (exportBitmap, shouldRecycleCopy) = prepareWritableBitmap(bitmap)
            try {
                val dir = File(appContext.filesDir, NOTE_CROPS_DIR).apply {
                    if (!exists()) mkdirs()
                }
                val imageFile = File(dir, "crop_${UUID.randomUUID()}.png")
                FileOutputStream(imageFile).use { out ->
                    if (!exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        error("Görsel dosyaya yazılamadı")
                    }
                    out.flush()
                }

                val now = System.currentTimeMillis()
                val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now))
                val note = NoteEntity(
                    id = UUID.randomUUID().toString(),
                    title = "$noteTitlePrefix ($timestamp)",
                    content = "[PDF_PAGE:${imageFile.absolutePath}]\n\nKement ile kırpılan ekran görüntüsü (${exportBitmap.width}×${exportBitmap.height} px)",
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
                if (shouldRecycleCopy && !exportBitmap.isRecycled) {
                    exportBitmap.recycle()
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
