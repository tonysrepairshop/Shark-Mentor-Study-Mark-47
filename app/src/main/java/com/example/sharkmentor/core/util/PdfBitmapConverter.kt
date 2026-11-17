package com.example.sharkmentor.core.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

data class ImportedPdfFile(
    val localFile: File,
    val localUri: Uri,
    val displayName: String,
    val title: String,
    val pageCount: Int
)

/**
 * OOM-safe, on-demand PDF page renderer backed by Android SDK [PdfRenderer] and [LruCache].
 * Renders only visible ± 1 pages and automatically evicts distant pages.
 */
class LazyPdfPageRenderer(private val pdfFile: File) {

    private val renderMutex = Mutex()
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var isClosed = false

    var pageCount: Int = 0
        private set

    // Memory-bounded LruCache (max 1/10th of heap, capped at 24 MB, and max ~5 pages around visible page)
    private val maxCacheKb: Int = ((Runtime.getRuntime().maxMemory() / 1024L) / 10L)
        .toInt()
        .coerceIn(6 * 1024, 24 * 1024)

    private val pageCache = object : LruCache<Int, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    init {
        runCatching {
            if (pdfFile.exists()) {
                val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                fileDescriptor = pfd
                val renderer = PdfRenderer(pfd)
                pdfRenderer = renderer
                pageCount = renderer.pageCount
            }
        }.onFailure { e ->
            Log.e("LazyPdfPageRenderer", "Failed to open PdfRenderer for ${pdfFile.absolutePath}", e)
        }
    }

    fun getCachedPage(pageIndex: Int): Bitmap? {
        return synchronized(pageCache) {
            pageCache.get(pageIndex)?.takeIf { !it.isRecycled }
        }
    }

    /**
     * Evicts any cached page further than [keepRadius] (default 1 => visible ±1 page)
     * so memory footprint stays tiny even on 1000+ page PDFs.
     */
    fun trimAroundVisiblePage(visiblePage: Int, keepRadius: Int = 1) {
        synchronized(pageCache) {
            val keys = pageCache.snapshot().keys.toList()
            for (k in keys) {
                if (abs(k - visiblePage) > keepRadius) {
                    pageCache.remove(k)
                }
            }
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        maxWidthPx: Int = 1200,
        visiblePageHint: Int = pageIndex
    ): Bitmap? = withContext(Dispatchers.IO) {
        getCachedPage(pageIndex)?.let { return@withContext it }

        renderMutex.withLock {
            if (isClosed) return@withLock null
            getCachedPage(pageIndex)?.let { return@withLock it }

            trimAroundVisiblePage(visiblePageHint, keepRadius = 1)

            val renderer = pdfRenderer ?: return@withLock null
            if (pageIndex !in 0 until renderer.pageCount) return@withLock null

            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(pageIndex)
                val srcW = page.width.coerceAtLeast(1)
                val srcH = page.height.coerceAtLeast(1)
                val targetW = minOf((srcW * 2).coerceAtLeast(720), maxWidthPx)
                val targetH = ((srcH.toFloat() / srcW.toFloat()) * targetW.toFloat()).toInt().coerceAtLeast(1)

                val bitmap = try {
                    Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                } catch (oom: OutOfMemoryError) {
                    Log.w("LazyPdfPageRenderer", "OOM on page $pageIndex, clearing cache and retrying at reduced size")
                    synchronized(pageCache) { pageCache.evictAll() }
                    System.gc()
                    val fallbackW = (targetW / 2).coerceAtLeast(480)
                    val fallbackH = ((srcH.toFloat() / srcW.toFloat()) * fallbackW.toFloat()).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(fallbackW, fallbackH, Bitmap.Config.RGB_565)
                }

                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                synchronized(pageCache) {
                    pageCache.put(pageIndex, bitmap)
                }
                bitmap
            } catch (t: Throwable) {
                Log.e("LazyPdfPageRenderer", "Error rendering page $pageIndex", t)
                null
            } finally {
                runCatching { page?.close() }
            }
        }
    }

    fun close() {
        isClosed = true
        synchronized(pageCache) {
            pageCache.evictAll()
        }
        runCatching { pdfRenderer?.close() }
        runCatching { fileDescriptor?.close() }
        pdfRenderer = null
        fileDescriptor = null
    }
}

object PdfBitmapConverter {

    private const val MAX_RENDER_WIDTH_PX = 1080
    private const val DEFAULT_MAX_PAGES = 25
    private const val PDF_PAGES_DIR = "rendered_pdf_pages"
    const val PRIVATE_PDFS_DIR = "pdfs"

    /**
     * Takes persistable URI read permission (when supported by the provider) and copies the
     * incoming PDF into the app's private `files/pdfs/` directory using an 8KB streaming buffer.
     * Prevents URI permission loss and OOM on large PDFs.
     */
    fun copyUriToPrivatePdfsDir(context: Context, uri: Uri): ImportedPdfFile? {
        val resolver = context.contentResolver

        // 1. Attempt persistable read permission safely
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure {
                Log.d("PdfBitmapConverter", "URI does not support persistable permission (will use private copy): ${it.message}")
            }
        }

        val displayName = resolvePdfDisplayName(resolver, uri)
        val cleanTitle = displayName.substringBeforeLast('.', displayName).ifBlank { "PDF Belgesi" }

        return try {
            val pdfsDir = File(context.filesDir, PRIVATE_PDFS_DIR).apply {
                if (!exists()) mkdirs()
            }

            // If URI already points to a file inside our private pdfsDir, reuse it directly
            if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
                val existing = File(uri.path ?: uri.toString())
                if (existing.exists() && existing.length() > 0L) {
                    val count = readPageCountSafely(existing)
                    return ImportedPdfFile(
                        localFile = existing,
                        localUri = Uri.fromFile(existing),
                        displayName = existing.name,
                        title = existing.name.substringBeforeLast('.', existing.name).ifBlank { cleanTitle },
                        pageCount = count
                    )
                }
            }

            // Compute a stable filename so reopening the same URI reuses the private copy
            val uriHash = runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(uri.toString().toByteArray(Charsets.UTF_8))
                    .take(6)
                    .joinToString("") { "%02x".format(it) }
            }.getOrDefault(uri.toString().hashCode().toUInt().toString(16))

            val safeFileName = displayName
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .take(64)
                .let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }

            val targetFile = File(pdfsDir, "pdf_${uriHash}_${safeFileName}")

            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: run {
                // Fallback if openInputStream is null: try openFileDescriptor
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output, bufferSize = 8 * 1024)
                        }
                    }
                }
            }

            if (!targetFile.exists() || targetFile.length() == 0L) {
                Log.e("PdfBitmapConverter", "Copied PDF file is empty for $uri")
                return null
            }

            val pageCount = readPageCountSafely(targetFile)
            ImportedPdfFile(
                localFile = targetFile,
                localUri = Uri.fromFile(targetFile),
                displayName = displayName,
                title = cleanTitle,
                pageCount = pageCount
            )
        } catch (e: Exception) {
            Log.e("PdfBitmapConverter", "Failed copying PDF URI to private storage: $uri", e)
            null
        }
    }

    fun readPageCountSafely(pdfFile: File): Int {
        if (!pdfFile.exists()) return 0
        return runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
        }.getOrDefault(0)
    }

    fun resolvePdfDisplayName(resolver: ContentResolver, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "belge.pdf"
    }

    private fun openSeekableDescriptor(context: Context, pdfUri: Uri): Pair<ParcelFileDescriptor?, File?> {
        try {
            if (pdfUri.scheme == "file" || pdfUri.scheme == null) {
                val f = File(pdfUri.path ?: pdfUri.toString())
                if (f.exists()) {
                    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) to null
                }
            }
            val directPfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
            if (directPfd != null) {
                return directPfd to null
            }
        } catch (e: Exception) {
            Log.w("PdfBitmapConverter", "Direct openFileDescriptor failed, falling back to temp file copy: ${e.message}")
        }

        return try {
            val tempFile = File.createTempFile("pdf_import_", ".pdf", context.cacheDir)
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd to tempFile
        } catch (e: Exception) {
            Log.e("PdfBitmapConverter", "Failed to open PDF stream for $pdfUri", e)
            null to null
        }
    }

    /**
     * Renders all pages (up to [maxPages] = 25) of a PDF onto white-background Bitmaps (max width 1080px),
     * saves each page as a compressed JPEG file in the app's files directory, and returns the full
     * `List<String>` of saved page file paths.
     */
    fun convertPdfUriToPagePaths(
        context: Context,
        pdfUri: Uri,
        maxPages: Int = DEFAULT_MAX_PAGES
    ): List<String> {
        val savedPaths = mutableListOf<String>()
        var fileDescriptor: ParcelFileDescriptor? = null
        var tempPdfFile: File? = null
        var pdfRenderer: PdfRenderer? = null

        try {
            val (pfd, tempFile) = openSeekableDescriptor(context, pdfUri)
            fileDescriptor = pfd
            tempPdfFile = tempFile

            if (fileDescriptor != null) {
                pdfRenderer = PdfRenderer(fileDescriptor)
                val pageLimit = minOf(pdfRenderer.pageCount, maxPages.coerceAtMost(DEFAULT_MAX_PAGES))
                val outputDir = File(context.filesDir, PDF_PAGES_DIR).apply {
                    if (!exists()) mkdirs()
                }
                val docBatchId = UUID.randomUUID().toString().take(8)

                for (pageIndex in 0 until pageLimit) {
                    runCatching {
                        val page = pdfRenderer.openPage(pageIndex)
                        try {
                            val srcW = page.width.coerceAtLeast(1)
                            val srcH = page.height.coerceAtLeast(1)
                            val targetWidth = minOf((srcW * 2).coerceAtLeast(720), MAX_RENDER_WIDTH_PX)
                            val targetHeight = ((srcH.toFloat() / srcW.toFloat()) * targetWidth.toFloat())
                                .toInt()
                                .coerceAtLeast(1)

                            val bitmap = Bitmap.createBitmap(
                                targetWidth,
                                targetHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)

                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )

                            val pageFile = File(outputDir, "pdf_${docBatchId}_page_${pageIndex + 1}.jpg")
                            FileOutputStream(pageFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                            }
                            bitmap.recycle()
                            savedPaths.add(pageFile.absolutePath)
                        } finally {
                            page.close()
                        }
                    }.onFailure { e ->
                        Log.e("PdfBitmapConverter", "Error rendering PDF page $pageIndex", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PdfBitmapConverter", "Failed converting PDF to page paths", e)
        } finally {
            runCatching { pdfRenderer?.close() }
            runCatching { fileDescriptor?.close() }
            runCatching { tempPdfFile?.delete() }
        }

        return savedPaths
    }

    /**
     * Converts up to [maxPages] of a PDF document into a list of Bitmaps (max width 1080px).
     * Renders onto a white background for better AI OCR performance and OOM safety.
     */
    fun convertPdfUriToBitmaps(
        context: Context,
        pdfUri: Uri,
        maxPages: Int = DEFAULT_MAX_PAGES
    ): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        var fileDescriptor: ParcelFileDescriptor? = null
        var tempPdfFile: File? = null
        var pdfRenderer: PdfRenderer? = null

        try {
            val (pfd, tempFile) = openSeekableDescriptor(context, pdfUri)
            fileDescriptor = pfd
            tempPdfFile = tempFile

            if (fileDescriptor != null) {
                pdfRenderer = PdfRenderer(fileDescriptor)
                val pageCount = minOf(pdfRenderer.pageCount, maxPages.coerceAtMost(DEFAULT_MAX_PAGES))
                for (pageIndex in 0 until pageCount) {
                    runCatching {
                        val page = pdfRenderer.openPage(pageIndex)
                        try {
                            val srcW = page.width.coerceAtLeast(1)
                            val srcH = page.height.coerceAtLeast(1)
                            val width = minOf((srcW * 2).coerceAtLeast(720), MAX_RENDER_WIDTH_PX)
                            val height = ((srcH.toFloat() / srcW.toFloat()) * width.toFloat())
                                .toInt()
                                .coerceAtLeast(1)

                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)

                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmaps.add(bitmap)
                        } finally {
                            page.close()
                        }
                    }.onFailure { e ->
                        Log.e("PdfBitmapConverter", "Failed rendering PDF page $pageIndex into Bitmap", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PdfBitmapConverter", "Failed converting PDF to Bitmaps", e)
        } finally {
            runCatching { pdfRenderer?.close() }
            runCatching { fileDescriptor?.close() }
            runCatching { tempPdfFile?.delete() }
        }

        return bitmaps
    }

    /**
     * Extracts plain text from a PDF file using stream operators and FlateDecode decompression.
     * Runs safely on any thread (best on Dispatchers.IO) with no external library dependencies.
     */
    fun extractPdfPlainText(context: Context, pdfUri: Uri, maxLines: Int = 500): String {
        return runCatching {
            val bytes = context.contentResolver.openInputStream(pdfUri)?.use { it.readBytes() } ?: return@runCatching ""
            extractPdfPlainTextFromBytes(bytes, maxLines)
        }.getOrDefault("")
    }

    fun extractPdfPlainTextFromBytes(pdfBytes: ByteArray, maxLines: Int = 500): String {
        return runCatching {
            val rawLatin = String(pdfBytes, Charsets.ISO_8859_1)
            val collectedLines = mutableListOf<String>()

            // Extract text operators from uncompressed and FlateDecode-compressed PDF streams
            val streamRegex = Regex("stream\\r?\\n([\\s\\S]*?)\\r?endstream")
            for (match in streamRegex.findAll(rawLatin)) {
                val streamContent = match.groupValues[1]
                val streamBytes = streamContent.toByteArray(Charsets.ISO_8859_1)
                val decodedStream = inflatePdfStreamIfPossible(streamBytes)
                if (decodedStream != null) {
                    extractTextFromPdfContentStream(decodedStream, collectedLines)
                }
                if (collectedLines.size >= maxLines) break
            }

            val rawResult = collectedLines.joinToString("\n\n").trim()
            DocumentTextExtractor.sanitizeExtractedText(rawResult)
        }.getOrDefault("")
    }

    private fun inflatePdfStreamIfPossible(bytes: ByteArray): String? {
        for (nowrap in listOf(false, true)) {
            val inflater = java.util.zip.Inflater(nowrap)
            try {
                inflater.setInput(bytes)
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (!inflater.finished() && total < 1_048_576) {
                    val count = inflater.inflate(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    total += count
                }
                if (output.size() > 0) {
                    val decodedBytes = output.toByteArray()
                    return try {
                        val utf8 = String(decodedBytes, Charsets.UTF_8)
                        if (utf8.contains('\uFFFD')) String(decodedBytes, Charsets.ISO_8859_1) else utf8
                    } catch (_: Exception) {
                        String(decodedBytes, Charsets.ISO_8859_1)
                    }
                }
            } catch (_: Exception) {
                // Not a zlib/deflate stream
            } finally {
                inflater.end()
            }
        }
        return null
    }

    private val BT_ET_REGEX = Regex("""BT([\s\S]*?)ET""")
    private val TJ_ARRAY_REGEX = Regex("""\[([\s\S]*?)\]\s*TJ""")
    private val TJ_SINGLE_REGEX = Regex("""\(([^()\\]*(?:\\.[^()\\]*)*)\)\s*(?:Tj|'|")""")
    private val TJ_HEX_REGEX = Regex("""<([0-9A-Fa-f]{4,})>\s*Tj""")
    private val PAREN_TOKEN_REGEX = Regex("""\(([^()\\]*(?:\\.[^()\\]*)*)\)""")
    private val VOWEL_CHECK_REGEX = Regex("""[aeıioöuüAEIİOÖUÜ]""")

    private fun extractTextFromPdfContentStream(streamText: String, out: MutableList<String>) {
        if (!streamText.contains("BT") || !streamText.contains("ET")) return

        for (block in BT_ET_REGEX.findAll(streamText)) {
            val blockBody = block.groupValues[1]
            val lineBuilder = StringBuilder()

            // 1. Process array text operators: [(Text) -10 (More)] TJ
            for (arrayMatch in TJ_ARRAY_REGEX.findAll(blockBody)) {
                val arrayContent = arrayMatch.groupValues[1]
                for (strToken in PAREN_TOKEN_REGEX.findAll(arrayContent)) {
                    val decoded = decodePdfParenString(strToken.groupValues[1])
                    if (decoded.isNotBlank()) {
                        if (lineBuilder.isNotEmpty() && !lineBuilder.endsWith(" ")) lineBuilder.append(" ")
                        lineBuilder.append(decoded)
                    }
                }
            }

            // 2. Process single string text operators: (Text) Tj
            if (lineBuilder.isEmpty()) {
                for (singleMatch in TJ_SINGLE_REGEX.findAll(blockBody)) {
                    val decoded = decodePdfParenString(singleMatch.groupValues[1])
                    if (decoded.isNotBlank()) {
                        if (lineBuilder.isNotEmpty() && !lineBuilder.endsWith(" ")) lineBuilder.append(" ")
                        lineBuilder.append(decoded)
                    }
                }
            }

            // 3. Process hex text operators: <00480065006C006C006F> Tj
            if (lineBuilder.isEmpty()) {
                for (hexMatch in TJ_HEX_REGEX.findAll(blockBody)) {
                    val hexStr = hexMatch.groupValues[1]
                    val decoded = decodePdfHexString(hexStr)
                    if (decoded.isNotBlank()) {
                        if (lineBuilder.isNotEmpty() && !lineBuilder.endsWith(" ")) lineBuilder.append(" ")
                        lineBuilder.append(decoded)
                    }
                }
            }

            val cleaned = lineBuilder.toString().trim()
            if (isValidPdfLine(cleaned)) {
                out.add(cleaned)
            }
        }
    }

    private fun decodePdfParenString(raw: String): String {
        var str = raw
            .replace("\\n", "\n")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\\\", "\\")
            .replace("\u0000", "")

        // Handle octal escape codes e.g. \040 -> space, \101 -> 'A'
        if (str.contains('\\')) {
            str = Regex("""\\([0-7]{1,3})""").replace(str) { match ->
                val oct = match.groupValues[1].toIntOrNull(8)
                if (oct != null && oct in 32..255) oct.toChar().toString() else ""
            }
        }

        return str.trim()
    }

    private fun decodePdfHexString(hex: String): String {
        if (hex.length < 4) return ""

        val sb = StringBuilder()
        // Check if UTF-16BE (length multiple of 4, alternate byte is 0x00 for ASCII or non-zero for Unicode)
        if (hex.length % 4 == 0) {
            var allValid = true
            for (i in 0 until hex.length step 4) {
                val b1 = hex.substring(i, i + 2).toIntOrNull(16) ?: 0
                val b2 = hex.substring(i + 2, i + 4).toIntOrNull(16) ?: 0
                val charCode = (b1 shl 8) or b2
                if (charCode in 32..126 || charCode in 0x00A0..0x024F ||
                    charCode in listOf(0x011E, 0x011F, 0x0130, 0x0131, 0x00D6, 0x00F6, 0x00DC, 0x00FC, 0x00C7, 0x00E7, 0x015E, 0x015F)
                ) {
                    sb.append(charCode.toChar())
                } else if (charCode == 0) {
                    // skip null
                } else {
                    allValid = false
                    break
                }
            }
            if (allValid && sb.length >= 2) return sb.toString()
        }

        // Fallback: 2 hex digits per character (only if clean readable characters)
        sb.clear()
        if (hex.length % 2 == 0) {
            for (i in 0 until hex.length step 2) {
                val b = hex.substring(i, i + 2).toIntOrNull(16) ?: continue
                if (b in 32..126) sb.append(b.toChar())
            }
        }
        val result = sb.toString().trim()
        return if (result.length >= 2 && VOWEL_CHECK_REGEX.containsMatchIn(result)) result else ""
    }

    private fun isValidPdfLine(line: String): Boolean {
        if (line.length < 2) return false
        val lower = line.lowercase()

        // Filter out Postscript operator debris and PDF structural tokens
        if (lower.startsWith("/f") && lower.contains("tf")) return false
        if (lower.startsWith("obj") || lower.startsWith("endobj") || lower.startsWith("xref") || lower.startsWith("trailer")) return false
        if (lower.startsWith("<<") || lower.startsWith(">>") || lower.startsWith("startxref") || lower.startsWith("%%eof")) return false
        if (lower.matches(Regex("""^([0-9.-]+\s+)+(cm|re|rg|sc|cs|tm)\s*$"""))) return false

        // Drop lines that are corrupted glyph symbol sequences (e.g. !#$%&'()*+,-./0123...)
        if (line.length >= 8) {
            val nonAlnum = line.count { !it.isLetterOrDigit() && !it.isWhitespace() }
            if (nonAlnum.toFloat() / line.length >= 0.40f && !line.contains("=")) return false
            if (line.length >= 12 && !VOWEL_CHECK_REGEX.containsMatchIn(line) && line.none { it.isDigit() }) return false
        }

        return true
    }
}
