package com.example.sharkmentor.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.sharkmentor.core.model.TwinQuestionData
import com.example.sharkmentor.core.util.ImageStorageManager
import com.example.sharkmentor.core.util.PdfBitmapConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ParsedPlanResult(
    val summary: String,
    val tasks: List<PlanTaskDto>
)

data class PlanTaskDto(
    val title: String,
    val targetDayOrWeek: String,
    val estimatedMinutes: Int,
    val description: String
)

class GeminiCoreService(private val context: Context) {

    init {
        UserAiPreferences.initialize(context)
    }

    companion object {
        private const val MAX_INLINE_PDF_BYTES = 12 * 1024 * 1024
        const val SYSTEM_PERSONA_RULES = """
KESİN KURALLAR:
1. SIFIR YALAKALIK & GİRİŞ YASAĞI: "Harika bir soru!", "Çok güzel düşünmüşsün!", "Merhaba! Sana bugün nasıl yardımcı olabilirim?" gibi yapay övgüleri ve giriş cümlelerini KESİNLİKLE KULLANMA. Doğrudan konuya gir.
2. ORANTILI UZUNLUK: Selamlamalara maksimum 1 kısa cümleyle yanıt ver. Standart açıklamaları 4-6 kısa ve net cümleyle sınırla.
3. SOHBET KANCASI: Yanıtı "Başarılar dilerim" gibi klişelerle bitirme; öğrenciyi akışta tutan tek cümlelik doğal bir takip sorusuyla bitir.
"""
    }

    /**
     * Streams a text chat completion strictly via `gemini-3.6-flash` using user keys first and BuildConfig fallback.
     */
    suspend fun streamTextChatWithGemini(
        systemInstruction: String,
        userPrompt: String,
        chatHistory: List<OpenAiChatMessage> = emptyList(),
        temperature: Float = 0.35f,
        maxTokens: Int = 4096,
        onPartialText: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val geminiTurns = chatHistory.takeLast(6).map { msg ->
            GeminiChatTurn(role = msg.role, text = msg.content)
        }
        val thinkingLevel = if (maxTokens >= 6000) GeminiThinkingLevel.MEDIUM else GeminiThinkingLevel.LOW
        val effectiveMaxTokens = maxTokens.coerceAtLeast(4096)

        GeminiRestClient.streamWithFailover(
            context = context,
            systemInstruction = systemInstruction.trim(),
            userPrompt = userPrompt,
            chatHistory = geminiTurns,
            responseJson = false,
            maxOutputTokens = effectiveMaxTokens,
            thinkingLevel = thinkingLevel,
            timeoutMs = 60_000L,
            onPartialText = onPartialText
        ).mapCatching { response ->
            val text = response.text.trim()
            if (text.isBlank()) throw IllegalStateException("Boş yanıt alındı.")
            text
        }.also { res ->
            res.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    private suspend fun generateTextForUri(
        uriString: String,
        prompt: String,
        errorMsg: String,
        responseJson: Boolean = false,
        maxOutputTokens: Int = 4096,
        thinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.LOW
    ): String {
        val uri = Uri.parse(uriString)
        val mimeType = getMimeType(uri)
        val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
            uriString.endsWith(".pdf", ignoreCase = true)

        val bitmapsToRecycle = mutableListOf<Bitmap>()
        try {
            var bitmaps: List<Bitmap> = emptyList()
            var inlineFiles: List<Pair<String, ByteArray>> = emptyList()

            if (isPdf) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception(errorMsg)
                if (bytes.size <= MAX_INLINE_PDF_BYTES) {
                    inlineFiles = listOf("application/pdf" to bytes)
                } else {
                    val rendered = runCatching {
                        PdfBitmapConverter.convertPdfUriToBitmaps(context, uri, maxPages = 5)
                    }.getOrDefault(emptyList())
                    if (rendered.isEmpty()) throw Exception(errorMsg)
                    bitmapsToRecycle.addAll(rendered)
                    bitmaps = rendered
                }
            } else {
                val bitmap = ImageStorageManager.getOptimizedBitmap(context, uriString)
                    ?: throw Exception(errorMsg)
                bitmapsToRecycle.add(bitmap)
                bitmaps = listOf(bitmap)
            }

            val result = GeminiRestClient.generateWithFailover(
                context = context,
                systemInstruction = SYSTEM_PERSONA_RULES,
                userPrompt = prompt,
                bitmaps = bitmaps,
                inlineFiles = inlineFiles,
                responseJson = responseJson,
                maxOutputTokens = maxOutputTokens,
                thinkingLevel = thinkingLevel,
                timeoutMs = 60_000L
            ).getOrThrow()

            val text = result.text.trim()
            if (text.isBlank()) throw Exception("Boş yanıt alındı.")
            return text
        } finally {
            bitmapsToRecycle.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
    }

    private fun getMimeType(uri: Uri): String? {
        if (uri.scheme == "content") return context.contentResolver.getType(uri)
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    // 1. SOCRATIC HINT GENERATOR
    suspend fun generateSocraticHint(questionUri: String, topic: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Sen Sokratik bir ders mentorusun. Konu: $topic.
                Görseldeki veya belgedeki soru için KESİNLİKLE cevabı veya şıkkı söyleme.
                Öğrencinin kuralı hatırlamasını sağlayacak TEK CÜMLELİK Sokratik bir yönlendirme sorusu sor. Dil: Türkçe.
            """.trimIndent()
            val text = generateTextForUri(
                uriString = questionUri,
                prompt = prompt,
                errorMsg = "Soru belgesi okunamadı.",
                responseJson = false,
                maxOutputTokens = 1024,
                thinkingLevel = GeminiThinkingLevel.LOW
            )
            Result.success(text)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 2. ISOMORPHIC TWIN QUESTION GENERATOR
    suspend fun generateTwinQuestion(questionUri: String, topic: String): Result<TwinQuestionData> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Bu belgedeki soruyu incele. Konu: $topic.
                Aynı kazanımı ölçen, sayıları ve bağlamı değiştirilmiş 1 adet İkiz (İzomorfik) çoktan seçmeli test sorusu üret.
                SADECE şu JSON formatında yanıt ver:
                {
                  "questionText": "Soru metni...",
                  "options": ["A) ...", "B) ...", "C) ...", "D) ...", "E) ..."],
                  "correctIndex": 0,
                  "explanation": "Kısa açıklama..."
                }
            """.trimIndent()

            val raw = generateTextForUri(
                uriString = questionUri,
                prompt = prompt,
                errorMsg = "Soru belgesi okunamadı.",
                responseJson = true,
                maxOutputTokens = 4096,
                thinkingLevel = GeminiThinkingLevel.LOW
            )
            val startIdx = raw.indexOf('{')
            val endIdx = raw.lastIndexOf('}')
            if (startIdx == -1 || endIdx == -1) throw Exception("JSON formatı bulunamadı.")

            val jsonObj = JSONObject(raw.substring(startIdx, endIdx + 1))
            val qText = jsonObj.getString("questionText")
            val optsArray = jsonObj.getJSONArray("options")
            val options = (0 until optsArray.length()).map { optsArray.getString(it) }
            val correctIdx = jsonObj.getInt("correctIndex")
            val exp = jsonObj.optString("explanation", "")

            Result.success(TwinQuestionData(qText, options, correctIdx, exp))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 3. BULK TOPIC SCANNER (PDF/JPG)
    suspend fun extractTopicsFromDocument(documentUri: String): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Bu müfredat / konu takip belgesindeki tüm dersleri ve alt konularını tespit et.
                SADECE şu JSON dizi formatında ver:
                [
                  {"subject": "Fizik", "topic": "Vektörler"},
                  {"subject": "Matematik", "topic": "Trigonometri"}
                ]
            """.trimIndent()

            val raw = generateTextForUri(
                uriString = documentUri,
                prompt = prompt,
                errorMsg = "Belge okunamadı.",
                responseJson = true,
                maxOutputTokens = 4096,
                thinkingLevel = GeminiThinkingLevel.LOW
            )
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start == -1 || end == -1) throw Exception("JSON dizisi bulunamadı.")

            val arr = JSONArray(raw.substring(start, end + 1))
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(obj.getString("subject") to obj.getString("topic"))
            }
            Result.success(list)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 4. STUDY PLAN PARSER (LONG/SHORT TERM) WITH TASKS
    suspend fun parseStudyPlanWithTasks(planUri: String, isLongTerm: Boolean): Result<ParsedPlanResult> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Bu ${if (isLongTerm) "Uzun Vadeli" else "Kısa Vadeli"} çalışma planını analiz et.
                1. Genel stratejiyi özetleyen bir 'summary' metni hazırla.
                2. Plandaki her bir ders/görev maddesini tek tek ayrıştırarak 'tasks' listesine ekle.
                
                SADECE aşağıdaki geçerli JSON formatında yanıt ver:
                {
                  "summary": "Plan genelinde haftalık 20 saatlik çalışma ve TYT-AYT dengesi hedeflenmiştir...",
                  "tasks": [
                    {
                      "title": "Fizik - Vektörler Test Çözümü",
                      "targetDayOrWeek": "1. Hafta / Pazartesi",
                      "estimatedMinutes": 60,
                      "description": "2 test çözülecek, yanlışlar kumbaraya atılacak."
                    }
                  ]
                }
            """.trimIndent()

            val raw = generateTextForUri(
                uriString = planUri,
                prompt = prompt,
                errorMsg = "Plan belgesi okunamadı.",
                responseJson = true,
                maxOutputTokens = 6144,
                thinkingLevel = GeminiThinkingLevel.LOW
            )
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start == -1 || end == -1) throw Exception("JSON formatı ayrıştırılamadı.")

            val jsonObj = JSONObject(raw.substring(start, end + 1))
            val summary = jsonObj.getString("summary")
            val tasksArr = jsonObj.getJSONArray("tasks")
            val tasks = mutableListOf<PlanTaskDto>()

            for (i in 0 until tasksArr.length()) {
                val item = tasksArr.getJSONObject(i)
                tasks.add(
                    PlanTaskDto(
                        title = item.getString("title"),
                        targetDayOrWeek = item.optString("targetDayOrWeek", "Genel"),
                        estimatedMinutes = item.optInt("estimatedMinutes", 45),
                        description = item.optString("description", "")
                    )
                )
            }
            Result.success(ParsedPlanResult(summary, tasks))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 5. AI GOAL PROJECTION ASSISTANT
    suspend fun analyzeConcreteTarget(
        examType: String,
        targetName: String,
        targetDetail: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val prompt = """
            Sen SharkMentor Study'nin Akademik Sınav ve Strateji Uzmanısın.
            Öğrencinin Hedefi:
            - Sınav Türü: $examType
            - Hedef Üniversite / Bölüm / Derece: $targetName
            - Ek Bilgi / İstenen Sıralama: $targetDetail
            
            GÖREV:
            Bu hedef için güncel sınav sistemine göre gerçekçi bir gereksinim analizi çıkar:
            1. Hangi puan türüyle öğrenci alıyor? (Örn: SAY, EA, SAT Math/EBRW, vs.)
            2. Son yıllardaki ortalama taban başarı sıralaması veya puan barajı nedir?
            3. Bu dereceyi yakalamak için gereken tahmini net/doğru soru dağılımı (Ders bazında kısa özet).
            
            Metni doğrudan öğrenciye hitap eden, motive edici, maddeler halinde ve 150 kelimeyi geçmeyecek netlikte Türkçe olarak yaz.
        """.trimIndent()

        GeminiRestClient.generateWithFailover(
            context = context,
            systemInstruction = SYSTEM_PERSONA_RULES,
            userPrompt = prompt,
            responseJson = false,
            maxOutputTokens = 2048,
            thinkingLevel = GeminiThinkingLevel.LOW,
            timeoutMs = 45_000L
        ).map { res ->
            res.text.trim().ifBlank { "Hedef analizi tamamlanamadı." }
        }
    }

    suspend fun generateGoalProjectionAdvice(
        overallScore: Int,
        weakestSubject: String?,
        focusMinutes: Long,
        reflectionNote: String?,
        concreteTargetSummary: String?,
        latestMonthlyNets: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val prompt = """
            Sen SharkMentor Study'nin Bilişsel Mentorusun.
            Öğrenci Profili:
            - Genel Ustalık: %$overallScore
            - En Zayıf Ders: ${weakestSubject ?: "Dengeli"}
            - Toplam Odak: $focusMinutes dk
            - Öğrencinin Son Duygu Notu: "${reflectionNote ?: "Belirtilmedi"}"
            - Öğrencinin Somut Hedefi: "${concreteTargetSummary ?: "Belirtilmedi"}"
            - Öğrencinin Son Deneme Netleri: "${latestMonthlyNets ?: "Belirtilmedi"}"
            
            Talimat:
            Eğer öğrencinin somut hedefi ve netleri varsa, doğrudan bu hedefe ulaşması için en zayıf dersini ve mevcut netlerini kapatacak nokta atışı stratejik bir taktik ver.
            Eğer hedef yoksa, genel bilişsel çalışma disiplini üzerinden rehberlik et.
            Samimi, disiplinli bir mentör dili kullan. 3 cümleyi geçme.
        """.trimIndent()

        GeminiRestClient.generateWithFailover(
            context = context,
            systemInstruction = SYSTEM_PERSONA_RULES,
            userPrompt = prompt,
            responseJson = false,
            maxOutputTokens = 1024,
            thinkingLevel = GeminiThinkingLevel.MINIMAL,
            timeoutMs = 45_000L
        ).map { res ->
            res.text.trim().ifBlank { "Çalışmalarını düzenli sürdürerek hedeflerine adım adım yaklaşabilirsin." }
        }
    }
}
