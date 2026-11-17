package com.example.sharkmentor.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object ImageStorageManager {
    private const val FOLDER_NAME = "error_bank_images"
    private const val MAX_DIMENSION = 1024
    private const val MAX_AI_BYTES = 350 * 1024 // < 350KB

    private fun readBytesFromUri(context: Context, uri: Uri, rawPathFallback: String = uri.toString()): ByteArray? {
        return runCatching {
            when (uri.scheme) {
                "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                "file" -> {
                    val path = uri.path ?: rawPathFallback
                    File(path).takeIf { it.exists() }?.inputStream()?.use { it.readBytes() }
                }
                null -> {
                    val f = File(rawPathFallback)
                    if (f.exists()) f.inputStream().use { it.readBytes() }
                    else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }.onFailure { e ->
            Log.w("ImageStorageManager", "Failed to read bytes from URI $uri: ${e.message}")
        }.getOrNull()
    }

    /**
     * Unified zero-crash image pipeline for Gemini OCR & Multimodal AI.
     * - Pass 1: Safely reads URI stream once into memory, ensuring all streams close immediately.
     * - Pass 2: Calculate `inSampleSize` so max dimension <= 1024px, decode safely.
     * - Pass 3 (Scanner Enhancement): Apply contrast boost (`1.25f` contrast, `-15f` brightness) for crisp math/text OCR.
     * - Pass 4: Compress to JPEG 80% quality (< 350KB) and return in-memory ARGB_8888 Bitmap.
     */
    suspend fun prepareOptimizedBitmapForAi(
        context: Context,
        uri: Uri,
        enhanceContrast: Boolean = true
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val rawPath = uri.toString()
            val rawBytes = readBytesFromUri(context, uri, rawPath) ?: return@runCatching null
            if (rawBytes.isEmpty()) return@runCatching null

            // Pass 1: Read bounds from byte array
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                // Fallback direct decode
                return@runCatching BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            }

            // Pass 2: Calculate power-of-two inSampleSize targeting <= 1024px
            var sampleSize = 1
            while (
                (boundsOptions.outWidth / sampleSize) > MAX_DIMENSION ||
                (boundsOptions.outHeight / sampleSize) > MAX_DIMENSION
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return@runCatching null

            // Exact cap to 1024px if still slightly above
            val maxSide = maxOf(decoded.width, decoded.height)
            val scaled = if (maxSide > MAX_DIMENSION) {
                val ratio = MAX_DIMENSION.toFloat() / maxSide.toFloat()
                val targetW = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            // Pass 3: Document Scanner Contrast Enhancement (contrast = 1.25f, brightness = -15f)
            val processed = if (enhanceContrast) {
                applyScannerContrastFilter(scaled).also {
                    if (it !== scaled) scaled.recycle()
                }
            } else {
                scaled
            }

            // Pass 4: Compress to JPEG 80% quality
            val compressedBytes = compressToTargetJpegBytes(processed, initialQuality = 80)
            processed.recycle()

            BitmapFactory.decodeByteArray(
                compressedBytes,
                0,
                compressedBytes.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }.onFailure { e ->
            Log.e("SharkAiVision", "prepareOptimizedBitmapForAi failed for URI: $uri", e)
        }.getOrNull()
    }

    /**
     * Applies a document scanner contrast boost (`contrast = 1.25f`, `brightness = -15f`)
     * so faint pencil/ink math exponents, fractions, and geometry diagrams are crisp.
     */
    private fun applyScannerContrastFilter(source: Bitmap): Bitmap {
        return runCatching {
            val contrast = 1.25f
            val brightness = -15f
            val cm = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val output = Bitmap.createBitmap(
                source.width.coerceAtLeast(1),
                source.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(source, 0f, 0f, paint)
            output
        }.getOrDefault(source)
    }

    private fun compressToTargetJpegBytes(bitmap: Bitmap, initialQuality: Int = 75): ByteArray {
        val baos = ByteArrayOutputStream()
        var quality = initialQuality
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        while (baos.size() > MAX_AI_BYTES && quality > 45) {
            baos.reset()
            quality -= 10
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        }
        return baos.toByteArray()
    }

    suspend fun getOptimizedBitmap(context: Context, uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        val uri = runCatching {
            val f = File(uriString)
            if (f.exists()) Uri.fromFile(f) else Uri.parse(uriString)
        }.getOrNull() ?: return@withContext null

        prepareOptimizedBitmapForAi(context, uri, enhanceContrast = true)
    }

    suspend fun processAndSaveImage(context: Context, sourceUri: Uri, isQuestion: Boolean): String = withContext(Dispatchers.IO) {
        val optimized = prepareOptimizedBitmapForAi(
            context = context,
            uri = sourceUri,
            enhanceContrast = isQuestion
        ) ?: throw IllegalArgumentException("Görsel okunamadı: $sourceUri")

        val prefix = if (isQuestion) "question" else "solution"
        val fileName = "${prefix}_${UUID.randomUUID()}.jpg"

        val directory = File(context.filesDir, FOLDER_NAME)
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val file = File(directory, fileName)
        FileOutputStream(file).use { out ->
            val jpegBytes = compressToTargetJpegBytes(optimized, initialQuality = 75)
            out.write(jpegBytes)
        }
        optimized.recycle()

        return@withContext file.absolutePath
    }

    suspend fun deleteImageFile(uriString: String?) = withContext(Dispatchers.IO) {
        if (uriString.isNullOrBlank()) return@withContext
        runCatching {
            val file = File(uriString)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun createTempCameraFileUri(context: Context): Uri {
        val directory = File(context.cacheDir, "error_bank_camera")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val tempFile = File.createTempFile("camera_capture_", ".jpg", directory)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }
}
