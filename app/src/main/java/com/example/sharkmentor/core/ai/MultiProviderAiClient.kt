package com.example.sharkmentor.core.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Unified REST client for live model discovery, connection testing, and SSE streaming
 * across DeepSeek, Kimi (Moonshot .ai / .cn), Claude (Anthropic), and GPT (OpenAI).
 */
object MultiProviderAiClient {

    private const val TAG = "MultiProviderAiClient"

    private const val DEEPSEEK_MODELS_URL = "https://api.deepseek.com/models"
    private const val DEEPSEEK_CHAT_URL = "https://api.deepseek.com/v1/chat/completions"

    private val KIMI_HOSTS = listOf(
        "https://api.moonshot.ai",
        "https://api.moonshot.cn"
    )

    private const val CLAUDE_MODELS_URL = "https://api.anthropic.com/v1/models?limit=100"
    private const val CLAUDE_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    private const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"
    private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var preferredKimiBaseHost: String = KIMI_HOSTS.first()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun getClaudeApiKey(context: Context? = null): String =
        UserAiPreferences.getOrNull(context)?.getUserClaudeKey().orEmpty()

    fun getOpenAiApiKey(context: Context? = null): String =
        UserAiPreferences.getOrNull(context)?.getUserOpenAiKey().orEmpty()

    fun hasAnyExternalProviderKey(context: Context? = null): Boolean =
        DeepSeekClient.getDeepSeekApiKey(context).isNotBlank() ||
            DeepSeekClient.getKimiApiKey(context).isNotBlank() ||
            getClaudeApiKey(context).isNotBlank() ||
            getOpenAiApiKey(context).isNotBlank()

    /**
     * Queries the live `/models` endpoint of [provider] using [apiKeyRaw] and returns
     * formatted, sorted model options for selection in Settings.
     */
    suspend fun fetchSupportedModels(
        provider: AiProviderChoice,
        apiKeyRaw: String
    ): GeminiModelDiscoveryResult = withContext(Dispatchers.IO) {
        if (provider == AiProviderChoice.GEMINI || provider == AiProviderChoice.AUTO) {
            return@withContext GeminiRestClient.fetchSupportedModels(apiKeyRaw)
        }

        val cleanKey = UserAiPreferences.sanitizeKey(apiKeyRaw).ifBlank {
            when (provider) {
                AiProviderChoice.DEEPSEEK -> DeepSeekClient.getDeepSeekApiKey(null)
                AiProviderChoice.KIMI -> DeepSeekClient.getKimiApiKey(null)
                else -> ""
            }
        }
        if (cleanKey.isBlank()) {
            return@withContext GeminiModelDiscoveryResult(
                success = false,
                models = emptyList(),
                summary = "Model listelemek için önce ${provider.label} API anahtarınızı girin."
            )
        }

        return@withContext when (provider) {
            AiProviderChoice.DEEPSEEK -> fetchOpenAiStyleModels(
                providerLabel = "DeepSeek",
                urls = listOf(DEEPSEEK_MODELS_URL, "https://api.deepseek.com/v1/models"),
                apiKey = cleanKey,
                filterAndSort = { ids -> sortDeepSeekModels(ids) }
            )

            AiProviderChoice.KIMI -> fetchKimiModels(cleanKey)

            AiProviderChoice.CLAUDE -> fetchClaudeModels(cleanKey)

            AiProviderChoice.OPENAI -> fetchOpenAiStyleModels(
                providerLabel = "GPT (OpenAI)",
                urls = listOf(OPENAI_MODELS_URL),
                apiKey = cleanKey,
                filterAndSort = { ids -> filterAndSortOpenAiModels(ids) }
            )

            AiProviderChoice.GEMINI, AiProviderChoice.AUTO ->
                GeminiRestClient.fetchSupportedModels(apiKeyRaw)
        }
    }

    /**
     * Executes a live minimal request against [provider] using [candidateKey] + [candidateModelId]
     * and returns clear diagnostics for the Settings test card.
     */
    suspend fun testConnection(
        provider: AiProviderChoice,
        context: Context? = null,
        candidateKey: String? = null,
        candidateModelId: String? = null
    ): AiProviderConnectionResult = withContext(Dispatchers.IO) {
        if (provider == AiProviderChoice.GEMINI || provider == AiProviderChoice.AUTO) {
            return@withContext GeminiRestClient.testApiConnection(
                context = context,
                candidateUserKeysRaw = candidateKey,
                candidateModelId = candidateModelId
            )
        }

        val prefs = UserAiPreferences.getOrNull(context)
        val cleanCandidate = UserAiPreferences.sanitizeKey(candidateKey)
        val effectiveKey = if (cleanCandidate.isNotBlank()) {
            cleanCandidate
        } else {
            UserAiPreferences.sanitizeKey(
                when (provider) {
                    AiProviderChoice.DEEPSEEK -> DeepSeekClient.getDeepSeekApiKey(if (candidateKey == null) context else null)
                    AiProviderChoice.KIMI -> DeepSeekClient.getKimiApiKey(if (candidateKey == null) context else null)
                    AiProviderChoice.CLAUDE -> if (candidateKey == null) prefs?.getUserClaudeKey().orEmpty() else ""
                    AiProviderChoice.OPENAI -> if (candidateKey == null) prefs?.getUserOpenAiKey().orEmpty() else ""
                    else -> ""
                }
            )
        }

        if (effectiveKey.isBlank()) {
            return@withContext AiProviderConnectionResult(
                success = false,
                summary = "${provider.label} API anahtarı girilmedi.",
                details = listOf("Önce ${provider.label} API anahtarınızı yapıştırın.")
            )
        }

        val defaultModel = when (provider) {
            AiProviderChoice.DEEPSEEK -> UserAiPreferences.DEFAULT_DEEPSEEK_MODEL
            AiProviderChoice.KIMI -> UserAiPreferences.DEFAULT_KIMI_MODEL
            AiProviderChoice.CLAUDE -> UserAiPreferences.DEFAULT_CLAUDE_MODEL
            AiProviderChoice.OPENAI -> UserAiPreferences.DEFAULT_OPENAI_MODEL
            else -> UserAiPreferences.STANDARD_GEMINI_MODEL
        }
        val effectiveModel = UserAiPreferences.sanitizeProviderModelId(
            candidateModelId ?: prefs?.getSelectedModelFor(provider).orEmpty(),
            defaultModel
        )
        val masked = maskKey(effectiveKey)

        val result = runCatching {
            when (provider) {
                AiProviderChoice.CLAUDE -> executeClaudeSingleTurn(
                    apiKey = effectiveKey,
                    model = effectiveModel,
                    systemPrompt = "",
                    messages = listOf(OpenAiChatMessage("user", "Sadece OK yaz.")),
                    maxTokens = 32
                )

                AiProviderChoice.OPENAI -> executeOpenAiSingleTurn(
                    url = OPENAI_CHAT_URL,
                    apiKey = effectiveKey,
                    model = effectiveModel,
                    systemPrompt = "",
                    userPrompt = "Sadece OK yaz.",
                    maxTokens = 32
                )

                AiProviderChoice.DEEPSEEK -> executeOpenAiSingleTurn(
                    url = DEEPSEEK_CHAT_URL,
                    apiKey = effectiveKey,
                    model = effectiveModel,
                    systemPrompt = "",
                    userPrompt = "Sadece OK yaz.",
                    maxTokens = 32
                )

                AiProviderChoice.KIMI -> {
                    var lastErr: Throwable? = null
                    var reply: String? = null
                    for (host in orderedKimiHosts()) {
                        try {
                            reply = executeOpenAiSingleTurn(
                                url = "$host/v1/chat/completions",
                                apiKey = effectiveKey,
                                model = effectiveModel,
                                systemPrompt = "",
                                userPrompt = "Sadece OK yaz.",
                                maxTokens = 32
                            )
                            preferredKimiBaseHost = host
                            break
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Throwable) {
                            lastErr = e
                        }
                    }
                    reply ?: throw (lastErr ?: IllegalStateException("Kimi yanıt vermedi."))
                }

                AiProviderChoice.GEMINI, AiProviderChoice.AUTO -> "OK"
            }
        }

        return@withContext if (result.isSuccess) {
            val replyPreview = result.getOrDefault("").trim().take(80)
            AiProviderConnectionResult(
                success = true,
                summary = "${provider.shortLabel} bağlantısı başarılı ($effectiveModel)",
                details = listOf(
                    "Anahtar: $masked",
                    "Model: $effectiveModel",
                    "Yanıt: ${replyPreview.ifBlank { "OK" }}"
                )
            )
        } else {
            val err = result.exceptionOrNull()
            if (err is CancellationException) throw err
            AiProviderConnectionResult(
                success = false,
                summary = "${provider.shortLabel} bağlantısı başarısız ($effectiveModel)",
                details = listOf(
                    "Anahtar: $masked",
                    err?.localizedMessage?.take(180) ?: err?.javaClass?.simpleName ?: "Bilinmeyen hata"
                )
            )
        }
    }

    /**
     * Streams chat completion for [provider] (`DEEPSEEK`, `KIMI`, `CLAUDE`, or `OPENAI`).
     */
    suspend fun streamChatCompletion(
        provider: AiProviderChoice,
        systemPrompt: String,
        userPrompt: String,
        chatHistory: List<OpenAiChatMessage> = emptyList(),
        temperature: Float = 0.3f,
        maxTokens: Int = 4096,
        imageBase64: String? = null,
        imageMimeType: String = "image/jpeg",
        context: Context? = null,
        onAccumulatedChunk: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val prefs = UserAiPreferences.getOrNull(context)
        val accumulated = StringBuilder()

        when (provider) {
            AiProviderChoice.CLAUDE -> {
                val apiKey = prefs?.getUserClaudeKey().orEmpty()
                if (apiKey.isBlank()) throw IllegalStateException("Claude API anahtarı tanımlı değil.")
                val model = prefs?.getSelectedClaudeModel()?.ifBlank { UserAiPreferences.DEFAULT_CLAUDE_MODEL }
                    ?: UserAiPreferences.DEFAULT_CLAUDE_MODEL
                streamClaudeMessages(
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    chatHistory = chatHistory,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    imageBase64 = imageBase64,
                    imageMimeType = imageMimeType
                ) { token ->
                    accumulated.append(token)
                    onAccumulatedChunk(accumulated.toString())
                }
            }

            AiProviderChoice.OPENAI -> {
                val apiKey = prefs?.getUserOpenAiKey().orEmpty()
                if (apiKey.isBlank()) throw IllegalStateException("GPT (OpenAI) API anahtarı tanımlı değil.")
                val model = prefs?.getSelectedOpenAiModel()?.ifBlank { UserAiPreferences.DEFAULT_OPENAI_MODEL }
                    ?: UserAiPreferences.DEFAULT_OPENAI_MODEL
                streamOpenAiChatCompletions(
                    url = OPENAI_CHAT_URL,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    chatHistory = chatHistory,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    imageBase64 = imageBase64,
                    imageMimeType = imageMimeType,
                    useMaxCompletionTokensIfNeeded = true
                ) { token ->
                    accumulated.append(token)
                    onAccumulatedChunk(accumulated.toString())
                }
            }

            AiProviderChoice.DEEPSEEK -> {
                val apiKey = DeepSeekClient.getDeepSeekApiKey(context)
                if (apiKey.isBlank()) throw IllegalStateException("DeepSeek API anahtarı tanımlı değil.")
                val model = prefs?.getSelectedDeepSeekModel()?.ifBlank { UserAiPreferences.DEFAULT_DEEPSEEK_MODEL }
                    ?: UserAiPreferences.DEFAULT_DEEPSEEK_MODEL
                streamOpenAiChatCompletions(
                    url = DEEPSEEK_CHAT_URL,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    chatHistory = chatHistory,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    imageBase64 = null,
                    imageMimeType = imageMimeType,
                    useMaxCompletionTokensIfNeeded = false
                ) { token ->
                    accumulated.append(token)
                    onAccumulatedChunk(accumulated.toString())
                }
            }

            AiProviderChoice.KIMI -> {
                val apiKey = DeepSeekClient.getKimiApiKey(context)
                if (apiKey.isBlank()) throw IllegalStateException("Kimi API anahtarı tanımlı değil.")
                val model = prefs?.getSelectedKimiModel()?.ifBlank { UserAiPreferences.DEFAULT_KIMI_MODEL }
                    ?: UserAiPreferences.DEFAULT_KIMI_MODEL
                var lastErr: Throwable? = null
                for (host in orderedKimiHosts()) {
                    try {
                        streamOpenAiChatCompletions(
                            url = "$host/v1/chat/completions",
                            apiKey = apiKey,
                            model = model,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            chatHistory = chatHistory,
                            temperature = temperature,
                            maxTokens = maxTokens,
                            imageBase64 = imageBase64,
                            imageMimeType = imageMimeType,
                            useMaxCompletionTokensIfNeeded = false
                        ) { token ->
                            accumulated.append(token)
                            onAccumulatedChunk(accumulated.toString())
                        }
                        preferredKimiBaseHost = host
                        lastErr = null
                        break
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        if (accumulated.isNotEmpty()) break
                        lastErr = e
                    }
                }
                if (lastErr != null && accumulated.isEmpty()) throw lastErr
            }

            AiProviderChoice.GEMINI, AiProviderChoice.AUTO -> {
                throw IllegalArgumentException("Use GeminiAiFactory for Gemini provider.")
            }
        }

        accumulated.toString()
    }

    // ── Discovery implementations ────────────────────────────────────────────

    private fun fetchOpenAiStyleModels(
        providerLabel: String,
        urls: List<String>,
        apiKey: String,
        filterAndSort: (List<String>) -> List<GeminiModelOption>
    ): GeminiModelDiscoveryResult {
        var lastError: String? = null
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { res ->
                    val raw = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                    if (!res.isSuccessful) {
                        lastError = extractHttpErrorSummary(res.code, raw)
                        return@use
                    }
                    val root = runCatching { JSONObject(raw) }.getOrNull()
                    val data = root?.optJSONArray("data")
                    val ids = mutableListOf<String>()
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                            if (id.isNotBlank()) ids.add(id)
                        }
                    }
                    val models = filterAndSort(ids)
                    if (models.isNotEmpty()) {
                        return GeminiModelDiscoveryResult(
                            success = true,
                            models = models,
                            summary = "${models.size} $providerLabel modeli bulundu."
                        )
                    } else {
                        lastError = "$providerLabel için uygun sohbet modeli bulunamadı."
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (e is UnknownHostException || e is ConnectException) {
                    lastError = "İnternet bağlantısı kurulamadı."
                } else {
                    lastError = e.localizedMessage?.take(140) ?: e.javaClass.simpleName
                }
            }
        }
        return GeminiModelDiscoveryResult(
            success = false,
            models = emptyList(),
            summary = lastError ?: "$providerLabel modelleri alınamadı."
        )
    }

    private fun fetchKimiModels(apiKey: String): GeminiModelDiscoveryResult {
        val urls = orderedKimiHosts().map { "$it/v1/models" }
        var lastError: String? = null
        for ((idx, url) in urls.withIndex()) {
            val host = orderedKimiHosts()[idx]
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { res ->
                    val raw = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                    if (!res.isSuccessful) {
                        lastError = extractHttpErrorSummary(res.code, raw)
                        return@use
                    }
                    val root = runCatching { JSONObject(raw) }.getOrNull()
                    val data = root?.optJSONArray("data")
                    val ids = mutableListOf<String>()
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                            if (id.isNotBlank()) ids.add(id)
                        }
                    }
                    val models = sortKimiModels(ids)
                    if (models.isNotEmpty()) {
                        preferredKimiBaseHost = host
                        return GeminiModelDiscoveryResult(
                            success = true,
                            models = models,
                            summary = "${models.size} Kimi modeli bulundu."
                        )
                    } else {
                        lastError = "Kimi için model bulunamadı."
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                lastError = if (e is UnknownHostException || e is ConnectException) {
                    "İnternet bağlantısı kurulamadı."
                } else {
                    e.localizedMessage?.take(140) ?: e.javaClass.simpleName
                }
            }
        }
        return GeminiModelDiscoveryResult(
            success = false,
            models = emptyList(),
            summary = lastError ?: "Kimi modelleri alınamadı."
        )
    }

    private fun fetchClaudeModels(apiKey: String): GeminiModelDiscoveryResult {
        return try {
            val req = Request.Builder()
                .url(CLAUDE_MODELS_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()
            httpClient.newCall(req).execute().use { res ->
                val raw = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                if (!res.isSuccessful) {
                    return GeminiModelDiscoveryResult(
                        success = false,
                        models = emptyList(),
                        summary = extractHttpErrorSummary(res.code, raw)
                    )
                }
                val root = runCatching { JSONObject(raw) }.getOrNull()
                val data = root?.optJSONArray("data")
                val list = mutableListOf<GeminiModelOption>()
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val obj = data.optJSONObject(i) ?: continue
                        val id = obj.optString("id").trim()
                        if (id.isBlank()) continue
                        val displayName = obj.optString("display_name", id).ifBlank { formatModelTitle(id) }
                        list.add(
                            GeminiModelOption(
                                id = id,
                                displayName = displayName,
                                description = describeClaudeModel(id)
                            )
                        )
                    }
                }
                val sorted = list.distinctBy { it.id }
                if (sorted.isEmpty()) {
                    GeminiModelDiscoveryResult(
                        success = false,
                        models = emptyList(),
                        summary = "Claude için desteklenen model bulunamadı."
                    )
                } else {
                    GeminiModelDiscoveryResult(
                        success = true,
                        models = sorted,
                        summary = "${sorted.size} Claude modeli bulundu."
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            GeminiModelDiscoveryResult(
                success = false,
                models = emptyList(),
                summary = if (e is UnknownHostException || e is ConnectException) {
                    "İnternet bağlantısı kurulamadı."
                } else {
                    e.localizedMessage?.take(140) ?: e.javaClass.simpleName
                }
            )
        }
    }

    private fun sortDeepSeekModels(ids: List<String>): List<GeminiModelOption> {
        return ids.distinct().sortedWith(
            compareByDescending<String> { it.contains("chat", ignoreCase = true) }
                .thenByDescending { it.contains("reasoner", ignoreCase = true) }
                .thenBy { it }
        ).map { id ->
            val (title, desc) = when {
                id.contains("reasoner", ignoreCase = true) ->
                    "DeepSeek Reasoner" to "Derin akıl yürütme ve karmaşık problem çözümü"
                id.contains("chat", ignoreCase = true) ->
                    "DeepSeek Chat" to "Hızlı ve dengeli genel sohbet & anlatım modeli"
                else -> formatModelTitle(id) to "DeepSeek API modeli"
            }
            GeminiModelOption(id = id, displayName = title, description = desc)
        }
    }

    private fun sortKimiModels(ids: List<String>): List<GeminiModelOption> {
        return ids.distinct().sortedWith(
            compareByDescending<String> { it.contains("k2", ignoreCase = true) }
                .thenByDescending { it.contains("128k", ignoreCase = true) }
                .thenByDescending { it.contains("32k", ignoreCase = true) }
                .thenByDescending { it.contains("8k", ignoreCase = true) }
                .thenBy { it }
        ).map { id ->
            val desc = when {
                id.contains("k2", ignoreCase = true) -> "Kimi K2 yeni nesil akıl yürütme ve sohbet"
                id.contains("128k", ignoreCase = true) -> "Çok uzun belge ve not bağlamı (128K)"
                id.contains("32k", ignoreCase = true) -> "Uzun metin ve ders özeti bağlamı (32K)"
                id.contains("8k", ignoreCase = true) -> "Standart hızlı sohbet bağlamı (8K)"
                id.contains("vision", ignoreCase = true) -> "Görsel destekli Kimi modeli"
                else -> "Moonshot Kimi modeli"
            }
            GeminiModelOption(id = id, displayName = formatModelTitle(id), description = desc)
        }
    }

    private fun filterAndSortOpenAiModels(ids: List<String>): List<GeminiModelOption> {
        val ignoredFragments = listOf(
            "whisper", "tts", "dall-e", "embedding", "moderation",
            "realtime", "audio", "transcribe", "sora", "babbage", "davinci",
            "search-preview", "image", "codex-mini"
        )
        return ids.distinct()
            .filter { id ->
                val lower = id.lowercase(Locale.ROOT)
                val isChatFamily = lower.startsWith("gpt-") ||
                    lower.startsWith("o1") ||
                    lower.startsWith("o3") ||
                    lower.startsWith("o4") ||
                    lower.startsWith("chatgpt-")
                val isIgnored = ignoredFragments.any { lower.contains(it) }
                isChatFamily && !isIgnored
            }
            .sortedWith(
                compareByDescending<String> { it.startsWith("gpt-5", ignoreCase = true) }
                    .thenByDescending { it.startsWith("gpt-4.1", ignoreCase = true) }
                    .thenByDescending { it.startsWith("gpt-4o", ignoreCase = true) }
                    .thenByDescending { it.startsWith("o4", ignoreCase = true) || it.startsWith("o3", ignoreCase = true) }
                    .thenByDescending { it.startsWith("gpt-4", ignoreCase = true) }
                    .thenBy { it }
            )
            .map { id ->
                val lower = id.lowercase(Locale.ROOT)
                val desc = when {
                    lower.startsWith("gpt-5") -> "Yeni nesil amiral OpenAI GPT-5 ailesi"
                    lower.startsWith("gpt-4.1") -> "Yüksek doğruluklu GPT-4.1 modeli"
                    lower.startsWith("gpt-4o-mini") -> "Hızlı ve düşük maliyetli multimodal GPT-4o mini"
                    lower.startsWith("gpt-4o") -> "Çok yönlü multimodal GPT-4o"
                    lower.startsWith("o4") || lower.startsWith("o3") || lower.startsWith("o1") ->
                        "İleri düzey akıl yürütme (Reasoning) modeli"
                    else -> "OpenAI sohbet modeli"
                }
                GeminiModelOption(id = id, displayName = formatModelTitle(id), description = desc)
            }
    }

    private fun describeClaudeModel(id: String): String {
        val lower = id.lowercase(Locale.ROOT)
        return when {
            lower.contains("opus") -> "En yüksek derinlik ve karmaşık analiz (Claude Opus)"
            lower.contains("sonnet") -> "Hız ve yüksek akıl yürütme dengesi (Claude Sonnet)"
            lower.contains("haiku") -> "Ultra hızlı ve hafif yanıt (Claude Haiku)"
            else -> "Anthropic Claude modeli"
        }
    }

    private fun formatModelTitle(id: String): String {
        return id.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                if (part.equals("gpt", true)) "GPT"
                else if (part.length <= 3 && part.any { it.isDigit() }) part.uppercase(Locale.ROOT)
                else part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString() }
            }
    }

    // ── Execution: Claude (Anthropic /v1/messages) ───────────────────────────

    private fun normalizeClaudeMessages(
        chatHistory: List<OpenAiChatMessage>,
        userPrompt: String,
        imageBase64: String? = null,
        imageMimeType: String = "image/jpeg"
    ): List<Map<String, Any>> {
        val rawPairs = mutableListOf<Pair<String, String>>()
        for (turn in chatHistory.takeLast(8)) {
            val role = if (turn.role.equals("assistant", true) || turn.role.equals("model", true)) "assistant" else "user"
            val text = turn.content.trim()
            if (text.isNotBlank()) {
                rawPairs.add(role to text)
            }
        }

        // Merge consecutive same-role entries; Claude requires first message role == "user"
        val merged = mutableListOf<Pair<String, String>>()
        for ((role, text) in rawPairs) {
            if (merged.isEmpty() && role == "assistant") continue
            val last = merged.lastOrNull()
            if (last != null && last.first == role) {
                merged[merged.lastIndex] = role to "${last.second}\n\n$text"
            } else {
                merged.add(role to text)
            }
        }

        val cleanUserPrompt = userPrompt.trim().ifBlank { "Eklenen görseli incele." }
        if (imageBase64.isNullOrBlank()) {
            val last = merged.lastOrNull()
            if (last != null && last.first == "user") {
                merged[merged.lastIndex] = "user" to "${last.second}\n\n$cleanUserPrompt"
            } else {
                merged.add("user" to cleanUserPrompt)
            }
            return merged.map { (role, content) -> mapOf("role" to role, "content" to content) }
        } else {
            val userBlocks = listOf(
                mapOf(
                    "type" to "image",
                    "source" to mapOf(
                        "type" to "base64",
                        "media_type" to imageMimeType,
                        "data" to imageBase64
                    )
                ),
                mapOf("type" to "text", "text" to cleanUserPrompt)
            )
            // Ensure previous message isn't also "user"
            if (merged.lastOrNull()?.first == "user") {
                val prevUserText = merged.removeAt(merged.lastIndex).second
                val combinedBlocks = listOf(
                    userBlocks[0],
                    mapOf("type" to "text", "text" to "$prevUserText\n\n$cleanUserPrompt")
                )
                return merged.map { (r, c) -> mapOf("role" to r, "content" to c) } +
                    listOf(mapOf("role" to "user", "content" to combinedBlocks))
            }
            return merged.map { (r, c) -> mapOf("role" to r, "content" to c) } +
                listOf(mapOf("role" to "user", "content" to userBlocks))
        }
    }

    private fun executeClaudeSingleTurn(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<OpenAiChatMessage>,
        maxTokens: Int
    ): String {
        val bodyMap = linkedMapOf<String, Any>(
            "model" to model,
            "max_tokens" to maxTokens,
            "messages" to messages.map {
                mapOf(
                    "role" to if (it.role.equals("assistant", true)) "assistant" else "user",
                    "content" to it.content
                )
            }
        )
        if (systemPrompt.isNotBlank()) {
            bodyMap["system"] = systemPrompt
        }

        val req = Request.Builder()
            .url(CLAUDE_MODELS_URL.substringBefore("/models") + "/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(bodyMap).toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(req).execute().use { res ->
            val raw = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
            if (!res.isSuccessful) {
                throw IllegalStateException(extractHttpErrorSummary(res.code, raw))
            }
            val root = JSONObject(raw)
            val arr = root.optJSONArray("content")
            val text = buildString {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("type") == "text") {
                            append(item.optString("text"))
                        }
                    }
                }
            }.trim()
            if (text.isBlank()) throw IllegalStateException("Claude boş yanıt döndürdü.")
            return text
        }
    }

    private suspend fun streamClaudeMessages(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        chatHistory: List<OpenAiChatMessage>,
        temperature: Float,
        maxTokens: Int,
        imageBase64: String?,
        imageMimeType: String,
        onToken: suspend (String) -> Unit
    ) {
        val normalizedMsgs = normalizeClaudeMessages(chatHistory, userPrompt, imageBase64, imageMimeType)
        val bodyMap = linkedMapOf<String, Any>(
            "model" to model,
            "max_tokens" to maxTokens.coerceAtLeast(1024),
            "temperature" to temperature.coerceIn(0f, 1f),
            "stream" to true,
            "messages" to normalizedMsgs
        )
        if (systemPrompt.isNotBlank()) {
            bodyMap["system"] = systemPrompt.trim()
        }

        val req = Request.Builder()
            .url(CLAUDE_MESSAGES_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(gson.toJson(bodyMap).toRequestBody(jsonMediaType))
            .build()

        val res = httpClient.newCall(req).execute()
        if (!res.isSuccessful) {
            val errRaw = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
            res.close()
            throw IllegalStateException(extractHttpErrorSummary(res.code, errRaw))
        }

        val body = res.body ?: throw IllegalStateException("Claude yanıt gövdesi boş.")
        body.byteStream().use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val payload = trimmed.substring(5).trim()
                        if (payload.isNotEmpty() && payload != "[DONE]") {
                            val obj = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull()
                            if (obj != null) {
                                when (obj.get("type")?.asString) {
                                    "content_block_delta" -> {
                                        val delta = obj.getAsJsonObject("delta")
                                        val deltaType = delta?.get("type")?.asString
                                        if (deltaType == "text_delta") {
                                            val token = delta.get("text")?.takeIf { !it.isJsonNull }?.asString
                                            if (!token.isNullOrEmpty()) onToken(token)
                                        }
                                    }
                                    "error" -> {
                                        val msg = obj.getAsJsonObject("error")?.get("message")?.asString
                                            ?: "Claude stream hatası"
                                        throw IllegalStateException(msg)
                                    }
                                    "message_stop" -> break
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
    }

    // ── Execution: OpenAI-compatible (GPT / DeepSeek / Kimi) ─────────────────

    private fun isNewOpenAiReasoningModel(model: String): Boolean {
        val lower = model.lowercase(Locale.ROOT)
        return lower.startsWith("o1") ||
            lower.startsWith("o3") ||
            lower.startsWith("o4") ||
            lower.startsWith("gpt-5")
    }

    private fun executeOpenAiSingleTurn(
        url: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): String {
        val msgs = buildList {
            if (systemPrompt.isNotBlank()) {
                add(mapOf("role" to "system", "content" to systemPrompt))
            }
            add(mapOf("role" to "user", "content" to userPrompt))
        }

        val useCompletionTokens = url.contains("openai.com") && isNewOpenAiReasoningModel(model)
        val bodyMap = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to msgs,
            "stream" to false
        )
        if (useCompletionTokens) {
            bodyMap["max_completion_tokens"] = maxTokens
        } else {
            bodyMap["max_tokens"] = maxTokens
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(bodyMap).toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(req).execute().use { res ->
            val raw = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
            if (!res.isSuccessful) {
                // Retry once with max_completion_tokens if OpenAI rejects max_tokens
                if (res.code == 400 && !useCompletionTokens && raw.contains("max_completion_tokens", ignoreCase = true)) {
                    val retryMap = linkedMapOf<String, Any>(
                        "model" to model,
                        "messages" to msgs,
                        "max_completion_tokens" to maxTokens,
                        "stream" to false
                    )
                    val retryReq = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(gson.toJson(retryMap).toRequestBody(jsonMediaType))
                        .build()
                    httpClient.newCall(retryReq).execute().use { retryRes ->
                        val retryRaw = runCatching { retryRes.body?.string().orEmpty() }.getOrDefault("")
                        if (!retryRes.isSuccessful) {
                            throw IllegalStateException(extractHttpErrorSummary(retryRes.code, retryRaw))
                        }
                        return extractOpenAiNonStreamText(retryRaw)
                    }
                }
                throw IllegalStateException(extractHttpErrorSummary(res.code, raw))
            }
            return extractOpenAiNonStreamText(raw)
        }
    }

    private fun extractOpenAiNonStreamText(rawJson: String): String {
        val root = JSONObject(rawJson)
        val choices = root.optJSONArray("choices")
        val first = choices?.optJSONObject(0)
        val msg = first?.optJSONObject("message")
        val text = msg?.optString("content")?.trim().orEmpty()
        if (text.isBlank()) throw IllegalStateException("Model boş yanıt döndürdü.")
        return text
    }

    private suspend fun streamOpenAiChatCompletions(
        url: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        chatHistory: List<OpenAiChatMessage>,
        temperature: Float,
        maxTokens: Int,
        imageBase64: String?,
        imageMimeType: String,
        useMaxCompletionTokensIfNeeded: Boolean,
        onToken: suspend (String) -> Unit
    ) {
        val messagesPayload = buildList<Map<String, Any>> {
            if (systemPrompt.isNotBlank()) {
                add(mapOf("role" to "system", "content" to systemPrompt.trim()))
            }
            for (turn in chatHistory.takeLast(6)) {
                val role = if (turn.role.equals("assistant", true) || turn.role.equals("model", true)) "assistant" else "user"
                if (turn.content.isNotBlank()) {
                    add(mapOf("role" to role, "content" to turn.content))
                }
            }
            val cleanPrompt = userPrompt.trim().ifBlank { "Eklenen görseli incele." }
            if (!imageBase64.isNullOrBlank()) {
                add(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "text", "text" to cleanPrompt),
                            mapOf(
                                "type" to "image_url",
                                "image_url" to mapOf("url" to "data:$imageMimeType;base64,$imageBase64")
                            )
                        )
                    )
                )
            } else {
                add(mapOf("role" to "user", "content" to cleanPrompt))
            }
        }

        val preferCompletionTokens = useMaxCompletionTokensIfNeeded && isNewOpenAiReasoningModel(model)

        fun buildRequest(forceCompletionTokens: Boolean, omitTemperature: Boolean): Request {
            val map = linkedMapOf<String, Any>(
                "model" to model,
                "messages" to messagesPayload,
                "stream" to true
            )
            if (forceCompletionTokens) {
                map["max_completion_tokens"] = maxTokens
            } else {
                map["max_tokens"] = maxTokens
            }
            if (!omitTemperature && !model.contains("reasoner", ignoreCase = true)) {
                map["temperature"] = temperature
            }
            return Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(gson.toJson(map).toRequestBody(jsonMediaType))
                .build()
        }

        var response = httpClient.newCall(buildRequest(preferCompletionTokens, preferCompletionTokens)).execute()
        if (!response.isSuccessful && response.code == 400) {
            val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            response.close()
            if (errBody.contains("max_completion_tokens", true) || errBody.contains("temperature", true)) {
                response = httpClient.newCall(buildRequest(forceCompletionTokens = true, omitTemperature = true)).execute()
            } else {
                throw IllegalStateException(extractHttpErrorSummary(400, errBody))
            }
        }

        if (!response.isSuccessful) {
            val errRaw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            response.close()
            throw IllegalStateException(extractHttpErrorSummary(response.code, errRaw))
        }

        val body = response.body ?: throw IllegalStateException("Boş yanıt gövdesi ($url)")
        body.byteStream().use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val payload = trimmed.substring(5).trim()
                        if (payload == "[DONE]") break
                        if (payload.isNotEmpty()) {
                            val (token, finishReason) = parseOpenAiDelta(payload)
                            if (!token.isNullOrEmpty()) {
                                onToken(token)
                            }
                            if (finishReason == "stop" || finishReason == "length") break
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
    }

    private fun parseOpenAiDelta(jsonLine: String): Pair<String?, String?> {
        return runCatching {
            val root = gson.fromJson(jsonLine, JsonObject::class.java)
            val choices = root.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val first = choices[0].asJsonObject
                val finishReason = first.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString
                val delta = first.getAsJsonObject("delta")
                val content = if (delta != null && delta.has("content")) {
                    val elem = delta.get("content")
                    if (!elem.isJsonNull) elem.asString else null
                } else null
                content to finishReason
            } else null to null
        }.getOrDefault(null to null)
    }

    private fun orderedKimiHosts(): List<String> {
        val current = preferredKimiBaseHost
        return listOf(current) + KIMI_HOSTS.filter { it != current }
    }

    private fun extractHttpErrorSummary(code: Int, rawBody: String): String {
        val parsedMsg = runCatching {
            val root = JSONObject(rawBody)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        val cleanMsg = parsedMsg?.take(150) ?: rawBody.take(150).ifBlank { "HTTP $code" }
        return "HTTP $code: $cleanMsg"
    }

    private fun maskKey(key: String): String {
        val clean = UserAiPreferences.sanitizeKey(key)
        return if (clean.length <= 8) "***" else "${clean.take(4)}...${clean.takeLast(4)}"
    }
}
