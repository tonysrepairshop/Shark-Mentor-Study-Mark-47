package com.example.sharkmentor.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

enum class AiTaskType(
    val modelName: String,
    val defaultTemperature: Float,
    val defaultMaxOutputTokens: Int,
    val defaultTopP: Float
) {
    LIGHT_INTERACTION(
        modelName = GeminiAiFactory.SOLE_GEMINI_MODEL,
        defaultTemperature = 0.25f,
        defaultMaxOutputTokens = 4096,
        defaultTopP = 0.8f
    ),
    HEAVY_ANALYSIS(
        modelName = GeminiAiFactory.SOLE_GEMINI_MODEL,
        defaultTemperature = 0.45f,
        defaultMaxOutputTokens = 8192,
        defaultTopP = 0.9f
    );

    fun toGenerationConfig(): GeminiGenerationConfig = GeminiGenerationConfig(
        temperature = defaultTemperature,
        maxOutputTokens = defaultMaxOutputTokens,
        topP = defaultTopP
    )
}

data class GeminiSdkResult(
    val text: String,
    val functionCalls: List<GeminiFunctionCall> = emptyList(),
    val usedKeyIndex: Int = 0,
    val modelUsed: String = GeminiAiFactory.SOLE_GEMINI_MODEL
)

/**
 * Pure OkHttp + Gson Gemini execution & failover pipeline.
 *
 * - Default built-in keys (`BuildConfig.GEMINI_API_KEY_1/2/3`) strictly run `gemini-3.6-flash`
 *   (with controlled 404 fallback to `gemini-2.5-flash` only if 3.6 returns 404 across both
 *   Vertex AI Express & AI Studio across all built-in keys).
 * - User-entered keys run the user's selected supported model, falling back to built-in keys
 *   on `gemini-3.6-flash` if custom keys exhaust quota or fail.
 */
object GeminiAiFactory {
    const val SOLE_GEMINI_MODEL = UserAiPreferences.BUILTIN_DEFAULT_MODEL
    const val CONTROLLED_404_FALLBACK_MODEL = UserAiPreferences.CONTROLLED_404_FALLBACK_MODEL
    const val VISION_MODEL = SOLE_GEMINI_MODEL
    const val FAST_MODEL_3_6 = SOLE_GEMINI_MODEL
    const val FALLBACK_MODEL_3_6 = SOLE_GEMINI_MODEL

    val VISION_PRESET_CONFIG = GeminiGenerationConfig(
        temperature = 0.1f,
        maxOutputTokens = 8192,
        topP = 0.8f
    )

    val FAST_PRESET_CONFIG = GeminiGenerationConfig(
        temperature = 0.25f,
        maxOutputTokens = 4096,
        topP = 0.8f
    )

    val DEEP_PRESET_CONFIG = GeminiGenerationConfig(
        temperature = 0.45f,
        maxOutputTokens = 8192,
        topP = 0.9f
    )

    private const val TAG = "GeminiAiFactory"

    private fun safeLogW(msg: String, t: Throwable? = null) {
        try {
            if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
        } catch (_: Throwable) {
            // Pure-JVM unit test fallback
        }
    }

    private fun safeLogE(msg: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        } catch (_: Throwable) {
            // Pure-JVM unit test fallback
        }
    }

    fun sanitizeModelName(name: String? = null): String {
        return UserAiPreferences.resolveEffectiveModel(name)
    }

    fun resolveModelName(context: Context? = null, forKey: String? = null): String =
        UserAiPreferences.resolveEffectiveGeminiModel(context, forKey)

    fun resolveCleanApiKey(overrideKey: String? = null, context: Context? = null): String {
        if (context != null) {
            UserAiPreferences.initialize(context)
        }
        val rawKey = when {
            !overrideKey.isNullOrBlank() -> overrideKey
            UserAiPreferences.hasCustomKey() ->
                UserAiPreferences.getParsedUserGeminiKeys().firstOrNull() ?: UserAiPreferences.getCustomApiKey()
            else -> runCatching { ApiKeyRotator.getActiveKey(context) }.getOrDefault("")
        }
        return GeminiRestClient.sanitizeApiKey(rawKey)
    }

    internal fun resolveKeyCandidates(overrideKey: String? = null, context: Context? = null): Pair<List<String>, Boolean> {
        if (!overrideKey.isNullOrBlank()) {
            val parsedOverride = UserAiPreferences.parseGeminiKeyList(overrideKey)
            return if (parsedOverride.isNotEmpty()) parsedOverride to false else emptyList<String>() to false
        }
        val effective = ApiKeyRotator.getAllEffectiveKeys(context)
        val hasCustom = UserAiPreferences.hasCustomUserGeminiKeys(context)
        if (effective.isEmpty()) {
            val fallback = resolveCleanApiKey(null, context)
            return if (fallback.isNotBlank()) listOf(fallback) to !hasCustom else emptyList<String>() to !hasCustom
        }
        val startIdx = ApiKeyRotator.getStartIndex(context).coerceAtLeast(0) % effective.size
        val ordered = List(effective.size) { offset ->
            effective[(startIdx + offset) % effective.size]
        }
        return ordered to !hasCustom
    }

    fun bytesToInlineData(
        bytes: ByteArray,
        mimeType: String = "application/pdf"
    ): GeminiPart.InlineData {
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return GeminiPart.InlineData(
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            base64Data = base64
        )
    }

    fun bitmapToInlineData(
        bitmap: Bitmap,
        jpegQuality: Int = 82
    ): GeminiPart.InlineData {
        if (bitmap.isRecycled) {
            throw IllegalStateException("Görsel bellekte serbest bırakılmış (recycled Bitmap).")
        }
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(40, 95), baos)
        val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())
        return GeminiPart.InlineData(mimeType = "image/jpeg", base64Data = base64)
    }

    suspend fun generateContent(
        contents: List<GeminiContent>,
        systemPrompt: String = "",
        taskType: AiTaskType = AiTaskType.LIGHT_INTERACTION,
        generationConfig: GeminiGenerationConfig? = null,
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        overrideKey: String? = null,
        overrideModel: String? = null
    ): GeminiResponse = withContext(Dispatchers.IO) {
        val (keys, isBuiltInMode) = resolveKeyCandidates(overrideKey, null)
        if (keys.isEmpty()) {
            throw GeminiApiException(
                message = "Gemini API anahtarı bulunamadı. Ayarlar'dan kendi anahtarınızı ekleyebilirsiniz.",
                httpCode = 401,
                apiStatus = "UNAUTHENTICATED"
            )
        }

        val resolvedConfig = generationConfig ?: taskType.toGenerationConfig()
        val customKeysSet = UserAiPreferences.getParsedUserGeminiKeys().toSet()

        val primaryAttempt = tryExecuteKeys(
            keys = keys,
            modelResolver = { key ->
                when {
                    !overrideModel.isNullOrBlank() && (!overrideKey.isNullOrBlank() || key in customKeysSet) ->
                        GeminiRestClient.sanitizeModelName(overrideModel)
                    key in customKeysSet ->
                        UserAiPreferences.resolveEffectiveGeminiModel(null, key)
                    else ->
                        SOLE_GEMINI_MODEL
                }
            },
            contents = contents,
            systemPrompt = systemPrompt,
            generationConfig = resolvedConfig,
            tools = tools
        )
        primaryAttempt.response?.let { return@withContext it }

        val primaryError = primaryAttempt.lastError!!
        val builtInKeys = keys.filter { it !in customKeysSet }
        val hasGenuine404 = primaryAttempt.genuine404Error != null || isGenuine404Failure(primaryError)
        if (builtInKeys.isNotEmpty() && (isBuiltInMode || primaryAttempt.triedBuiltInKeys) && hasGenuine404) {
            safeLogW(
                "$SOLE_GEMINI_MODEL returned 404 across built-in endpoints/keys. Trying controlled fallback $CONTROLLED_404_FALLBACK_MODEL..."
            )
            val fallbackAttempt = tryExecuteKeys(
                keys = builtInKeys,
                modelResolver = { CONTROLLED_404_FALLBACK_MODEL },
                contents = contents,
                systemPrompt = systemPrompt,
                generationConfig = resolvedConfig,
                tools = tools
            )
            fallbackAttempt.response?.let { return@withContext it }
            val fallbackErr = fallbackAttempt.lastError
            throw GeminiApiException(
                message = "Model $SOLE_GEMINI_MODEL ve yedek $CONTROLLED_404_FALLBACK_MODEL erişilemedi: ${fallbackErr?.localizedMessage ?: primaryError.localizedMessage}",
                httpCode = (fallbackErr as? GeminiApiException)?.httpCode ?: 404,
                modelName = CONTROLLED_404_FALLBACK_MODEL,
                cause = fallbackErr ?: primaryError
            )
        }

        throw primaryError
    }

    suspend fun generateText(
        prompt: String,
        systemPrompt: String = "",
        taskType: AiTaskType = AiTaskType.LIGHT_INTERACTION,
        generationConfig: GeminiGenerationConfig? = null,
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        overrideKey: String? = null,
        overrideModel: String? = null
    ): String {
        val response = generateContent(
            contents = listOf(GeminiContent.userText(prompt)),
            systemPrompt = systemPrompt,
            taskType = taskType,
            generationConfig = generationConfig,
            tools = tools,
            overrideKey = overrideKey,
            overrideModel = overrideModel
        )
        return response.text
    }

    suspend fun generateWithImages(
        prompt: String,
        bitmaps: List<Bitmap> = emptyList(),
        inlineDataList: List<GeminiPart.InlineData> = emptyList(),
        systemPrompt: String = "",
        taskType: AiTaskType = AiTaskType.HEAVY_ANALYSIS,
        generationConfig: GeminiGenerationConfig? = VISION_PRESET_CONFIG,
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        overrideKey: String? = null,
        overrideModel: String? = null
    ): String {
        val parts = buildList {
            bitmaps.forEach { if (!it.isRecycled) add(bitmapToInlineData(it)) }
            addAll(inlineDataList)
            add(GeminiPart.Text(prompt))
        }
        val response = generateContent(
            contents = listOf(GeminiContent(role = "user", parts = parts)),
            systemPrompt = systemPrompt,
            taskType = taskType,
            generationConfig = generationConfig,
            tools = tools,
            overrideKey = overrideKey,
            overrideModel = overrideModel
        )
        return response.text
    }

    suspend fun streamContents(
        contents: List<GeminiContent>,
        systemPrompt: String = "",
        taskType: AiTaskType = AiTaskType.LIGHT_INTERACTION,
        generationConfig: GeminiGenerationConfig? = null,
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        overrideKey: String? = null,
        overrideModel: String? = null,
        onPartialText: (String) -> Unit = {},
        onThinkingText: ((String) -> Unit)? = null
    ): GeminiResponse = withContext(Dispatchers.IO) {
        val (keys, isBuiltInMode) = resolveKeyCandidates(overrideKey, null)
        if (keys.isEmpty()) {
            throw GeminiApiException(
                message = "Gemini API anahtarı bulunamadı. Ayarlar'dan kendi anahtarınızı ekleyebilirsiniz.",
                httpCode = 401,
                apiStatus = "UNAUTHENTICATED"
            )
        }

        val resolvedConfig = generationConfig ?: taskType.toGenerationConfig()
        val customKeysSet = UserAiPreferences.getParsedUserGeminiKeys().toSet()

        val primaryAttempt = tryStreamKeys(
            keys = keys,
            modelResolver = { key ->
                when {
                    !overrideModel.isNullOrBlank() && (!overrideKey.isNullOrBlank() || key in customKeysSet) ->
                        GeminiRestClient.sanitizeModelName(overrideModel)
                    key in customKeysSet ->
                        UserAiPreferences.resolveEffectiveGeminiModel(null, key)
                    else ->
                        SOLE_GEMINI_MODEL
                }
            },
            contents = contents,
            systemPrompt = systemPrompt,
            generationConfig = resolvedConfig,
            tools = tools,
            onPartialText = onPartialText,
            onThinkingText = onThinkingText
        )
        primaryAttempt.response?.let { return@withContext it }

        val primaryError = primaryAttempt.lastError!!
        val builtInKeys = keys.filter { it !in customKeysSet }
        val hasGenuine404 = primaryAttempt.genuine404Error != null || isGenuine404Failure(primaryError)
        if (builtInKeys.isNotEmpty() && (isBuiltInMode || primaryAttempt.triedBuiltInKeys) && hasGenuine404) {
            safeLogW(
                "Stream: $SOLE_GEMINI_MODEL returned 404 across built-in endpoints/keys. Trying controlled fallback $CONTROLLED_404_FALLBACK_MODEL..."
            )
            val fallbackAttempt = tryStreamKeys(
                keys = builtInKeys,
                modelResolver = { CONTROLLED_404_FALLBACK_MODEL },
                contents = contents,
                systemPrompt = systemPrompt,
                generationConfig = resolvedConfig,
                tools = tools,
                onPartialText = onPartialText,
                onThinkingText = onThinkingText
            )
            fallbackAttempt.response?.let { return@withContext it }
            val fallbackErr = fallbackAttempt.lastError
            throw GeminiApiException(
                message = "Model $SOLE_GEMINI_MODEL ve yedek $CONTROLLED_404_FALLBACK_MODEL erişilemedi: ${fallbackErr?.localizedMessage ?: primaryError.localizedMessage}",
                httpCode = (fallbackErr as? GeminiApiException)?.httpCode ?: 404,
                modelName = CONTROLLED_404_FALLBACK_MODEL,
                cause = fallbackErr ?: primaryError
            )
        }

        throw primaryError
    }

    suspend fun streamText(
        prompt: String,
        systemPrompt: String = "",
        taskType: AiTaskType = AiTaskType.LIGHT_INTERACTION,
        generationConfig: GeminiGenerationConfig? = null,
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        overrideKey: String? = null,
        overrideModel: String? = null,
        onPartialText: (String) -> Unit = {},
        onThinkingText: ((String) -> Unit)? = null
    ): GeminiResponse {
        return streamContents(
            contents = listOf(GeminiContent.userText(prompt)),
            systemPrompt = systemPrompt,
            taskType = taskType,
            generationConfig = generationConfig,
            tools = tools,
            overrideKey = overrideKey,
            overrideModel = overrideModel,
            onPartialText = onPartialText,
            onThinkingText = onThinkingText
        )
    }

    suspend fun streamWithImages(
        prompt: String,
        bitmaps: List<Bitmap> = emptyList(),
        inlineDataList: List<GeminiPart.InlineData> = emptyList(),
        systemPrompt: String = "",
        taskType: AiTaskType = AiTaskType.HEAVY_ANALYSIS,
        generationConfig: GeminiGenerationConfig? = VISION_PRESET_CONFIG,
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        overrideKey: String? = null,
        overrideModel: String? = null,
        onPartialText: (String) -> Unit = {},
        onThinkingText: ((String) -> Unit)? = null
    ): GeminiResponse {
        val parts = buildList {
            bitmaps.forEach { if (!it.isRecycled) add(bitmapToInlineData(it)) }
            addAll(inlineDataList)
            add(GeminiPart.Text(prompt))
        }
        return streamContents(
            contents = listOf(GeminiContent(role = "user", parts = parts)),
            systemPrompt = systemPrompt,
            taskType = taskType,
            generationConfig = generationConfig,
            tools = tools,
            overrideKey = overrideKey,
            overrideModel = overrideModel,
            onPartialText = onPartialText,
            onThinkingText = onThinkingText
        )
    }

    /**
     * Unified non-streaming failover entrypoint used across repositories, ViewModels, workers & activities.
     */
    suspend fun generateContentWithFailover(
        context: Context? = null,
        systemInstruction: String = "",
        userPrompt: String,
        chatHistory: List<GeminiChatTurn> = emptyList(),
        bitmaps: List<Bitmap> = emptyList(),
        inlineFiles: List<Pair<String, ByteArray>> = emptyList(),
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        responseJson: Boolean = false,
        temperature: Float? = null,
        maxOutputTokens: Int = 8192,
        topP: Float? = null,
        timeoutMs: Long = 60_000L
    ): Result<GeminiSdkResult> = withContext(Dispatchers.IO) {
        executeRestWithFailover(
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
            onPartialText = {}
        )
    }

    /**
     * Unified SSE streaming failover entrypoint used across Shark AI, Notes AI, and PDF Reader AI.
     */
    suspend fun streamContentWithFailover(
        context: Context? = null,
        systemInstruction: String = "",
        userPrompt: String,
        chatHistory: List<GeminiChatTurn> = emptyList(),
        bitmaps: List<Bitmap> = emptyList(),
        inlineFiles: List<Pair<String, ByteArray>> = emptyList(),
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        responseJson: Boolean = false,
        temperature: Float? = null,
        maxOutputTokens: Int = 8192,
        topP: Float? = null,
        timeoutMs: Long = 60_000L,
        onPartialText: (String) -> Unit = {}
    ): Result<GeminiSdkResult> = withContext(Dispatchers.IO) {
        executeRestWithFailover(
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
            onPartialText = onPartialText
        )
    }

    private suspend fun executeRestWithFailover(
        context: Context?,
        stream: Boolean,
        systemInstruction: String,
        userPrompt: String,
        chatHistory: List<GeminiChatTurn>,
        bitmaps: List<Bitmap>,
        inlineFiles: List<Pair<String, ByteArray>>,
        tools: List<GeminiFunctionDeclaration>,
        responseJson: Boolean,
        temperature: Float?,
        maxOutputTokens: Int,
        topP: Float?,
        onPartialText: (String) -> Unit
    ): Result<GeminiSdkResult> {
        if (context != null) {
            UserAiPreferences.initialize(context)
        }
        val keys = ApiKeyRotator.getAllEffectiveKeys(context)
            .map { GeminiRestClient.sanitizeApiKey(it) }
            .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            .distinct()

        if (keys.isEmpty()) {
            return Result.failure(
                IllegalStateException("Gemini API anahtarı tanımlı değil. Ayarlardan API anahtarı ekleyin.")
            )
        }

        val resolvedTemp = temperature ?: if (responseJson) 0.2f else if (bitmaps.isNotEmpty()) 0.1f else 0.35f
        val resolvedTopP = topP ?: if (responseJson) 0.8f else 0.85f
        val genConfig = GeminiGenerationConfig(
            temperature = resolvedTemp,
            maxOutputTokens = maxOutputTokens.coerceIn(256, 32768),
            topP = resolvedTopP,
            responseMimeType = if (responseJson) "application/json" else null
        )

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

            val startIdx = ApiKeyRotator.getStartIndex(context).coerceAtLeast(0) % keys.size
            val orderedWithIndex = List(keys.size) { offset ->
                val actualIdx = (startIdx + offset) % keys.size
                actualIdx to keys[actualIdx]
            }

            var lastException: Throwable? = null
            var genuine404BuiltInError: Throwable? = null
            var triedAnyBuiltInKey = false
            val userKeysSet = UserAiPreferences.getParsedUserGeminiKeys().toSet()
            val failureNotes = mutableListOf<String>()

            for ((pos, pair) in orderedWithIndex.withIndex()) {
                val (keyIndex, activeKey) = pair
                val masked = ApiKeyRotator.maskApiKey(activeKey, slotIndex = null)
                val modelName = UserAiPreferences.resolveEffectiveGeminiModel(context, activeKey)
                val isBuiltInKey = activeKey !in userKeysSet
                if (isBuiltInKey) triedAnyBuiltInKey = true

                val attempt = executeSingleKey(
                    key = activeKey,
                    modelName = modelName,
                    stream = stream,
                    contents = contents,
                    systemInstruction = systemInstruction,
                    generationConfig = genConfig,
                    tools = tools,
                    responseJson = responseJson,
                    onPartialText = onPartialText
                )

                attempt.response?.let { resp ->
                    val finalText = if (responseJson) {
                        GeminiRestClient.extractCleanJsonString(resp.text)
                    } else {
                        resp.text.trim()
                    }
                    if (finalText.isNotBlank() || resp.functionCalls.isNotEmpty()) {
                        if (finalText.isNotBlank()) {
                            onPartialText(finalText)
                        }
                        ApiKeyRotator.markCurrentIndex(keyIndex)
                        return Result.success(
                            GeminiSdkResult(
                                text = finalText,
                                functionCalls = resp.functionCalls,
                                usedKeyIndex = keyIndex,
                                modelUsed = resp.modelName.ifBlank { modelName }
                            )
                        )
                    }
                }

                val err = attempt.lastError
                if (err is CancellationException) throw err
                lastException = err

                if (err != null && GeminiRestClient.isNetworkUnreachableError(err)) {
                    return Result.failure(
                        Exception("İnternet bağlantısı kurulamadı. Lütfen ağ bağlantınızı kontrol edin.", err)
                    )
                }

                if (isBuiltInKey && genuine404BuiltInError == null && isGenuine404Failure(err)) {
                    genuine404BuiltInError = err
                }

                if (err != null) {
                    failureNotes.add("#${keyIndex + 1} ($masked • $modelName): ${formatError(err)}")
                }

                if (pos + 1 < orderedWithIndex.size) {
                    ApiKeyRotator.rotateToNextKey(context)
                    delay(100L)
                }
            }

            // Controlled 404 fallback ONLY on built-in keys when gemini-3.6-flash returns 404 across endpoints/keys
            if (triedAnyBuiltInKey && genuine404BuiltInError != null) {
                val builtInEntries = orderedWithIndex.filter { it.second !in userKeysSet }
                for ((keyIndex, activeKey) in builtInEntries) {
                    val attempt = executeSingleKey(
                        key = activeKey,
                        modelName = CONTROLLED_404_FALLBACK_MODEL,
                        stream = stream,
                        contents = contents,
                        systemInstruction = systemInstruction,
                        generationConfig = genConfig,
                        tools = tools,
                        responseJson = responseJson,
                        onPartialText = onPartialText
                    )
                    attempt.response?.let { resp ->
                        val finalText = if (responseJson) {
                            GeminiRestClient.extractCleanJsonString(resp.text)
                        } else {
                            resp.text.trim()
                        }
                        if (finalText.isNotBlank() || resp.functionCalls.isNotEmpty()) {
                            if (finalText.isNotBlank()) {
                                onPartialText(finalText)
                            }
                            ApiKeyRotator.markCurrentIndex(keyIndex)
                            return Result.success(
                                GeminiSdkResult(
                                    text = finalText,
                                    functionCalls = resp.functionCalls,
                                    usedKeyIndex = keyIndex,
                                    modelUsed = CONTROLLED_404_FALLBACK_MODEL
                                )
                            )
                        }
                    }
                }
            }

            val summary = buildFailureMessage(lastException, failureNotes)
            return Result.failure(Exception(summary, lastException))
        } finally {
            tempScaledBitmaps.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
    }

    private suspend fun executeSingleKey(
        key: String,
        modelName: String,
        stream: Boolean,
        contents: List<GeminiContent>,
        systemInstruction: String,
        generationConfig: GeminiGenerationConfig,
        tools: List<GeminiFunctionDeclaration>,
        responseJson: Boolean,
        onPartialText: (String) -> Unit
    ): KeyAttemptOutcome {
        var lastError: Throwable? = null
        val maxRetries = 2
        for (retry in 1..maxRetries) {
            val partialBackup = StringBuilder()
            try {
                val response = if (!stream) {
                    GeminiRestClient.generateContent(
                        apiKey = key,
                        modelName = modelName,
                        contents = contents,
                        systemInstruction = systemInstruction,
                        generationConfig = generationConfig,
                        tools = tools
                    )
                } else {
                    GeminiRestClient.streamGenerateContent(
                        apiKey = key,
                        modelName = modelName,
                        contents = contents,
                        systemInstruction = systemInstruction,
                        generationConfig = generationConfig,
                        tools = tools,
                        onPartialText = { accumulated ->
                            partialBackup.setLength(0)
                            partialBackup.append(accumulated)
                            if (!responseJson) {
                                onPartialText(accumulated)
                            }
                        }
                    )
                }
                if (response.text.isBlank() && response.functionCalls.isEmpty()) {
                    throw IllegalStateException("Model boş yanıt döndürdü ($modelName).")
                }
                return KeyAttemptOutcome(response = response)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (stream && partialBackup.length >= 40) {
                    safeLogW("Stream interrupted mid-response; retaining ${partialBackup.length} streamed chars.")
                    return KeyAttemptOutcome(
                        response = GeminiResponse(
                            text = partialBackup.toString(),
                            modelName = modelName
                        )
                    )
                }
                lastError = e
                safeLogE("Gemini REST failed ($modelName, retry=$retry): ${e.message}", e)
                if (GeminiRestClient.isNetworkUnreachableError(e)) {
                    return KeyAttemptOutcome(lastError = e)
                }
                val isTransient = (e as? GeminiApiException)?.isOverloadOrTransient == true
                if (isTransient && retry < maxRetries) {
                    delay(250L)
                    continue
                }
                break
            }
        }
        return KeyAttemptOutcome(
            lastError = lastError,
            genuine404Error = if (isGenuine404Failure(lastError)) lastError else null
        )
    }

    private fun buildContentsList(
        chatHistory: List<GeminiChatTurn>,
        userPrompt: String,
        bitmaps: List<Bitmap>,
        inlineFiles: List<Pair<String, ByteArray>>
    ): List<GeminiContent> {
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

        val result = mutableListOf<GeminiContent>()
        for ((role, sb) in mergedHistory) {
            result.add(
                GeminiContent(
                    role = role,
                    parts = listOf(GeminiPart.Text(sb.toString()))
                )
            )
        }

        val finalParts = buildList {
            for (bmp in bitmaps) {
                if (!bmp.isRecycled) {
                    add(bitmapToInlineData(bmp))
                }
            }
            for ((mimeType, bytes) in inlineFiles) {
                if (bytes.isNotEmpty()) {
                    add(bytesToInlineData(bytes, mimeType))
                }
            }
            add(GeminiPart.Text(trailingUserPrefix + effectivePrompt))
        }
        result.add(GeminiContent(role = "user", parts = finalParts))
        return result
    }

    suspend fun fetchAvailableModels(apiKey: String? = null): GeminiModelListResult {
        val key = GeminiRestClient.sanitizeApiKey(
            apiKey ?: if (UserAiPreferences.hasCustomKey()) UserAiPreferences.getCustomApiKey() else ApiKeyRotator.getActiveKey()
        )
        return GeminiRestClient.listModels(key).also { result ->
            if (result.models.isNotEmpty() && UserAiPreferences.hasCustomKey()) {
                UserAiPreferences.setCachedModels(result.models.map { it.id })
            }
        }
    }

    suspend fun testAllConfiguredConnections(
        customKeyOverride: String? = null,
        customModelOverride: String? = null
    ): List<GeminiConnectionTestResult> = withContext(Dispatchers.IO) {
        val cleanCustomOverride = GeminiRestClient.sanitizeApiKey(customKeyOverride)
        val effectiveCustomKey = when {
            cleanCustomOverride.isNotBlank() -> cleanCustomOverride
            UserAiPreferences.hasCustomKey() -> UserAiPreferences.getCustomApiKey()
            else -> ""
        }

        if (effectiveCustomKey.isNotBlank()) {
            val targetModel = GeminiRestClient.sanitizeModelName(
                customModelOverride?.takeIf { it.isNotBlank() }
                    ?: UserAiPreferences.getSelectedUserGeminiModel()
            ).ifBlank { SOLE_GEMINI_MODEL }

            return@withContext UserAiPreferences.parseGeminiKeyList(effectiveCustomKey).mapIndexed { idx, singleKey ->
                GeminiRestClient.testConnection(
                    apiKey = singleKey,
                    modelName = targetModel,
                    slotLabel = "Özel Anahtar #${idx + 1}",
                    maskedKey = ApiKeyRotator.maskApiKey(singleKey, slotIndex = null)
                )
            }
        }

        val builtInKeys = ApiKeyRotator.getAllBuiltInKeys()
        if (builtInKeys.isEmpty()) {
            return@withContext listOf(
                GeminiConnectionTestResult(
                    slotLabel = "Yerleşik Anahtarlar",
                    maskedKey = "Tanımsız",
                    isSuccess = false,
                    endpoint = GeminiEndpointType.GOOGLE_AI_STUDIO,
                    modelName = SOLE_GEMINI_MODEL,
                    httpCode = 0,
                    message = "BuildConfig içinde yerleşik Gemini API anahtarı bulunamadı. Yukarıdan özel anahtar girebilirsiniz."
                )
            )
        }

        builtInKeys.mapIndexed { idx, key ->
            GeminiRestClient.testConnection(
                apiKey = key,
                modelName = SOLE_GEMINI_MODEL,
                slotLabel = "Yerleşik ${ApiKeyRotator.getKeySlotLabel(idx)}",
                maskedKey = ApiKeyRotator.maskApiKey(key, slotIndex = idx)
            )
        }
    }

    private data class KeyAttemptOutcome(
        val response: GeminiResponse? = null,
        val lastError: Throwable? = null,
        val genuine404Error: Throwable? = null,
        val triedBuiltInKeys: Boolean = false
    )

    private suspend fun tryExecuteKeys(
        keys: List<String>,
        modelResolver: (String) -> String,
        contents: List<GeminiContent>,
        systemPrompt: String,
        generationConfig: GeminiGenerationConfig,
        tools: List<GeminiFunctionDeclaration>
    ): KeyAttemptOutcome {
        var lastError: Throwable? = null
        var genuine404Error: Throwable? = null
        val userKeysSet = UserAiPreferences.getParsedUserGeminiKeys().toSet()
        var triedBuiltIn = false

        for ((idx, key) in keys.withIndex()) {
            val modelName = modelResolver(key)
            if (key !in userKeysSet) triedBuiltIn = true
            val maxRetries = 2
            for (retry in 1..maxRetries) {
                try {
                    val response = GeminiRestClient.generateContent(
                        apiKey = key,
                        modelName = modelName,
                        contents = contents,
                        systemInstruction = systemPrompt,
                        generationConfig = generationConfig,
                        tools = tools
                    )
                    return KeyAttemptOutcome(response = response, triedBuiltInKeys = triedBuiltIn)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    lastError = e
                    if (genuine404Error == null && isGenuine404Failure(e)) {
                        genuine404Error = e
                    }
                    safeLogE("generateContent failed ($modelName, keySlot=${idx + 1}/${keys.size}): ${e.message}", e)

                    if (GeminiRestClient.isNetworkUnreachableError(e)) {
                        return KeyAttemptOutcome(
                            lastError = GeminiApiException(
                                message = "Lütfen internet bağlantınızı kontrol edin. Ağ erişilemiyor.",
                                httpCode = 0,
                                modelName = modelName,
                                cause = e
                            ),
                            triedBuiltInKeys = triedBuiltIn
                        )
                    }

                    val apiEx = e as? GeminiApiException
                    val is503 = apiEx?.isOverloadOrTransient == true

                    if (is503 && retry < maxRetries) {
                        delay(250L)
                        continue
                    }

                    if (idx < keys.lastIndex) {
                        if (ApiKeyRotator.shouldRotateKey(e) || apiEx?.isModelNotFound == true) {
                            ApiKeyRotator.rotateToNextKey()
                            if (apiEx?.isModelNotFound != true) {
                                delay(100L)
                            }
                            break
                        }
                    }

                    break
                }
            }
        }

        return KeyAttemptOutcome(
            lastError = lastError ?: GeminiApiException("Gemini yanıt vermedi.", modelName = SOLE_GEMINI_MODEL),
            genuine404Error = genuine404Error,
            triedBuiltInKeys = triedBuiltIn
        )
    }

    private suspend fun tryStreamKeys(
        keys: List<String>,
        modelResolver: (String) -> String,
        contents: List<GeminiContent>,
        systemPrompt: String,
        generationConfig: GeminiGenerationConfig,
        tools: List<GeminiFunctionDeclaration>,
        onPartialText: (String) -> Unit,
        onThinkingText: ((String) -> Unit)?
    ): KeyAttemptOutcome {
        var lastError: Throwable? = null
        var genuine404Error: Throwable? = null
        val userKeysSet = UserAiPreferences.getParsedUserGeminiKeys().toSet()
        var triedBuiltIn = false

        for ((idx, key) in keys.withIndex()) {
            val modelName = modelResolver(key)
            if (key !in userKeysSet) triedBuiltIn = true
            val maxRetries = 2
            for (retry in 1..maxRetries) {
                val partialBackup = StringBuilder()
                try {
                    val response = GeminiRestClient.streamGenerateContent(
                        apiKey = key,
                        modelName = modelName,
                        contents = contents,
                        systemInstruction = systemPrompt,
                        generationConfig = generationConfig,
                        tools = tools,
                        onPartialText = { accumulated ->
                            partialBackup.setLength(0)
                            partialBackup.append(accumulated)
                            onPartialText(accumulated)
                        },
                        onThinkingText = onThinkingText
                    )
                    return KeyAttemptOutcome(response = response, triedBuiltInKeys = triedBuiltIn)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    if (partialBackup.length >= 40) {
                        safeLogW("Stream interrupted mid-response; retaining ${partialBackup.length} streamed chars.")
                        return KeyAttemptOutcome(
                            response = GeminiResponse(
                                text = partialBackup.toString(),
                                modelName = modelName
                            ),
                            triedBuiltInKeys = triedBuiltIn
                        )
                    }
                    lastError = e
                    if (genuine404Error == null && isGenuine404Failure(e)) {
                        genuine404Error = e
                    }
                    safeLogE("streamGenerateContent failed ($modelName, keySlot=${idx + 1}/${keys.size}): ${e.message}", e)

                    if (GeminiRestClient.isNetworkUnreachableError(e)) {
                        return KeyAttemptOutcome(
                            lastError = GeminiApiException(
                                message = "Lütfen internet bağlantınızı kontrol edin. Ağ erişilemiyor.",
                                httpCode = 0,
                                modelName = modelName,
                                cause = e
                            ),
                            triedBuiltInKeys = triedBuiltIn
                        )
                    }

                    val apiEx = e as? GeminiApiException
                    val is503 = apiEx?.isOverloadOrTransient == true

                    if (is503 && retry < maxRetries) {
                        delay(250L)
                        continue
                    }

                    if (idx < keys.lastIndex) {
                        if (ApiKeyRotator.shouldRotateKey(e) || apiEx?.isModelNotFound == true) {
                            ApiKeyRotator.rotateToNextKey()
                            if (apiEx?.isModelNotFound != true) {
                                delay(100L)
                            }
                            break
                        }
                    }

                    break
                }
            }
        }

        return KeyAttemptOutcome(
            lastError = lastError ?: GeminiApiException("Gemini akışı yanıt vermedi.", modelName = SOLE_GEMINI_MODEL),
            genuine404Error = genuine404Error,
            triedBuiltInKeys = triedBuiltIn
        )
    }

    private fun isGenuine404Failure(throwable: Throwable?): Boolean {
        return (throwable as? GeminiApiException)?.isModelNotFound == true ||
            throwable?.message?.contains("404") == true ||
            throwable?.message?.contains("NOT_FOUND", ignoreCase = true) == true
    }

    private fun formatError(t: Throwable?): String {
        if (t == null) return "Bilinmeyen hata"
        return (t.localizedMessage ?: t.message ?: t.javaClass.simpleName).take(160)
    }

    private fun buildFailureMessage(lastException: Throwable?, notes: List<String>): String {
        val raw = "${lastException?.message} ${lastException?.cause?.message}".lowercase()
        val detail = notes.lastOrNull().orEmpty()
        val apiEx = lastException as? GeminiApiException
        return when {
            apiEx?.isAuthOrKeyError == true || raw.contains("401") || raw.contains("403") || raw.contains("api_key") ->
                "API anahtarı doğrulama hatası. Ayarlardan anahtarınızı kontrol edin. [$detail]"
            apiEx?.isQuotaOrRateLimit == true || raw.contains("429") || raw.contains("quota") ->
                "API kota limiti doldu (429). Kısa süre sonra tekrar deneyin veya yeni bir anahtar ekleyin."
            apiEx?.isOverloadOrTransient == true || raw.contains("503") || raw.contains("unavailable") ->
                "Yapay zeka sunucularında anlık yoğunluk yaşanıyor (503). Lütfen biraz sonra tekrar deneyin."
            else -> lastException?.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Gemini API bağlantısı kurulamadı."
        }
    }
}
