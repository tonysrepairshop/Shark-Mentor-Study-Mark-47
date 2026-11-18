package com.example.sharkmentor.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.sharkmentor.core.ai.GeminiAiFactory
import com.example.sharkmentor.core.ai.UserAiPreferences
import com.example.sharkmentor.core.util.ImageStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiSolverRepository @Inject constructor(
    private val context: Context
) {

    init {
        UserAiPreferences.initialize(context)
    }

    suspend fun getFullSolution(
        questionText: String,
        subject: String,
        optionsText: String? = null,
        questionImage: Bitmap? = null
    ): Result<String> = withContext(Dispatchers.IO) {

        var actualQuestionText = questionText
        var actualImage = questionImage
        var ownsLoadedBitmap = false

        if (questionText.startsWith("content://") || questionText.startsWith("file://") || questionText.startsWith("/")) {
            actualQuestionText = "Görsel ektedir."
            if (actualImage == null) {
                actualImage = ImageStorageManager.getOptimizedBitmap(context, questionText)
                ownsLoadedBitmap = actualImage != null
            }
        }

        val prompt = """
            Sen SharkMentor uygulamasının görsel pedagoji uzmanı öğretmenisin.
            Ders: $subject
            Soru: $actualQuestionText
            ${if (!optionsText.isNullOrBlank()) "Şıklar:\n$optionsText" else ""}

            Yanıtını KESİNLİKLE şu 3 özel etiket bloğu arasında ver:

            [KURAL]
            Sorunun temel mantığını ve formülünü tek bir vurucu cümleyle özetle.
            [/KURAL]

            [ADIMLAR]
            Her adımı başına 'ADIM:' koyarak yaz.
            ADIM: Verilen atomların elektron sayıları (N:7, O:8, Cl:17, P:15)
            ADIM: NO₃⁻ iyonu hesabı (7 + 24 + 1 = 32 e⁻)
            ADIM: ClO₃⁻ iyonu hesabı (17 + 24 + 1 = 42 e⁻)
            ADIM: PO₄³⁻ iyonu hesabı (15 + 32 + 3 = 50 e⁻)
            [/ADIMLAR]

            [SONUC]
            Nihai sıralama ve doğru seçenek (Örn: 50 > 42 > 32 ⇒ III > II > I)
            [/SONUC]

            LaTeX KODU (${'$'} veya \) KULLANMA. İyonları Unicode yaz (NO₃⁻, PO₄³⁻).
        """.trimIndent()

        try {
            val result = GeminiAiFactory.generateContentWithFailover(
                context = context,
                userPrompt = prompt,
                bitmaps = if (actualImage != null && !actualImage.isRecycled) listOf(actualImage) else emptyList(),
                responseJson = false,
                temperature = 0.2f,
                maxOutputTokens = 4096,
                timeoutMs = 60_000L
            )
            val text = result.getOrThrow().text
            if (text.isNotBlank()) {
                Result.success(sanitizeLatexToReadableText(text))
            } else {
                Result.failure(Exception("Modelden boş yanıt döndü."))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(
                Exception("Çözüm üretilemedi. Lütfen ağ ve API bağlantısını kontrol edin. Hata: ${e.localizedMessage}", e)
            )
        } finally {
            if (ownsLoadedBitmap && actualImage != null && !actualImage.isRecycled) {
                runCatching { actualImage.recycle() }
            }
        }
    }

    private fun sanitizeLatexToReadableText(raw: String): String {
        return raw
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("""\\text\{([^}]+)\}"""), "$1")
            .replace("\\rightarrow", "→")
            .replace("\\Rightarrow", "⇒")
            .replace("\\implies", "⇒")
            .replace("\\times", "x")
            .replace("\\cdot", "•")
            .replace("\\pm", "±")
            .replace("\\le", "≤")
            .replace("\\ge", "≥")
            .replace("$", "")
            .replace("{", "")
            .replace("}", "")
            .trim()
    }
}
