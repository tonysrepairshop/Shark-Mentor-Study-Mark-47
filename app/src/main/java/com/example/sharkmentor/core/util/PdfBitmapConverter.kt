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
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
                    Bitmap.createBitmap(fallbackW, fallbackH, Bitmap.Config.ARGB_8888)
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

    private const val TAG = "PdfBitmapConverter"
    private const val MAX_RENDER_WIDTH_PX = 1080
    private const val DEFAULT_MAX_PAGES = 25
    private const val MAX_PDFBOX_PAGES = 60
    private const val MAX_PDFBOX_CHARS = 45_000
    private const val MAX_OCR_PAGES = 10
    private const val OCR_RENDER_WIDTH_PX = 1440
    private const val PDF_PAGES_DIR = "rendered_pdf_pages"
    const val PRIVATE_PDFS_DIR = "pdfs"

    @Volatile
    private var isPdfBoxInitialized = false
    private val pdfBoxInitLock = Any()

    private val CID_TAG_REGEX = Regex("""\(cid:\d+\)""")
    private val INTRA_WORD_NOISE_REGEX = Regex("""[A-Za-zçÇğĞıİöÖşŞüÜ][#$@|~`\\^][A-Za-zçÇğĞıİöÖşŞüÜ]""")
    private val WHITESPACE_SPLIT_REGEX = Regex("""\s+""")

    private const val TURKISH_SPECIAL_LETTERS = "çÇğĞıİöÖşŞüÜâÂîÎûÛ"
    private const val VOWEL_CHARS = "aeıioöuüAEIİOÖUÜâîûÂÎÛyY"
    private const val STANDARD_SYMBOLS = ".,;:!?()[]{}'\"’‘“”…-–—−/\\•▪✓→↔+=*×÷±≈%°<>@#&_~^|₺€\$²³¹′παβγθ∆∑√∞≤≥≠"

    fun ensurePdfBoxInitialized(context: Context) {
        if (isPdfBoxInitialized) return
        synchronized(pdfBoxInitLock) {
            if (!isPdfBoxInitialized) {
                runCatching {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    isPdfBoxInitialized = true
                }.onFailure { e ->
                    Log.w(TAG, "PDFBoxResourceLoader.init failed: ${e.message}")
                }
            }
        }
    }

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
                Log.d(TAG, "URI does not support persistable permission (will use private copy): ${it.message}")
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
                Log.e(TAG, "Copied PDF file is empty for $uri")
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
            Log.e(TAG, "Failed copying PDF URI to private storage: $uri", e)
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
            Log.w(TAG, "Direct openFileDescriptor failed, falling back to temp file copy: ${e.message}")
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
            Log.e(TAG, "Failed to open PDF stream for $pdfUri", e)
            null to null
        }
    }

    private fun resolveReadablePdfFile(context: Context, pdfUri: Uri): Pair<File?, Boolean> {
        if (pdfUri.scheme == ContentResolver.SCHEME_FILE || pdfUri.scheme == null) {
            val direct = File(pdfUri.path ?: pdfUri.toString())
            if (direct.exists() && direct.length() > 0L) {
                return direct to false
            }
        }

        return runCatching {
            val tempFile = File.createTempFile("pdf_extract_", ".pdf", context.cacheDir)
            var wroteBytes = false
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 8 * 1024)
                    output.flush()
                    wroteBytes = tempFile.length() > 0L
                }
            } ?: run {
                context.contentResolver.openFileDescriptor(pdfUri, "r")?.use { pfd ->
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output, bufferSize = 8 * 1024)
                            output.flush()
                            wroteBytes = tempFile.length() > 0L
                        }
                    }
                }
            }
            if (wroteBytes) {
                tempFile to true
            } else {
                runCatching { tempFile.delete() }
                null to false
            }
        }.getOrElse { e ->
            Log.w(TAG, "Could not resolve PDF file for $pdfUri: ${e.message}")
            null to false
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
                        Log.e(TAG, "Error rendering PDF page $pageIndex", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed converting PDF to page paths", e)
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
                        Log.e(TAG, "Failed rendering PDF page $pageIndex into Bitmap", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed converting PDF to Bitmaps", e)
        } finally {
            runCatching { pdfRenderer?.close() }
            runCatching { fileDescriptor?.close() }
            runCatching { tempPdfFile?.delete() }
        }

        return bitmaps
    }

    /**
     * Checks whether extracted PDF text is clean, human-readable Turkish/Latin prose rather than
     * broken `/ToUnicode` CMap glyph tables, PUA/replacement mojibake, `(cid:N)` tokens, or symbol noise.
     */
    fun isExtractedTextReadable(rawText: String, sanitizedText: String = rawText): Boolean {
        val raw = rawText.trim()
        val clean = sanitizedText.trim()
        if (raw.isEmpty() || clean.isEmpty()) return false

        // 1. Detect unmapped CID glyphs produced when /ToUnicode CMap is missing or corrupt
        val cidCount = CID_TAG_REGEX.findAll(raw).count()
        if (cidCount >= 2) {
            val rawNonWs = raw.count { !it.isWhitespace() }.coerceAtLeast(1)
            val withoutCid = CID_TAG_REGEX.replace(raw, "").trim()
            if (withoutCid.count { !it.isWhitespace() } < 12) return false
            if ((cidCount * 7).toFloat() / rawNonWs.toFloat() > 0.06f) return false
        }

        // 2. Check raw stream corruption ratio (PUA, replacement U+FFFD, control chars) before sanitization
        val rawNonWs = raw.count { !it.isWhitespace() }.coerceAtLeast(1)
        val rawCorruptCount = raw.count { ch ->
            ch == '\uFFFD' ||
                ch in '\uE000'..'\uF8FF' ||
                (ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t')
        }
        if (rawCorruptCount.toFloat() / rawNonWs.toFloat() > 0.12f) {
            return false
        }

        // 3. Analyze sanitized text character distribution
        val cleanNonWs = clean.count { !it.isWhitespace() }
        if (cleanNonWs < 3) return false

        var allowedLetters = 0
        var digits = 0
        var allowedPunct = 0
        var unknownChars = 0

        for (ch in clean) {
            if (ch.isWhitespace()) continue
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || TURKISH_SPECIAL_LETTERS.indexOf(ch) >= 0 -> allowedLetters++
                ch in '0'..'9' -> digits++
                STANDARD_SYMBOLS.indexOf(ch) >= 0 -> allowedPunct++
                else -> unknownChars++
            }
        }

        if (allowedLetters < 2) return false
        if (unknownChars.toFloat() / cleanNonWs.toFloat() > 0.12f) return false
        if ((allowedLetters + digits).toFloat() / cleanNonWs.toFloat() < 0.45f) return false

        // 4. Word-level noise and Turkish/Latin vowel presence check
        val tokens = clean.split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }
        if (tokens.size >= 4) {
            val noisyWordCount = tokens.count { INTRA_WORD_NOISE_REGEX.containsMatchIn(it) }
            if (noisyWordCount >= 2 && noisyWordCount.toFloat() / tokens.size.toFloat() >= 0.12f) {
                return false
            }
        }

        val regularWords = mutableListOf<String>()
        for (token in tokens) {
            val lettersOnly = buildString {
                for (ch in token) {
                    if (ch in 'A'..'Z' || ch in 'a'..'z' || TURKISH_SPECIAL_LETTERS.indexOf(ch) >= 0) {
                        append(ch)
                    }
                }
            }
            if (lettersOnly.length >= 3) {
                // Skip short ALL-CAPS acronyms like YKS, AYT, TYT, PDF, DNA
                val isShortAcronym = lettersOnly.length <= 5 && lettersOnly.all { it.isUpperCase() }
                if (!isShortAcronym) {
                    regularWords.add(lettersOnly)
                }
            }
        }

        if (regularWords.size >= 4) {
            val wordsWithVowels = regularWords.count { word ->
                word.any { ch -> VOWEL_CHARS.indexOf(ch) >= 0 }
            }
            if (wordsWithVowels.toFloat() / regularWords.size.toFloat() < 0.55f) {
                return false
            }
        }

        return true
    }

    /**
     * Extracts clean UTF-8 Turkish text from a PDF:
     * 1. Initializes PDFBox and extracts text page-by-page using [PDFTextStripper].
     * 2. Validates readability ([isExtractedTextReadable]); if empty or corrupted (e.g. broken `/ToUnicode` CMap),
     *    falls back to rendering up to the first 10 pages via [PdfRenderer] and running offline ML Kit OCR.
     * Never returns binary or garbled glyph dumps.
     */
    fun extractPdfPlainText(context: Context, pdfUri: Uri, maxLines: Int = 500): String {
        ensurePdfBoxInitialized(context)

        val (targetFile, deleteAfter) = resolveReadablePdfFile(context, pdfUri)
        if (targetFile == null) return ""

        try {
            // 1. Primary pass: PDFBox page-by-page extraction
            val pdfBoxResult = extractWithPdfBoxFile(targetFile, maxLines)
            if (pdfBoxResult.isNotBlank()) {
                return pdfBoxResult
            }

            // 2. Fallback pass: Render up to first 10 pages with PdfRenderer and run offline ML Kit OCR
            Log.i(TAG, "PDFBox text was empty or unreadable for ${targetFile.name}, running ML Kit OCR fallback.")
            val ocrRaw = extractPdfTextWithMlKitOcr(targetFile, maxPages = MAX_OCR_PAGES)
            val ocrClean = DocumentTextExtractor.sanitizeExtractedText(ocrRaw)
            return if (isExtractedTextReadable(ocrClean, ocrClean)) {
                limitLines(ocrClean, maxLines)
            } else {
                ""
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) System.gc()
            Log.e(TAG, "extractPdfPlainText failed for $pdfUri", t)
            return ""
        } finally {
            if (deleteAfter) {
                runCatching { targetFile.delete() }
            }
        }
    }

    /**
     * Fast, OOM-safe PDFBox text-layer extraction for ONLY the requested 0-based page range.
     * Strictly NEVER runs offline ML Kit OCR — if the PDF is scanned or has broken CMap fonts,
     * returns empty string immediately so Vision can read the rendered page cleanly.
     */
    fun extractPdfPageRangeTextFast(
        context: Context,
        pdfFile: File,
        startPageZeroBased: Int,
        endPageZeroBased: Int,
        maxChars: Int = 4_500
    ): String {
        if (!pdfFile.exists() || pdfFile.length() == 0L) return ""
        ensurePdfBoxInitialized(context)
        return runCatching {
            val rawBuilder = StringBuilder()
            PDDocument.load(pdfFile).use { document ->
                val pageTotal = document.numberOfPages
                if (pageTotal <= 0) return@use
                val start1 = (startPageZeroBased + 1).coerceIn(1, pageTotal)
                val end1 = (endPageZeroBased + 1).coerceIn(start1, pageTotal)
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                for (pageNum in start1..end1) {
                    if (rawBuilder.length >= maxChars) break
                    runCatching {
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum
                        val pageText = stripper.getText(document)
                        if (!pageText.isNullOrBlank()) {
                            if (rawBuilder.isNotEmpty()) rawBuilder.append("\n\n")
                            rawBuilder.append("[Sayfa $pageNum]\n")
                            rawBuilder.append(pageText.trim())
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "Fast PDFBox range failed on page $pageNum: ${e.message}")
                    }
                }
            }
            val rawText = rawBuilder.toString().take(maxChars)
            val cleaned = DocumentTextExtractor.sanitizeExtractedText(rawText)
            if (isExtractedTextReadable(rawText, cleaned)) {
                cleaned.take(maxChars)
            } else {
                ""
            }
        }.getOrElse { t ->
            if (t is OutOfMemoryError) System.gc()
            Log.w(TAG, "extractPdfPageRangeTextFast failed for ${pdfFile.name}: ${t.message}")
            ""
        }
    }

    /**
     * Fast PDFBox text-layer-only extraction across up to [maxPages] pages (capped at [maxChars]).
     * Strictly NEVER triggers ML Kit OCR so scanned PDFs return immediately without hanging/OOM.
     */
    fun extractPdfTextOnlyFast(
        context: Context,
        pdfFile: File,
        maxPages: Int = 25,
        maxChars: Int = 10_000
    ): String {
        if (!pdfFile.exists() || pdfFile.length() == 0L) return ""
        return extractPdfPageRangeTextFast(
            context = context,
            pdfFile = pdfFile,
            startPageZeroBased = 0,
            endPageZeroBased = (maxPages - 1).coerceAtLeast(0),
            maxChars = maxChars
        )
    }

    fun extractPdfPlainTextFromBytes(pdfBytes: ByteArray, maxLines: Int = 500): String {
        if (pdfBytes.isEmpty()) return ""
        return runCatching {
            val rawBuilder = StringBuilder()
            PDDocument.load(ByteArrayInputStream(pdfBytes)).use { document ->
                val pageLimit = minOf(document.numberOfPages, MAX_PDFBOX_PAGES)
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                for (pageNum in 1..pageLimit) {
                    if (rawBuilder.length >= MAX_PDFBOX_CHARS) break
                    runCatching {
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum
                        val pageText = stripper.getText(document)
                        if (!pageText.isNullOrBlank()) {
                            if (rawBuilder.isNotEmpty()) rawBuilder.append("\n\n")
                            rawBuilder.append(pageText.trim())
                        }
                    }
                }
            }
            val rawText = rawBuilder.toString()
            val cleaned = DocumentTextExtractor.sanitizeExtractedText(rawText)
            if (isExtractedTextReadable(rawText, cleaned)) {
                limitLines(cleaned, maxLines)
            } else {
                ""
            }
        }.getOrElse { t ->
            if (t is OutOfMemoryError) System.gc()
            ""
        }
    }

    private fun extractWithPdfBoxFile(pdfFile: File, maxLines: Int): String {
        return runCatching {
            val rawBuilder = StringBuilder()
            PDDocument.load(pdfFile).use { document ->
                val pageLimit = minOf(document.numberOfPages, MAX_PDFBOX_PAGES)
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                for (pageNum in 1..pageLimit) {
                    if (rawBuilder.length >= MAX_PDFBOX_CHARS) break
                    runCatching {
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum
                        val pageText = stripper.getText(document)
                        if (!pageText.isNullOrBlank()) {
                            if (rawBuilder.isNotEmpty()) rawBuilder.append("\n\n")
                            rawBuilder.append(pageText.trim())
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "PDFBox failed on page $pageNum: ${e.message}")
                    }
                }
            }
            val rawText = rawBuilder.toString()
            val cleaned = DocumentTextExtractor.sanitizeExtractedText(rawText)
            if (isExtractedTextReadable(rawText, cleaned)) {
                limitLines(cleaned, maxLines)
            } else {
                ""
            }
        }.getOrElse { t ->
            if (t is OutOfMemoryError) System.gc()
            Log.w(TAG, "PDDocument.load failed for ${pdfFile.name}: ${t.message}")
            ""
        }
    }

    private fun extractPdfTextWithMlKitOcr(pdfFile: File, maxPages: Int = MAX_OCR_PAGES): String {
        if (!pdfFile.exists() || pdfFile.length() == 0L) return ""

        // Ensure Tasks.await is never called on the UI main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val executor = Executors.newSingleThreadExecutor()
            return try {
                executor.submit<String> {
                    performMlKitOcrSync(pdfFile, maxPages)
                }.get(45L, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                Log.w(TAG, "Executor ML Kit OCR failed: ${t.message}")
                ""
            } finally {
                executor.shutdownNow()
            }
        }

        return performMlKitOcrSync(pdfFile, maxPages)
    }

    private fun performMlKitOcrSync(pdfFile: File, maxPages: Int): String {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val ocrBuilder = StringBuilder()

        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pageLimit = minOf(renderer.pageCount, maxPages)

            for (pageIndex in 0 until pageLimit) {
                var page: PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                try {
                    page = renderer.openPage(pageIndex)
                    val srcW = page.width.coerceAtLeast(1)
                    val srcH = page.height.coerceAtLeast(1)
                    val targetW = minOf((srcW * 2).coerceAtLeast(1080), OCR_RENDER_WIDTH_PX)
                    val targetH = ((srcH.toFloat() / srcW.toFloat()) * targetW.toFloat()).toInt().coerceAtLeast(1)

                    bitmap = try {
                        Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    } catch (oom: OutOfMemoryError) {
                        System.gc()
                        val reducedW = 720
                        val reducedH = ((srcH.toFloat() / srcW.toFloat()) * reducedW.toFloat()).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(reducedW, reducedH, Bitmap.Config.ARGB_8888)
                    }

                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val visionText = Tasks.await(recognizer.process(inputImage), 15L, TimeUnit.SECONDS)
                    val recognized = visionText?.text?.trim().orEmpty()
                    if (recognized.isNotBlank()) {
                        if (ocrBuilder.isNotEmpty()) ocrBuilder.append("\n\n")
                        ocrBuilder.append(recognized)
                    }
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError) System.gc()
                    Log.w(TAG, "ML Kit OCR skipped page $pageIndex: ${t.message}")
                } finally {
                    runCatching { bitmap?.recycle() }
                    runCatching { page?.close() }
                }
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) System.gc()
            Log.e(TAG, "ML Kit OCR pipeline error", t)
        } finally {
            runCatching { recognizer.close() }
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }

        return ocrBuilder.toString().trim()
    }

    private fun limitLines(text: String, maxLines: Int): String {
        if (maxLines <= 0 || text.isBlank()) return ""
        val lines = text.lineSequence().toList()
        return if (lines.size <= maxLines) {
            text.trim()
        } else {
            lines.take(maxLines).joinToString("\n").trim()
        }
    }
}

