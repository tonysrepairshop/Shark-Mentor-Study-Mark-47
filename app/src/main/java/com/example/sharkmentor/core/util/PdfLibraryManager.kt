package com.example.sharkmentor.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingScreenshotHelper
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingSerializer
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingStroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Immutable
data class ImportedPdfDocument(
    val id: String,
    val title: String,
    val displayName: String,
    val localFilePath: String,
    val thumbnailPath: String? = null,
    val pageCount: Int = 0,
    val lastReadPage: Int = 0,
    val annotatedPageCount: Int = 0,
    val fileSizeBytes: Long = 0L,
    val isPinned: Boolean = false,
    val importedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis()
)

/**
 * Private imported PDF library backed by atomic JSON index (`files/pdfs/pdf_library_index.json`).
 * Keeps original user PDFs untouched on device storage while persisting app-private copies,
 * page-1 cover thumbnails, reading progress, and per-page vector annotations durably.
 */
object PdfLibraryManager {

    private const val TAG = "PdfLibraryManager"
    private const val INDEX_FILE_NAME = "pdf_library_index.json"
    private const val THUMBS_DIR = "rendered_pdf_pages"
    private const val SHARED_PDFS_DIR = "shared_pdfs"

    private val lock = Any()

    @Volatile
    private var isInitialized = false

    private val _documentsFlow = MutableStateFlow<List<ImportedPdfDocument>>(emptyList())
    val documentsFlow: StateFlow<List<ImportedPdfDocument>> = _documentsFlow.asStateFlow()

    private fun getPdfsDir(context: Context): File =
        File(context.applicationContext.filesDir, PdfBitmapConverter.PRIVATE_PDFS_DIR).apply {
            if (!exists()) mkdirs()
        }

    private fun getIndexFile(context: Context): File =
        File(getPdfsDir(context), INDEX_FILE_NAME)

    private fun sortDocuments(list: List<ImportedPdfDocument>): List<ImportedPdfDocument> =
        list.sortedWith(
            compareByDescending<ImportedPdfDocument> { it.isPinned }
                .thenByDescending { it.lastOpenedAt }
                .thenByDescending { it.importedAt }
        )

    fun init(context: Context) {
        synchronized(lock) {
            reloadLocked(context.applicationContext)
            isInitialized = true
        }
    }

    fun ensureLoaded(context: Context) {
        if (!isInitialized) {
            init(context)
        }
    }

    fun refresh(context: Context) {
        synchronized(lock) {
            reloadLocked(context.applicationContext)
            isInitialized = true
        }
    }

    private fun reloadLocked(appContext: Context) {
        val pdfsDir = getPdfsDir(appContext)
        val indexFile = getIndexFile(appContext)
        val parsed = mutableListOf<ImportedPdfDocument>()
        var dirty = false

        if (indexFile.exists()) {
            runCatching {
                val root = JSONObject(indexFile.readText(Charsets.UTF_8))
                val arr = root.optJSONArray("documents") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val path = obj.optString("localFilePath").trim()
                    if (path.isBlank()) {
                        dirty = true
                        continue
                    }
                    val file = File(path)
                    if (!file.exists() || file.length() <= 0L) {
                        dirty = true
                        continue
                    }
                    val thumb = obj.optString("thumbnailPath").takeIf {
                        it.isNotBlank() && File(it).exists() && File(it).length() > 0L
                    }
                    val pageCount = obj.optInt("pageCount", 0).let {
                        if (it > 0) it else PdfBitmapConverter.readPageCountSafely(file)
                    }
                    val liveLastPage = DrawingSerializer.loadPdfLastReadPage(appContext, path)
                    val liveAnnotated = DrawingSerializer.getPdfAnnotatedPageCount(appContext, path)
                    val recordedLastPage = obj.optInt("lastReadPage", 0).coerceAtLeast(0)
                    val effectiveLastPage = maxOf(liveLastPage, recordedLastPage)
                        .coerceIn(0, (pageCount - 1).coerceAtLeast(0))

                    parsed.add(
                        ImportedPdfDocument(
                            id = obj.optString("id").ifBlank { DrawingSerializer.computePdfDocKey(path) },
                            title = obj.optString("title").ifBlank { inferCleanTitleFromFile(file) },
                            displayName = obj.optString("displayName").ifBlank { file.name },
                            localFilePath = path,
                            thumbnailPath = thumb,
                            pageCount = pageCount,
                            lastReadPage = effectiveLastPage,
                            annotatedPageCount = liveAnnotated,
                            fileSizeBytes = file.length(),
                            isPinned = obj.optBoolean("isPinned", false),
                            importedAt = obj.optLong("importedAt", file.lastModified()),
                            lastOpenedAt = obj.optLong("lastOpenedAt", file.lastModified())
                        )
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed reading PDF library index: ${e.message}")
            }
        }

        // Auto-discover any existing PDFs already in files/pdfs/ (e.g. opened before library tab existed)
        val knownPaths = parsed.map { it.localFilePath }.toHashSet()
        pdfsDir.listFiles()?.forEach { candidate ->
            if (candidate.isFile &&
                candidate.name.endsWith(".pdf", ignoreCase = true) &&
                !candidate.name.startsWith(".") &&
                candidate.length() > 0L &&
                candidate.absolutePath !in knownPaths
            ) {
                val path = candidate.absolutePath
                val pageCount = PdfBitmapConverter.readPageCountSafely(candidate)
                val thumbPath = findOrGenerateThumbnail(appContext, candidate, generateIfMissing = true)
                parsed.add(
                    ImportedPdfDocument(
                        id = DrawingSerializer.computePdfDocKey(path),
                        title = inferCleanTitleFromFile(candidate),
                        displayName = candidate.name,
                        localFilePath = path,
                        thumbnailPath = thumbPath,
                        pageCount = pageCount,
                        lastReadPage = DrawingSerializer.loadPdfLastReadPage(appContext, path)
                            .coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                        annotatedPageCount = DrawingSerializer.getPdfAnnotatedPageCount(appContext, path),
                        fileSizeBytes = candidate.length(),
                        isPinned = false,
                        importedAt = candidate.lastModified(),
                        lastOpenedAt = candidate.lastModified()
                    )
                )
                knownPaths.add(path)
                dirty = true
            }
        }

        val sorted = sortDocuments(parsed)
        if (dirty) {
            persistLocked(appContext, sorted)
        }
        _documentsFlow.value = sorted
    }

    /**
     * Imports a PDF from a user-selected [uri] into app-private storage (deduplicated by content hash)
     * and registers it in the `PDF'lerim` library so original files stay untouched and annotations persist.
     */
    fun importPdfFromUri(context: Context, uri: Uri): ImportedPdfDocument? {
        val appContext = context.applicationContext
        val copied = PdfBitmapConverter.copyUriToPrivatePdfsDir(appContext, uri) ?: return null
        return registerOrUpdatePdf(appContext, copied)
    }

    /**
     * Registers or updates an already-local [ImportedPdfFile] in the `PDF'lerim` library.
     */
    fun registerOrUpdatePdf(
        context: Context,
        imported: ImportedPdfFile,
        titleOverride: String? = null,
        generateThumbnail: Boolean = true
    ): ImportedPdfDocument {
        val appContext = context.applicationContext
        val file = imported.localFile
        val path = file.absolutePath
        val now = System.currentTimeMillis()
        val computedPageCount = if (imported.pageCount > 0) {
            imported.pageCount
        } else {
            PdfBitmapConverter.readPageCountSafely(file)
        }
        val liveLastPage = DrawingSerializer.loadPdfLastReadPage(appContext, path)
            .coerceIn(0, (computedPageCount - 1).coerceAtLeast(0))
        val liveAnnotated = DrawingSerializer.getPdfAnnotatedPageCount(appContext, path)
        val thumbPath = findOrGenerateThumbnail(appContext, file, generateIfMissing = generateThumbnail)

        synchronized(lock) {
            if (!isInitialized) {
                reloadLocked(appContext)
                isInitialized = true
            }
            val current = _documentsFlow.value.toMutableList()
            val existingIdx = current.indexOfFirst { it.localFilePath == path }
            val resolvedTitle = titleOverride?.trim()?.takeIf { it.isNotBlank() }
                ?: imported.title.trim().takeIf { it.isNotBlank() && !it.startsWith("pdf_") }
                ?: inferCleanTitleFromFile(file)

            val doc = if (existingIdx >= 0) {
                val prev = current[existingIdx]
                prev.copy(
                    title = titleOverride?.trim()?.takeIf { it.isNotBlank() } ?: prev.title,
                    displayName = imported.displayName.ifBlank { prev.displayName },
                    thumbnailPath = thumbPath ?: prev.thumbnailPath,
                    pageCount = if (computedPageCount > 0) computedPageCount else prev.pageCount,
                    lastReadPage = liveLastPage,
                    annotatedPageCount = liveAnnotated,
                    fileSizeBytes = file.length(),
                    lastOpenedAt = now
                ).also { current[existingIdx] = it }
            } else {
                ImportedPdfDocument(
                    id = DrawingSerializer.computePdfDocKey(path),
                    title = resolvedTitle,
                    displayName = imported.displayName.ifBlank { file.name },
                    localFilePath = path,
                    thumbnailPath = thumbPath,
                    pageCount = computedPageCount,
                    lastReadPage = liveLastPage,
                    annotatedPageCount = liveAnnotated,
                    fileSizeBytes = file.length(),
                    isPinned = false,
                    importedAt = now,
                    lastOpenedAt = now
                ).also { current.add(it) }
            }

            val sorted = sortDocuments(current)
            persistLocked(appContext, sorted)
            _documentsFlow.value = sorted
            return doc
        }
    }

    /**
     * Registers a legacy `[PDF_FILE:...]` path into `PDF'lerim` if the file still exists on disk.
     */
    fun migrateLegacyPdfPath(
        context: Context,
        localPdfPath: String,
        title: String?,
        isPinned: Boolean = false
    ): ImportedPdfDocument? {
        val file = File(localPdfPath)
        if (!file.exists() || file.length() <= 0L) return null
        val appContext = context.applicationContext
        val imported = ImportedPdfFile(
            localFile = file,
            localUri = Uri.fromFile(file),
            displayName = file.name,
            title = title?.takeIf { it.isNotBlank() } ?: inferCleanTitleFromFile(file),
            pageCount = PdfBitmapConverter.readPageCountSafely(file)
        )
        val doc = registerOrUpdatePdf(appContext, imported, titleOverride = title, generateThumbnail = true)
        if (isPinned && !doc.isPinned) {
            togglePin(appContext, doc.id)
        }
        return doc
    }

    fun updateProgressAndAnnotations(
        context: Context,
        pdfFilePath: String,
        lastReadPage: Int,
        annotatedPageCount: Int
    ) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (!isInitialized) {
                reloadLocked(appContext)
                isInitialized = true
            }
            val current = _documentsFlow.value.toMutableList()
            val idx = current.indexOfFirst { it.localFilePath == pdfFilePath }
            if (idx < 0) return
            val prev = current[idx]
            val clampedPage = lastReadPage.coerceIn(0, (prev.pageCount - 1).coerceAtLeast(0))
            if (prev.lastReadPage == clampedPage && prev.annotatedPageCount == annotatedPageCount) return
            current[idx] = prev.copy(
                lastReadPage = clampedPage,
                annotatedPageCount = annotatedPageCount.coerceAtLeast(0),
                lastOpenedAt = System.currentTimeMillis()
            )
            val sorted = sortDocuments(current)
            persistLocked(appContext, sorted)
            _documentsFlow.value = sorted
        }
    }

    fun togglePin(context: Context, id: String) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val current = _documentsFlow.value.toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            current[idx] = current[idx].copy(isPinned = !current[idx].isPinned)
            val sorted = sortDocuments(current)
            persistLocked(appContext, sorted)
            _documentsFlow.value = sorted
        }
    }

    fun renameDocument(context: Context, id: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        val appContext = context.applicationContext
        synchronized(lock) {
            val current = _documentsFlow.value.toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            current[idx] = current[idx].copy(title = trimmed)
            val sorted = sortDocuments(current)
            persistLocked(appContext, sorted)
            _documentsFlow.value = sorted
        }
    }

    /**
     * Deletes the imported PDF from the app's private library, along with its thumbnail and drawings.
     * Safety guard ensures external user files outside `filesDir/pdfs/` are NEVER deleted.
     */
    fun deleteDocument(context: Context, id: String) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val current = _documentsFlow.value.toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val removed = current.removeAt(idx)
            val sorted = sortDocuments(current)
            persistLocked(appContext, sorted)
            _documentsFlow.value = sorted

            // Clean up sidecar drawings & thumbnail
            DrawingSerializer.deletePdfData(appContext, removed.localFilePath)
            removed.thumbnailPath?.let { thumbPath ->
                runCatching { File(thumbPath).delete() }
            }
            // Delete private PDF copy only if inside our private pdfsDir
            val pdfsDirCanonical = runCatching { getPdfsDir(appContext).canonicalPath }.getOrNull()
            val docFile = File(removed.localFilePath)
            val docCanonical = runCatching { docFile.canonicalPath }.getOrNull()
            if (pdfsDirCanonical != null && docCanonical != null && docCanonical.startsWith(pdfsDirCanonical)) {
                runCatching { docFile.delete() }
            }
        }
    }

    /**
     * Renders a single PDF page with all of its saved vector [strokes] baked in as a crisp [Bitmap].
     * Used by "Bu Sayfayı Not Olarak Kaydet" so notes capture actual page visuals + handwriting.
     */
    fun renderAnnotatedPageToBitmap(
        pdfFile: File,
        pageIndex: Int,
        strokes: List<DrawingStroke>,
        maxWidthPx: Int = 1440
    ): Bitmap? {
        if (!pdfFile.exists() || pdfFile.length() <= 0L) return null
        return runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return@use null
                    renderer.openPage(pageIndex).use { page ->
                        val srcW = page.width.coerceAtLeast(1)
                        val srcH = page.height.coerceAtLeast(1)
                        val targetW = minOf((srcW * 2).coerceAtLeast(960), maxWidthPx)
                        val targetH = ((srcH.toFloat() / srcW.toFloat()) * targetW.toFloat()).toInt().coerceAtLeast(1)

                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        if (strokes.isNotEmpty()) {
                            val refW = strokes.firstOrNull { it.canvasWidth > 10f }?.canvasWidth ?: targetW.toFloat()
                            val refH = strokes.firstOrNull { it.canvasHeight > 10f }?.canvasHeight ?: targetH.toFloat()
                            val scaleX = targetW.toFloat() / refW
                            val scaleY = targetH.toFloat() / refH
                            DrawingScreenshotHelper.drawStrokesOnAndroidCanvas(
                                canvas = canvas,
                                strokes = strokes,
                                scaleX = scaleX,
                                scaleY = scaleY
                            )
                        }
                        bmp
                    }
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "renderAnnotatedPageToBitmap failed: ${e.message}")
            null
        }
    }

    /**
     * Exports a shareable PDF copy into `cacheDir/shared_pdfs/` with all saved annotations baked onto
     * the annotated pages (leaving the original user PDF untouched). If the PDF has zero drawings,
     * returns [pdfFile] directly without re-rendering.
     */
    fun exportAnnotatedPdfToCache(
        context: Context,
        pdfFile: File,
        title: String
    ): File? {
        if (!pdfFile.exists() || pdfFile.length() <= 0L) return null
        val appContext = context.applicationContext
        val allAnnotations = DrawingSerializer.loadAllPdfAnnotations(appContext, pdfFile.absolutePath)
        if (allAnnotations.isEmpty()) {
            return pdfFile
        }

        return runCatching {
            val cacheDir = File(appContext.cacheDir, SHARED_PDFS_DIR).apply {
                if (!exists()) mkdirs()
                listFiles()?.forEach { old ->
                    if (System.currentTimeMillis() - old.lastModified() > 60L * 60L * 1000L) {
                        old.delete()
                    }
                }
            }
            val safeName = title.replace(Regex("[^a-zA-Z0-9çÇğĞıİöÖşŞüÜ._ -]"), "_").trim().ifBlank { "PDF" }.take(48)
            val outFile = File(cacheDir, "${safeName}_cizimli.pdf")

            val outDoc = PdfDocument()
            try {
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        for (pageIdx in 0 until renderer.pageCount) {
                            renderer.openPage(pageIdx).use { page ->
                                val ptW = page.width.coerceAtLeast(1)
                                val ptH = page.height.coerceAtLeast(1)
                                val pageInfo = PdfDocument.PageInfo.Builder(ptW, ptH, pageIdx + 1).create()
                                val outPage = outDoc.startPage(pageInfo)
                                try {
                                    // Render crisp 2x bitmap (capped at 1400px width for OOM safety)
                                    val renderW = minOf((ptW * 2).coerceAtLeast(720), 1400)
                                    val renderH = ((ptH.toFloat() / ptW.toFloat()) * renderW.toFloat()).toInt().coerceAtLeast(1)
                                    val bmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                                    try {
                                        val bmpCanvas = Canvas(bmp)
                                        bmpCanvas.drawColor(Color.WHITE)
                                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        outPage.canvas.drawBitmap(
                                            bmp,
                                            Rect(0, 0, renderW, renderH),
                                            Rect(0, 0, ptW, ptH),
                                            bmpPaint
                                        )
                                    } finally {
                                        if (!bmp.isRecycled) bmp.recycle()
                                    }

                                    // Draw crisp vector annotations directly onto the PDF page canvas
                                    val pageStrokes = allAnnotations[pageIdx].orEmpty()
                                    if (pageStrokes.isNotEmpty()) {
                                        val refW = pageStrokes.firstOrNull { it.canvasWidth > 10f }?.canvasWidth ?: ptW.toFloat()
                                        val refH = pageStrokes.firstOrNull { it.canvasHeight > 10f }?.canvasHeight ?: ptH.toFloat()
                                        DrawingScreenshotHelper.drawStrokesOnAndroidCanvas(
                                            canvas = outPage.canvas,
                                            strokes = pageStrokes,
                                            scaleX = ptW.toFloat() / refW,
                                            scaleY = ptH.toFloat() / refH
                                        )
                                    }
                                } finally {
                                    outDoc.finishPage(outPage)
                                }
                            }
                        }
                    }
                }
                FileOutputStream(outFile).use { fos ->
                    outDoc.writeTo(fos)
                    fos.flush()
                }
            } finally {
                outDoc.close()
            }
            if (outFile.exists() && outFile.length() > 0L) outFile else pdfFile
        }.getOrElse { e ->
            Log.e(TAG, "exportAnnotatedPdfToCache failed, falling back to base PDF", e)
            pdfFile
        }
    }

    private fun findOrGenerateThumbnail(
        appContext: Context,
        pdfFile: File,
        generateIfMissing: Boolean
    ): String? {
        val thumbDir = File(appContext.filesDir, THUMBS_DIR).apply {
            if (!exists()) mkdirs()
        }
        val docKey = DrawingSerializer.computePdfDocKey(pdfFile.absolutePath)
        val thumbFile = File(thumbDir, "pdf_thumb_${docKey}_p1.jpg")
        if (thumbFile.exists() && thumbFile.length() > 0L) {
            return thumbFile.absolutePath
        }
        if (!generateIfMissing) return null

        return runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return@use null
                    renderer.openPage(0).use { page ->
                        val srcW = page.width.coerceAtLeast(1)
                        val srcH = page.height.coerceAtLeast(1)
                        val targetW = 480
                        val targetH = ((srcH.toFloat() / srcW.toFloat()) * targetW.toFloat()).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        try {
                            val c = Canvas(bmp)
                            c.drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            FileOutputStream(thumbFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
                                out.flush()
                            }
                        } finally {
                            if (!bmp.isRecycled) bmp.recycle()
                        }
                    }
                }
            }
            if (thumbFile.exists() && thumbFile.length() > 0L) thumbFile.absolutePath else null
        }.getOrNull()
    }

    private fun persistLocked(appContext: Context, docs: List<ImportedPdfDocument>) {
        runCatching {
            val root = JSONObject()
            root.put("version", 1)
            root.put("updatedAt", System.currentTimeMillis())
            val arr = JSONArray()
            for (doc in docs) {
                arr.put(
                    JSONObject().apply {
                        put("id", doc.id)
                        put("title", doc.title)
                        put("displayName", doc.displayName)
                        put("localFilePath", doc.localFilePath)
                        if (doc.thumbnailPath != null) put("thumbnailPath", doc.thumbnailPath)
                        put("pageCount", doc.pageCount)
                        put("lastReadPage", doc.lastReadPage)
                        put("annotatedPageCount", doc.annotatedPageCount)
                        put("fileSizeBytes", doc.fileSizeBytes)
                        put("isPinned", doc.isPinned)
                        put("importedAt", doc.importedAt)
                        put("lastOpenedAt", doc.lastOpenedAt)
                    }
                )
            }
            root.put("documents", arr)
            val target = getIndexFile(appContext)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                runCatching { tmp.delete() }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed persisting PDF library index", e)
        }
    }

    /**
     * Strips internal `pdf_<12hex>_` prefix from private filenames to recover a clean display title.
     */
    internal fun inferCleanTitleFromFile(file: File): String {
        val base = file.name.substringBeforeLast('.', file.name)
        val stripped = base.replace(Regex("^pdf_[0-9a-fA-F]{6,16}_"), "")
            .replace('_', ' ')
            .trim()
        return stripped.ifBlank { base.ifBlank { "PDF Belgesi" } }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.0f KB", kb)
            else -> "$bytes B"
        }
    }
}
