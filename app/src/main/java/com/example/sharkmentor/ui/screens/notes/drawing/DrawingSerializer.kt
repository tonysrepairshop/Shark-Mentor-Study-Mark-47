package com.example.sharkmentor.ui.screens.notes.drawing

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Sidecar JSON persistence for Note drawings and page-indexed PDF annotations.
 * Keeps Room schemas (NoteEntity) completely untouched while providing durable storage.
 */
object DrawingSerializer {

    private const val TAG = "DrawingSerializer"
    private const val NOTE_DRAWINGS_DIR = "note_drawings"
    private const val PDF_ANNOTATIONS_DIR = "pdf_annotations"
    private const val FORMAT_VERSION = 1

    // ── JSON Encoding / Decoding ─────────────────────────────────────────────

    fun strokesToJson(strokes: List<DrawingStroke>): String {
        return runCatching {
            val root = JSONObject()
            root.put("version", FORMAT_VERSION)
            root.put("updatedAt", System.currentTimeMillis())

            val strokesArray = JSONArray()
            for (stroke in strokes) {
                if (stroke.points.isEmpty()) continue
                val strokeObj = JSONObject().apply {
                    put("id", stroke.id)
                    put("tool", stroke.tool.name)
                    put("shapeKind", stroke.shapeKind.name)
                    put("colorArgb", stroke.colorArgb)
                    put("strokeWidth", stroke.strokeWidth.toDouble())
                    put("alpha", stroke.alpha.toDouble())
                    put("canvasWidth", stroke.canvasWidth.toDouble())
                    put("canvasHeight", stroke.canvasHeight.toDouble())
                    put("pressureSensitive", stroke.pressureSensitive)

                    val ptsArray = JSONArray()
                    for (pt in stroke.points) {
                        val ptObj = JSONObject()
                        ptObj.put("x", pt.x.toDouble())
                        ptObj.put("y", pt.y.toDouble())
                        if (kotlin.math.abs(pt.pressure - 1.0f) > 0.005f) {
                            ptObj.put("p", ((pt.pressure * 1000f).toInt() / 1000.0))
                        }
                        ptsArray.put(ptObj)
                    }
                    put("points", ptsArray)
                }
                strokesArray.put(strokeObj)
            }
            root.put("strokes", strokesArray)
            root.toString()
        }.getOrElse { e ->
            Log.e(TAG, "Failed to serialize strokes to JSON", e)
            "{\"version\":1,\"strokes\":[]}"
        }
    }

    fun strokesFromJson(jsonStr: String): List<DrawingStroke> {
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(jsonStr)
            val strokesArray = root.optJSONArray("strokes") ?: return@runCatching emptyList()
            val result = ArrayList<DrawingStroke>(strokesArray.length())

            for (i in 0 until strokesArray.length()) {
                val obj = strokesArray.optJSONObject(i) ?: continue
                val ptsArray = obj.optJSONArray("points") ?: continue
                val points = ArrayList<DrawingPoint>(ptsArray.length())
                for (pIdx in 0 until ptsArray.length()) {
                    val ptObj = ptsArray.optJSONObject(pIdx) ?: continue
                    val x = ptObj.optDouble("x", Double.NaN).toFloat()
                    val y = ptObj.optDouble("y", Double.NaN).toFloat()
                    val pressure = ptObj.optDouble("p", 1.0).toFloat().coerceIn(0.1f, 2.0f)
                    if (!x.isNaN() && !y.isNaN()) {
                        points.add(DrawingPoint(x, y, pressure))
                    }
                }
                if (points.isEmpty()) continue

                val toolName = obj.optString("tool", DrawingTool.FOUNTAIN_PEN.name)
                val tool = runCatching { DrawingTool.valueOf(toolName) }.getOrDefault(DrawingTool.FOUNTAIN_PEN)

                val shapeName = obj.optString("shapeKind", ShapeKind.NONE.name)
                val shapeKind = runCatching { ShapeKind.valueOf(shapeName) }.getOrDefault(ShapeKind.NONE)

                result.add(
                    DrawingStroke(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        tool = tool,
                        shapeKind = shapeKind,
                        colorArgb = obj.optInt("colorArgb", 0xFF1E88E5.toInt()),
                        strokeWidth = obj.optDouble("strokeWidth", 4.5).toFloat().coerceIn(0.5f, 80f),
                        alpha = obj.optDouble("alpha", 1.0).toFloat().coerceIn(0.05f, 1.0f),
                        points = points,
                        canvasWidth = obj.optDouble("canvasWidth", 1080.0).toFloat().coerceAtLeast(1f),
                        canvasHeight = obj.optDouble("canvasHeight", 1920.0).toFloat().coerceAtLeast(1f),
                        pressureSensitive = obj.optBoolean("pressureSensitive", true)
                    )
                )
            }
            result
        }.getOrElse { e ->
            Log.e(TAG, "Failed to parse strokes from JSON", e)
            emptyList()
        }
    }

    // ── Note Sidecar Storage (files/note_drawings/note_<id>.json & note_<id>_p<page>.json) ──

    private fun sanitizeNoteId(noteId: String): String =
        noteId.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "default" }

    private fun getNoteDrawingDir(context: Context): File =
        File(context.filesDir, NOTE_DRAWINGS_DIR).apply {
            if (!exists()) mkdirs()
        }

    private fun getNoteDrawingFile(context: Context, noteId: String): File {
        val dir = getNoteDrawingDir(context)
        val safeId = sanitizeNoteId(noteId)
        return File(dir, "note_${safeId}.json")
    }

    private fun getNotePageDrawingFile(context: Context, noteId: String, pageIndex: Int): File {
        if (pageIndex <= 0) return getNoteDrawingFile(context, noteId)
        val dir = getNoteDrawingDir(context)
        val safeId = sanitizeNoteId(noteId)
        return File(dir, "note_${safeId}_p${pageIndex}.json")
    }

    fun saveNoteDrawing(context: Context, noteId: String, strokes: List<DrawingStroke>) {
        saveNotePageDrawing(context, noteId, pageIndex = 0, strokes = strokes)
    }

    fun loadNoteDrawing(context: Context, noteId: String): List<DrawingStroke> {
        return loadNotePageDrawing(context, noteId, pageIndex = 0)
    }

    fun saveNotePageDrawing(
        context: Context,
        noteId: String,
        pageIndex: Int,
        strokes: List<DrawingStroke>
    ) {
        if (noteId.isBlank() || pageIndex < 0) return
        runCatching {
            val file = getNotePageDrawingFile(context, noteId, pageIndex)
            if (strokes.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            val json = strokesToJson(strokes)
            file.writeText(json, Charsets.UTF_8)
        }.onFailure { e ->
            Log.e(TAG, "Failed saving note drawing for $noteId page $pageIndex", e)
        }
    }

    fun loadNotePageDrawing(
        context: Context,
        noteId: String,
        pageIndex: Int
    ): List<DrawingStroke> {
        if (noteId.isBlank() || pageIndex < 0) return emptyList()
        return runCatching {
            val file = getNotePageDrawingFile(context, noteId, pageIndex)
            if (!file.exists()) return emptyList()
            strokesFromJson(file.readText(Charsets.UTF_8))
        }.getOrElse { e ->
            Log.e(TAG, "Failed loading note drawing for $noteId page $pageIndex", e)
            emptyList()
        }
    }

    fun deleteNotePageDrawingAndShift(
        context: Context,
        noteId: String,
        deletedPageIndex: Int,
        totalPagesBeforeDelete: Int
    ) {
        if (noteId.isBlank() || deletedPageIndex < 0 || totalPagesBeforeDelete <= 1) return
        runCatching {
            for (idx in deletedPageIndex until (totalPagesBeforeDelete - 1)) {
                val nextStrokes = loadNotePageDrawing(context, noteId, idx + 1)
                saveNotePageDrawing(context, noteId, idx, nextStrokes)
            }
            val lastFile = getNotePageDrawingFile(context, noteId, totalPagesBeforeDelete - 1)
            if (lastFile.exists()) lastFile.delete()
        }.onFailure { e ->
            Log.w(TAG, "Failed shifting note page drawings for $noteId", e)
        }
    }

    fun hasNoteDrawing(context: Context, noteId: String): Boolean {
        if (noteId.isBlank()) return false
        return runCatching {
            val dir = getNoteDrawingDir(context)
            val safeId = sanitizeNoteId(noteId)
            val primary = File(dir, "note_${safeId}.json")
            if (primary.exists() && primary.length() > 30L) return@runCatching true
            val prefix = "note_${safeId}_p"
            dir.listFiles()?.any {
                it.name.startsWith(prefix) && it.name.endsWith(".json") && it.length() > 30L
            } == true
        }.getOrDefault(false)
    }

    fun duplicateNoteDrawing(context: Context, sourceNoteId: String, targetNoteId: String) {
        if (sourceNoteId.isBlank() || targetNoteId.isBlank()) return
        runCatching {
            val dir = getNoteDrawingDir(context)
            val srcSafe = sanitizeNoteId(sourceNoteId)
            val dstSafe = sanitizeNoteId(targetNoteId)
            val srcPrimary = File(dir, "note_${srcSafe}.json")
            if (srcPrimary.exists()) {
                srcPrimary.copyTo(File(dir, "note_${dstSafe}.json"), overwrite = true)
            }
            val pagePrefix = "note_${srcSafe}_p"
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith(pagePrefix) && file.name.endsWith(".json")) {
                    val suffix = file.name.removePrefix("note_${srcSafe}")
                    file.copyTo(File(dir, "note_${dstSafe}${suffix}"), overwrite = true)
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed duplicating note drawing from $sourceNoteId to $targetNoteId", e)
        }
    }

    fun deleteNoteDrawing(context: Context, noteId: String) {
        if (noteId.isBlank()) return
        runCatching {
            val dir = getNoteDrawingDir(context)
            val safeId = sanitizeNoteId(noteId)
            val primary = File(dir, "note_${safeId}.json")
            if (primary.exists()) primary.delete()
            val pagePrefix = "note_${safeId}_p"
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith(pagePrefix) && file.name.endsWith(".json")) {
                    file.delete()
                }
            }
        }
    }

    // ── PDF Per-Page Annotation Sidecar Storage (files/pdf_annotations/) ─────

    fun computePdfDocKey(pdfIdentifier: String): String {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(pdfIdentifier.trim().toByteArray(Charsets.UTF_8))
            hash.take(12).joinToString("") { "%02x".format(it) }
        }.getOrElse {
            pdfIdentifier.hashCode().toUInt().toString(16)
        }
    }

    private fun getPdfDocFile(context: Context, pdfIdentifier: String): File {
        val dir = File(context.filesDir, PDF_ANNOTATIONS_DIR).apply {
            if (!exists()) mkdirs()
        }
        val docKey = computePdfDocKey(pdfIdentifier)
        return File(dir, "pdf_${docKey}.json")
    }

    private fun writeTextAtomically(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent ?: target.absoluteFile.parentFile, "${target.name}.tmp")
        tempFile.writeText(content, Charsets.UTF_8)
        if (!tempFile.renameTo(target)) {
            // Fallback on filesystems where rename over existing fails
            tempFile.copyTo(target, overwrite = true)
            runCatching { tempFile.delete() }
        }
    }

    /**
     * Saves annotations for a single page [pageIndex] of a PDF identified by [pdfIdentifier].
     * Stored atomically in a single structured per-document JSON file containing all annotated pages and metadata.
     */
    @Synchronized
    fun savePdfPageAnnotations(
        context: Context,
        pdfIdentifier: String,
        pageIndex: Int,
        strokes: List<DrawingStroke>
    ) {
        if (pdfIdentifier.isBlank() || pageIndex < 0) return
        runCatching {
            val file = getPdfDocFile(context, pdfIdentifier)
            val root = if (file.exists()) {
                runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())
            } else {
                JSONObject()
            }

            root.put("version", FORMAT_VERSION)
            root.put("pdfIdentifier", pdfIdentifier)
            root.put("updatedAt", System.currentTimeMillis())

            val pagesObj = root.optJSONObject("pages") ?: JSONObject()
            if (strokes.isEmpty()) {
                pagesObj.remove(pageIndex.toString())
            } else {
                val pageJson = JSONObject(strokesToJson(strokes))
                pagesObj.put(pageIndex.toString(), pageJson.optJSONArray("strokes") ?: JSONArray())
            }
            root.put("pages", pagesObj)
            writeTextAtomically(file, root.toString())
        }.onFailure { e ->
            Log.e(TAG, "Failed saving PDF annotations for page $pageIndex", e)
        }
    }

    @Synchronized
    fun loadPdfPageAnnotations(
        context: Context,
        pdfIdentifier: String,
        pageIndex: Int
    ): List<DrawingStroke> {
        if (pdfIdentifier.isBlank() || pageIndex < 0) return emptyList()
        return runCatching {
            val file = getPdfDocFile(context, pdfIdentifier)
            if (!file.exists()) return emptyList()
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val pagesObj = root.optJSONObject("pages") ?: return emptyList()
            val strokesArray = pagesObj.optJSONArray(pageIndex.toString()) ?: return emptyList()
            val wrapper = JSONObject().apply {
                put("version", FORMAT_VERSION)
                put("strokes", strokesArray)
            }
            strokesFromJson(wrapper.toString())
        }.getOrElse { e ->
            Log.e(TAG, "Failed loading PDF annotations for page $pageIndex", e)
            emptyList()
        }
    }

    @Synchronized
    fun loadAllPdfAnnotations(
        context: Context,
        pdfIdentifier: String
    ): Map<Int, List<DrawingStroke>> {
        if (pdfIdentifier.isBlank()) return emptyMap()
        return runCatching {
            val file = getPdfDocFile(context, pdfIdentifier)
            if (!file.exists()) return emptyMap()
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val pagesObj = root.optJSONObject("pages") ?: return emptyMap()
            val map = mutableMapOf<Int, List<DrawingStroke>>()
            val keys = pagesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val pageIdx = key.toIntOrNull() ?: continue
                val arr = pagesObj.optJSONArray(key) ?: continue
                val wrapper = JSONObject().apply {
                    put("version", FORMAT_VERSION)
                    put("strokes", arr)
                }
                val parsed = strokesFromJson(wrapper.toString())
                if (parsed.isNotEmpty()) {
                    map[pageIdx] = parsed
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun getPdfAnnotatedPageCount(context: Context, pdfIdentifier: String): Int {
        if (pdfIdentifier.isBlank()) return 0
        return runCatching {
            val file = getPdfDocFile(context, pdfIdentifier)
            if (!file.exists()) return 0
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val pagesObj = root.optJSONObject("pages") ?: return 0
            var count = 0
            val keys = pagesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = pagesObj.optJSONArray(key)
                if (arr != null && arr.length() > 0) {
                    count++
                }
            }
            count
        }.getOrDefault(0)
    }

    @Synchronized
    fun deletePdfData(context: Context, pdfIdentifier: String) {
        if (pdfIdentifier.isBlank()) return
        runCatching {
            val file = getPdfDocFile(context, pdfIdentifier)
            if (file.exists()) file.delete()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            if (tmp.exists()) tmp.delete()
        }
    }

    @Synchronized
    fun savePdfLastReadPage(context: Context, pdfIdentifier: String, pageIndex: Int) {
        if (pdfIdentifier.isBlank() || pageIndex < 0) return
        runCatching {
            val file = getPdfDocFile(context, pdfIdentifier)
            val root = if (file.exists()) {
                runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())
            } else {
                JSONObject()
            }
            root.put("lastReadPage", pageIndex)
            root.put("updatedAt", System.currentTimeMillis())
            writeTextAtomically(file, root.toString())
        }
    }

    @Synchronized
    fun loadPdfLastReadPage(context: Context, pdfIdentifier: String): Int {
        if (pdfIdentifier.isBlank()) return 0
        return runCatching {
            val file = getPdfDocFile(context, pdfIdentifier)
            if (!file.exists()) return 0
            val root = JSONObject(file.readText(Charsets.UTF_8))
            root.optInt("lastReadPage", 0).coerceAtLeast(0)
        }.getOrDefault(0)
    }
}
