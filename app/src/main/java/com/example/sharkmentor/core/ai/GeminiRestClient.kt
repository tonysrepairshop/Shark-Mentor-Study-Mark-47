package com.example.sharkmentor.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class GeminiThinkingLevel(val apiValue: String) {
    MINIMAL("MINIMAL"),
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH")
}

data class GeminiChatTurn(
    val role: String,
    val text: String
)

data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parametersJsonSchema: JSONObject
)

data class GeminiFunctionCall(
    val name: String,
    val args: Map<String, String>
)

data class GeminiResponseResult(
    val text: String,
    val functionCalls: List<GeminiFunctionCall> = emptyList(),
    val usedKeyIndex: Int = 0,
    val gatewayUsed: String = ""
)

data class GeminiConnectionTestResult(
    val success: Boolean,
    val summary: String,
    val details: List<String> = emptyList()
)

class GeminiApiException(
    val httpCode: Int,
    val apiStatus: String? = null,
    val apiReason: String? = null,
    override val message: String,
    val isKeyOrAuthIssue: Boolean = false,
    val isGatewayMismatch: Boolean = false,
    val isTransientServerIssue: Boolean = false
) : Exception(message)

/**
 * Unified, modern REST client strictly targeting `gemini-3.6-flash` over OkHttp.
 *
 * Designed specifically for 2026 Gemini 3.6 Flash semantics & `AQ.` Auth Keys:
 * - Authenticates cleanly via `x-goog-api-key` header (no conflicting `Bearer` or `?key=` query).
 * - Strips deprecated sampling parameters (`temperature`, `topP`, `topK`) and forbids prefilled `model` tail turns.
 * - Tolerates dynamic thinking metadata (`thought: true`, `thoughtSignature`, `thoughtsTokenCount`) and ignores unknown JSON fields.
 * - Supports both Google AI Studio (`generativelanguage.googleapis.com`) and Vertex Express Mode (`aiplatform.googleapis.com`) for `AQ.` keys.
 * - Cycles automatically through all user-supplied keys and BuildConfig default keys.
 */
object GeminiRestClient {

    private const val TAG = "GeminiRestClient"
    const val MODEL_ID = "gemini-3.6-flash"

    private const val AI_STUDIO_BASE =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID"
    private const val VERTEX_EXPRESS_V1BETA1_BASE =
        "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/$MODEL_ID"
    private const val VERTEX_EXPRESS_V1_BASE =
        "https://aiplatform.googleapis.com/v1/publishers/google/models/$MODEL_ID"

    private enum class Gateway(val label: String, val baseUrl: String) {
        AI_STUDIO("generativelanguage", AI_STUDIO_BASE),
        VERTEX_EXPRESS_BETA("aiplatform-v1beta1", VERTEX_EXPRESS_V1BETA1_BASE),
        VERTEX_EXPRESS_V1("aiplatform-v1", VERTEX_EXPRESS_V1_BASE)
    }

    private val preferredGatewayByKey = ConcurrentHashMap<String, Gateway>()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun generateWithFailover(
        context: Context? = null,
        systemInstruction: String = "",
        userPrompt: String,
        chatHistory: List<GeminiChatTurn> = emptyList(),
        bitmaps: List<Bitmap> = emptyList(),
        inlineFiles: List<Pair<String, ByteArray>> = emptyList(),
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        responseJson: Boolean = false,
        maxOutputTokens: Int = 8192,
        thinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.LOW,
        timeoutMs: Long = 90_000L
    ): Result<GeminiResponseResult> = withContext(Dispatchers.IO) {
        executeWithFailover(
            context = context,
            overrideKeys = null,
            stream = false,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            chatHistory = chatHistory,
            bitmaps = bitmaps,
            inlineFiles = inlineFiles,
            tools = tools,
            responseJson = responseJson,
            maxOutputTokens = maxOutputTokens,
            thinkingLevel = thinkingLevel,
            timeoutMs = timeoutMs,
            onPartialText = {}
        )
    }

    suspend fun streamWithFailover(
        context: Context? = null,
        systemInstruction: String = "",
        userPrompt: String,
        chatHistory: List<GeminiChatTurn> = emptyList(),
        bitmaps: List<Bitmap> = emptyList(),
        inlineFiles: List<Pair<String, ByteArray>> = emptyList(),
        tools: List<GeminiFunctionDeclaration> = emptyList(),
        responseJson: Boolean = false,
        maxOutputTokens: Int = 8192,
        thinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.LOW,
        timeoutMs: Long = 90_000L,
        onPartialText: (String) -> Unit = {}
    ): Result<GeminiResponseResult> = withContext(Dispatchers.IO) {
        executeWithFailover(
            context = context,
            overrideKeys = null,
            stream = true,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            chatHistory = chatHistory,
            bitmaps = bitmaps,
            inlineFiles = inlineFiles,
            tools = tools,
            responseJson = responseJson,
            maxOutputTokens = maxOutputTokens,
            thinkingLevel = thinkingLevel,
            timeoutMs = timeoutMs,
            onPartialText = onPartialText
        )
    }

    /**
     * Runs a fast diagnostic check against candidate Gemini keys (unsaved Settings input + defaults,
     * or currently configured keys if input is blank) and reports clear HTTP status diagnostics.
     */
    suspend fun testApiConnection(
        context: Context? = null,
        candidateUserKeysRaw: String? = null
    ): GeminiConnectionTestResult = withContext(Dispatchers.IO) {
        val candidateKeys = if (candidateUserKeysRaw != null) {
            val parsedUser = UserAiPreferences.parseGeminiKeyList(candidateUserKeysRaw)
            (parsedUser + ApiKeyRotator.getBuildConfigKeys()).distinct()
        } else {
            ApiKeyRotator.getAllEffectiveKeys(context)
        }

        if (candidateKeys.isEmpty()) {
            return@withContext GeminiConnectionTestResult(
                success = false,
                summary = "Tanımlı Gemini API anahtarı bulunamadı.",
                details = listOf("Ayarlardan bir anahtar girin veya uygulama derleme anahtarlarını kontrol edin.")
            )
        }

        val perKeyDiagnostics = mutableListOf<String>()
        for ((idx, key) in candidateKeys.withIndex()) {
            val masked = maskKey(key)
            val attempt = runCatching {
                executeSingleKey(
                    key = key,
                    keyIndex = idx,
                    stream = false,
                    systemInstruction = "",
                    userPrompt = "Sadece OK yaz.",
                    chatHistory = emptyList(),
                    encodedMediaParts = emptyList(),
                    tools = emptyList(),
                    responseJson = false,
                    maxOutputTokens = 256,
                    thinkingLevel = GeminiThinkingLevel.MINIMAL,
                    timeoutMs = 25_000L,
                    onPartialText = {}
                )
            }
            if (attempt.isSuccess) {
                val res = attempt.getOrThrow()
                val note = if (perKeyDiagnostics.isNotEmpty()) {
                    perKeyDiagnostics + "Anahtar #${idx + 1} ($masked): OK (${res.gatewayUsed})"
                } else {
                    listOf("Anahtar #${idx + 1} ($masked) aktif (${res.gatewayUsed}).")
                }
                return@withContext GeminiConnectionTestResult(
                    success = true,
                    summary = "Bağlantı başarılı ($MODEL_ID • Anahtar #${idx + 1} $masked)",
                    details = note
                )
            } else {
                val err = attempt.exceptionOrNull()
                val detail = formatDiagnosticError(err)
                perKeyDiagnostics.add("Anahtar #${idx + 1} ($masked): $detail")
            }
        }

        GeminiConnectionTestResult(
            success = false,
            summary = "Tüm Gemini anahtarları başarısız oldu (${candidateKeys.size} anahtar denendi).",
            details = perKeyDiagnostics
        )
    }

    private suspend fun executeWithFailover(
        context: Context?,
        overrideKeys: List<String>?,
        stream: Boolean,
        systemInstruction: String,
        userPrompt: String,
        chatHistory: List<GeminiChatTurn>,
        bitmaps: List<Bitmap>,
        inlineFiles: List<Pair<String, ByteArray>>,
        tools: List<GeminiFunctionDeclaration>,
        responseJson: Boolean,
        maxOutputTokens: Int,
        thinkingLevel: GeminiThinkingLevel,
        timeoutMs: Long,
        onPartialText: (String) -> Unit
    ): Result<GeminiResponseResult> {
        val keys = (overrideKeys ?: ApiKeyRotator.getAllEffectiveKeys(context))
            .map { cleanKeyString(it) }
            .filter { it.isNotBlank() && it != "null" }
            .distinct()

        if (keys.isEmpty()) {
            return Result.failure(
                IllegalStateException("Gemini API anahtarı tanımlı değil. Ayarlardan API anahtarı ekleyin.")
            )
        }

        val encodedMediaParts = encodeMediaParts(bitmaps, inlineFiles)
        val startIdx = ApiKeyRotator.getStartIndex(context).coerceAtLeast(0) % keys.size
        var lastException: Throwable? = null
        val failureNotes = mutableListOf<String>()

        for (offset in keys.indices) {
            val keyIndex = (startIdx + offset) % keys.size
            val key = keys[keyIndex]
            val masked = maskKey(key)

            val maxTransientRetries = 2
            for (retry in 0 until maxTransientRetries) {
                try {
                    val result = executeSingleKey(
                        key = key,
                        keyIndex = keyIndex,
                        stream = stream,
                        systemInstruction = systemInstruction,
                        userPrompt = userPrompt,
                        chatHistory = chatHistory,
                        encodedMediaParts = encodedMediaParts,
                        tools = tools,
                        responseJson = responseJson,
                        maxOutputTokens = maxOutputTokens,
                        thinkingLevel = thinkingLevel,
                        timeoutMs = timeoutMs,
                        onPartialText = onPartialText
                    )
                    ApiKeyRotator.markCurrentIndex(keyIndex)
                    return Result.success(result)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    lastException = e
                    val isNetworkDown = isOfflineNetworkFailure(e)
                    if (isNetworkDown) {
                        Log.e(TAG, "Offline/DNS error on key #$keyIndex ($masked): ${e.message}")
                        return Result.failure(
                            Exception("İnternet bağlantısı kurulamadı. Lütfen ağ bağlantınızı kontrol edin.")
                        )
                    }

                    val isTransient = isTransientRetryable(e)
                    Log.w(
                        TAG,
                        "Request failed on key #${keyIndex + 1}/${keys.size} ($masked, retry=${retry + 1}): ${e.message}"
                    )

                    if (isTransient && retry + 1 < maxTransientRetries) {
                        delay(550L * (retry + 1))
                        continue
                    }

                    failureNotes.add("#${keyIndex + 1} ($masked): ${formatDiagnosticError(e)}")
                    break
                }
            }

            if (offset + 1 < keys.size) {
                ApiKeyRotator.rotateToNextKey(context)
                delay(150L)
            }
        }

        val finalErrorMessage = buildFailureSummary(lastException, failureNotes)
        return Result.failure(GeminiApiException(
            httpCode = (lastException as? GeminiApiException)?.httpCode ?: 0,
            apiStatus = (lastException as? GeminiApiException)?.apiStatus,
            apiReason = (lastException as? GeminiApiException)?.apiReason,
            message = finalErrorMessage,
            isKeyOrAuthIssue = (lastException as? GeminiApiException)?.isKeyOrAuthIssue == true,
            isGatewayMismatch = (lastException as? GeminiApiException)?.isGatewayMismatch == true,
            isTransientServerIssue = (lastException as? GeminiApiException)?.isTransientServerIssue == true
        ))
    }

    private fun executeSingleKey(
        key: String,
        keyIndex: Int,
        stream: Boolean,
        systemInstruction: String,
        userPrompt: String,
        chatHistory: List<GeminiChatTurn>,
        encodedMediaParts: List<JSONObject>,
        tools: List<GeminiFunctionDeclaration>,
        responseJson: Boolean,
        maxOutputTokens: Int,
        thinkingLevel: GeminiThinkingLevel,
        timeoutMs: Long,
        onPartialText: (String) -> Unit
    ): GeminiResponseResult {
        val gateways = buildOrderedGatewaysForKey(key)
        var lastError: Throwable? = null

        for ((gwIdx, gateway) in gateways.withIndex()) {
            try {
                val res = executeOnGatewayWithThinkingFallback(
                    gateway = gateway,
                    key = key,
                    keyIndex = keyIndex,
                    stream = stream,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    chatHistory = chatHistory,
                    encodedMediaParts = encodedMediaParts,
                    tools = tools,
                    responseJson = responseJson,
                    maxOutputTokens = maxOutputTokens,
                    thinkingLevel = thinkingLevel,
                    timeoutMs = timeoutMs,
                    onPartialText = onPartialText
                )
                preferredGatewayByKey[key] = gateway
                return res
            } catch (ce: CancellationException) {
                throw ce
            } catch (apiEx: GeminiApiException) {
                lastError = apiEx
                val shouldTryExpressGateway = gwIdx < gateways.lastIndex && (
                    apiEx.httpCode == 401 ||
                        apiEx.httpCode == 403 ||
                        apiEx.httpCode == 404 ||
                        (apiEx.httpCode == 400 && (apiEx.isKeyOrAuthIssue || gateway != Gateway.AI_STUDIO)) ||
                        apiEx.isGatewayMismatch
                    )
                if (shouldTryExpressGateway) {
                    Log.i(
                        TAG,
                        "Gateway ${gateway.label} rejected key (${apiEx.httpCode} ${apiEx.apiReason ?: ""}), probing next gateway..."
                    )
                    preferredGatewayByKey.remove(key)
                    continue
                }
                throw apiEx
            }
        }

        throw lastError ?: IllegalStateException("Gemini API isteği başarısız oldu.")
    }

    private fun executeOnGatewayWithThinkingFallback(
        gateway: Gateway,
        key: String,
        keyIndex: Int,
        stream: Boolean,
        systemInstruction: String,
        userPrompt: String,
        chatHistory: List<GeminiChatTurn>,
        encodedMediaParts: List<JSONObject>,
        tools: List<GeminiFunctionDeclaration>,
        responseJson: Boolean,
        maxOutputTokens: Int,
        thinkingLevel: GeminiThinkingLevel,
        timeoutMs: Long,
        onPartialText: (String) -> Unit
    ): GeminiResponseResult {
        return try {
            executeHttpRequest(
                gateway = gateway,
                key = key,
                keyIndex = keyIndex,
                stream = stream,
                systemInstruction = systemInstruction,
                userPrompt = userPrompt,
                chatHistory = chatHistory,
                encodedMediaParts = encodedMediaParts,
                tools = tools,
                responseJson = responseJson,
                maxOutputTokens = maxOutputTokens,
                thinkingLevel = thinkingLevel,
                includeThinkingConfig = true,
                timeoutMs = timeoutMs,
                onPartialText = onPartialText
            )
        } catch (apiEx: GeminiApiException) {
            val lowerMsg = apiEx.message.lowercase()
            val isThinkingFieldRejected = apiEx.httpCode == 400 && (
                lowerMsg.contains("thinking") ||
                    lowerMsg.contains("thinkingconfig") ||
                    lowerMsg.contains("thinkinglevel") ||
                    lowerMsg.contains("unknown name")
                )
            if (isThinkingFieldRejected) {
                Log.w(TAG, "Gateway ${gateway.label} rejected thinkingConfig, retrying cleanly without it...")
                executeHttpRequest(
                    gateway = gateway,
                    key = key,
                    keyIndex = keyIndex,
                    stream = stream,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    chatHistory = chatHistory,
                    encodedMediaParts = encodedMediaParts,
                    tools = tools,
                    responseJson = responseJson,
                    maxOutputTokens = maxOutputTokens,
                    thinkingLevel = thinkingLevel,
                    includeThinkingConfig = false,
                    timeoutMs = timeoutMs,
                    onPartialText = onPartialText
                )
            } else {
                throw apiEx
            }
        }
    }

    private fun executeHttpRequest(
        gateway: Gateway,
        key: String,
        keyIndex: Int,
        stream: Boolean,
        systemInstruction: String,
        userPrompt: String,
        chatHistory: List<GeminiChatTurn>,
        encodedMediaParts: List<JSONObject>,
        tools: List<GeminiFunctionDeclaration>,
        responseJson: Boolean,
        maxOutputTokens: Int,
        thinkingLevel: GeminiThinkingLevel,
        includeThinkingConfig: Boolean,
        timeoutMs: Long,
        onPartialText: (String) -> Unit
    ): GeminiResponseResult {
        val url = if (stream) {
            "${gateway.baseUrl}:streamGenerateContent?alt=sse"
        } else {
            "${gateway.baseUrl}:generateContent"
        }

        val payloadJson = buildRequestPayload(
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            chatHistory = chatHistory,
            encodedMediaParts = encodedMediaParts,
            tools = tools,
            responseJson = responseJson,
            maxOutputTokens = maxOutputTokens,
            thinkingLevel = thinkingLevel,
            includeThinkingConfig = includeThinkingConfig
        ).toString()

        val client = if (timeoutMs != 90_000L) {
            httpClient.newBuilder()
                .readTimeout(timeoutMs.coerceAtLeast(15_000L), TimeUnit.MILLISECONDS)
                .callTimeout((timeoutMs + 25_000L).coerceAtLeast(25_000L), TimeUnit.MILLISECONDS)
                .build()
        } else {
            httpClient
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("x-goog-api-key", key)
            .post(payloadJson.toRequestBody(jsonMediaType))

        if (stream) {
            requestBuilder.addHeader("Accept", "text/event-stream")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                throw parseApiError(response.code, errBody)
            }

            val body = response.body
                ?: throw GeminiApiException(
                    httpCode = response.code,
                    message = "Sunucudan boş yanıt gövdesi geldi.",
                    isTransientServerIssue = true
                )

            if (!stream) {
                val rawJson = body.string()
                val parsed = parseCandidateResponse(rawJson)
                val finalText = if (responseJson) extractCleanJsonString(parsed.first) else parsed.first.trim()
                if (finalText.isBlank() && parsed.second.isEmpty()) {
                    throw GeminiApiException(
                        httpCode = 200,
                        message = "Model boş yanıt döndürdü ($MODEL_ID).",
                        isTransientServerIssue = true
                    )
                }
                if (finalText.isNotBlank()) {
                    onPartialText(finalText)
                }
                return GeminiResponseResult(
                    text = finalText,
                    functionCalls = parsed.second,
                    usedKeyIndex = keyIndex,
                    gatewayUsed = gateway.label
                )
            }

            // Streaming SSE response parsing (with fallback if server responds with non-SSE JSON)
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("application/json")) {
                val raw = body.string()
                val parsed = parseJsonOrArrayStreamFallback(raw)
                val finalText = if (responseJson) extractCleanJsonString(parsed.first) else parsed.first.trim()
                if (finalText.isBlank() && parsed.second.isEmpty()) {
                    throw GeminiApiException(
                        httpCode = 200,
                        message = "Model boş yanıt döndürdü ($MODEL_ID).",
                        isTransientServerIssue = true
                    )
                }
                if (finalText.isNotBlank()) {
                    onPartialText(finalText)
                }
                return GeminiResponseResult(
                    text = finalText,
                    functionCalls = parsed.second,
                    usedKeyIndex = keyIndex,
                    gatewayUsed = gateway.label
                )
            }

            val accumulated = StringBuilder()
            val functionCalls = mutableListOf<GeminiFunctionCall>()
            val sseBuffer = StringBuilder()
            var blockFinishReason: String? = null

            BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) {
                            if (sseBuffer.isNotEmpty()) {
                                val chunkOutcome = consumeSseChunk(
                                    payload = sseBuffer.toString().trim(),
                                    accumulated = accumulated,
                                    functionCalls = functionCalls,
                                    responseJson = responseJson,
                                    onPartialText = onPartialText
                                )
                                if (chunkOutcome != null) blockFinishReason = chunkOutcome
                                sseBuffer.setLength(0)
                            }
                            continue
                        }
                        if (line.startsWith(":")) continue
                        if (line.startsWith("data:")) {
                            val payloadPiece = line.substring(5).trimStart()
                            if (payloadPiece == "[DONE]") {
                                sseBuffer.setLength(0)
                                break
                            }
                            if (sseBuffer.isNotEmpty()) sseBuffer.append('\n')
                            sseBuffer.append(payloadPiece)
                        } else if (sseBuffer.isEmpty() && (line.trimStart().startsWith("{") || line.trimStart().startsWith("["))) {
                            // Proxy stripped SSE framing and sent raw JSON
                            sseBuffer.append(line)
                        }
                    }
                    if (sseBuffer.isNotEmpty()) {
                        val rawTrailing = sseBuffer.toString().trim()
                        if (rawTrailing.startsWith("[")) {
                            val parsed = parseJsonOrArrayStreamFallback(rawTrailing)
                            if (parsed.first.isNotBlank()) {
                                accumulated.append(parsed.first)
                                onPartialText(accumulated.toString())
                            }
                            functionCalls.addAll(parsed.second)
                        } else {
                            val chunkOutcome = consumeSseChunk(
                                payload = rawTrailing,
                                accumulated = accumulated,
                                functionCalls = functionCalls,
                                responseJson = responseJson,
                                onPartialText = onPartialText
                            )
                            if (chunkOutcome != null) blockFinishReason = chunkOutcome
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (streamErr: Throwable) {
                    if (accumulated.length > 60) {
                        Log.w(TAG, "Stream interrupted after ${accumulated.length} chars, keeping partial response: ${streamErr.message}")
                    } else {
                        throw streamErr
                    }
                }
            }

            val rawResult = accumulated.toString()
            val finalText = if (responseJson) extractCleanJsonString(rawResult) else rawResult.trim()
            if (finalText.isBlank() && functionCalls.isEmpty()) {
                val blockedSuffix = blockFinishReason?.let { " ($it)" }.orEmpty()
                throw GeminiApiException(
                    httpCode = 200,
                    message = "Model boş yanıt döndürdü$blockedSuffix.",
                    isTransientServerIssue = true
                )
            }
            if (finalText.isNotBlank()) {
                onPartialText(finalText)
            }
            return GeminiResponseResult(
                text = finalText,
                functionCalls = functionCalls,
                usedKeyIndex = keyIndex,
                gatewayUsed = gateway.label
            )
        }
    }

    private fun consumeSseChunk(
        payload: String,
        accumulated: StringBuilder,
        functionCalls: MutableList<GeminiFunctionCall>,
        responseJson: Boolean,
        onPartialText: (String) -> Unit
    ): String? {
        if (payload.isBlank() || payload == "[DONE]") return null
        // Check if Google sent an inline error object inside stream
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (root.has("error")) {
            val errObj = root.optJSONObject("error")
            val code = errObj?.optInt("code", 500) ?: 500
            throw parseApiError(code, payload)
        }
        val parsed = extractFromRootJson(root)
        if (parsed.text.isNotEmpty()) {
            accumulated.append(parsed.text)
            if (!responseJson) {
                onPartialText(accumulated.toString())
            }
        }
        if (parsed.functionCalls.isNotEmpty()) {
            functionCalls.addAll(parsed.functionCalls)
        }
        return parsed.finishReason
    }

    private data class ParsedCandidateSlice(
        val text: String,
        val functionCalls: List<GeminiFunctionCall>,
        val finishReason: String?
    )

    private fun parseCandidateResponse(rawJson: String): Pair<String, List<GeminiFunctionCall>> {
        val trimmed = rawJson.trim()
        if (trimmed.startsWith("[")) {
            return parseJsonOrArrayStreamFallback(trimmed)
        }
        val root = JSONObject(trimmed)
        if (root.has("error")) {
            val errObj = root.optJSONObject("error")
            throw parseApiError(errObj?.optInt("code", 500) ?: 500, trimmed)
        }
        val slice = extractFromRootJson(root)
        return slice.text to slice.functionCalls
    }

    private fun parseJsonOrArrayStreamFallback(raw: String): Pair<String, List<GeminiFunctionCall>> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            val sb = StringBuilder()
            val calls = mutableListOf<GeminiFunctionCall>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.has("error")) {
                    val errObj = item.optJSONObject("error")
                    throw parseApiError(errObj?.optInt("code", 500) ?: 500, item.toString())
                }
                val slice = extractFromRootJson(item)
                sb.append(slice.text)
                calls.addAll(slice.functionCalls)
            }
            return sb.toString() to calls
        }
        return parseCandidateResponse(trimmed)
    }

    private fun extractFromRootJson(root: JSONObject): ParsedCandidateSlice {
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val promptFeedback = root.optJSONObject("promptFeedback")
            val blockReason = promptFeedback?.optString("blockReason").takeIf { !it.isNullOrBlank() }
            return ParsedCandidateSlice("", emptyList(), blockReason)
        }

        val firstCandidate = candidates.optJSONObject(0)
            ?: return ParsedCandidateSlice("", emptyList(), null)
        val finishReason = firstCandidate.optString("finishReason").takeIf { it.isNotBlank() }
        val content = firstCandidate.optJSONObject("content")
            ?: return ParsedCandidateSlice("", emptyList(), finishReason)
        val parts = content.optJSONArray("parts")
            ?: return ParsedCandidateSlice("", emptyList(), finishReason)

        val visibleText = StringBuilder()
        val calls = mutableListOf<GeminiFunctionCall>()

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            // Skip Gemini 3.6 Flash internal thinking parts (`thought: true`)
            if (part.optBoolean("thought", false)) continue

            if (part.has("text")) {
                val txt = part.optString("text", "")
                if (txt.isNotEmpty()) {
                    visibleText.append(txt)
                }
            }
            if (part.has("functionCall")) {
                val fc = part.optJSONObject("functionCall")
                val name = fc?.optString("name", "").orEmpty()
                if (name.isNotBlank()) {
                    val argsObj = fc?.optJSONObject("args")
                    val argsMap = linkedMapOf<String, String>()
                    if (argsObj != null) {
                        val keysIter = argsObj.keys()
                        while (keysIter.hasNext()) {
                            val k = keysIter.next()
                            val v = argsObj.opt(k)
                            if (v != null && v != JSONObject.NULL) {
                                argsMap[k] = v.toString()
                            }
                        }
                    }
                    calls.add(GeminiFunctionCall(name = name, args = argsMap))
                }
            }
        }

        return ParsedCandidateSlice(visibleText.toString(), calls, finishReason)
    }

    private fun buildRequestPayload(
        systemInstruction: String,
        userPrompt: String,
        chatHistory: List<GeminiChatTurn>,
        encodedMediaParts: List<JSONObject>,
        tools: List<GeminiFunctionDeclaration>,
        responseJson: Boolean,
        maxOutputTokens: Int,
        thinkingLevel: GeminiThinkingLevel,
        includeThinkingConfig: Boolean
    ): JSONObject {
        val root = JSONObject()

        if (systemInstruction.isNotBlank()) {
            val sysParts = JSONArray().put(JSONObject().put("text", systemInstruction.trim()))
            root.put("systemInstruction", JSONObject().put("parts", sysParts))
        }

        val contentsArray = JSONArray()
        val sanitizedTurns = mutableListOf<Pair<String, JSONArray>>()

        // Normalize historical turns: map assistant -> model, skip blanks, merge adjacent same roles
        for (turn in chatHistory) {
            val text = turn.text.trim()
            if (text.isBlank()) continue
            val mappedRole = if (turn.role.equals("model", ignoreCase = true) ||
                turn.role.equals("assistant", ignoreCase = true) ||
                turn.role.equals("ai", ignoreCase = true)
            ) "model" else "user"

            val last = sanitizedTurns.lastOrNull()
            if (last != null && last.first == mappedRole) {
                last.second.put(JSONObject().put("text", text))
            } else {
                sanitizedTurns.add(mappedRole to JSONArray().put(JSONObject().put("text", text)))
            }
        }

        // Gemini requires the conversation history not to end with a trailing model prefill if combining,
        // and the final turn must always be `"user"`.
        if (sanitizedTurns.firstOrNull()?.first == "model") {
            sanitizedTurns.removeAt(0)
        }

        val userParts = JSONArray()
        for (mediaPart in encodedMediaParts) {
            userParts.put(mediaPart)
        }
        val effectiveUserPrompt = userPrompt.trim().ifBlank { "Devam et." }
        userParts.put(JSONObject().put("text", effectiveUserPrompt))

        val lastTurn = sanitizedTurns.lastOrNull()
        if (lastTurn != null && lastTurn.first == "user") {
            for (i in 0 until userParts.length()) {
                lastTurn.second.put(userParts.get(i))
            }
        } else {
            sanitizedTurns.add("user" to userParts)
        }

        for ((role, parts) in sanitizedTurns) {
            contentsArray.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", parts)
            )
        }
        root.put("contents", contentsArray)

        val genConfig = JSONObject()
            .put("maxOutputTokens", maxOutputTokens.coerceIn(256, 65536))
        if (responseJson) {
            genConfig.put("responseMimeType", "application/json")
        }
        if (includeThinkingConfig) {
            genConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", thinkingLevel.apiValue)
            )
        }
        root.put("generationConfig", genConfig)

        if (tools.isNotEmpty()) {
            val declArray = JSONArray()
            for (decl in tools) {
                declArray.put(
                    JSONObject()
                        .put("name", decl.name)
                        .put("description", decl.description)
                        .put("parameters", decl.parametersJsonSchema)
                )
            }
            root.put(
                "tools",
                JSONArray().put(JSONObject().put("functionDeclarations", declArray))
            )
        }

        return root
    }

    private fun encodeMediaParts(
        bitmaps: List<Bitmap>,
        inlineFiles: List<Pair<String, ByteArray>>
    ): List<JSONObject> {
        val parts = mutableListOf<JSONObject>()
        for (bmp in bitmaps) {
            if (bmp.isRecycled) continue
            val b64 = encodeBitmapToBase64Jpeg(bmp) ?: continue
            parts.add(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", b64)
                )
            )
        }
        for ((mimeType, bytes) in inlineFiles) {
            if (bytes.isEmpty()) continue
            val safeMime = mimeType.ifBlank { "application/octet-stream" }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            parts.add(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", safeMime)
                        .put("data", b64)
                )
            )
        }
        return parts
    }

    private fun encodeBitmapToBase64Jpeg(bitmap: Bitmap): String? {
        return runCatching {
            val maxDim = maxOf(bitmap.width, bitmap.height)
            var scaled: Bitmap? = null
            val target = if (maxDim > 1600) {
                val scale = 1600f / maxDim.toFloat()
                val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true).also { scaled = it }
            } else {
                bitmap
            }
            try {
                val baos = ByteArrayOutputStream()
                target.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } finally {
                if (scaled != null && scaled !== bitmap) {
                    runCatching { scaled?.recycle() }
                }
            }
        }.getOrNull()
    }

    private fun buildOrderedGatewaysForKey(key: String): List<Gateway> {
        val preferred = preferredGatewayByKey[key]
        val defaultOrder = listOf(
            Gateway.AI_STUDIO,
            Gateway.VERTEX_EXPRESS_BETA,
            Gateway.VERTEX_EXPRESS_V1
        )
        return if (preferred != null) {
            (listOf(preferred) + defaultOrder).distinct()
        } else {
            defaultOrder
        }
    }

    private fun parseApiError(httpCode: Int, rawBody: String): GeminiApiException {
        var apiStatus: String? = null
        var apiMessage: String? = null
        var apiReason: String? = null

        runCatching {
            val root = JSONObject(rawBody)
            val errObj = root.optJSONObject("error")
            if (errObj != null) {
                apiStatus = errObj.optString("status").takeIf { it.isNotBlank() }
                apiMessage = errObj.optString("message").takeIf { it.isNotBlank() }
                val details = errObj.optJSONArray("details")
                if (details != null && details.length() > 0) {
                    for (i in 0 until details.length()) {
                        val item = details.optJSONObject(i) ?: continue
                        val reason = item.optString("reason").takeIf { it.isNotBlank() }
                        if (reason != null) {
                            apiReason = reason
                            break
                        }
                    }
                }
            }
        }

        val lowerMsg = "${apiStatus.orEmpty()} ${apiReason.orEmpty()} ${apiMessage.orEmpty()} $rawBody".lowercase()
        val isGatewayMismatch = (httpCode == 401 || httpCode == 403) && (
            lowerMsg.contains("api_key_service_blocked") ||
                lowerMsg.contains("credentials_missing") ||
                lowerMsg.contains("access_token_type_unsupported")
            )
        val isKeyOrAuth = httpCode == 401 ||
            httpCode == 403 ||
            httpCode == 429 ||
            lowerMsg.contains("api_key_invalid") ||
            lowerMsg.contains("api key not valid") ||
            lowerMsg.contains("quota") ||
            lowerMsg.contains("resource_exhausted") ||
            lowerMsg.contains("rate_limit")
        val isTransient = httpCode == 429 || httpCode == 500 || httpCode == 502 || httpCode == 503 || httpCode == 504

        val conciseDetail = apiMessage?.take(220) ?: rawBody.take(220).ifBlank { "Bilinmeyen API yanıtı" }
        val formattedMessage = buildString {
            append("HTTP $httpCode")
            if (!apiStatus.isNullOrBlank()) append(" $apiStatus")
            if (!apiReason.isNullOrBlank()) append(" ($apiReason)")
            append(": $conciseDetail")
        }

        return GeminiApiException(
            httpCode = httpCode,
            apiStatus = apiStatus,
            apiReason = apiReason,
            message = formattedMessage,
            isKeyOrAuthIssue = isKeyOrAuth,
            isGatewayMismatch = isGatewayMismatch,
            isTransientServerIssue = isTransient
        )
    }

    private fun isOfflineNetworkFailure(t: Throwable): Boolean {
        if (t is UnknownHostException || t is ConnectException) return true
        val msg = "${t.javaClass.simpleName} ${t.message} ${t.cause?.message}".lowercase()
        return msg.contains("enetunreach") ||
            msg.contains("network is unreachable") ||
            msg.contains("no address associated with hostname") ||
            msg.contains("unknownhostexception") ||
            msg.contains("failed to connect")
    }

    private fun isTransientRetryable(t: Throwable): Boolean {
        if (t is SocketTimeoutException) return true
        if (t is GeminiApiException && t.httpCode in listOf(500, 502, 503, 504)) return true
        val msg = "${t.message} ${t.cause?.message}".lowercase()
        return msg.contains("timeout") ||
            msg.contains("503") ||
            msg.contains("unavailable") ||
            msg.contains("overloaded")
    }

    private fun formatDiagnosticError(t: Throwable?): String {
        if (t == null) return "Bilinmeyen hata"
        if (t is GeminiApiException) return t.message
        if (isOfflineNetworkFailure(t)) return "Ağ/DNS hatası (${t.javaClass.simpleName})"
        return t.localizedMessage?.take(160) ?: t.javaClass.simpleName
    }

    private fun buildFailureSummary(lastException: Throwable?, failureNotes: List<String>): String {
        if (lastException is GeminiApiException) {
            return when {
                lastException.httpCode == 401 || lastException.httpCode == 403 ->
                    "API anahtarı yetki hatası (${lastException.httpCode} ${lastException.apiReason ?: lastException.apiStatus ?: "AUTH"}). Ayarlardan anahtarlarınızı doğrulayın. [${failureNotes.lastOrNull().orEmpty()}]"
                lastException.httpCode == 429 ->
                    "API kota limiti doldu (429). Kısa süre sonra tekrar deneyin veya yeni bir anahtar ekleyin."
                lastException.httpCode == 503 ->
                    "Gemini sunucuları şu an yoğun (503). Lütfen biraz sonra tekrar deneyin."
                else -> lastException.message
            }
        }
        return lastException?.localizedMessage?.takeIf { it.isNotBlank() }
            ?: "Gemini API bağlantısı kurulamadı."
    }

    fun extractCleanJsonString(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length - 3)
            }
            cleaned = cleaned.trim()
        }
        if (cleaned.startsWith("{") || cleaned.startsWith("[")) {
            return cleaned
        }
        val firstObj = cleaned.indexOf('{')
        val firstArr = cleaned.indexOf('[')
        val useArr = firstArr != -1 && (firstObj == -1 || firstArr < firstObj)
        return if (useArr) {
            val endArr = cleaned.lastIndexOf(']')
            if (endArr > firstArr) cleaned.substring(firstArr, endArr + 1) else cleaned
        } else if (firstObj != -1) {
            val endObj = cleaned.lastIndexOf('}')
            if (endObj > firstObj) cleaned.substring(firstObj, endObj + 1) else cleaned
        } else {
            cleaned
        }
    }

    private fun cleanKeyString(raw: String): String =
        raw.replace("\r", "")
            .replace("\n", "")
            .replace("\"", "")
            .replace("'", "")
            .trim()

    private fun maskKey(key: String): String {
        val clean = cleanKeyString(key)
        return if (clean.length <= 8) "***" else "${clean.take(3)}...${clean.takeLast(4)}"
    }
}
