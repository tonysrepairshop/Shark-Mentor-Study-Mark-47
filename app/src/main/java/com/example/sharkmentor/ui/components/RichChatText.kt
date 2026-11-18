package com.example.sharkmentor.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BOLD_TOKEN_REGEX = Regex("\\*\\*(.*?)\\*\\*")
private val HEADING_REGEX = Regex("^(#{1,3})\\s+(.*)")
private val STEP_HEADER_REGEX = Regex(
    "^((?:(?:\\d+\\.\\s*Adım|Adım\\s*\\d+|Çözüm|Sonuç|Cevap|Formül|Verilenler|İstenen|Not|Dikkat|Önemli)[:.]?))\\s*(.*)",
    RegexOption.IGNORE_CASE
)
private val OPTION_PREFIX_REGEX = Regex("^([A-E][\\).])\\s*(.*)")
private val INLINE_CODE_REGEX = Regex("`(.*?)`")
private val ACTION_BLOCK_REGEX = Regex("#ACTION:(\\{.*?\\})#", RegexOption.DOT_MATCHES_ALL)
private val ACTION_TAG_REGEX = Regex("#ACTION:[A-Z_]+")
private val LATEX_FRAC_REGEX = Regex("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}")
private val LATEX_SQRT_N_REGEX = Regex("\\\\sqrt\\[([^]]+)\\]\\{([^}]+)\\}")
private val LATEX_SQRT_REGEX = Regex("\\\\sqrt\\{([^}]+)\\}")
private val LATEX_TEXT_CMD_REGEX = Regex("\\\\(?:text|mathrm|mathit)\\{([^}]+)\\}")
private val LATEX_MATHBF_REGEX = Regex("\\\\mathbf\\{([^}]+)\\}")
private val LATEX_FUNC_REGEX = Regex("\\\\(sin|cos|tan|cot|sec|csc|log|ln|lim|det)")
private val LATEX_BRACED_SUP_REGEX = Regex("\\^\\{([^}]+)\\}")
private val LATEX_BRACED_SUB_REGEX = Regex("_\\{([^}]+)\\}")
private val LATEX_SPACE_CMD_REGEX = Regex("\\\\([ ,;:])")
private val LONE_DOLLAR_REGEX = Regex("(?<!\\\\)\\$")

@Composable
fun RichChatText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFFF8FAFC) // High-contrast Slate-50 for crystal clarity
) {
    val annotated = remember(text, textColor) {
        val cleanedText = cleanAndFormatMath(text)
        buildAnnotatedString {
            var cursor = 0
            // Match **bold** tokens
            val matches = BOLD_TOKEN_REGEX.findAll(cleanedText)

            for (match in matches) {
                val range = match.range
                if (range.first > cursor) {
                    val segment = cleanedText.substring(cursor, range.first)
                    appendFormattedSegment(segment, textColor)
                }
                // Bold text with high contrast
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White))
                append(match.groupValues[1])
                pop()
                cursor = range.last + 1
            }
            if (cursor < cleanedText.length) {
                val segment = cleanedText.substring(cursor)
                appendFormattedSegment(segment, textColor)
            }
        }
    }

    Text(
        text = annotated,
        modifier = modifier,
        color = textColor,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp
    )
}

/**
 * Appends regular text with enhanced styling for:
 * - Step headers: 1. Adım, 2. Adım, Çözüm, Sonuç, Formül, Not, Dikkat
 * - Multiple choice options: A), B), C), D), E)
 * - Code/Formula backticks: `formula`
 * - Markdown headings: ### Header
 * - Clean bullet points
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendFormattedSegment(
    segment: String,
    baseColor: Color
) {
    val lines = segment.split("\n")
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()

        // 1. Markdown Headings: ### Header or ## Header
        val headingMatch = HEADING_REGEX.find(trimmed)
        if (headingMatch != null) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp))
            append(headingMatch.groupValues[2])
            pop()
        } else {
            // 2. Step Headers: 1. Adım, Adım 1, Çözüm, Sonuç, Formül, etc.
            val stepMatch = STEP_HEADER_REGEX.find(trimmed)

            if (stepMatch != null) {
                val stepLabel = stepMatch.groupValues[1]
                val stepRest = stepMatch.groupValues[2]

                val labelColor = when {
                    stepLabel.contains("Sonuç", ignoreCase = true) || stepLabel.contains("Cevap", ignoreCase = true) -> Color(0xFF34D399) // Emerald-400
                    stepLabel.contains("Dikkat", ignoreCase = true) || stepLabel.contains("Önemli", ignoreCase = true) || stepLabel.contains("Not", ignoreCase = true) -> Color(0xFFFBBF24) // Amber-400
                    else -> Color(0xFF38BDF8) // Sky-400 Cyan for Steps & Solutions
                }

                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = labelColor))
                append(stepLabel)
                pop()
                if (stepRest.isNotEmpty()) {
                    append(" ")
                    appendInlineStyledText(stepRest, baseColor)
                }
            } else {
                // 3. Multiple Choice Options: A), B), C), D), E) or A., B., etc.
                val optionMatch = OPTION_PREFIX_REGEX.find(trimmed)

                if (optionMatch != null) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))) // Sky-400
                    append(optionMatch.groupValues[1])
                    pop()
                    append(" ")
                    appendInlineStyledText(optionMatch.groupValues[2], baseColor)
                } else {
                    // Regular line: format bullet points and inline codes
                    val bulletFormatted = if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
                        "• " + line.substring(line.indexOfFirst { it == '*' || it == '-' } + 1).trimStart()
                    } else {
                        line
                    }
                    appendInlineStyledText(bulletFormatted, baseColor)
                }
            }
        }

        if (index < lines.size - 1) {
            append("\n")
        }
    }
}

/**
 * Handles inline code/formula backticks `formula` with monospace styling.
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineStyledText(
    text: String,
    baseColor: Color
) {
    if (!text.contains("`")) {
        append(text)
        return
    }

    var cursor = 0
    val matches = INLINE_CODE_REGEX.findAll(text)

    for (match in matches) {
        val range = match.range
        if (range.first > cursor) {
            append(text.substring(cursor, range.first))
        }
        // Formula/code highlight
        pushStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF7DD3FC), // Sky-300
                background = Color(0xFF0F172A).copy(alpha = 0.6f)
            )
        )
        append(" " + match.groupValues[1] + " ")
        pop()
        cursor = range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

/**
 * Transforms raw LaTeX math formulas and symbols into clean, universal Unicode characters
 * for seamless reading on Android displays (phones, foldables, and tablets).
 */
fun cleanAndFormatMath(raw: String): String {
    var s = raw
        // Remove Action blocks
        .replace(ACTION_BLOCK_REGEX, "")
        .replace(ACTION_TAG_REGEX, "")
        // Remove LaTeX delimiters
        .replace("\\[", "")
        .replace("\\]", "")
        .replace("\\(", "")
        .replace("\\)", "")
        .replace("$$", "")

    // Fractions: \frac{a}{b} -> (a / b)
    s = s.replace(LATEX_FRAC_REGEX) {
        "(${it.groupValues[1]} / ${it.groupValues[2]})"
    }

    // Roots: \sqrt[n]{x} -> ⁿ√(x) & \sqrt{x} -> √(x)
    s = s.replace(LATEX_SQRT_N_REGEX) {
        "${toSuperScript(it.groupValues[1])}√(${it.groupValues[2]})"
    }
    s = s.replace(LATEX_SQRT_REGEX) {
        "√(${it.groupValues[1]})"
    }

    // Text formatting inside LaTeX
    s = s.replace(LATEX_TEXT_CMD_REGEX) { it.groupValues[1] }
    s = s.replace(LATEX_MATHBF_REGEX) { "**${it.groupValues[1]}**" }

    // Standard Math Functions
    s = s.replace(LATEX_FUNC_REGEX) { it.groupValues[1] }

    // Parentheses & brackets
    s = s.replace("\\left(", "(").replace("\\right)", ")")
        .replace("\\left[", "[").replace("\\right]", "]")
        .replace("\\left\\{", "{").replace("\\right\\}", "}")

    // Mathematical operators & relations
    s = s.replace("\\times", "×")
        .replace("\\cdot", "·")
        .replace("\\div", "÷")
        .replace("\\pm", "±")
        .replace("\\mp", "∓")
        .replace("\\approx", "≈")
        .replace("\\equiv", "≡")
        .replace("\\neq", "≠")
        .replace("\\leq", "≤")
        .replace("\\geq", "≥")
        .replace("\\infty", "∞")
        .replace("\\rightarrow", "→")
        .replace("\\to", "→")
        .replace("\\leftarrow", "←")
        .replace("\\Rightarrow", "⇒")
        .replace("\\Leftrightarrow", "⇔")
        .replace("\\rightleftharpoons", "⇄")
        .replace("\\leftrightarrow", "⇄")
        .replace("\\degree", "°")
        .replace("^\\circ", "°")
        .replace("\\int", "∫")
        .replace("\\sum", "∑")
        .replace("\\in", "∈")
        .replace("\\notin", "∉")
        .replace("\\subset", "⊂")
        .replace("\\cup", "∪")
        .replace("\\cap", "∩")

    // Greek letters
    s = s.replace("\\Delta", "Δ")
        .replace("\\delta", "δ")
        .replace("\\pi", "π")
        .replace("\\theta", "θ")
        .replace("\\alpha", "α")
        .replace("\\beta", "β")
        .replace("\\gamma", "γ")
        .replace("\\lambda", "λ")
        .replace("\\mu", "μ")
        .replace("\\sigma", "σ")
        .replace("\\omega", "ω")
        .replace("\\rho", "ρ")
        .replace("\\phi", "φ")
        .replace("\\epsilon", "ε")

    // Braced Superscripts: ^{12}, ^{x}, etc.
    s = s.replace(LATEX_BRACED_SUP_REGEX) {
        toSuperScript(it.groupValues[1])
    }

    // Direct simple superscripts
    s = s.replace("^2", "²")
        .replace("^3", "³")
        .replace("^4", "⁴")
        .replace("^5", "⁵")
        .replace("^6", "⁶")
        .replace("^7", "⁷")
        .replace("^8", "⁸")
        .replace("^9", "⁹")
        .replace("^0", "⁰")
        .replace("^1", "¹")
        .replace("^n", "ⁿ")
        .replace("^x", "ˣ")
        .replace("^y", "ʸ")
        .replace("^k", "ᵏ")
        .replace("^(-1)", "⁻¹")
        .replace("^-1", "⁻¹")

    // Braced Subscripts: _{12}, _{max}, etc.
    s = s.replace(LATEX_BRACED_SUB_REGEX) {
        toSubScript(it.groupValues[1])
    }

    // Direct simple subscripts
    s = s.replace("_0", "₀")
        .replace("_1", "₁")
        .replace("_2", "₂")
        .replace("_3", "₃")
        .replace("_4", "₄")
        .replace("_5", "₅")
        .replace("_6", "₆")
        .replace("_7", "₇")
        .replace("_8", "₈")
        .replace("_9", "₉")
        .replace("_x", "ₓ")
        .replace("_y", "ᵧ")
        .replace("_n", "ₙ")

    // Clean remaining lone backslashes used as LaTeX spaces (e.g. \ )
    s = s.replace(LATEX_SPACE_CMD_REGEX, "$1")
    // Remove isolated single $ signs
    s = s.replace(LONE_DOLLAR_REGEX, "")

    return s.trim()
}

private fun toSuperScript(str: String): String {
    return str.map { ch ->
        when (ch) {
            '0' -> '⁰'
            '1' -> '¹'
            '2' -> '²'
            '3' -> '³'
            '4' -> '⁴'
            '5' -> '⁵'
            '6' -> '⁶'
            '7' -> '⁷'
            '8' -> '⁸'
            '9' -> '⁹'
            '+' -> '⁺'
            '-' -> '⁻'
            'n' -> 'ⁿ'
            'x' -> 'ˣ'
            'y' -> 'ʸ'
            'k' -> 'ᵏ'
            '(' -> '⁽'
            ')' -> '⁾'
            else -> ch
        }
    }.joinToString("")
}

private fun toSubScript(str: String): String {
    return str.map { ch ->
        when (ch) {
            '0' -> '₀'
            '1' -> '₁'
            '2' -> '₂'
            '3' -> '₃'
            '4' -> '₄'
            '5' -> '₅'
            '6' -> '₆'
            '7' -> '₇'
            '8' -> '₈'
            '9' -> '₉'
            '+' -> '₊'
            '-' -> '₋'
            'x' -> 'ₓ'
            'y' -> 'ᵧ'
            'n' -> 'ₙ'
            '(' -> '₍'
            ')' -> '₎'
            else -> ch
        }
    }.joinToString("")
}
