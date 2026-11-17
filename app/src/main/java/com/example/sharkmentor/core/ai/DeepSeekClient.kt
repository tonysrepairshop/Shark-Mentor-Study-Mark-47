package com.example.sharkmentor.core.ai

import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class OpenAiChatMessage(
    val role: String,
    val content: String
)

/**
 * High-performance, lightweight streaming HTTP client for Text/Chat requests.
 * Primary Provider: DeepSeek API (`deepseek-chat`).
 * Automatic Fallback: Kimi / Moonshot API (`moonshot-v1-8k`) if DeepSeek returns 4xx/5xx or network errors.
 */
object DeepSeekClient {
    private const val TAG = "DeepSeekClient"

    private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1/chat/completions"
    private const val DEEPSEEK_MODEL = "deepseek-chat"

    private const val KIMI_BASE_URL = "https://api.moonshot.cn/v1/chat/completions"
    private const val KIMI_MODEL = "moonshot-v1-8k"
    private const val KIMI_VISION_MODEL = "moonshot-v1-8k-vision-preview"

    const val DEFAULT_PEER_SYSTEM_PROMPT = """
Sen SharkMentor'un samimi, motive edici ve gerçekçi akran mentörüsün (YKS/LGS Peer Mentor).
KESİN DAVRANIŞ VE ÜSLUP KURALLARI (ASLA İHLAL ETME):
1. SIFIR YALAKALIK & GİRİŞ YASAĞI: "Harika bir soru!", "Merhaba! Sana nasıl yardımcı olabilirim?" gibi yapay kurumsal kalıpları KESİNLİKLE KULLANMA. Doğrudan konuya, stratejiye veya cevaba gir.
2. ORANTILI UZUNLUK:
   - Kullanıcı sadece "Selam", "Nasılsın", "Merhaba" yazarsa MAKSİMUM 1 KISA CÜMLE ile cevap ver (Örn: "Selam! Bugün hangi derse odaklanıyoruz?").
   - Standart konu anlatımı ve rehberlik açıklamalarını 4-6 net cümle ile sınırla.
3. SOHBET KANCASI:
   - Yanıtını klişe bitirişlerle ("Başarılar dilerim") kapatma. Öğrenciyi aksiyonda tutacak tek cümlelik doğal bir takip sorusu sor.
"""

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun getDeepSeekApiKey(): String {
        return runCatching {
            BuildConfig.DEEPSEEK_API_KEY
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
        }.getOrDefault("")
    }

    fun getKimiApiKey(): String {
        return runCatching {
            BuildConfig.KIMI_API_KEY
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
        }.getOrDefault("")
    }

    /**
     * Streams tokens emitted incrementally as a Kotlin Flow<String>.
     * Attempts DeepSeek first; if DeepSeek returns 4xx/5xx or connection error, seamlessly falls back to Kimi.
     */
    fun streamChatCompletionFlow(
        systemPrompt: String = DEFAULT_PEER_SYSTEM_PROMPT,
        userPrompt: String,
        chatHistory: List<OpenAiChatMessage> = emptyList(),
        temperature: Float = 0.3f,
        maxTokens: Int = 4096
    ): Flow<String> = flow {
        val messages = buildList {
            add(OpenAiChatMessage(role = "system", content = systemPrompt.trim()))
            // Rolling Context Window: Attach up to last 6 dialogue turns
            addAll(chatHistory.takeLast(6))
            add(OpenAiChatMessage(role = "user", content = userPrompt.trim()))
        }

        var streamedTokensCount = 0
        var deepSeekFailed = false
        var lastException: Throwable? = null

        val deepSeekKey = getDeepSeekApiKey()
        if (deepSeekKey.isNotBlank()) {
            try {
                executeStreamingRequest(
                    url = DEEPSEEK_BASE_URL,
                    apiKey = deepSeekKey,
                    model = DEEPSEEK_MODEL,
                    messages = messages,
                    temperature = temperature,
                    maxTokens = maxTokens
                ) { token ->
                    streamedTokensCount++
                    emit(token)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                Log.w(TAG, "DeepSeek chat completion failed: ${e.message}", e)
                deepSeekFailed = true
                lastException = e
            }
        } else {
            deepSeekFailed = true
            Log.w(TAG, "DEEPSEEK_API_KEY is empty, switching to Kimi fallback.")
        }

        // If DeepSeek failed and NO tokens were streamed yet, fallback to Kimi
        if (deepSeekFailed && streamedTokensCount == 0) {
            val kimiKey = getKimiApiKey()
            if (kimiKey.isNotBlank()) {
                Log.i(TAG, "Falling back to Kimi ($KIMI_MODEL)...")
                try {
                    executeStreamingRequest(
                        url = KIMI_BASE_URL,
                        apiKey = kimiKey,
                        model = KIMI_MODEL,
                        messages = messages,
                        temperature = temperature,
                        maxTokens = maxTokens
                    ) { token ->
                        emit(token)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    Log.e(TAG, "Kimi fallback failed as well: ${e.message}", e)
                    throw lastException ?: e
                }
            } else {
                throw lastException ?: IllegalStateException("Neither DeepSeek nor Kimi API keys are configured.")
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Executes streaming chat completion and delivers accumulated text to `onAccumulatedChunk` callback.
     * Prevents stream resets so partial tokens remain safe on network blips.
     */
    suspend fun streamChatCompletion(
        systemPrompt: String = DEFAULT_PEER_SYSTEM_PROMPT,
        userPrompt: String,
        chatHistory: List<OpenAiChatMessage> = emptyList(),
        temperature: Float = 0.3f,
        maxTokens: Int = 2048,
        onAccumulatedChunk: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val accumulated = StringBuilder()
        try {
            streamChatCompletionFlow(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                chatHistory = chatHistory,
                temperature = temperature,
                maxTokens = maxTokens
            ).collect { token ->
                accumulated.append(token)
                onAccumulatedChunk(accumulated.toString())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Log.w(TAG, "Stream ended with error: ${e.message}. Retaining ${accumulated.length} accumulated chars.")
            if (accumulated.isEmpty()) {
                throw e
            }
        }
        accumulated.toString()
    }

    /**
     * Executes streaming vision completion via Kimi / Moonshot Multimodal API.
     */
    suspend fun streamKimiVisionCompletion(
        systemPrompt: String = DEFAULT_PEER_SYSTEM_PROMPT,
        userPrompt: String,
        base64Image: String,
        mimeType: String = "image/jpeg",
        temperature: Float = 0.2f,
        maxTokens: Int = 4096,
        onAccumulatedChunk: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val kimiKey = getKimiApiKey()
        if (kimiKey.isBlank()) {
            throw IllegalStateException("KIMI_API_KEY is not configured.")
        }
        val accumulated = StringBuilder()
        val userContent = listOf(
            mapOf("type" to "text", "text" to userPrompt.trim()),
            mapOf(
                "type" to "image_url",
                "image_url" to mapOf(
                    "url" to "data:$mimeType;base64,$base64Image"
                )
            )
        )
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt.trim()),
            mapOf("role" to "user", "content" to userContent)
        )
        val requestMap = mapOf(
            "model" to KIMI_VISION_MODEL,
            "messages" to messages,
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "stream" to true
        )
        val jsonString = gson.toJson(requestMap)
        val request = Request.Builder()
            .url(KIMI_BASE_URL)
            .addHeader("Authorization", "Bearer $kimiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(jsonString.toRequestBody(jsonMediaType))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw IllegalStateException("HTTP ${response.code} from Kimi Vision: $errorBody")
        }

        val body = response.body ?: throw IllegalStateException("Empty response body from Kimi Vision")
        body.byteStream().use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val payload = trimmed.substring(5).trim()
                        if (payload == "[DONE]") break
                        if (payload.isNotEmpty()) {
                            val (token, finishReason) = parseDeltaTokenWithFinishReason(payload)
                            if (!token.isNullOrEmpty()) {
                                accumulated.append(token)
                                onAccumulatedChunk(accumulated.toString())
                            }
                            if (finishReason == "stop" || finishReason == "length") break
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
        accumulated.toString()
    }

    private suspend fun executeStreamingRequest(
        url: String,
        apiKey: String,
        model: String,
        messages: List<OpenAiChatMessage>,
        temperature: Float,
        maxTokens: Int,
        onToken: suspend (String) -> Unit
    ) {
        val requestMap = mapOf(
            "model" to model,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "stream" to true
        )
        val jsonString = gson.toJson(requestMap)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(jsonString.toRequestBody(jsonMediaType))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw IllegalStateException("HTTP ${response.code} from $url: $errorBody")
        }

        val body = response.body ?: throw IllegalStateException("Empty response body from $url")
        body.byteStream().use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val payload = trimmed.substring(5).trim()
                        if (payload == "[DONE]") {
                            break
                        }
                        if (payload.isNotEmpty()) {
                            val (token, finishReason) = parseDeltaTokenWithFinishReason(payload)
                            if (!token.isNullOrEmpty()) {
                                onToken(token)
                            }
                            if (finishReason == "stop" || finishReason == "length") {
                                // Full stream reached stop condition
                                break
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
    }

    private fun parseDeltaTokenWithFinishReason(jsonLine: String): Pair<String?, String?> {
        return runCatching {
            val root = gson.fromJson(jsonLine, JsonObject::class.java)
            val choices = root.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val firstChoice = choices[0].asJsonObject
                val finishReason = firstChoice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString
                val delta = firstChoice.getAsJsonObject("delta")
                val content = if (delta != null && delta.has("content")) {
                    val contentElem = delta.get("content")
                    if (!contentElem.isJsonNull) contentElem.asString else null
                } else null
                content to finishReason
            } else null to null
        }.getOrDefault(null to null)
    }

    private fun parseDeltaToken(jsonLine: String): String? {
        return parseDeltaTokenWithFinishReason(jsonLine).first
    }
}
