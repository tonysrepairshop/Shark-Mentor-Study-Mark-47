package com.example.sharkmentor.data.remote

import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.data.local.dao.InstitutionScheduleDao
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import com.google.ai.client.generativeai.type.RequestOptions
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject

class SchedulePlannerEngine @Inject constructor(
    /** Nullable so existing call-sites that don't pass a DAO continue to compile. */
    private val institutionScheduleDao: InstitutionScheduleDao? = null
) {

    private fun createModel(modelName: String): GenerativeModel {
        val key = ApiKeyRotator.getActiveKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
        val cleanKey = key.trim().replace("\"", "").replace("'", "")
        val cleanModel = modelName.trim().removePrefix("models/").removePrefix("/")
        return GenerativeModel(
            modelName = cleanModel,
            apiKey = cleanKey,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                temperature = 0.2f
            },
            requestOptions = RequestOptions(timeout = 60_000L)
        )
    }

    /**
     * Builds the Turkish hard-constraint preamble from local Room institution slots.
     * Returns an empty string when no slots are stored (no-op for users without institution data).
     *
     * Example output:
     *   KRİTİK KISITLAMALAR:
     *   1. Öğrencinin kurum ders saatleri şunlardır:
     *      - Salı 12:00-18:00 Matematik (DERSHANE)
     *      ...
     *   - BU SAAT ARALIKLARINDA KESİNLİKLE BİREYSEL PLAN VE GÖREV YAZMA.
     *   - Kurumda 3 saatten fazla Matematik olduğu günlerin akşamına tekrar Matematik yazma;
     *     hafif tekrar veya sözel/paragraf rutini ata.
     *   - Tüm seanslar çakışmasız ve ardışık olmalıdır.
     */
    private suspend fun buildInstitutionConstraintsBlock(): String {
        val dao = institutionScheduleDao ?: return ""
        val slots = try {
            dao.getAllSchedules()
        } catch (e: Exception) {
            Log.w("SchedulePlannerEngine", "Could not read institution schedule: ${e.message}")
            return ""
        }
        if (slots.isEmpty()) return ""

        val dayNames = mapOf(
            1 to "Pazartesi", 2 to "Salı", 3 to "Çarşamba",
            4 to "Perşembe", 5 to "Cuma", 6 to "Cumartesi", 7 to "Pazar"
        )

        val slotLines = slots.joinToString("\n") { s ->
            "   - ${dayNames[s.dayOfWeek] ?: "Gün${s.dayOfWeek}"} " +
            "${s.startTime}-${s.endTime} ${s.subject} (${s.type})"
        }

        // Identify subjects where institution hours exceed 3 h on a given day
        val heavySubjects = slots
            .groupBy { it.dayOfWeek to it.subject }
            .filter { (_, group) ->
                val totalMinutes = group.sumOf { s ->
                    runCatching {
                        val (sh, sm) = s.startTime.split(":").map { it.toInt() }
                        val (eh, em) = s.endTime.split(":").map { it.toInt() }
                        (eh * 60 + em) - (sh * 60 + sm)
                    }.getOrDefault(0)
                }
                totalMinutes > 180
            }
            .map { (key, _) -> "${dayNames[key.first] ?: key.first} - ${key.second}" }

        val heavySubjectNote = if (heavySubjects.isNotEmpty()) {
            "- Kurumda 3 saatten fazla ${heavySubjects.joinToString(", ")} olduğu günlerin " +
            "akşamına tekrar aynı dersi yazma; hafif tekrar veya sözel/paragraf rutini ata.\n"
        } else ""

        return """
KRİTİK KISITLAMALAR:
1. Öğrencinin kurum ders saatleri şunlardır:
$slotLines
- BU SAAT ARALIKLARINDA KESİNLİKLE BİREYSEL PLAN VE GÖREV YAZMA.
${heavySubjectNote}- Tüm seanslar çakışmasız ve ardışık olmalıdır.
""".trimIndent()
    }

    suspend fun generateSchedule(userPrompt: String): Result<List<ScannedTaskDto>> = withContext(Dispatchers.IO) {
        try {
            // Fetch institution hard constraints (empty string if none stored)
            val constraintsBlock = buildInstitutionConstraintsBlock()

            val systemPrompt = buildString {
                if (constraintsBlock.isNotBlank()) {
                    appendLine(constraintsBlock)
                    appendLine()
                }
                append("""
            GÖREV: Öğrencinin talebine göre 7 günlük (Pazartesi-Pazar) sıkı bir ders çalışma programı oluştur.

            KURALLAR:
            1. GÜNLER: dayOfWeek değeri MUTLAKA 1 (Pazartesi) ile 7 (Pazar) arasında bir tamsayı olmalıdır.
            2. ZAMAN BLOKLARI (ASLA ÇAKIŞMA OLMAYACAK):
               - Bir günde aynı saatte ASLA iki görev olamaz.
               - Her ders bloğu ardışık olmalıdır (Örn: 08:30 - 09:30, 09:45 - 11:15, 11:30 - 13:00, 14:00 - 15:30).
               - Bloklar arasında 15 dakika mola bırak.
            3. SORU HEDEFLERİ:
               - "targetQuestions" ASLA 0 olamaz! Konu tekrarı bile olsa minimum 20, soru çözüm oturumlarına 30-50 soru hedefi yaz.
            4. ÇIKTI FORMATI: SADECE aşağıdaki JSON şemasında yanıt ver:
            [
              {
                "dayOfWeek": 1,
                "scheduledTime": "08:30 - 09:30",
                "subject": "Türkçe",
                "topic": "Paragraf Rutini",
                "targetQuestions": 30
              }
            ]
            """.trimIndent())
            }

            var rawJson: String? = null
            var lastErr: Exception? = null
            var currentDelay = 1500L
            val modelName = "gemini-3.6-flash"
            val maxRetries = 3

            for (attempt in 1..maxRetries) {
                try {
                    val model = createModel(modelName)
                    val resp = model.generateContent(
                        content {
                            text("$systemPrompt\n\nÖĞRENCİ TALEBİ / BİLGİSİ: $userPrompt")
                        }
                    )
                    rawJson = resp.text
                    if (!rawJson.isNullOrBlank()) break
                } catch (e: Exception) {
                    val rootCause = e.cause?.message ?: e.message
                    Log.e("SchedulePlannerEngine", "Call failed on $modelName: ${e.javaClass.simpleName} | Root:$rootCause", e)
                    lastErr = e
                    if (ApiKeyRotator.shouldRotateKey(e)) {
                        Log.w("SchedulePlannerEngine", "Quota limit reached on current key. Rotating key...")
                        ApiKeyRotator.rotateToNextKey()
                    }

                    val isUnavailable = e.message?.contains("503") == true || 
                                        e.message?.contains("high demand", ignoreCase = true) == true ||
                                        e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
                    
                    if (isUnavailable && attempt < maxRetries) {
                        Log.i("SchedulePlannerEngine", "Server busy (503). Retrying in ${currentDelay}ms...")
                        kotlinx.coroutines.delay(currentDelay)
                        currentDelay *= 2
                    } else if (attempt == maxRetries && isUnavailable) {
                        lastErr = java.lang.Exception("Yapay zeka sunucularında anlık yoğunluk yaşanıyor (503). Lütfen birkaç saniye sonra tekrar deneyin.")
                    }
                }
            }

            if (rawJson.isNullOrBlank()) {
                throw lastErr ?: java.lang.Exception("Plan oluşturulamadı.")
            }
            Log.d("SchedulePlannerEngine", "AI Response: $rawJson")

            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<ScannedTaskDto>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val targetQ = obj.optInt("targetQuestions", 30)
                val safeQuestions = if (targetQ > 0) targetQ else 30

                list.add(
                    ScannedTaskDto(
                        dayOfWeek = obj.optInt("dayOfWeek", 1).coerceIn(1, 7),
                        scheduledTime = obj.optString("scheduledTime", "08:30 - 09:30").ifBlank { "08:30 - 09:30" },
                        subject = obj.optString("subject", "Ders").ifBlank { "Ders" },
                        topic = obj.optString("topic", "Konu Çalışması").ifBlank { "Konu Çalışması" },
                        targetQuestions = safeQuestions,
                        teacherName = obj.optString("teacherName", ""),
                        period = obj.optInt("period", 0)
                    )
                )
            }

            if (list.isEmpty()) {
                Result.failure(Exception("Plan oluşturulamadı."))
            } else {
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e("SchedulePlannerEngine", "Generation failed", e)
            Result.failure(e)
        }
    }
}
