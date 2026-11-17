package com.example.sharkmentor.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.util.ImageStorageManager
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiSolverRepository @Inject constructor(
    private val context: Context
) {

    private val apiKey: String
        get() = ApiKeyRotator.getActiveKey().takeIf { it.isNotBlank() && it != "null" } ?: "YOUR_API_KEY"

    // Primary Tier: Fast & stable model
    private val primaryModel by lazy {
        GenerativeModel(
            modelName = "gemini-3.6-flash",
            apiKey = apiKey
        )
    }

    // Fallback Tier: Stable fallback model
    private val fallbackModel by lazy {
        GenerativeModel(
            modelName = "gemini-3.6-flash",
            apiKey = apiKey
        )
    }

    suspend fun getFullSolution(
        questionText: String,
        subject: String,
        optionsText: String? = null,
        questionImage: Bitmap? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        
        var actualQuestionText = questionText
        var actualImage = questionImage

        // Preserve original URI loading logic for backwards compatibility
        if (questionText.startsWith("content://") || questionText.startsWith("file://") || questionText.startsWith("/")) {
            actualQuestionText = "Görsel ektedir."
            if (actualImage == null) {
                actualImage = ImageStorageManager.getOptimizedBitmap(context, questionText)
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

        // 1. Try Primary Model (gemini-3.6-flash)
        try {
            val response = executeGeneration(primaryModel, prompt, actualImage)
            val text = response?.text
            if (!text.isNullOrBlank()) {
                return@withContext Result.success(sanitizeLatexToReadableText(text))
            }
        } catch (primaryError: Exception) {
            // Log primary failure and continue to fallback
            android.util.Log.w("AiSolverRepository", "Primary model failed. Triggering fallback: ${primaryError.localizedMessage}")
        }

        // 2. Try Fallback Model (gemini-3.6-flash)
        try {
            val fallbackResponse = executeGeneration(fallbackModel, prompt, actualImage)
            val fallbackText = fallbackResponse?.text
            if (!fallbackText.isNullOrBlank()) {
                return@withContext Result.success(sanitizeLatexToReadableText(fallbackText))
            } else {
                return@withContext Result.failure(Exception("Yedek modelden boş yanıt döndü."))
            }
        } catch (fallbackError: Exception) {
            return@withContext Result.failure(
                Exception("Çözüm üretilemedi. Sistem bağlantısını kontrol edin patron. Hata: ${fallbackError.localizedMessage}")
            )
        }
    }

    private fun sanitizeLatexToReadableText(raw: String): String {
        return raw
            // Markdown header hashtaglerini temizle
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            // \text{...} kalıplarını içindeki metne çevir
            .replace(Regex("""\\text\{([^}]+)\}"""), "$1")
            // Yaygın LaTeX sembollerini temiz Unicode'a çevir
            .replace("\\rightarrow", "→")
            .replace("\\Rightarrow", "⇒")
            .replace("\\implies", "⇒")
            .replace("\\times", "x")
            .replace("\\cdot", "•")
            .replace("\\pm", "±")
            .replace("\\le", "≤")
            .replace("\\ge", "≥")
            // Dolar ($) işaretlerini tamamen kaldır
            .replace("$", "")
            // Gereksiz süslü parantez artıklarını toparla
            .replace("{", "")
            .replace("}", "")
            .trim()
    }

    private suspend fun executeGeneration(
        model: GenerativeModel,
        prompt: String,
        image: Bitmap?
    ) = if (image != null) {
        val inputContent = content {
            image(image)
            text(prompt)
        }
        model.generateContent(inputContent)
    } else {
        model.generateContent(prompt)
    }
}
