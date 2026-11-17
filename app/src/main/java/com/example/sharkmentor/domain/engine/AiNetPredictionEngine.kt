package com.example.sharkmentor.domain.engine

import com.example.sharkmentor.data.local.entity.NetProjectionEntity
import com.example.sharkmentor.data.remote.GeminiRepository
import org.json.JSONObject
import javax.inject.Inject

class AiNetPredictionEngine @Inject constructor(
    private val geminiRepository: GeminiRepository
) {

    suspend fun requestAiProjection(
        tytTarget: Float,
        aytTarget: Float,
        recentDenemes: List<Pair<String, Float>>, // e.g. ["TYT" to 65.5f, "AYT" to 34f]
        topicMasteryList: List<Triple<String, String, Int>> // Subject, TopicName, MasteryScore (0-100)
    ): Result<NetProjectionEntity> {

        val denemeText = recentDenemes.joinToString { "${it.first}:${it.second} Net" }
        val topicText = topicMasteryList.joinToString(separator = "\n") { 
            "- [${it.first}] ${it.second}: ${it.third}% hakimiyet" 
        }

        val prompt = """
        GÖREV: Öğrencinin deneme geçmişini ve konu hakimiyet listesini analiz ederek YKS sezon sonu projeksiyonunu hesapla.
        
        ÖĞRENCİ VERİLERİ:
        - TYT Hedefi: $tytTarget Net | AYT Hedefi: $aytTarget Net
        - Son Denemeler: ${if (denemeText.isBlank()) "Henüz deneme girilmedi" else denemeText}
        - Konu Hakimiyetleri:
        $topicText

        KRİTİK HESAPLAMA KURALLARI:
        1. "tytCurrentNet" değeri, öğrencinin ŞU ANKİ konu hakimiyet puanlarının ve varsa denemelerinin net karşılığıdır. Asla uydurma değer verme.
        2. "tytProjectedNet", öğrencinin sene sonuna kadar kalan süredeki gerçekçi tavanıdır. 
           DİKKAT: Projeksiyon neti, mevcut netten astronomik derecede yüksek olamaz! Kalan sürede gerçekçi artış potansiyeli mevcut netin üzerine maksimum +15 ila +25 net aralığında olabilir.
        3. TYT maksimum 120, AYT maksimum 80 net olabilir.
        4. En çok net kazandıracak 3 kritik eksik konuyu belirle.
        5. Yanıtı SADECE geçerli JSON olarak döndür:
        {
          "tytCurrentNet": Float,
          "tytProjectedNet": Float,
          "tytNetGap": Float,
          "aytCurrentNet": Float,
          "aytProjectedNet": Float,
          "aytNetGap": Float,
          "priorityTopics": [
            {"subject": "Ders", "topic": "Konu", "potentialBoost": Float, "category": "TYT|AYT"}
          ],
          "strategicSummary": "2 cümlelik net analiz."
        }
        """.trimIndent()

        val aiResult = geminiRepository.generateJsonResponse(prompt)
        return aiResult.mapCatching { rawJson ->
            val json = JSONObject(rawJson)
            NetProjectionEntity(
                id = 1,
                tytCurrentNet = json.getDouble("tytCurrentNet").toFloat(),
                tytProjectedNet = json.getDouble("tytProjectedNet").toFloat(),
                tytTargetNet = tytTarget,
                tytNetGap = json.getDouble("tytNetGap").toFloat(),
                aytCurrentNet = json.getDouble("aytCurrentNet").toFloat(),
                aytProjectedNet = json.getDouble("aytProjectedNet").toFloat(),
                aytTargetNet = aytTarget,
                aytNetGap = json.getDouble("aytNetGap").toFloat(),
                priorityTopicsJson = json.getJSONArray("priorityTopics").toString(),
                strategicSummary = json.getString("strategicSummary"),
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        }
    }
}
