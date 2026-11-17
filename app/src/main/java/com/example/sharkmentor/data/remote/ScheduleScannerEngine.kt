package com.example.sharkmentor.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.sharkmentor.core.ai.GeminiRestClient
import com.example.sharkmentor.core.ai.GeminiThinkingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class ScannedTaskDto(
    val dayOfWeek: Int,
    val scheduledTime: String,
    val subject: String,
    val topic: String,
    val targetQuestions: Int,
    val teacherName: String = "",
    val period: Int = 0
)

class ScheduleScannerEngine @Inject constructor() {

    private fun decodeSampledBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    // Downscale large camera shots to prevent OOM
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > 1600) {
                        val sampleSize = maxDim / 1600
                        decoder.setTargetSampleSize(sampleSize)
                    }
                }
            } else {
                // Pre-copy to cache file to prevent stream/contentResolver read issues
                val tempFile = File(context.cacheDir, "scan_temp_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tempFile.absolutePath, options)

                var inSampleSize = 1
                while (options.outWidth / inSampleSize > 1600 || options.outHeight / inSampleSize > 1600) {
                    inSampleSize *= 2
                }

                options.inJustDecodeBounds = false
                options.inSampleSize = inSampleSize
                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, options)
                tempFile.delete()
                bitmap
            }
        } catch (e: Exception) {
            Log.e("ScheduleScanner", "Failed to decode bitmap from URI: ${e.message}", e)
            null
        }
    }

    suspend fun scanSchedule(context: Context, uri: Uri): Result<List<ScannedTaskDto>> = withContext(Dispatchers.IO) {
        val bitmap = decodeSampledBitmapFromUri(context, uri)
            ?: return@withContext Result.failure(Exception("Görsel okunamadı. Lütfen geçerli bir fotoğraf seçin."))

        try {
            Log.d("ScheduleScanner", "Image decoded successfully (${bitmap.width}x${bitmap.height}). Dispatching to Gemini...")

            val prompt = """
            GÖREV: Bu görseldeki ders çalışma programını, sınav çizelgesini veya haftalık planı analiz et.
            
            KURALLAR:
            1. 'dayOfWeek' alanını MUTLAKA 1 ile 7 arasında bir tamsayı olarak ver (1=Pazartesi, 2=Salı, 3=Çarşamba, 4=Perşembe, 5=Cuma, 6=Cumartesi, 7=Pazar).
            2. Eğer gün belirtilmemişse 1 yap.
            3. ZAMAN BLOKLARI (ASLA ÇAKIŞMA OLMAYACAK): Saat aralıklarını "08:30 - 09:30", "09:45 - 11:15" şeklinde ver. Aynı saatte çakışan iki ders yazma.
            4. SORU HEDEFLERİ: "targetQuestions" ASLA 0 olamaz! Soru sayısı belirtilmemişse minimum 20 veya 30 yaz.
            5. ÖĞRETMEN VE DERS SAATİ: Eğer görselde öğretmen adı varsa 'teacherName' olarak kısalt (örn: 'A.ÇKM'), yoksa boş bırak. Dersin kaçıncı saat/periyot olduğunu 'period' (1, 2, 3...) olarak belirt, eğer bulunamıyorsa 0 yaz.
            6. Yanıtı SADECE aşağıdaki JSON formatında döndür:
            [
              {
                "dayOfWeek": 1,
                "scheduledTime": "08:30 - 09:30",
                "subject": "Matematik",
                "topic": "Fonksiyonlar",
                "targetQuestions": 30,
                "teacherName": "Ö.YLM",
                "period": 1
              }
            ]
            """.trimIndent()

            val response = GeminiRestClient.generateWithFailover(
                context = context,
                userPrompt = prompt,
                bitmaps = listOf(bitmap),
                responseJson = true,
                maxOutputTokens = 8192,
                thinkingLevel = GeminiThinkingLevel.LOW,
                timeoutMs = 75_000L
            ).getOrThrow()

            val cleanedJson = GeminiRestClient.extractCleanJsonString(response.text)
            if (cleanedJson.isBlank()) {
                throw Exception("Görsel taranamadı.")
            }

            val jsonArray = JSONArray(cleanedJson)
            val list = mutableListOf<ScannedTaskDto>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawQuestions = obj.optInt("targetQuestions", 30)
                val safeQuestions = if (rawQuestions > 0) rawQuestions else 30

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
                Result.failure(Exception("Görselde ders programı tespit edilemedi."))
            } else {
                Result.success(list)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e("ScheduleScanner", "AI Call failed", e)
            Result.failure(e)
        } finally {
            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }
}
