package com.example.sharkmentor.core.ai

import android.content.Context
import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
 * - DeepSeek (`deepseek-chat`): Peer chat, "Detaylı" (PRO) deep topic synthesis/explanations, short-medium note summaries.
 * - Kimi / Moonshot (`moonshot-v1-32k` / `moonshot-v1-8k`): Long text/document context summaries and immediate fallback on DeepSeek error/quota.
 */
object DeepSeekClient {
    private const val TAG = "DeepSeekClient"

    private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1/chat/completions"
    private const val DEEPSEEK_MODEL = "deepseek-chat"

    private const val KIMI_BASE_URL = "https://api.moonshot.cn/v1/chat/completions"
    private const val KIMI_MODEL = "moonshot-v1-8k"
    private const val KIMI_LONG_CONTEXT_MODEL = "moonshot-v1-32k"
    private const val KIMI_VISION_MODEL = "moonshot-v1-8k-vision-preview"

    private const val LONG_CONTEXT_CHAR_THRESHOLD = 4200

    const val DEFAULT_PEER_SYSTEM_PROMPT = """
Sen SharkMentor'un samimi, motive edici ve gerçekçi akran mentörüsün (YKS/LGS Peer Mentor).
KESİN DAVRANIŞ VE ÜSLUP KURALLARI (ASLA İHLAL ETME):
1. SIFIR YALAKALIK & GİRİŞ YASAĞI: "Harika bir soru!", "Merhaba! Sana nasıl yardımcı olabilirim?" gibi yapay kurumsal kalıpları KESİNLİKLE KULLANMA. Doğrudan konuya, stratejiye veya cevaba gir.
2. ORANTILI UZUNLUK:
   - Kullanıcı sadece "Selam", "Nasılsın", "Merhaba" yazarsa MAKSİMUM 1 KISA CÜMLE ile cevap ver (Örn: "Selam! Bugün hangi derse odaklanıyoruz?").
   - Standart konu anlatımı ve rehberlik açıklamalarını net, anlaşılır ve kotayı boşa harcamayacak yoğunlukta tut.
3. SOHBET KANCASI:
   - Yanıtını klişe bitirişlerle ("Başarılar dilerim") kapatma. Öğrenciyi aksiyonda tutan tek cümlelik doğal bir takip sorusu sor.
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

    fun getDeepSeekApiKey(): String = getDeepSeekApiKey(null)

    fun getDeepSeekApiKey(context: Context?): String {
        val userKey = runCatching {
            UserAiPreferences.getOrNull(context)?.getUserDeepSeekKey()
        }.getOrNull()
        if (!userKey.isNullOrBlank()) {
            return userKey
        }
        return runCatching {
            BuildConfig.DEEPSEEK_API_KEY
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
        }.getOrDefault("")
    }

    fun getKimiApiKey(): String = getKimiApiKey(null)

    fun getKimiApiKey(context: Context?): String {
        val userKey = runCatching {
            UserAiPreferences.getOrNull(context)?.getUserKimiKey()
        }.getOrNull()
        if (!userKey.isNullOrBlank()) {
            return userKey
        }
        return runCatching {
            BuildConfig.KIMI_API_KEY
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
        }.getOrDefault("")
    }

    fun getGrokApiKey(context: Context? = null): String =
        MultiProviderAiClient.getGrokApiKey(context)

    fun hasAnyOpenAiProviderKey(context: Context? = null): Boolean =
        getDeepSeekApiKey(context).isNotBlank() ||
            getKimiApiKey(context).isNotBlank() ||
            getGrokApiKey(context).isNotBlank()

    fun isLongContextPrompt(userPrompt: String, systemPrompt: String = ""): Boolean {
        val lower = userPrompt.lowercase()
        val hasLongDocIntent = lower.contains("uzun metin") ||
            lower.contains("belge özeti") ||
            lower.contains("doküman özeti") ||
            lower.contains("makale özeti") ||
            lower.contains("tüm metni özetle")
        return userPrompt.length >= LONG_CONTEXT_CHAR_THRESHOLD ||
            (userPrompt.length + systemPrompt.length) >= (LONG_CONTEXT_CHAR_THRESHOLD * 2) ||
            (hasLongDocIntent && userPrompt.length >= 1200)
    }

    /**
     * Streams tokens emitted incrementally as a Kotlin Flow<String>.
     * Honors `UserAiPreferences.selectedAiProvider` (`CLAUDE`, `OPENAI`, `GROK`, `DEEPSEEK`, `KIMI`, or `AUTO`)
     * and the user's selected model for each provider, with automatic fallback between configured external providers.
     */
    fun streamChatCompletionFlow(
        systemPrompt: String = DEFAULT_PEER_SYSTEM_PROMPT,
        userPrompt: String,
        chatHistory: List<OpenAiChatMessage> = emptyList(),
        temperature: Float = 0.3f,
        maxTokens: Int = 4096,
        preferKimiLongContext: Boolean = false,
        context: Context? = null
    ): Flow<String> = channelFlow {
        val prefs = runCatching { UserAiPreferences.getOrNull(context) }.getOrNull()
        val selectedProvider = prefs?.getSelectedProvider() ?: AiProviderChoice.AUTO

        val candidates: List<AiProviderChoice> = when (selectedProvider) {
            AiProviderChoice.CLAUDE -> listOf(
                AiProviderChoice.CLAUDE,
                AiProviderChoice.OPENAI,
                AiProviderChoice.GROK,
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.KIMI
            )
            AiProviderChoice.OPENAI -> listOf(
                AiProviderChoice.OPENAI,
                AiProviderChoice.GROK,
                AiProviderChoice.CLAUDE,
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.KIMI
            )
            AiProviderChoice.GROK -> listOf(
                AiProviderChoice.GROK,
                AiProviderChoice.OPENAI,
                AiProviderChoice.CLAUDE,
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.KIMI
            )
            AiProviderChoice.KIMI -> listOf(
                AiProviderChoice.KIMI,
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.GROK,
                AiProviderChoice.CLAUDE,
                AiProviderChoice.OPENAI
            )
            AiProviderChoice.DEEPSEEK -> listOf(
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.KIMI,
                AiProviderChoice.GROK,
                AiProviderChoice.CLAUDE,
                AiProviderChoice.OPENAI
            )
            AiProviderChoice.AUTO, AiProviderChoice.GEMINI -> {
                if (preferKimiLongContext || isLongContextPrompt(userPrompt, systemPrompt)) {
                    listOf(
                        AiProviderChoice.KIMI,
                        AiProviderChoice.CLAUDE,
                        AiProviderChoice.OPENAI,
                        AiProviderChoice.GROK,
                        AiProviderChoice.DEEPSEEK
                    )
                } else {
                    listOf(
                        AiProviderChoice.CLAUDE,
                        AiProviderChoice.OPENAI,
                        AiProviderChoice.GROK,
                        AiProviderChoice.DEEPSEEK,
                        AiProviderChoice.KIMI
                    )
                }
            }
        }.filter { candidate ->
            when (candidate) {
                AiProviderChoice.CLAUDE -> MultiProviderAiClient.getClaudeApiKey(context).isNotBlank()
                AiProviderChoice.OPENAI -> MultiProviderAiClient.getOpenAiApiKey(context).isNotBlank()
                AiProviderChoice.GROK -> getGrokApiKey(context).isNotBlank()
                AiProviderChoice.DEEPSEEK -> getDeepSeekApiKey(context).isNotBlank()
                AiProviderChoice.KIMI -> getKimiApiKey(context).isNotBlank()
                AiProviderChoice.AUTO, AiProviderChoice.GEMINI -> false
            }
        }

        if (candidates.isEmpty()) {
            throw IllegalStateException("Harici AI sağlayıcı anahtarı yapılandırılmamış.")
        }

        var emittedAny = false
        var lastException: Throwable? = null

        for (provider in candidates) {
            var lastLength = 0
            try {
                MultiProviderAiClient.streamChatCompletion(
                    provider = provider,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    chatHistory = chatHistory,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    context = context,
                    onAccumulatedChunk = { accumulated ->
                        if (accumulated.length > lastLength) {
                            val delta = accumulated.substring(lastLength)
                            lastLength = accumulated.length
                            if (delta.isNotEmpty()) {
                                emittedAny = true
                                trySend(delta)
                            }
                        }
                    }
                )
                return@channelFlow
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                Log.w(TAG, "${provider.shortLabel} completion failed: ${e.message}", e)
                lastException = e
                if (emittedAny) {
                    return@channelFlow
                }
            }
        }

        if (!emittedAny) {
            throw (lastException ?: IllegalStateException("Seçili AI sağlayıcıları yanıt vermedi."))
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
        preferKimiLongContext: Boolean = false,
        context: Context? = null,
        onAccumulatedChunk: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val prefs = runCatching { UserAiPreferences.getOrNull(context) }.getOrNull()
        val selectedProvider = prefs?.getSelectedProvider() ?: AiProviderChoice.AUTO
        val candidates: List<AiProviderChoice> = when (selectedProvider) {
            AiProviderChoice.CLAUDE -> listOf(
                AiProviderChoice.CLAUDE,
                AiProviderChoice.OPENAI,
                AiProviderChoice.GROK,
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.KIMI
            )
            AiProviderChoice.OPENAI -> listOf(
                AiProviderChoice.OPENAI,
                AiProviderChoice.GROK,
                AiProviderChoice.CLAUDE,
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.KIMI
            )
            AiProviderChoice.GROK -> listOf(
                AiProviderChoice.GROK,
                AiProviderChoice.OPENAI,
                AiProviderChoice.CLAUDE,
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.KIMI
            )
            AiProviderChoice.KIMI -> listOf(
                AiProviderChoice.KIMI,
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.GROK,
                AiProviderChoice.CLAUDE,
                AiProviderChoice.OPENAI
            )
            AiProviderChoice.DEEPSEEK -> listOf(
                AiProviderChoice.DEEPSEEK,
                AiProviderChoice.KIMI,
                AiProviderChoice.GROK,
                AiProviderChoice.CLAUDE,
                AiProviderChoice.OPENAI
            )
            AiProviderChoice.AUTO, AiProviderChoice.GEMINI -> {
                if (preferKimiLongContext || isLongContextPrompt(userPrompt, systemPrompt)) {
                    listOf(
                        AiProviderChoice.KIMI,
                        AiProviderChoice.CLAUDE,
                        AiProviderChoice.OPENAI,
                        AiProviderChoice.GROK,
                        AiProviderChoice.DEEPSEEK
                    )
                } else {
                    listOf(
                        AiProviderChoice.CLAUDE,
                        AiProviderChoice.OPENAI,
                        AiProviderChoice.GROK,
                        AiProviderChoice.DEEPSEEK,
                        AiProviderChoice.KIMI
                    )
                }
            }
        }.filter { candidate ->
            when (candidate) {
                AiProviderChoice.CLAUDE -> MultiProviderAiClient.getClaudeApiKey(context).isNotBlank()
                AiProviderChoice.OPENAI -> MultiProviderAiClient.getOpenAiApiKey(context).isNotBlank()
                AiProviderChoice.GROK -> getGrokApiKey(context).isNotBlank()
                AiProviderChoice.DEEPSEEK -> getDeepSeekApiKey(context).isNotBlank()
                AiProviderChoice.KIMI -> getKimiApiKey(context).isNotBlank()
                AiProviderChoice.AUTO, AiProviderChoice.GEMINI -> false
            }
        }

        if (candidates.isEmpty()) {
            throw IllegalStateException("Harici AI sağlayıcı anahtarı yapılandırılmamış.")
        }

        var lastException: Throwable? = null
        var lastPartial = ""
        for (provider in candidates) {
            try {
                lastPartial = ""
                val full = MultiProviderAiClient.streamChatCompletion(
                    provider = provider,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    chatHistory = chatHistory,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    context = context,
                    onAccumulatedChunk = { partial ->
                        lastPartial = partial
                        onAccumulatedChunk(partial)
                    }
                )
                if (full.isNotBlank()) return@withContext full
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                Log.w(TAG, "${provider.shortLabel} stream failed: ${e.message}", e)
                if (lastPartial.isNotEmpty()) return@withContext lastPartial
                lastException = e
            }
        }

        throw (lastException ?: IllegalStateException("Seçili AI sağlayıcıları yanıt vermedi."))
    }

    /**
     * Summarizes short-to-medium study notes via DeepSeek or long documents via Kimi.
     */
    suspend fun summarizeTextContent(
        rawContent: String,
        isDetailed: Boolean = false,
        context: Context? = null,
        onAccumulatedChunk: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val isLongDocument = rawContent.length >= LONG_CONTEXT_CHAR_THRESHOLD
            val sysPrompt = if (isDetailed) {
                "Sen SharkMentor Akademik Sentez Motorusun. Verilen ders notunu veya belgeyi kavramsal çerçevesi, kritik formül/kuralları ve ÖSYM/MEB sınav tuzaklarıyla adım adım derinlemesine özetle. Dil: Türkçe."
            } else {
                "Sen SharkMentor Hızlı Özet Motorusun. Verilen metni en kritik 4-5 vurucu madde halinde sıfır laf kalabalığıyla özetle. Dil: Türkçe."
            }
            streamChatCompletion(
                systemPrompt = sysPrompt,
                userPrompt = rawContent,
                temperature = if (isDetailed) 0.35f else 0.2f,
                maxTokens = if (isDetailed) 3072 else 1024,
                preferKimiLongContext = isLongDocument,
                context = context,
                onAccumulatedChunk = onAccumulatedChunk
            )
        }
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
        context: Context? = null,
        onAccumulatedChunk: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        MultiProviderAiClient.streamChatCompletion(
            provider = AiProviderChoice.KIMI,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = temperature,
            maxTokens = maxTokens,
            imageBase64 = base64Image,
            imageMimeType = mimeType,
            context = context,
            onAccumulatedChunk = onAccumulatedChunk
        )
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
