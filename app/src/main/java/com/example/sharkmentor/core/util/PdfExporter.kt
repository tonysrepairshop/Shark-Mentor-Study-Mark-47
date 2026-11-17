package com.example.sharkmentor.core.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfExporter {

    private const val PAGE_WIDTH = 595 // Standard A4 points (72 DPI)
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 45f
    private const val MARGIN_TOP = 50f
    private const val MARGIN_BOTTOM = 50f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2)

    data class ExportResult(
        val fileUri: Uri,
        val fileName: String,
        val filePathDescription: String
    )

    fun exportNoteToPdf(
        context: Context,
        title: String,
        rawContent: String,
        dateMillis: Long = System.currentTimeMillis()
    ): Result<ExportResult> = runCatching {
        val displayTitle = title.trim().ifBlank { "Başlıksız Not" }
        val cleanContent = sanitizeContentForPdf(rawContent)
        val formattedDate = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(dateMillis))

        val document = PdfDocument()

        // Paints
        val headerCategoryPaint = Paint().apply {
            color = Color.rgb(100, 116, 139) // Slate 500
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
            letterSpacing = 0.12f
        }

        val headerDatePaint = Paint().apply {
            color = Color.rgb(148, 163, 184) // Slate 400
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val titlePaint = Paint().apply {
            color = Color.rgb(15, 23, 42) // Slate 900
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = Paint().apply {
            color = Color.rgb(30, 41, 59) // Slate 800
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }

        val checkedBodyPaint = Paint().apply {
            color = Color.rgb(148, 163, 184) // Slate 400
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isStrikeThruText = true
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = Color.rgb(226, 232, 240) // Slate 200
            strokeWidth = 0.75f
            isAntiAlias = true
        }

        val footerPaint = Paint().apply {
            color = Color.rgb(148, 163, 184)
            textSize = 8.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // Layout lines
        val lineSpacing = 16f
        val paragraphSpacing = 10f

        // Split content into clean printable lines
        val contentParagraphs = cleanContent.split("\n")
        val linesToRender = mutableListOf<PdfLine>()

        for (para in contentParagraphs) {
            val trimmedPara = para.trimEnd()
            if (trimmedPara.isEmpty()) {
                linesToRender.add(PdfLine("", isBlank = true))
                continue
            }

            var prefix = ""
            var isChecked = false
            var textToWrap = trimmedPara

            when {
                trimmedPara.startsWith("[✓] ") -> {
                    prefix = "[✓] "
                    isChecked = true
                    textToWrap = trimmedPara.removePrefix("[✓] ")
                }
                trimmedPara.startsWith("[x] ") || trimmedPara.startsWith("[X] ") -> {
                    prefix = "[✓] "
                    isChecked = true
                    textToWrap = trimmedPara.removeRange(0, 4)
                }
                trimmedPara.startsWith("[ ] ") -> {
                    prefix = "[ ] "
                    isChecked = false
                    textToWrap = trimmedPara.removePrefix("[ ] ")
                }
                trimmedPara.startsWith("☑ ") -> {
                    prefix = "[✓] "
                    isChecked = true
                    textToWrap = trimmedPara.removePrefix("☑ ")
                }
                trimmedPara.startsWith("☐ ") -> {
                    prefix = "[ ] "
                    isChecked = false
                    textToWrap = trimmedPara.removePrefix("☐ ")
                }
                trimmedPara.startsWith("• ") -> {
                    prefix = "• "
                    textToWrap = trimmedPara.removePrefix("• ")
                }
                trimmedPara.startsWith("- ") -> {
                    prefix = "• "
                    textToWrap = trimmedPara.removePrefix("- ")
                }
                Regex("^\\d+\\.\\s").containsMatchIn(trimmedPara) -> {
                    val match = Regex("^(\\d+\\.\\s)").find(trimmedPara)
                    if (match != null) {
                        prefix = match.value
                        textToWrap = trimmedPara.substring(match.range.last + 1)
                    }
                }
            }

            val prefixWidth = if (prefix.isNotEmpty()) bodyPaint.measureText(prefix) + 4f else 0f
            val maxTextWidth = CONTENT_WIDTH - prefixWidth

            val wrappedWords = wrapTextIntoLines(textToWrap, bodyPaint, maxTextWidth)
            if (wrappedWords.isEmpty()) {
                linesToRender.add(PdfLine(prefix, isChecked = isChecked))
            } else {
                for ((idx, lineStr) in wrappedWords.withIndex()) {
                    if (idx == 0) {
                        linesToRender.add(PdfLine(lineStr, prefix = prefix, isChecked = isChecked))
                    } else {
                        linesToRender.add(PdfLine(lineStr, indentWidth = prefixWidth, isChecked = isChecked))
                    }
                }
            }
        }

        // Pagination
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas

        fun drawHeaderAndTitle(c: Canvas, isFirstPage: Boolean): Float {
            var currentY = MARGIN_TOP

            // Header banner
            c.drawText("SHARKMENTOR STUDY", MARGIN_X, currentY, headerCategoryPaint)
            c.drawText(formattedDate, PAGE_WIDTH - MARGIN_X, currentY, headerDatePaint)
            currentY += 12f

            c.drawLine(MARGIN_X, currentY, PAGE_WIDTH - MARGIN_X, currentY, dividerPaint)
            currentY += 18f

            if (isFirstPage) {
                // Draw Title wrapped
                val titleLines = wrapTextIntoLines(displayTitle, titlePaint, CONTENT_WIDTH)
                for (tLine in titleLines) {
                    c.drawText(tLine, MARGIN_X, currentY + titlePaint.textSize, titlePaint)
                    currentY += titlePaint.textSize + 6f
                }
                currentY += 10f
                c.drawLine(MARGIN_X, currentY, PAGE_WIDTH - MARGIN_X, currentY, dividerPaint)
                currentY += 18f
            }

            return currentY
        }

        fun drawFooter(c: Canvas, pageNum: Int) {
            val footerY = PAGE_HEIGHT - MARGIN_BOTTOM + 20f
            c.drawLine(MARGIN_X, footerY - 14f, PAGE_WIDTH - MARGIN_X, footerY - 14f, dividerPaint)
            c.drawText("Sayfa $pageNum", (PAGE_WIDTH / 2).toFloat(), footerY, footerPaint)
        }

        var cursorY = drawHeaderAndTitle(canvas, isFirstPage = true)

        for (line in linesToRender) {
            val requiredHeight = if (line.isBlank) paragraphSpacing else lineSpacing
            if (cursorY + requiredHeight > (PAGE_HEIGHT - MARGIN_BOTTOM)) {
                // Close current page and start next
                drawFooter(canvas, pageNumber)
                document.finishPage(page)

                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas

                cursorY = drawHeaderAndTitle(canvas, isFirstPage = false)
            }

            if (line.isBlank) {
                cursorY += paragraphSpacing
                continue
            }

            val currentPaint = if (line.isChecked) checkedBodyPaint else bodyPaint
            var startX = MARGIN_X + line.indentWidth

            if (line.prefix.isNotEmpty()) {
                canvas.drawText(line.prefix, startX, cursorY + currentPaint.textSize, currentPaint)
                startX += currentPaint.measureText(line.prefix) + 4f
            }

            canvas.drawText(line.text, startX, cursorY + currentPaint.textSize, currentPaint)
            cursorY += lineSpacing
        }

        drawFooter(canvas, pageNumber)
        document.finishPage(page)

        // Save file
        val cleanFileName = displayTitle
            .replace(Regex("[^a-zA-Z0-9çÇğĞıİöÖşŞüÜ\\s_-]"), "")
            .replace("\\s+".toRegex(), "_")
            .take(40)
            .ifBlank { "Not" } + "_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.pdf"

        val exportResult = savePdfToDevice(context, document, cleanFileName)
        document.close()
        exportResult
    }

    private data class PdfLine(
        val text: String,
        val prefix: String = "",
        val indentWidth: Float = 0f,
        val isChecked: Boolean = false,
        val isBlank: Boolean = false
    )

    private fun wrapTextIntoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return emptyList()
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (word.isEmpty()) continue
            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine.append(if (currentLine.isEmpty()) word else " $word")
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    // Single word is longer than maxWidth, break by chars
                    lines.add(word)
                    currentLine = StringBuilder()
                }
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
    }

    private fun sanitizeContentForPdf(raw: String): String {
        return raw
            .replace(Regex("<color[^>]*>"), "")
            .replace("</color>", "")
            .replace(Regex("<highlight[^>]*>"), "")
            .replace("</highlight>", "")
            .replace("<b>", "")
            .replace("</b>", "")
            .replace("<i>", "")
            .replace("</i>", "")
            .replace("<u>", "")
            .replace("</u>", "")
            .replace(Regex("\\[PDF_PAGE:[^\\]]+\\]"), "")
            .replace("\r", "")
            .trim()
    }

    private fun savePdfToDevice(context: Context, document: PdfDocument, fileName: String): ExportResult {
        // 1. Write to internal/external cache first for FileProvider
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val localFile = File(cacheDir, fileName)
        FileOutputStream(localFile).use { out ->
            document.writeTo(out)
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            localFile
        )

        // 2. Also copy to system Downloads if supported so user can find it in their Files app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SharkMentor")
                }
                val downloadUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (downloadUri != null) {
                    resolver.openOutputStream(downloadUri)?.use { out ->
                        localFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                }
            }
        } else {
            runCatching {
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SharkMentor")
                if (!publicDir.exists()) publicDir.mkdirs()
                val targetFile = File(publicDir, fileName)
                localFile.copyTo(targetFile, overwrite = true)
            }
        }

        return ExportResult(
            fileUri = contentUri,
            fileName = fileName,
            filePathDescription = "İndirilenler / SharkMentor / $fileName"
        )
    }

    fun openOrSharePdf(context: Context, uri: Uri, fileName: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "PDF'i Paylaş veya Aç")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
