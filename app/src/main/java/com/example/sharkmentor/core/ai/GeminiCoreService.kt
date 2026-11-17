package com.example.sharkmentor.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.core.model.TwinQuestionData
import com.example.sharkmentor.core.util.ImageStorageManager
import com.example.sharkmentor.core.analytics.SubjectMastery
import com.example.sharkmentor.data.remote.GeminiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    companion object {
        const val SYSTEM_PERSONA_RULES = """
KESİN KURALLAR:
1. SIFIR YALAKALIK & GİRİŞ YASAĞI: "Harika bir soru!", "Çok güzel düşünmüşsün!", "Merhaba! Sana bugün nasıl yardımcı olabilirim?" gibi yapay övgüleri ve giriş cümlelerini KESİNLİKLE KULLANMA. Doğrudan konuya gir.
2. ORANTILI UZUNLUK: Selamlamalara maksimum 1 kısa cümleyle yanıt ver. Standart açıklamaları 4-6 kısa ve net cümleyle sınırla.
3. SOHBET KANCASI: Yanıtı "Başarılar dilerim" gibi klişelerle bitirme; öğrenciyi akışta tutan tek cümlelik doğal bir takip sorusuyla bitir.
"""
    }

    private fun sanitizeModelName(name: String): String {
        return GeminiAiFactory.SOLE_GEMINI_MODEL
    }

    private fun createModel(apiKey: String, modelName: String = GeminiRepository.ACTIVE_MODEL_NAME): GenerativeModel {
        if (apiKey.isBlank() || apiKey == "null") {
            Log.e("GeminiError", "AI Call Failed: API Key is missing or invalid.")
            throw IllegalStateException("Gemini API Key is missing or invalid. Check local.properties or BuildConfig.")
        }
        val cleanKey = apiKey.trim().replace("\"", "").replace("'", "")
        val cleanModelName = sanitizeModelName(modelName)
        return GenerativeModel(
            modelName = cleanModelName,
            apiKey = cleanKey,
            generationConfig = GeminiAiFactory.FAST_PRESET_CONFIG,
            tools = emptyList(),
            systemInstruction = content { text(SYSTEM_PERSONA_RULES) },
            requestOptions = RequestOptions(timeout = 30_000L)
        )
    }

    // Failover Execution: Cycles through all available accounts and fallback models before returning failure
    private suspend fun <T> executeWithKeyFailover(
        action: suspend (GenerativeModel) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        val totalKeys = ApiKeyRotator.getKeyCount().coerceAtLeast(1)
        val modelName = "gemini-3.6-flash"
        var lastException: Exception? = null
        val maxRetries = 2
        var currentDelay = 600L

        for (attempt in 0 until totalKeys) {
            val activeKey = ApiKeyRotator.getActiveKey()
            if (activeKey.isBlank() || activeKey == "null") {
                ApiKeyRotator.rotateToNextKey()
                continue
            }

            for (retry in 1..maxRetries) {
                try {
                    val model = createModel(activeKey, modelName)
                    val result = action(model)
                    return@withContext Result.success(result)
                } catch (e: Exception) {
                    Log.e("GeminiError", "AI Call Failed ($modelName): ${e.javaClass.simpleName} - ${e.message}", e)
                    lastException = Exception("AI Hatası: ${e.localizedMessage ?: "Sunucu bağlantısı kurulamadı"}")
                    
                    if (ApiKeyRotator.shouldRotateKey(e)) {
                        Log.w("GeminiCoreService", "Quota/Key limit reached on current key. Rotating key...")
                        break // Break inner retry loop to trigger key rotation in outer loop
                    }

                    val isUnavailable = e.message?.contains("503") == true || 
                                        e.message?.contains("high demand", ignoreCase = true) == true ||
                                        e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
                    
                    if (isUnavailable && retry < maxRetries) {
                        Log.i("GeminiCoreService", "Server busy (503). Retrying in ${currentDelay}ms...")
                        kotlinx.coroutines.delay(currentDelay)
                        currentDelay *= 2
                    } else if (retry == maxRetries && isUnavailable) {
                        lastException = Exception("Yapay zeka sunucularında anlık yoğunluk yaşanıyor (503). Lütfen birkaç saniye sonra tekrar deneyin.")
                        break // Break to trigger rotation, though usually 503 is per-model not per-key
                    }
                }
            }

            Log.w("GeminiCoreService", "AI Deneme ${attempt + 1}/$totalKeys tüm modellerde başarısız. Sonraki anahtara devrediliyor...")
            ApiKeyRotator.rotateToNextKey()
            delay(400)
        }

        Result.failure(lastException ?: Exception("Mevcut API anahtarlarıyla işlem tamamlanamadı."))
    }

    private suspend fun generateContentForUri(model: GenerativeModel, uriString: String, prompt: String, errorMsg: String): GenerateContentResponse {
        val uri = Uri.parse(uriString)
        val mimeType = getMimeType(uri)

        return if (mimeType == "application/pdf") {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception(errorMsg)
            model.generateContent(content {
                blob("application/pdf", bytes)
                text(prompt)
            })
        } else {
            val bitmap = ImageStorageManager.getOptimizedBitmap(context, uriString)
                ?: throw Exception(errorMsg)
            model.generateContent(content {
                image(bitmap)
                text(prompt)
            })
        }
    }

    private fun getMimeType(uri: Uri): String? {
        if (uri.scheme == "content") return context.contentResolver.getType(uri)
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    // 1. SOCRATIC HINT GENERATOR
    suspend fun generateSocraticHint(questionUri: String, topic: String): Result<String> {
        return executeWithKeyFailover { model ->
            val prompt = """
                Sen Sokratik bir ders mentorusun. Konu: $topic.
                Görseldeki veya belgedeki soru için KESİNLİKLE cevabı veya şıkkı söyleme.
                Öğrencinin kuralı hatırlamasını sağlayacak TEK CÜMLELİK Sokratik bir yönlendirme sorusu sor. Dil: Türkçe.
            """.trimIndent()

            val response = generateContentForUri(model, questionUri, prompt, "Soru belgesi okunamadı.")
            response.text?.trim() ?: throw Exception("Boş yanıt alındı.")
        }
    }

    // 2. ISOMORPHIC TWIN QUESTION GENERATOR
    suspend fun generateTwinQuestion(questionUri: String, topic: String): Result<TwinQuestionData> {
        return executeWithKeyFailover { model ->
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

            val response = generateContentForUri(model, questionUri, prompt, "Soru belgesi okunamadı.")
            val raw = response.text ?: throw Exception("Boş yanıt alındı.")
            val startIdx = raw.indexOf('{')
            val endIdx = raw.lastIndexOf('}')
            if (startIdx == -1 || endIdx == -1) throw Exception("JSON formatı bulunamadı.")

            val jsonObj = JSONObject(raw.substring(startIdx, endIdx + 1))
            val qText = jsonObj.getString("questionText")
            val optsArray = jsonObj.getJSONArray("options")
            val options = (0 until optsArray.length()).map { optsArray.getString(it) }
            val correctIdx = jsonObj.getInt("correctIndex")
            val exp = jsonObj.optString("explanation", "")

            TwinQuestionData(qText, options, correctIdx, exp)
        }
    }

    // 3. BULK TOPIC SCANNER (PDF/JPG)
    suspend fun extractTopicsFromDocument(documentUri: String): Result<List<Pair<String, String>>> {
        return executeWithKeyFailover { model ->
            val prompt = """
                Bu müfredat / konu takip belgesindeki tüm dersleri ve alt konularını tespit et.
                SADECE şu JSON dizi formatında ver:
                [
                  {"subject": "Fizik", "topic": "Vektörler"},
                  {"subject": "Matematik", "topic": "Trigonometri"}
                ]
            """.trimIndent()

            val response = generateContentForUri(model, documentUri, prompt, "Belge okunamadı.")
            val raw = response.text ?: throw Exception("Boş yanıt.")
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start == -1 || end == -1) throw Exception("JSON dizisi bulunamadı.")

            val arr = JSONArray(raw.substring(start, end + 1))
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(obj.getString("subject") to obj.getString("topic"))
            }
            list
        }
    }

    // 4. STUDY PLAN PARSER (LONG/SHORT TERM) WITH TASKS
    suspend fun parseStudyPlanWithTasks(planUri: String, isLongTerm: Boolean): Result<ParsedPlanResult> {
        return executeWithKeyFailover { model ->
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

            val response = generateContentForUri(model, planUri, prompt, "Plan belgesi okunamadı.")
            val raw = response.text ?: throw Exception("Boş yanıt.")
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
            ParsedPlanResult(summary, tasks)
        }
    }

    // 5. AI GOAL PROJECTION ASSISTANT
    suspend fun analyzeConcreteTarget(
        examType: String,
        targetName: String,
        targetDetail: String
    ): Result<String> = executeWithKeyFailover { model ->
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

        val response = model.generateContent(prompt)
        response.text?.trim() ?: "Hedef analizi tamamlanamadı."
    }

    suspend fun generateGoalProjectionAdvice(
        overallScore: Int,
        weakestSubject: String?,
        focusMinutes: Long,
        reflectionNote: String?,
        concreteTargetSummary: String?,
        latestMonthlyNets: String?
    ): Result<String> = executeWithKeyFailover { model ->
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

        val response = model.generateContent(prompt)
        response.text?.trim() ?: "Çalışmalarını düzenli sürdürerek hedeflerine adım adım yaklaşabilirsin."
    }
}
