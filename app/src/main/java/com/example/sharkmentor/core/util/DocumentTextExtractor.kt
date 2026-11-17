package com.example.sharkmentor.core.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

object DocumentTextExtractor {

    private const val TAG = "DocumentTextExtractor"

    private val P_REGEX = Regex("""<w:p\b[^>]*>([\s\S]*?)</w:p>""")
    private val TEXT_NODE_REGEX = Regex("""<(?:w|m):t\b[^>]*>([^<]*)</(?:w|m):t>""")
    private val BR_REGEX = Regex("""<w:br\b[^>]*/>""")
    private val TAB_REGEX = Regex("""<w:tab\b[^>]*/>""")
    private val INSTR_TEXT_REGEX = Regex("""<w:instrText\b[^>]*>[\s\S]*?</w:instrText>""")
    private val SYM_REGEX = Regex("""<w:sym\b[^>]*w:char=["']([0-9a-fA-F]+)["'][^>]*/>""")

    /**
     * Extracts clean, human-readable plain text from a given URI (PDF, DOCX, DOC, TXT).
     * Eliminates binary markers, ZIP artifacts, unprintable control characters, and PDF stream tokens.
     */
    fun extractCleanText(context: Context, uri: Uri, displayName: String, mimeType: String?): String {
        val resolver = context.contentResolver
        val isPdf = mimeType == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true)
        val isDocx = mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            displayName.endsWith(".docx", ignoreCase = true)
        val isDoc = mimeType == "application/msword" || displayName.endsWith(".doc", ignoreCase = true)

        return when {
            isPdf -> extractPdfCleanText(context, uri)
            isDocx -> extractDocxCleanText(resolver, uri)
            isDoc -> extractDocCleanText(resolver, uri)
            else -> extractPlainText(resolver, uri)
        }
    }

    private fun extractPdfCleanText(context: Context, uri: Uri): String {
        return runCatching {
            val raw = PdfBitmapConverter.extractPdfPlainText(context, uri)
            sanitizeExtractedText(raw)
        }.getOrElse { e ->
            Log.w(TAG, "Failed extracting PDF text: ${e.message}")
            ""
        }
    }

    fun extractDocxCleanText(resolver: ContentResolver, uri: Uri): String {
        return runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                extractDocxFromStream(stream)
            } ?: ""
        }.getOrElse { e ->
            Log.w(TAG, "Failed extracting DOCX text: ${e.message}")
            ""
        }
    }

    fun extractDocxFromStream(inputStream: InputStream): String {
        return runCatching {
            val sb = StringBuilder()
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xmlContent = BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).readText()
                        parseDocxXml(xmlContent, sb)
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            sanitizeExtractedText(sb.toString())
        }.getOrElse { e ->
            Log.w(TAG, "Failed parsing DOCX stream: ${e.message}")
            ""
        }
    }

    private fun parseDocxXml(xml: String, out: StringBuilder) {
        for (pMatch in P_REGEX.findAll(xml)) {
            var pContent = pMatch.groupValues[1]

            // Strip field codes and instructions like TOC, PAGE, PAGEREF, HYPERLINK
            pContent = INSTR_TEXT_REGEX.replace(pContent, "")

            // Map soft breaks and tabs into natural characters
            pContent = BR_REGEX.replace(pContent, "\n")
            pContent = TAB_REGEX.replace(pContent, "\t")

            // Map Word Wingdings/Symbol symbols inside <w:sym>
            pContent = SYM_REGEX.replace(pContent) { match ->
                when (match.groupValues[1].uppercase()) {
                    "F0B7" -> "• "
                    "F0A7" -> "▪ "
                    "F0A8" -> "[ ] "
                    "F0FE" -> "[✓] "
                    "F076" -> "✓ "
                    "F0D8" -> "→ "
                    else -> " "
                }
            }

            val lineBuilder = StringBuilder()
            for (tMatch in TEXT_NODE_REGEX.findAll(pContent)) {
                val decodedText = decodeXmlEntities(tMatch.groupValues[1])
                lineBuilder.append(decodedText)
            }

            val paragraph = lineBuilder.toString().trim()
            if (paragraph.isNotBlank()) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(paragraph)
            }
        }
    }

    private fun extractDocCleanText(resolver: ContentResolver, uri: Uri): String {
        return runCatching {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
            extractPrintableStringsFromBinary(bytes)
        }.getOrElse { e ->
            Log.w(TAG, "Failed extracting DOC binary text: ${e.message}")
            ""
        }
    }

    private fun extractPlainText(resolver: ContentResolver, uri: Uri): String {
        return runCatching {
            val raw = resolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            } ?: ""
            sanitizeExtractedText(raw)
        }.getOrElse { e ->
            Log.w(TAG, "Failed reading text file: ${e.message}")
            ""
        }
    }

    private val OLE_METADATA_STRINGS = setOf(
        "root entry", "compobj", "worddocument", "1table", "0table",
        "summaryinformation", "documentsummaryinformation", "normal.dotm",
        "normal.dot", "default paragraph font", "times new roman", "calibri",
        "microsoft word", "title", "author", "subject", "keywords", "comments",
        "template", "revision number", "total editing time"
    )

    /**
     * Extracts printable Unicode/ASCII character sequences from legacy binary files (.doc).
     * Filters out internal OLE structured storage metadata streams.
     */
    private fun extractPrintableStringsFromBinary(bytes: ByteArray): String {
        val sb = StringBuilder()
        val line = StringBuilder()

        var i = 0
        while (i < bytes.size - 1) {
            val b1 = bytes[i].toInt() and 0xFF
            val b2 = bytes[i + 1].toInt() and 0xFF
            val charCode = (b2 shl 8) or b1

            val isPrintable = (charCode in 32..126) ||
                (charCode in 0x00A0..0x024F) ||
                (charCode in listOf(0x011E, 0x011F, 0x0130, 0x0131, 0x00D6, 0x00F6, 0x00DC, 0x00FC, 0x00C7, 0x00E7, 0x015E, 0x015F))

            if (isPrintable) {
                line.append(charCode.toChar())
                i += 2
            } else if (charCode == 10 || charCode == 13) {
                appendDocLineIfValid(line.toString().trim(), sb)
                line.clear()
                i += 2
            } else {
                appendDocLineIfValid(line.toString().trim(), sb)
                line.clear()
                i++
            }
        }

        appendDocLineIfValid(line.toString().trim(), sb)
        return sanitizeExtractedText(sb.toString())
    }

    private fun appendDocLineIfValid(candidate: String, sb: StringBuilder) {
        if (candidate.length < 3) return
        val lower = candidate.lowercase()
        if (OLE_METADATA_STRINGS.contains(lower)) return
        if (lower.startsWith("msworddoc") || lower.startsWith("word.document")) return
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append(candidate)
    }

    /**
     * Maps Private Use Area characters from Symbol / Wingdings fonts into clean Unicode,
     * and strips unprintable control codes and zero-width markers.
     */
    private fun cleanPrivateUseAndControlChars(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '\uF0B7' -> sb.append("• ")
                '\uF0A7' -> sb.append("▪ ")
                '\uF0A8' -> sb.append("[ ] ")
                '\uF0FE' -> sb.append("[✓] ")
                '\uF076' -> sb.append("✓ ")
                '\uF0D8' -> sb.append("→ ")
                in '\uE000'..'\uF8FF' -> {} // Strip other Private Use Area characters (Wingdings tofu)
                in '\uFFF0'..'\uFFFF' -> {} // Strip specials
                in '\u200B'..'\u200F' -> {} // Zero-width spaces & directional marks
                in '\u202A'..'\u202E' -> {} // Directional embedding overrides
                in '\u2060'..'\u206F' -> {} // Invisible formatting controls
                '\uFEFF' -> {} // Byte order mark
                '\uFFFD' -> {} // Replacement character ()
                in '\u0000'..'\u0008', in '\u000B'..'\u000C', in '\u000E'..'\u001F', in '\u007F'..'\u009F' -> {} // Control codes (keep \t, \n)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Fully decodes HTML/XML entities including decimal, hex, and named symbols.
     */
    private fun decodeXmlEntities(raw: String): String {
        var str = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#160;", " ")
            .replace("&nbsp;", " ")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
            .replace("&#8216;", "'")
            .replace("&#8217;", "'")
            .replace("&#8211;", "–")
            .replace("&#8212;", "—")
            .replace("&#8230;", "…")
            .replace("&hellip;", "…")
            .replace("&bull;", "•")
            .replace("&times;", "×")
            .replace("&divide;", "÷")
            .replace("&plusmn;", "±")
            .replace("&minus;", "−")

        if (str.contains("&#")) {
            // Decimal entities e.g. &#65;
            str = Regex("""&#([0-9]{1,6});""").replace(str) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null && code in 32..65533 && code !in 0xD800..0xDFFF) {
                    code.toChar().toString()
                } else ""
            }
            // Hex entities e.g. &#x41;
            str = Regex("""&#x([0-9a-fA-F]{1,5});""").replace(str) { match ->
                val code = match.groupValues[1].toIntOrNull(16)
                if (code != null && code in 32..65533 && code !in 0xD800..0xDFFF) {
                    code.toChar().toString()
                } else ""
            }
        }

        return str
    }

    private val VOWEL_REGEX = Regex("""[aeıioöuüAEIİOÖUÜ]""")
    private val PDF_OPERATOR_LINE_REGEX = Regex("""(?m)^\s*(?:BT|ET|q|Q|stream|endstream|xref|trailer|startxref|endobj|%%EOF|<<|>>)\s*$""")
    private val PDF_STATE_LINE_REGEX = Regex("""(?m)^\s*(?:[0-9.-]+\s+){2,6}(?:cm|Tm|rg|RG|sc|SC|cs|CS|re|m|l|c|v|y|d|w|J|j|M|ri|gs)\s*$""")
    private val PDF_FONT_SELECT_REGEX = Regex("""(?m)^\s*/[A-Za-z0-9_-]+\s+[0-9.]+\s+Tf\s*$""")
    private val PDF_CMAP_NOISE_REGEX = Regex("""(?m)^\s*(?:begincmap|endcmap|beginbfrange|endbfrange|begincodespacerange|endcodespacerange|/CIDInit|/ProcSet|/Registry|/Ordering|def|dict|findresource)\b.*$""")
    private val WORD_FIELD_INSTRUCTION_REGEX = Regex("""(?i)^\s*(?:PAGE|NUMPAGES|PAGEREF|TOC|HYPERLINK|MERGEFORMAT|FORMTEXT|AUTOTEXT|INDEX)\b.*$""")

    /**
     * Strips PDF_PAGE tags, file header junk, unprintable control chars, stream garbage,
     * and corrupted binary tokens from both PDF and Word documents.
     */
    fun sanitizeExtractedText(text: String): String {
        if (text.isBlank()) return ""

        // 1. Strip internal PDF page tags and path markers
        var cleaned = text
            .replace(Regex("""\[PDF_PAGE:[^\]]+\]\r?\n?"""), "")
            .replace(Regex("""^[^\n]+\((?:[0-9]+\s*sayfa|PDF Belgesi|DOCX Dosyası|Word Belgesi)[^)]*\)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[Not:\s*İçerik[^\]]+\]"""), "")
            .replace(Regex("""^\[DOCX Dosyası:[^\]]+\]\s*""", RegexOption.MULTILINE), "")

        // 2. Clean Private Use Area and invisible control characters
        cleaned = cleanPrivateUseAndControlChars(cleaned)

        // 3. Remove raw PDF operators and structure lines
        cleaned = PDF_OPERATOR_LINE_REGEX.replace(cleaned, "")
        cleaned = PDF_STATE_LINE_REGEX.replace(cleaned, "")
        cleaned = PDF_FONT_SELECT_REGEX.replace(cleaned, "")
        cleaned = PDF_CMAP_NOISE_REGEX.replace(cleaned, "")

        // 4. Line-by-line quality validation
        val lines = cleaned.lineSequence().map { it.trim() }.filter { line ->
            if (line.isBlank()) return@filter false
            if (WORD_FIELD_INSTRUCTION_REGEX.matches(line)) return@filter false
            if (line.startsWith("%PDF-") || line.startsWith("%EOF")) return@filter false
            if (line.startsWith("/") && line.contains("def") && line.length > 20) return@filter false

            // Drop lines that are corrupted stream noise (e.g. font subset glyph dumps like !#$%&'()*+,-./0123...)
            if (line.length >= 8) {
                val nonLetters = line.count { !it.isLetterOrDigit() && !it.isWhitespace() }
                val ratio = nonLetters.toFloat() / line.length
                if (ratio >= 0.40f && !line.contains("=") && !line.contains("+")) {
                    return@filter false
                }
                // Check if long line has no vowels at all (cannot be natural Turkish/English text)
                if (line.length >= 12 && !VOWEL_REGEX.containsMatchIn(line) && line.none { it.isDigit() }) {
                    return@filter false
                }
            }
            true
        }.toMutableList()

        // 5. Trim leading and trailing document debris
        while (lines.isNotEmpty() && isHeaderOrTrailerGarbage(lines.first())) {
            lines.removeAt(0)
        }
        while (lines.isNotEmpty() && isHeaderOrTrailerGarbage(lines.last())) {
            lines.removeAt(lines.lastIndex)
        }

        return lines.joinToString("\n\n").trim()
    }

    private fun isHeaderOrTrailerGarbage(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("obj") || lower.startsWith("endobj") ||
            lower.startsWith("xref") || lower.startsWith("trailer") ||
            lower.startsWith("startxref") || lower.startsWith("%%eof") ||
            lower.startsWith("%pdf-") || lower.startsWith("compobj") ||
            lower.startsWith("root entry") || lower.startsWith("worddocument") ||
            lower == "normal.dotm" || lower == "normal.dot" ||
            lower.startsWith("/title") || lower.startsWith("/producer")
    }
}

