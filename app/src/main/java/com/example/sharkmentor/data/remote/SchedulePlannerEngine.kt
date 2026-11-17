package com.example.sharkmentor.data.remote

import android.util.Log
import com.example.sharkmentor.core.ai.GeminiAiFactory
import com.example.sharkmentor.core.ai.GeminiRestClient
import com.example.sharkmentor.data.local.dao.InstitutionScheduleDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject

class SchedulePlannerEngine @Inject constructor(
    /** Nullable so existing call-sites that don't pass a DAO continue to compile. */
    private val institutionScheduleDao: InstitutionScheduleDao? = null
) {

    /**
     * Builds the Turkish hard-constraint preamble from local Room institution slots.
     * Returns an empty string when no slots are stored (no-op for users without institution data).
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

            val response = GeminiAiFactory.generateContentWithFailover(
                systemInstruction = systemPrompt,
                userPrompt = "ÖĞRENCİ TALEBİ / BİLGİSİ: $userPrompt",
                responseJson = true,
                maxOutputTokens = 8192,
                timeoutMs = 75_000L
            ).getOrThrow()

            val cleanedJson = GeminiRestClient.extractCleanJsonString(response.text)
            if (cleanedJson.isBlank()) {
                throw Exception("Plan oluşturulamadı.")
            }
            Log.d("SchedulePlannerEngine", "AI Response len=${cleanedJson.length}")

            val jsonArray = JSONArray(cleanedJson)
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e("SchedulePlannerEngine", "Generation failed", e)
            Result.failure(e)
        }
    }
}
