package com.example.sharkmentor.data.model

data class ParsedAiSolution(
    val rule: String,
    val steps: List<String>,
    val finalAnswer: String
)

fun parseSolutionText(rawText: String): ParsedAiSolution {
    val rule = Regex("\\[KURAL\\]([\\s\\S]*?)\\[/KURAL\\]")
        .find(rawText)?.groupValues?.get(1)?.trim() ?: ""

    val stepsRaw = Regex("\\[ADIMLAR\\]([\\s\\S]*?)\\[/ADIMLAR\\]")
        .find(rawText)?.groupValues?.get(1)?.trim() ?: ""
    val steps = stepsRaw.lines()
        .map { it.replace(Regex("^ADIM:\\s*"), "").trim() }
        .filter { it.isNotBlank() }

    val finalAnswer = Regex("\\[SONUC\\]([\\s\\S]*?)\\[/SONUC\\]")
        .find(rawText)?.groupValues?.get(1)?.trim() ?: ""

    return if (rule.isBlank() && steps.isEmpty()) {
        ParsedAiSolution(
            rule = "Detaylı Çözüm Adımları",
            steps = rawText.lines().filter { it.isNotBlank() },
            finalAnswer = ""
        )
    } else {
        ParsedAiSolution(rule, steps, finalAnswer)
    }
}
