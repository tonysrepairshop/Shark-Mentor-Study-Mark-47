package com.example.sharkmentor.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.ResponseStoppedException
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.UnknownHostException

enum class AiTaskType(
    val modelName: String,
    val defaultTemperature: Float,
    val defaultMaxOutputTokens: Int,
    val defaultTopP: Float
) {
    LIGHT_INTERACTION(
        modelName = UserAiPreferences.STANDARD_GEMINI_MODEL,
        defaultTemperature = 0.25f,
        defaultMaxOutputTokens = 4096,
        defaultTopP = 0.8f
    ),
    HEAVY_ANALYSIS(
        modelName = UserAiPreferences.STANDARD_GEMINI_MODEL,
        defaultTemperature = 0.45f,
        defaultMaxOutputTokens = 8192,
        defaultTopP = 0.9f
    )
}

data class GeminiSdkResult(
    val text: String,
    val functionCalls: List<FunctionCallPart> = emptyList(),
    val usedKeyIndex: Int = 0,
    val modelUsed: String = UserAiPreferences.STANDARD_GEMINI_MODEL
)

/**
 * Mark 42 Google Generative AI Android SDK (`GenerativeModel`) factory & failover pipeline.
 *
 * - Default built-in keys (`BuildConfig.GEMINI_API_KEY_1/2/3`) strictly run `gemini-3.6-flash`.
 * - User-entered keys in Settings run the user's selected supported model (discovered from AI Studio),
 *   defaulting to `gemini-3.6-flash`.
 */
object GeminiAiFactory {
    private const val TAG = "GeminiAiFactory"

    const val SOLE_GEMINI_MODEL = UserAiPreferences.STANDARD_GEMINI_MODEL
    const val VISION_MODEL = SOLE_GEMINI_MODEL
    const val FAST_MODEL_3_6 = SOLE_GEMINI_MODEL
    const val FALLBACK_MODEL_3_6 = SOLE_GEMINI_MODEL

    val VISION_PRESET_CONFIG: GenerationConfig = generationConfig {
        temperature = 0.1f
        maxOutputTokens = 8192
        topP = 0.8f
    }

    val FAST_PRESET_CONFIG: GenerationConfig = generationConfig {
        temperature = 0.25f
        maxOutputTokens = 4096
        topP = 0.8f
    }

    val DEEP_PRESET_CONFIG: GenerationConfig = generationConfig {
        temperature = 0.45f
        maxOutputTokens = 8192
        topP = 0.9f
    }

    fun resolveModelName(context: Context? = null, forKey: String? = null): String =
        UserAiPreferences.resolveEffectiveGeminiModel(context, forKey)

    fun sanitizeModelName(name: String, context: Context? = null, forKey: String? = null): String {
        val prefs = UserAiPreferences.getOrNull(context)
        val hasCustomUserKeys = prefs?.getParsedUserGeminiKeys()?.isNotEmpty() == true
        if (!hasCustomUserKeys) return SOLE_GEMINI_MODEL
        if (forKey != null) {
            val cleanedForKey = UserAiPreferences.sanitizeKey(forKey)
            val isUserKey = prefs?.getParsedUserGeminiKeys()?.any { it == cleanedForKey } == true
            if (!isUserKey) return SOLE_GEMINI_MODEL
        }
        return name.trim().removePrefix("models/").removePrefix("/").ifBlank { SOLE_GEMINI_MODEL }
    }

    fun resolveCleanApiKey(overrideKey: String? = null, context: Context? = null): String {
        if (context != null) {
            UserAiPreferences.initialize(context)
        }
        val rawKey = if (!overrideKey.isNullOrBlank()) {
            overrideKey
        } else {
            val userFirstKey = runCatching {
                UserAiPreferences.getOrNull(context)?.getParsedUserGeminiKeys()?.firstOrNull()
            }.getOrNull()
            if (!userFirstKey.isNullOrBlank()) {
                ApiKeyRotator.getActiveKey(context).ifBlank { userFirstKey }
            } else {
                try {
                    ApiKeyRotator.getActiveKey(context).ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
                } catch (_: Exception) {
                    BuildConfig.GEMINI_API_KEY.trim()
                }
            }
        }
        return rawKey.replace("\r", "")
            .replace("\n", "")
            .replace("\"", "")
            .replace("'", "")
            .trim()
    }

    fun createModel(
        taskType: AiTaskType,
        systemPrompt: String = "",
        generationConfig: GenerationConfig? = null,
        tools: List<Tool> = emptyList(),
        context: Context? = null,
        overrideKey: String? = null,
        overrideModelName: String? = null
    ): GenerativeModel {
        val cleanKey = resolveCleanApiKey(overrideKey = overrideKey, context = context)
        if (cleanKey.isBlank() || cleanKey == "null") {
            Log.e("GeminiError", "AI Call Failed: API Key is missing or invalid.")
            throw IllegalStateException("Gemini API anahtarı eksik veya geçersiz.")
        }

        val resolvedConfig = generationConfig ?: generationConfig {
            temperature = taskType.defaultTemperature
            maxOutputTokens = taskType.defaultMaxOutputTokens
            topP = taskType.defaultTopP
        }

        val cleanModelName = overrideModelName
            ?.let { sanitizeModelName(it, context, cleanKey) }
            ?: resolveModelName(context, cleanKey)

        return GenerativeModel(
            modelName = cleanModelName,
            apiKey = cleanKey,
            generationConfig = resolvedConfig,
            tools = tools,
            systemInstruction = if (systemPrompt.isNotBlank()) {
                content { text(systemPrompt) }
            } else null,
            requestOptions = RequestOptions(timeout = 45_000L)
        )
    }

    /**
     * Dedicated factory method for Google Gemini Vision requests.
     */
    fun createVisionModel(
        systemPrompt: String = "",
        overrideKey: String? = null,
        context: Context? = null
    ): GenerativeModel {
        val cleanKey = resolveCleanApiKey(overrideKey = overrideKey, context = context)
        if (cleanKey.isBlank() || cleanKey == "null") {
            throw IllegalStateException("Vision işlemi için Gemini API anahtarı bulunamadı.")
        }
        val cleanModelName = resolveModelName(context, cleanKey)
        return GenerativeModel(
            modelName = cleanModelName,
            apiKey = cleanKey,
            generationConfig = VISION_PRESET_CONFIG,
            tools = emptyList(),
            systemInstruction = if (systemPrompt.isNotBlank()) {
                content { text(systemPrompt) }
            } else null,
            requestOptions = RequestOptions(timeout = 60_000L)
        )
    }

    suspend fun generateContentWithFailover(
        context: Context? = null,
        systemInstruction: String = "",
        userPrompt: String,
        chatHistory: List<GeminiChatTurn> = emptyList(),
        bitmaps: List<Bitmap> = emptyList(),
        inlineFiles: List<Pair<String, ByteArray>> = emptyList(),
        tools: List<Tool> = emptyList(),
        responseJson: Boolean = false,
        temperature: Float? = null,
        maxOutputTokens: Int = 8192,
        topP: Float? = null,
        timeoutMs: Long = 60_000L
    ): Result<GeminiSdkResult> = withContext(Dispatchers.IO) {
        executeSdkWithFailover(
            context = context,
            stream = false,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            chatHistory = chatHistory,
            bitmaps = bitmaps,
            inlineFiles = inlineFiles,
            tools = tools,
            responseJson = responseJson,
            temperature = temperature,
            maxOutputTokens = maxOutputTokens,
            topP = topP,
            timeoutMs = timeoutMs,
            onPartialText = {}
        )
    }

    suspend fun streamContentWithFailover(
        context: Context? = null,
        systemInstruction: String = "",
        userPrompt: String,
        chatHistory: List<GeminiChatTurn> = emptyList(),
        bitmaps: List<Bitmap> = emptyList(),
        inlineFiles: List<Pair<String, ByteArray>> = emptyList(),
        tools: List<Tool> = emptyList(),
        responseJson: Boolean = false,
        temperature: Float? = null,
        maxOutputTokens: Int = 8192,
        topP: Float? = null,
        timeoutMs: Long = 60_000L,
        onPartialText: (String) -> Unit = {}
    ): Result<GeminiSdkResult> = withContext(Dispatchers.IO) {
        executeSdkWithFailover(
            context = context,
            stream = true,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            chatHistory = chatHistory,
            bitmaps = bitmaps,
            inlineFiles = inlineFiles,
            tools = tools,
            responseJson = responseJson,
            temperature = temperature,
            maxOutputTokens = maxOutputTokens,
            topP = topP,
            timeoutMs = timeoutMs,
            onPartialText = onPartialText
        )
    }

    private suspend fun executeSdkWithFailover(
        context: Context?,
        stream: Boolean,
        systemInstruction: String,
        userPrompt: String,
        chatHistory: List<GeminiChatTurn>,
        bitmaps: List<Bitmap>,
        inlineFiles: List<Pair<String, ByteArray>>,
        tools: List<Tool>,
        responseJson: Boolean,
        temperature: Float?,
        maxOutputTokens: Int,
        topP: Float?,
        timeoutMs: Long,
        onPartialText: (String) -> Unit
    ): Result<GeminiSdkResult> {
        if (context != null) {
            UserAiPreferences.initialize(context)
        }
        val keys = ApiKeyRotator.getAllEffectiveKeys(context)
            .map { UserAiPreferences.sanitizeKey(it) }
            .filter { it.isNotBlank() && it != "null" }
            .distinct()

        if (keys.isEmpty()) {
            return Result.failure(
                IllegalStateException("Gemini API anahtarı tanımlı değil. Ayarlardan API anahtarı ekleyin.")
            )
        }

        val resolvedTemp = temperature ?: if (responseJson) 0.2f else if (bitmaps.isNotEmpty()) 0.1f else 0.35f
        val resolvedTopP = topP ?: if (responseJson) 0.8f else 0.85f
        val genConfig = generationConfig {
            this.temperature = resolvedTemp
            this.maxOutputTokens = maxOutputTokens.coerceIn(256, 32768)
            this.topP = resolvedTopP
            if (responseJson) {
                responseMimeType = "application/json"
            }
        }

        val tempScaledBitmaps = mutableListOf<Bitmap>()
        try {
            val preparedBitmaps = bitmaps.mapNotNull { bmp ->
                if (bmp.isRecycled) return@mapNotNull null
                val maxDim = maxOf(bmp.width, bmp.height)
                if (maxDim > 1400) {
                    val scale = 1400f / maxDim.toFloat()
                    val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                    val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                    runCatching {
                        Bitmap.createScaledBitmap(bmp, w, h, true).also { tempScaledBitmaps.add(it) }
                    }.getOrDefault(bmp)
                } else {
                    bmp
                }
            }

            val contents = buildContentsList(
                chatHistory = chatHistory,
                userPrompt = userPrompt,
                bitmaps = preparedBitmaps,
                inlineFiles = inlineFiles
            )
            val contentsArray = contents.toTypedArray()

            val startIdx = ApiKeyRotator.getStartIndex(context).coerceAtLeast(0) % keys.size
            var lastException: Throwable? = null
            val failureNotes = mutableListOf<String>()

            for (offset in keys.indices) {
                val keyIndex = (startIdx + offset) % keys.size
                val activeKey = keys[keyIndex]
                val masked = maskKey(activeKey)
                val modelName = UserAiPreferences.resolveEffectiveGeminiModel(context, activeKey)

                val maxRetries = 2
                var retryDelayMs = 600L

                for (retry in 0 until maxRetries) {
                    try {
                        val model = GenerativeModel(
                            modelName = modelName,
                            apiKey = activeKey,
                            generationConfig = genConfig,
                            tools = tools,
                            systemInstruction = if (systemInstruction.isNotBlank()) {
                                content { text(systemInstruction.trim()) }
                            } else null,
                            requestOptions = RequestOptions(timeout = timeoutMs.coerceAtLeast(15_000L))
                        )

                        if (!stream) {
                            val response = model.generateContent(*contentsArray)
                            val rawText = runCatching { response.text }.getOrNull().orEmpty()
                            val calls = runCatching { response.functionCalls }.getOrDefault(emptyList())
                            val finalText = if (responseJson) {
                                GeminiRestClient.extractCleanJsonString(rawText)
                            } else {
                                rawText.trim()
                            }
                            if (finalText.isBlank() && calls.isEmpty()) {
                                throw IllegalStateException("Model boş yanıt döndürdü ($modelName).")
                            }
                            if (finalText.isNotBlank()) {
                                onPartialText(finalText)
                            }
                            ApiKeyRotator.markCurrentIndex(keyIndex)
                            return Result.success(
                                GeminiSdkResult(
                                    text = finalText,
                                    functionCalls = calls,
                                    usedKeyIndex = keyIndex,
                                    modelUsed = modelName
                                )
                            )
                        } else {
                            val accumulated = StringBuilder()
                            val collectedCalls = mutableListOf<FunctionCallPart>()
                            val responseFlow = model.generateContentStream(*contentsArray)

                            try {
                                responseFlow.collect { chunk ->
                                    val chunkText = runCatching { chunk.text }.getOrNull()
                                    if (!chunkText.isNullOrEmpty()) {
                                        accumulated.append(chunkText)
                                        if (!responseJson) {
                                            onPartialText(accumulated.toString())
                                        }
                                    }
                                    val chunkCalls = runCatching { chunk.functionCalls }.getOrDefault(emptyList())
                                    if (chunkCalls.isNotEmpty()) {
                                        collectedCalls.addAll(chunkCalls)
                                    }
                                }
                            } catch (rse: ResponseStoppedException) {
                                Log.w(TAG, "Stream stopped early (${rse.message}), accumulated=${accumulated.length}")
                                if (accumulated.isBlank() && collectedCalls.isEmpty()) {
                                    throw rse
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (streamErr: Throwable) {
                                if (accumulated.length > 50) {
                                    Log.w(TAG, "Stream interrupted after ${accumulated.length} chars, preserving partial text: ${streamErr.message}")
                                } else {
                                    throw streamErr
                                }
                            }

                            val rawText = accumulated.toString()
                            val finalText = if (responseJson) {
                                GeminiRestClient.extractCleanJsonString(rawText)
                            } else {
                                rawText.trim()
                            }
                            if (finalText.isBlank() && collectedCalls.isEmpty()) {
                                throw IllegalStateException("Model boş yanıt döndürdü ($modelName).")
                            }
                            if (finalText.isNotBlank()) {
                                onPartialText(finalText)
                            }
                            ApiKeyRotator.markCurrentIndex(keyIndex)
                            return Result.success(
                                GeminiSdkResult(
                                    text = finalText,
                                    functionCalls = collectedCalls,
                                    usedKeyIndex = keyIndex,
                                    modelUsed = modelName
                                )
                            )
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        lastException = e
                        if (isOfflineNetworkError(e)) {
                            Log.e(TAG, "Offline/DNS error on key #$keyIndex ($masked): ${e.message}")
                            return Result.failure(
                                Exception("İnternet bağlantısı kurulamadı. Lütfen ağ bağlantınızı kontrol edin.")
                            )
                        }

                        val isUnavailable = isTransientServerUnavailable(e)
                        val shouldRotate = ApiKeyRotator.shouldRotateKey(e) ||
                            e is QuotaExceededException ||
                            e is InvalidAPIKeyException

                        Log.w(
                            TAG,
                            "SDK call failed on $modelName (key #${keyIndex + 1}/${keys.size} $masked, retry=${retry + 1}): ${e.javaClass.simpleName} - ${e.message}"
                        )

                        if (isUnavailable && retry + 1 < maxRetries) {
                            delay(retryDelayMs)
                            retryDelayMs *= 2
                            continue
                        }

                        failureNotes.add("#${keyIndex + 1} ($masked • $modelName): ${formatError(e)}")
                        if (!shouldRotate && !isUnavailable) {
                            // Still allow cycling remaining keys on non-offline errors unless exhausted
                            break
                        }
                        break
                    }
                }

                if (offset + 1 < keys.size) {
                    ApiKeyRotator.rotateToNextKey(context)
                    delay(150L)
                }
            }

            val summary = buildFailureMessage(lastException, failureNotes)
            return Result.failure(Exception(summary, lastException))
        } finally {
            tempScaledBitmaps.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
    }

    private fun buildContentsList(
        chatHistory: List<GeminiChatTurn>,
        userPrompt: String,
        bitmaps: List<Bitmap>,
        inlineFiles: List<Pair<String, ByteArray>>
    ): List<Content> {
        val mergedHistory = mutableListOf<Pair<String, StringBuilder>>()
        for (turn in chatHistory) {
            val txt = turn.text.trim()
            if (txt.isBlank()) continue
            val role = if (turn.role.equals("model", ignoreCase = true) ||
                turn.role.equals("assistant", ignoreCase = true) ||
                turn.role.equals("ai", ignoreCase = true)
            ) "model" else "user"

            val last = mergedHistory.lastOrNull()
            if (last != null && last.first == role) {
                last.second.append("\n\n").append(txt)
            } else {
                mergedHistory.add(role to StringBuilder(txt))
            }
        }

        while (mergedHistory.firstOrNull()?.first == "model") {
            mergedHistory.removeAt(0)
        }

        val effectivePrompt = userPrompt.trim().ifBlank { "Devam et." }
        val lastIsUser = mergedHistory.lastOrNull()?.first == "user"
        val trailingUserPrefix = if (lastIsUser) {
            mergedHistory.removeAt(mergedHistory.lastIndex).second.toString() + "\n\n"
        } else {
            ""
        }

        val result = mutableListOf<Content>()
        for ((role, sb) in mergedHistory) {
            result.add(
                content(role = role) {
                    text(sb.toString())
                }
            )
        }

        result.add(
            content(role = "user") {
                for (bmp in bitmaps) {
                    if (!bmp.isRecycled) {
                        image(bmp)
                    }
                }
                for ((mimeType, bytes) in inlineFiles) {
                    if (bytes.isNotEmpty()) {
                        blob(mimeType.ifBlank { "application/octet-stream" }, bytes)
                    }
                }
                text(trailingUserPrefix + effectivePrompt)
            }
        )
        return result
    }

    private fun isOfflineNetworkError(t: Throwable): Boolean {
        if (t is UnknownHostException || t is ConnectException) return true
        val msg = "${t.javaClass.simpleName} ${t.message} ${t.cause?.message}".lowercase()
        return msg.contains("enetunreach") ||
            msg.contains("network is unreachable") ||
            msg.contains("no address associated with hostname") ||
            msg.contains("unknownhostexception") ||
            msg.contains("failed to connect")
    }

    private fun isTransientServerUnavailable(t: Throwable): Boolean {
        val msg = "${t.javaClass.simpleName} ${t.message} ${t.cause?.message}".lowercase()
        return msg.contains("503") ||
            msg.contains("high demand") ||
            msg.contains("unavailable") ||
            msg.contains("overload") ||
            msg.contains("timeout")
    }

    private fun formatError(t: Throwable?): String {
        if (t == null) return "Bilinmeyen hata"
        return (t.localizedMessage ?: t.message ?: t.javaClass.simpleName).take(160)
    }

    private fun buildFailureMessage(lastException: Throwable?, notes: List<String>): String {
        val raw = "${lastException?.message} ${lastException?.cause?.message}".lowercase()
        val detail = notes.lastOrNull().orEmpty()
        return when {
            lastException is InvalidAPIKeyException || raw.contains("401") || raw.contains("403") || raw.contains("api_key") ->
                "API anahtarı doğrulama hatası. Ayarlardan anahtarınızı kontrol edin. [$detail]"
            lastException is QuotaExceededException || raw.contains("429") || raw.contains("quota") ->
                "API kota limiti doldu (429). Kısa süre sonra tekrar deneyin veya yeni bir anahtar ekleyin."
            raw.contains("503") || raw.contains("unavailable") ->
                "Yapay zeka sunucularında anlık yoğunluk yaşanıyor (503). Lütfen biraz sonra tekrar deneyin."
            else -> lastException?.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Gemini API bağlantısı kurulamadı."
        }
    }

    private fun maskKey(key: String): String {
        val clean = UserAiPreferences.sanitizeKey(key)
        return if (clean.length <= 8) "***" else "${clean.take(3)}...${clean.takeLast(4)}"
    }
}
