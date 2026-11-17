package com.example.sharkmentor.ui.screens.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import java.util.Locale

object NoteRichTextHelper {

    enum class StyleType {
        BOLD, ITALIC, UNDERLINE
    }

    data class HighlightOption(
        val label: String,
        val color: Color,
        val hex: String
    )

    val HighlightPalette = listOf(
        HighlightOption("Sarı", Color(0xFFFFEB3B), "#FFEB3B"),
        HighlightOption("Yeşil", Color(0xFFA5D6A7), "#A5D6A7"),
        HighlightOption("Mavi", Color(0xFF81D4FA), "#81D4FA"),
        HighlightOption("Pembe", Color(0xFFF48FB1), "#F48FB1"),
        HighlightOption("Turuncu", Color(0xFFFFCC80), "#FFCC80"),
        HighlightOption("Mor", Color(0xFFCE93D8), "#CE93D8")
    )

    val TextColorPalette = listOf(
        "#000000" to Color(0xFF000000), // Siyah (Koyu Mürekkep)
        "#FFFFFF" to Color(0xFFFFFFFF), // Beyaz (Koyu Kağıt için)
        "#E53935" to Color(0xFFE53935), // Kırmızı
        "#1E88E5" to Color(0xFF1E88E5), // Mavi
        "#43A047" to Color(0xFF43A047), // Yeşil
        "#FB8C00" to Color(0xFFFB8C00), // Turuncu
        "#8E24AA" to Color(0xFF8E24AA), // Mor
        "#00897B" to Color(0xFF00897B)  // Turkuaz
    )

    fun colorToHex(color: Color): String {
        val argb = color.toArgb()
        val a = (argb shr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return if (a < 255) {
            String.format(Locale.US, "#%02X%02X%02X%02X", a, r, g, b)
        } else {
            String.format(Locale.US, "#%02X%02X%02X", r, g, b)
        }
    }

    fun parseHexColor(hex: String): Color? {
        val clean = hex.trim().removePrefix("#")
        return try {
            val longVal = clean.toLong(16)
            when (clean.length) {
                6 -> Color((0xFF000000 or longVal).toInt())
                8 -> Color(longVal.toInt())
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun toPlainText(raw: String): String {
        return raw.replace(Regex("<[^>]+>"), "")
    }

    /**
     * Parses tagged text (e.g. <b>, <i>, <u>, <color hex="#...">, <mark hex="#...">)
     * into a Jetpack Compose AnnotatedString with all SpanStyles accurately reconstructed.
     */
    fun parseTaggedText(raw: String): AnnotatedString {
        if (raw.isEmpty()) return AnnotatedString("")
        if (!raw.contains('<')) return AnnotatedString(raw)

        val plainTextBuilder = StringBuilder()
        val spanStyles = mutableListOf<AnnotatedString.Range<SpanStyle>>()

        data class OpenTag(
            val name: String,
            val startIndex: Int,
            val attrHex: String? = null
        )
        val openTags = mutableListOf<OpenTag>()

        var i = 0
        val len = raw.length
        val tagRegex = Regex("""^<\s*(/)?\s*([a-zA-Z0-9]+)(?:\s+(?:hex|color)=["']?([^"'>\s]+)["']?)?\s*>""")

        while (i < len) {
            if (raw[i] == '<') {
                val match = tagRegex.find(raw.substring(i))
                if (match != null) {
                    val isClosing = match.groupValues[1] == "/"
                    val tagName = match.groupValues[2].lowercase(Locale.ROOT)
                    val attr = match.groupValues[3].ifBlank { null }
                    val currentPlainLen = plainTextBuilder.length

                    if (!isClosing) {
                        openTags.add(OpenTag(tagName, currentPlainLen, attr))
                    } else {
                        val idx = openTags.indexOfLast { it.name == tagName }
                        if (idx != -1) {
                            val opened = openTags.removeAt(idx)
                            val start = opened.startIndex
                            val end = currentPlainLen
                            if (end > start) {
                                val style = createSpanStyle(opened.name, opened.attrHex)
                                if (style != null) {
                                    spanStyles.add(AnnotatedString.Range(style, start, end))
                                }
                            }
                        }
                    }
                    i += match.value.length
                    continue
                }
            }
            plainTextBuilder.append(raw[i])
            i++
        }

        val finalLen = plainTextBuilder.length
        while (openTags.isNotEmpty()) {
            val opened = openTags.removeAt(openTags.lastIndex)
            if (finalLen > opened.startIndex) {
                val style = createSpanStyle(opened.name, opened.attrHex)
                if (style != null) {
                    spanStyles.add(AnnotatedString.Range(style, opened.startIndex, finalLen))
                }
            }
        }

        return AnnotatedString(plainTextBuilder.toString(), spanStyles)
    }

    private fun createSpanStyle(tagName: String, attrHex: String?): SpanStyle? {
        return when (tagName) {
            "b" -> SpanStyle(fontWeight = FontWeight.Bold)
            "i" -> SpanStyle(fontStyle = FontStyle.Italic)
            "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
            "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            "color" -> attrHex?.let { parseHexColor(it) }?.let { SpanStyle(color = it) }
            "mark" -> attrHex?.let { parseHexColor(it) }?.let {
                // If opacity is full, ensure highlight is soft/translucent for readability
                val highlightBg = if (it.alpha >= 0.99f) it.copy(alpha = 0.55f) else it
                SpanStyle(background = highlightBg)
            }
            else -> null
        }
    }

    /**
     * Serializes an AnnotatedString with all its SpanStyles into human-readable, cleanly nested tags.
     */
    fun toTaggedString(annotatedString: AnnotatedString): String {
        val text = annotatedString.text
        if (text.isEmpty()) return ""
        val spanStyles = annotatedString.spanStyles
        if (spanStyles.isEmpty()) return text

        data class AtomicSpan(
            val type: String,
            val attrHex: String?,
            val start: Int,
            val end: Int
        )

        val atomicSpans = mutableListOf<AtomicSpan>()
        for (range in spanStyles) {
            val s = range.start.coerceIn(0, text.length)
            val e = range.end.coerceIn(0, text.length)
            if (e <= s) continue
            val style = range.item

            if (style.fontWeight == FontWeight.Bold) {
                atomicSpans.add(AtomicSpan("b", null, s, e))
            }
            if (style.fontStyle == FontStyle.Italic) {
                atomicSpans.add(AtomicSpan("i", null, s, e))
            }
            if (style.textDecoration?.contains(TextDecoration.Underline) == true) {
                atomicSpans.add(AtomicSpan("u", null, s, e))
            }
            if (style.textDecoration?.contains(TextDecoration.LineThrough) == true) {
                atomicSpans.add(AtomicSpan("s", null, s, e))
            }
            if (style.color != Color.Unspecified && style.color != Color.Transparent) {
                atomicSpans.add(AtomicSpan("color", colorToHex(style.color), s, e))
            }
            if (style.background != Color.Unspecified && style.background != Color.Transparent) {
                atomicSpans.add(AtomicSpan("mark", colorToHex(style.background), s, e))
            }
        }

        if (atomicSpans.isEmpty()) return text

        val boundaries = sortedSetOf(0, text.length)
        for (span in atomicSpans) {
            boundaries.add(span.start)
            boundaries.add(span.end)
        }
        val boundaryList = boundaries.toList()

        val sb = StringBuilder()
        for (idx in 0 until boundaryList.size - 1) {
            val bStart = boundaryList[idx]
            val bEnd = boundaryList[idx + 1]
            if (bEnd <= bStart) continue

            val segmentText = text.substring(bStart, bEnd)
            val activeSpans = atomicSpans.filter { it.start <= bStart && it.end >= bEnd }

            val bActive = activeSpans.any { it.type == "b" }
            val iActive = activeSpans.any { it.type == "i" }
            val uActive = activeSpans.any { it.type == "u" }
            val sActive = activeSpans.any { it.type == "s" }
            val colorHex = activeSpans.lastOrNull { it.type == "color" }?.attrHex
            val markHex = activeSpans.lastOrNull { it.type == "mark" }?.attrHex

            if (bActive) sb.append("<b>")
            if (iActive) sb.append("<i>")
            if (uActive) sb.append("<u>")
            if (sActive) sb.append("<s>")
            if (colorHex != null) sb.append("<color hex=\"$colorHex\">")
            if (markHex != null) sb.append("<mark hex=\"$markHex\">")

            sb.append(segmentText)

            if (markHex != null) sb.append("</mark>")
            if (colorHex != null) sb.append("</color>")
            if (sActive) sb.append("</s>")
            if (uActive) sb.append("</u>")
            if (iActive) sb.append("</i>")
            if (bActive) sb.append("</b>")
        }

        return sb.toString()
    }

    /**
     * Toggles Bold, Italic, or Underline on the selected range.
     */
    fun toggleStyleOnSelection(
        value: TextFieldValue,
        type: StyleType
    ): TextFieldValue {
        val selection = value.selection
        if (selection.collapsed) return value
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)

        val currentSpans = value.annotatedString.spanStyles
        val isAlreadyApplied = currentSpans.any { range ->
            range.start <= start && range.end >= end && when (type) {
                StyleType.BOLD -> range.item.fontWeight == FontWeight.Bold
                StyleType.ITALIC -> range.item.fontStyle == FontStyle.Italic
                StyleType.UNDERLINE -> range.item.textDecoration?.contains(TextDecoration.Underline) == true
            }
        }

        val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        if (isAlreadyApplied) {
            for (range in currentSpans) {
                val matchesType = when (type) {
                    StyleType.BOLD -> range.item.fontWeight == FontWeight.Bold
                    StyleType.ITALIC -> range.item.fontStyle == FontStyle.Italic
                    StyleType.UNDERLINE -> range.item.textDecoration?.contains(TextDecoration.Underline) == true
                }
                if (!matchesType) {
                    updatedSpans.add(range)
                    continue
                }
                if (range.end <= start || range.start >= end) {
                    updatedSpans.add(range)
                } else {
                    if (range.start < start) {
                        updatedSpans.add(AnnotatedString.Range(range.item, range.start, start))
                    }
                    if (range.end > end) {
                        updatedSpans.add(AnnotatedString.Range(range.item, end, range.end))
                    }
                }
            }
        } else {
            updatedSpans.addAll(currentSpans)
            val newStyle = when (type) {
                StyleType.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                StyleType.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                StyleType.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
            }
            updatedSpans.add(AnnotatedString.Range(newStyle, start, end))
        }

        return value.copy(
            annotatedString = AnnotatedString(value.text, updatedSpans)
        )
    }

    /**
     * Applies a text color to the selected text range.
     */
    fun applyColorToSelection(
        value: TextFieldValue,
        color: Color
    ): TextFieldValue {
        val selection = value.selection
        if (selection.collapsed) return value
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)

        val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        for (range in value.annotatedString.spanStyles) {
            if (range.item.color == Color.Unspecified || range.item.color == Color.Transparent) {
                updatedSpans.add(range)
                continue
            }
            if (range.end <= start || range.start >= end) {
                updatedSpans.add(range)
            } else {
                if (range.start < start) {
                    updatedSpans.add(AnnotatedString.Range(range.item, range.start, start))
                }
                if (range.end > end) {
                    updatedSpans.add(AnnotatedString.Range(range.item, end, range.end))
                }
            }
        }

        updatedSpans.add(AnnotatedString.Range(SpanStyle(color = color), start, end))
        return value.copy(
            annotatedString = AnnotatedString(value.text, updatedSpans)
        )
    }

    /**
     * Applies a highlight color to the selected text range, or removes highlight if null.
     */
    fun applyHighlightToSelection(
        value: TextFieldValue,
        highlightColor: Color?
    ): TextFieldValue {
        val selection = value.selection
        if (selection.collapsed) return value
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)

        val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        for (range in value.annotatedString.spanStyles) {
            if (range.item.background == Color.Unspecified || range.item.background == Color.Transparent) {
                updatedSpans.add(range)
                continue
            }
            if (range.end <= start || range.start >= end) {
                updatedSpans.add(range)
            } else {
                if (range.start < start) {
                    updatedSpans.add(AnnotatedString.Range(range.item, range.start, start))
                }
                if (range.end > end) {
                    updatedSpans.add(AnnotatedString.Range(range.item, end, range.end))
                }
            }
        }

        if (highlightColor != null && highlightColor != Color.Transparent) {
            val bg = if (highlightColor.alpha >= 0.99f) highlightColor.copy(alpha = 0.55f) else highlightColor
            updatedSpans.add(AnnotatedString.Range(SpanStyle(background = bg), start, end))
        }

        return value.copy(
            annotatedString = AnnotatedString(value.text, updatedSpans)
        )
    }

    /**
     * Automatically continues bullet, numbered, or checkbox lists when Enter is pressed,
     * or cancels the list if Enter is pressed on an empty list prefix.
     */
    fun handleEnterOnListItem(
        oldValue: TextFieldValue,
        newValue: TextFieldValue
    ): TextFieldValue? {
        val oldText = oldValue.text
        val newText = newValue.text
        if (newText.length != oldText.length + 1) return null

        val insertPos = oldValue.selection.min
        if (insertPos !in 0 until newText.length) return null
        if (newText[insertPos] != '\n') return null

        val lineStart = oldText.lastIndexOf('\n', (insertPos - 1).coerceAtLeast(0)).let {
            if (it == -1) 0 else it + 1
        }
        val prevLine = oldText.substring(lineStart, insertPos)

        // Bullet list (•)
        if (prevLine == "• ") {
            val updated = newText.removeRange(lineStart, lineStart + 2)
            val newCursor = lineStart.coerceIn(0, updated.length)
            return TextFieldValue(AnnotatedString(updated), TextRange(newCursor))
        } else if (prevLine.startsWith("• ")) {
            val updated = newText.substring(0, insertPos + 1) + "• " + newText.substring(insertPos + 1)
            val newCursor = insertPos + 1 + 2
            return TextFieldValue(AnnotatedString(updated), TextRange(newCursor))
        }

        // Checkbox list ([ ] or [✓] or [x] or legacy ☐ / ☑)
        val isCheckboxLine = prevLine.startsWith("[ ] ") || prevLine.startsWith("[✓] ") ||
            prevLine.startsWith("[x] ") || prevLine.startsWith("[X] ") ||
            prevLine.startsWith("☐ ") || prevLine.startsWith("☑ ")

        val isCheckboxEmpty = prevLine == "[ ] " || prevLine == "[✓] " ||
            prevLine == "[x] " || prevLine == "[X] " ||
            prevLine == "☐ " || prevLine == "☑ "

        if (isCheckboxEmpty) {
            val prefixLen = if (prevLine.startsWith("[")) 4 else 2
            val updated = newText.removeRange(lineStart, lineStart + prefixLen)
            val newCursor = lineStart.coerceIn(0, updated.length)
            return TextFieldValue(AnnotatedString(updated), TextRange(newCursor))
        } else if (isCheckboxLine) {
            val updated = newText.substring(0, insertPos + 1) + "[ ] " + newText.substring(insertPos + 1)
            val newCursor = insertPos + 1 + 4
            return TextFieldValue(AnnotatedString(updated), TextRange(newCursor))
        }

        // Numbered list (1., 2., etc.)
        val numMatch = Regex("""^(\d+)\.\s*(.*)""").find(prevLine)
        if (numMatch != null) {
            val num = numMatch.groupValues[1].toIntOrNull() ?: 1
            val rest = numMatch.groupValues[2]
            if (rest.isBlank()) {
                val prefixLen = numMatch.groupValues[1].length + 2
                val updated = newText.removeRange(lineStart, (lineStart + prefixLen).coerceAtMost(newText.length))
                val newCursor = lineStart.coerceIn(0, updated.length)
                return TextFieldValue(AnnotatedString(updated), TextRange(newCursor))
            } else {
                val nextPrefix = "${num + 1}. "
                val updated = newText.substring(0, insertPos + 1) + nextPrefix + newText.substring(insertPos + 1)
                val newCursor = insertPos + 1 + nextPrefix.length
                return TextFieldValue(AnnotatedString(updated), TextRange(newCursor))
            }
        }

        return null
    }

    /**
     * Toggles line prefix for bullet (• ), numbering (1. ), or checkbox (☐ ).
     */
    fun toggleLinePrefix(
        value: TextFieldValue,
        prefix: String
    ): TextFieldValue {
        val currentText = value.text
        val cursor = value.selection.start.coerceIn(0, currentText.length)
        val lineStart = currentText.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let {
            if (it == -1) 0 else it + 1
        }
        val lineEnd = currentText.indexOf('\n', lineStart).let {
            if (it == -1) currentText.length else it
        }
        val currentLine = currentText.substring(lineStart, lineEnd)

        val knownPrefixes = listOf("• ", "1. ", "[ ] ", "[✓] ", "[x] ", "[X] ", "☐ ", "☑ ")
        val matchedPrefix = knownPrefixes.firstOrNull { currentLine.startsWith(it) }
            ?: if (currentLine.matches(Regex("""^\d+\.\s+.*"""))) {
                currentLine.substring(0, currentLine.indexOf(' ') + 1)
            } else null

        val updatedText: String
        val cursorShift: Int

        if (matchedPrefix == prefix) {
            updatedText = currentText.removeRange(lineStart, lineStart + prefix.length)
            cursorShift = -prefix.length
        } else if (matchedPrefix != null) {
            updatedText = currentText.replaceRange(lineStart, lineStart + matchedPrefix.length, prefix)
            cursorShift = prefix.length - matchedPrefix.length
        } else {
            updatedText = currentText.substring(0, lineStart) + prefix + currentText.substring(lineStart)
            cursorShift = prefix.length
        }

        val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        for (range in value.annotatedString.spanStyles) {
            if (range.end <= lineStart) {
                updatedSpans.add(range)
            } else if (range.start >= lineStart) {
                val newStart = (range.start + cursorShift).coerceIn(0, updatedText.length)
                val newEnd = (range.end + cursorShift).coerceIn(0, updatedText.length)
                if (newEnd > newStart) {
                    updatedSpans.add(AnnotatedString.Range(range.item, newStart, newEnd))
                }
            } else {
                val newEnd = (range.end + cursorShift).coerceIn(0, updatedText.length)
                if (newEnd > range.start) {
                    updatedSpans.add(AnnotatedString.Range(range.item, range.start, newEnd))
                }
            }
        }

        val newCursor = (cursor + cursorShift).coerceIn(0, updatedText.length)
        return TextFieldValue(
            annotatedString = AnnotatedString(updatedText, updatedSpans),
            selection = TextRange(newCursor)
        )
    }

    /**
     * Preserves and expands/contracts existing styles when text is typed or deleted,
     * applying any currently active styles to newly inserted characters.
     */
    fun handleTextChange(
        oldValue: TextFieldValue,
        newValue: TextFieldValue,
        isBoldActive: Boolean,
        isItalicActive: Boolean,
        isUnderlineActive: Boolean,
        activeTextColor: Color?,
        activeHighlightColor: Color?
    ): TextFieldValue {
        val oldText = oldValue.text
        val newText = newValue.text

        if (oldText == newText) {
            return newValue.copy(annotatedString = oldValue.annotatedString)
        }

        val oldSpans = oldValue.annotatedString.spanStyles

        if (newText.length > oldText.length) {
            val insertStart = minOf(oldValue.selection.min, oldText.length)
            val addedCount = newText.length - oldText.length
            val insertEnd = insertStart + addedCount

            val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()

            for (range in oldSpans) {
                if (range.end <= insertStart) {
                    updatedSpans.add(range)
                } else if (range.start >= insertStart) {
                    updatedSpans.add(AnnotatedString.Range(range.item, range.start + addedCount, range.end + addedCount))
                } else {
                    updatedSpans.add(AnnotatedString.Range(range.item, range.start, range.end + addedCount))
                }
            }

            if (isBoldActive && !updatedSpans.any { it.start <= insertStart && it.end >= insertEnd && it.item.fontWeight == FontWeight.Bold }) {
                updatedSpans.add(AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), insertStart, insertEnd))
            }
            if (isItalicActive && !updatedSpans.any { it.start <= insertStart && it.end >= insertEnd && it.item.fontStyle == FontStyle.Italic }) {
                updatedSpans.add(AnnotatedString.Range(SpanStyle(fontStyle = FontStyle.Italic), insertStart, insertEnd))
            }
            if (isUnderlineActive && !updatedSpans.any { it.start <= insertStart && it.end >= insertEnd && it.item.textDecoration?.contains(TextDecoration.Underline) == true }) {
                updatedSpans.add(AnnotatedString.Range(SpanStyle(textDecoration = TextDecoration.Underline), insertStart, insertEnd))
            }
            if (activeTextColor != null && activeTextColor != Color.Unspecified && !updatedSpans.any { it.start <= insertStart && it.end >= insertEnd && it.item.color == activeTextColor }) {
                updatedSpans.add(AnnotatedString.Range(SpanStyle(color = activeTextColor), insertStart, insertEnd))
            }
            if (activeHighlightColor != null && activeHighlightColor != Color.Unspecified && activeHighlightColor != Color.Transparent && !updatedSpans.any { it.start <= insertStart && it.end >= insertEnd && it.item.background == activeHighlightColor }) {
                val bg = if (activeHighlightColor.alpha >= 0.99f) activeHighlightColor.copy(alpha = 0.55f) else activeHighlightColor
                updatedSpans.add(AnnotatedString.Range(SpanStyle(background = bg), insertStart, insertEnd))
            }

            return newValue.copy(
                annotatedString = AnnotatedString(newText, updatedSpans)
            )
        }

        // Deletion
        val deleteStart = newValue.selection.min
        val deletedCount = oldText.length - newText.length
        val deleteEnd = deleteStart + deletedCount

        val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        for (range in oldSpans) {
            if (range.end <= deleteStart) {
                updatedSpans.add(range)
            } else if (range.start >= deleteEnd) {
                updatedSpans.add(AnnotatedString.Range(range.item, range.start - deletedCount, range.end - deletedCount))
            } else {
                val newStart = minOf(range.start, deleteStart)
                val newEnd = maxOf(deleteStart, range.end - deletedCount)
                if (newEnd > newStart) {
                    updatedSpans.add(AnnotatedString.Range(range.item, newStart, newEnd))
                }
            }
        }

        return newValue.copy(
            annotatedString = AnnotatedString(newText, updatedSpans)
        )
    }

    data class CursorStyles(
        val isBold: Boolean,
        val isItalic: Boolean,
        val isUnderline: Boolean,
        val textColor: Color?,
        val highlightColor: Color?,
        val isBullet: Boolean,
        val isNumbered: Boolean,
        val isCheckbox: Boolean,
        val isCheckboxChecked: Boolean
    )

    fun getStylesAtCursor(
        value: TextFieldValue
    ): CursorStyles {
        val text = value.text
        val cursor = value.selection.start.coerceIn(0, text.length)
        val inspectPos = if (cursor > 0 && (cursor == text.length || !value.selection.collapsed)) cursor - 1 else cursor

        val spans = value.annotatedString.spanStyles
        val activeSpans = spans.filter { it.start <= inspectPos && it.end > inspectPos }

        val isBold = activeSpans.any { it.item.fontWeight == FontWeight.Bold }
        val isItalic = activeSpans.any { it.item.fontStyle == FontStyle.Italic }
        val isUnderline = activeSpans.any { it.item.textDecoration?.contains(TextDecoration.Underline) == true }
        val color = activeSpans.lastOrNull { it.item.color != Color.Unspecified && it.item.color != Color.Transparent }?.item?.color
        val highlight = activeSpans.lastOrNull { it.item.background != Color.Unspecified && it.item.background != Color.Transparent }?.item?.background

        val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
        val line = text.substring(lineStart, lineEnd)

        val isBullet = line.startsWith("• ")
        val isNumbered = line.matches(Regex("""^\d+\.\s+.*"""))
        val isCheckbox = line.startsWith("[ ] ") || line.startsWith("[✓] ") || line.startsWith("[x] ") || line.startsWith("[X] ") || line.startsWith("☐ ") || line.startsWith("☑ ")
        val isCheckboxChecked = line.startsWith("[✓] ") || line.startsWith("[x] ") || line.startsWith("[X] ") || line.startsWith("☑ ")

        return CursorStyles(
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            textColor = color,
            highlightColor = highlight,
            isBullet = isBullet,
            isNumbered = isNumbered,
            isCheckbox = isCheckbox,
            isCheckboxChecked = isCheckboxChecked
        )
    }

    /**
     * Toggles checkable to-do item on the line containing [offset]:
     * - "[ ] " -> "[✓] " (with strikethrough on line text)
     * - "[✓] " / "[x] " -> "[ ] " (with strikethrough removed)
     * - legacy "☐ " -> "[✓] "
     * - legacy "☑ " -> "[ ] "
     * - otherwise -> prepends "[ ] "
     */
    fun toggleCheckboxAtOffset(
        value: TextFieldValue,
        offset: Int
    ): TextFieldValue {
        val currentText = value.text
        if (currentText.isEmpty()) {
            return TextFieldValue(AnnotatedString("[ ] "), TextRange(4))
        }
        val targetPos = offset.coerceIn(0, currentText.length)
        val lineStart = currentText.lastIndexOf('\n', (targetPos - 1).coerceAtLeast(0)).let {
            if (it == -1) 0 else it + 1
        }
        val lineEnd = currentText.indexOf('\n', lineStart).let {
            if (it == -1) currentText.length else it
        }
        val currentLine = currentText.substring(lineStart, lineEnd)

        return when {
            // Checked: [✓] or [x] or [X] -> Uncheck to [ ]
            currentLine.startsWith("[✓] ") || currentLine.startsWith("[x] ") || currentLine.startsWith("[X] ") -> {
                val prefixLen = 4
                val updatedText = currentText.replaceRange(lineStart, lineStart + prefixLen, "[ ] ")
                val textStart = lineStart + 4
                val textEnd = lineEnd
                val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
                for (range in value.annotatedString.spanStyles) {
                    val isLineThrough = range.item.textDecoration?.contains(TextDecoration.LineThrough) == true
                    if (isLineThrough && range.start < textEnd && range.end > textStart) {
                        if (range.start < textStart) {
                            updatedSpans.add(AnnotatedString.Range(range.item, range.start, textStart))
                        }
                        if (range.end > textEnd) {
                            updatedSpans.add(AnnotatedString.Range(range.item, textEnd, range.end))
                        }
                        continue
                    }
                    updatedSpans.add(range)
                }
                TextFieldValue(
                    annotatedString = AnnotatedString(updatedText, updatedSpans),
                    selection = value.selection
                )
            }
            // Unchecked: [ ] -> Check to [✓] and strike through text
            currentLine.startsWith("[ ] ") -> {
                val updatedText = currentText.replaceRange(lineStart, lineStart + 4, "[✓] ")
                val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
                for (range in value.annotatedString.spanStyles) {
                    updatedSpans.add(range)
                }
                val textStart = lineStart + 4
                val textEnd = lineEnd
                if (textEnd > textStart) {
                    updatedSpans.add(AnnotatedString.Range(SpanStyle(textDecoration = TextDecoration.LineThrough), textStart, textEnd))
                }
                TextFieldValue(
                    annotatedString = AnnotatedString(updatedText, updatedSpans),
                    selection = value.selection
                )
            }
            // Legacy ☑ -> Uncheck to [ ]
            currentLine.startsWith("☑ ") -> {
                val updatedText = currentText.replaceRange(lineStart, lineStart + 2, "[ ] ")
                val textStart = lineStart + 4
                val textEnd = lineEnd + 2
                val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
                for (range in value.annotatedString.spanStyles) {
                    val isLineThrough = range.item.textDecoration?.contains(TextDecoration.LineThrough) == true
                    if (isLineThrough && range.start >= lineStart && range.end <= textEnd) {
                        continue
                    }
                    updatedSpans.add(range)
                }
                TextFieldValue(
                    annotatedString = AnnotatedString(updatedText, updatedSpans),
                    selection = value.selection
                )
            }
            // Legacy ☐ -> Check to [✓] with strikethrough
            currentLine.startsWith("☐ ") -> {
                val updatedText = currentText.replaceRange(lineStart, lineStart + 2, "[✓] ")
                val updatedSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
                for (range in value.annotatedString.spanStyles) {
                    updatedSpans.add(range)
                }
                val textStart = lineStart + 4
                val textEnd = lineEnd + 2
                if (textEnd > textStart) {
                    updatedSpans.add(AnnotatedString.Range(SpanStyle(textDecoration = TextDecoration.LineThrough), textStart, textEnd))
                }
                TextFieldValue(
                    annotatedString = AnnotatedString(updatedText, updatedSpans),
                    selection = value.selection
                )
            }
            else -> {
                toggleLinePrefix(value, "[ ] ")
            }
        }
    }
}
