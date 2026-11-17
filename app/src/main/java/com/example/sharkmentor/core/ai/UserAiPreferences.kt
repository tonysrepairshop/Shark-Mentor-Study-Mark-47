package com.example.sharkmentor.core.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class GeminiModelOption(
    val id: String,
    val displayName: String,
    val description: String = ""
)

typealias AiModelOption = GeminiModelOption

enum class AiProviderChoice(val id: String, val label: String, val shortLabel: String) {
    AUTO("auto", "Otomatik", "Otomatik"),
    GEMINI("gemini", "Gemini", "Gemini"),
    DEEPSEEK("deepseek", "DeepSeek", "DeepSeek"),
    KIMI("kimi", "Kimi", "Kimi"),
    CLAUDE("claude", "Claude", "Claude"),
    OPENAI("openai", "GPT (OpenAI)", "GPT");

    companion object {
        fun fromId(raw: String?): AiProviderChoice =
            entries.firstOrNull {
                it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true)
            } ?: AUTO
    }
}

/**
 * SharedPreferences-backed singleton holding user-supplied AI API keys,
 * discovered/selected models for all 5 supported providers
 * (Gemini, DeepSeek, Kimi, Claude, OpenAI GPT), and preferred AI provider.
 */
class UserAiPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Gemini ───────────────────────────────────────────────────────────────
    private val _userGeminiKeys = MutableStateFlow(
        sanitizeRawGeminiKeys(prefs.getString(KEY_GEMINI_KEYS, "").orEmpty())
    )
    val userGeminiKeys: StateFlow<String> = _userGeminiKeys.asStateFlow()

    private val _selectedUserGeminiModel = MutableStateFlow(
        sanitizeModelId(prefs.getString(KEY_SELECTED_GEMINI_MODEL, STANDARD_GEMINI_MODEL).orEmpty())
    )
    val selectedUserGeminiModel: StateFlow<String> = _selectedUserGeminiModel.asStateFlow()

    private val _discoveredGeminiModels = MutableStateFlow(
        decodeModelOptions(prefs.getString(KEY_DISCOVERED_GEMINI_MODELS, "").orEmpty())
    )
    val discoveredGeminiModels: StateFlow<List<GeminiModelOption>> = _discoveredGeminiModels.asStateFlow()

    // ── DeepSeek ─────────────────────────────────────────────────────────────
    private val _userDeepSeekKey = MutableStateFlow(
        sanitizeKey(prefs.getString(KEY_DEEPSEEK_KEY, "").orEmpty())
    )
    val userDeepSeekKey: StateFlow<String> = _userDeepSeekKey.asStateFlow()

    private val _selectedDeepSeekModel = MutableStateFlow(
        sanitizeProviderModelId(
            prefs.getString(KEY_SELECTED_DEEPSEEK_MODEL, DEFAULT_DEEPSEEK_MODEL).orEmpty(),
            DEFAULT_DEEPSEEK_MODEL
        )
    )
    val selectedDeepSeekModel: StateFlow<String> = _selectedDeepSeekModel.asStateFlow()

    private val _discoveredDeepSeekModels = MutableStateFlow(
        decodeModelOptions(prefs.getString(KEY_DISCOVERED_DEEPSEEK_MODELS, "").orEmpty())
    )
    val discoveredDeepSeekModels: StateFlow<List<GeminiModelOption>> = _discoveredDeepSeekModels.asStateFlow()

    // ── Kimi (Moonshot) ──────────────────────────────────────────────────────
    private val _userKimiKey = MutableStateFlow(
        sanitizeKey(prefs.getString(KEY_KIMI_KEY, "").orEmpty())
    )
    val userKimiKey: StateFlow<String> = _userKimiKey.asStateFlow()

    private val _selectedKimiModel = MutableStateFlow(
        sanitizeProviderModelId(
            prefs.getString(KEY_SELECTED_KIMI_MODEL, DEFAULT_KIMI_MODEL).orEmpty(),
            DEFAULT_KIMI_MODEL
        )
    )
    val selectedKimiModel: StateFlow<String> = _selectedKimiModel.asStateFlow()

    private val _discoveredKimiModels = MutableStateFlow(
        decodeModelOptions(prefs.getString(KEY_DISCOVERED_KIMI_MODELS, "").orEmpty())
    )
    val discoveredKimiModels: StateFlow<List<GeminiModelOption>> = _discoveredKimiModels.asStateFlow()

    // ── Claude (Anthropic) ───────────────────────────────────────────────────
    private val _userClaudeKey = MutableStateFlow(
        sanitizeKey(prefs.getString(KEY_CLAUDE_KEY, "").orEmpty())
    )
    val userClaudeKey: StateFlow<String> = _userClaudeKey.asStateFlow()

    private val _selectedClaudeModel = MutableStateFlow(
        sanitizeProviderModelId(
            prefs.getString(KEY_SELECTED_CLAUDE_MODEL, DEFAULT_CLAUDE_MODEL).orEmpty(),
            DEFAULT_CLAUDE_MODEL
        )
    )
    val selectedClaudeModel: StateFlow<String> = _selectedClaudeModel.asStateFlow()

    private val _discoveredClaudeModels = MutableStateFlow(
        decodeModelOptions(prefs.getString(KEY_DISCOVERED_CLAUDE_MODELS, "").orEmpty())
    )
    val discoveredClaudeModels: StateFlow<List<GeminiModelOption>> = _discoveredClaudeModels.asStateFlow()

    // ── OpenAI (GPT) ─────────────────────────────────────────────────────────
    private val _userOpenAiKey = MutableStateFlow(
        sanitizeKey(prefs.getString(KEY_OPENAI_KEY, "").orEmpty())
    )
    val userOpenAiKey: StateFlow<String> = _userOpenAiKey.asStateFlow()

    private val _selectedOpenAiModel = MutableStateFlow(
        sanitizeProviderModelId(
            prefs.getString(KEY_SELECTED_OPENAI_MODEL, DEFAULT_OPENAI_MODEL).orEmpty(),
            DEFAULT_OPENAI_MODEL
        )
    )
    val selectedOpenAiModel: StateFlow<String> = _selectedOpenAiModel.asStateFlow()

    private val _discoveredOpenAiModels = MutableStateFlow(
        decodeModelOptions(prefs.getString(KEY_DISCOVERED_OPENAI_MODELS, "").orEmpty())
    )
    val discoveredOpenAiModels: StateFlow<List<GeminiModelOption>> = _discoveredOpenAiModels.asStateFlow()

    // ── Selected Active Provider ─────────────────────────────────────────────
    private val _selectedAiProvider = MutableStateFlow(
        AiProviderChoice.fromId(prefs.getString(KEY_AI_PROVIDER, AiProviderChoice.AUTO.id))
    )
    val selectedAiProvider: StateFlow<AiProviderChoice> = _selectedAiProvider.asStateFlow()

    // ── Getters ──────────────────────────────────────────────────────────────
    fun getUserGeminiKeysRaw(): String = _userGeminiKeys.value
    fun getParsedUserGeminiKeys(): List<String> = parseGeminiKeyList(_userGeminiKeys.value)
    fun getSelectedUserGeminiModel(): String = _selectedUserGeminiModel.value
    fun getDiscoveredGeminiModels(): List<GeminiModelOption> = _discoveredGeminiModels.value

    fun getUserDeepSeekKey(): String = sanitizeKey(_userDeepSeekKey.value)
    fun getSelectedDeepSeekModel(): String = _selectedDeepSeekModel.value
    fun getDiscoveredDeepSeekModels(): List<GeminiModelOption> = _discoveredDeepSeekModels.value

    fun getUserKimiKey(): String = sanitizeKey(_userKimiKey.value)
    fun getSelectedKimiModel(): String = _selectedKimiModel.value
    fun getDiscoveredKimiModels(): List<GeminiModelOption> = _discoveredKimiModels.value

    fun getUserClaudeKey(): String = sanitizeKey(_userClaudeKey.value)
    fun getSelectedClaudeModel(): String = _selectedClaudeModel.value
    fun getDiscoveredClaudeModels(): List<GeminiModelOption> = _discoveredClaudeModels.value

    fun getUserOpenAiKey(): String = sanitizeKey(_userOpenAiKey.value)
    fun getSelectedOpenAiModel(): String = _selectedOpenAiModel.value
    fun getDiscoveredOpenAiModels(): List<GeminiModelOption> = _discoveredOpenAiModels.value

    fun getSelectedProvider(): AiProviderChoice = _selectedAiProvider.value

    /**
     * Resolves which Gemini model should be used for [forKey]:
     * - Built-in default keys (or when user has no custom Gemini key) ALWAYS enforce `gemini-3.6-flash`.
     * - Custom user keys use the user's selected model (defaulting to `gemini-3.6-flash`).
     */
    fun resolveEffectiveGeminiModel(forKey: String? = null): String {
        val userKeys = getParsedUserGeminiKeys()
        if (userKeys.isEmpty()) return STANDARD_GEMINI_MODEL
        if (forKey != null) {
            val cleanedForKey = sanitizeKey(forKey)
            if (userKeys.none { it == cleanedForKey }) {
                return STANDARD_GEMINI_MODEL
            }
        }
        return sanitizeModelId(_selectedUserGeminiModel.value)
    }

    /**
     * Resolves which provider & model are currently active for runtime and display (top-left of chat).
     */
    fun getActiveProviderAndModel(): Pair<AiProviderChoice, String> {
        return when (_selectedAiProvider.value) {
            AiProviderChoice.GEMINI -> AiProviderChoice.GEMINI to resolveEffectiveGeminiModel()
            AiProviderChoice.DEEPSEEK -> AiProviderChoice.DEEPSEEK to getSelectedDeepSeekModel()
            AiProviderChoice.KIMI -> AiProviderChoice.KIMI to getSelectedKimiModel()
            AiProviderChoice.CLAUDE -> AiProviderChoice.CLAUDE to getSelectedClaudeModel()
            AiProviderChoice.OPENAI -> AiProviderChoice.OPENAI to getSelectedOpenAiModel()
            AiProviderChoice.AUTO -> {
                when {
                    getParsedUserGeminiKeys().isNotEmpty() ->
                        AiProviderChoice.GEMINI to resolveEffectiveGeminiModel()
                    getUserClaudeKey().isNotBlank() ->
                        AiProviderChoice.CLAUDE to getSelectedClaudeModel()
                    getUserOpenAiKey().isNotBlank() ->
                        AiProviderChoice.OPENAI to getSelectedOpenAiModel()
                    getUserDeepSeekKey().isNotBlank() ->
                        AiProviderChoice.DEEPSEEK to getSelectedDeepSeekModel()
                    getUserKimiKey().isNotBlank() ->
                        AiProviderChoice.KIMI to getSelectedKimiModel()
                    else ->
                        AiProviderChoice.GEMINI to STANDARD_GEMINI_MODEL
                }
            }
        }
    }

    fun getActiveDisplayText(): String {
        val (provider, model) = getActiveProviderAndModel()
        return "${provider.shortLabel} • $model"
    }

    fun getSelectedModelFor(provider: AiProviderChoice): String = when (provider) {
        AiProviderChoice.GEMINI, AiProviderChoice.AUTO -> resolveEffectiveGeminiModel()
        AiProviderChoice.DEEPSEEK -> getSelectedDeepSeekModel()
        AiProviderChoice.KIMI -> getSelectedKimiModel()
        AiProviderChoice.CLAUDE -> getSelectedClaudeModel()
        AiProviderChoice.OPENAI -> getSelectedOpenAiModel()
    }

    fun setUserGeminiKeys(raw: String) {
        val cleaned = sanitizeRawGeminiKeys(raw)
        val editor = prefs.edit().putString(KEY_GEMINI_KEYS, cleaned)
        if (cleaned.isBlank()) {
            editor.putString(KEY_SELECTED_GEMINI_MODEL, STANDARD_GEMINI_MODEL)
            editor.remove(KEY_DISCOVERED_GEMINI_MODELS)
            _selectedUserGeminiModel.value = STANDARD_GEMINI_MODEL
            _discoveredGeminiModels.value = emptyList()
        }
        editor.apply()
        _userGeminiKeys.value = cleaned
        ApiKeyRotator.invalidateAndReset()
    }

    fun setSelectedUserGeminiModel(modelId: String) {
        val cleaned = sanitizeModelId(modelId)
        prefs.edit().putString(KEY_SELECTED_GEMINI_MODEL, cleaned).apply()
        _selectedUserGeminiModel.value = cleaned
    }

    fun setDiscoveredGeminiModels(models: List<GeminiModelOption>) {
        val distinctModels = cleanDistinctModels(models)
        prefs.edit().putString(KEY_DISCOVERED_GEMINI_MODELS, encodeModelOptions(distinctModels)).apply()
        _discoveredGeminiModels.value = distinctModels
    }

    fun setSelectedAiProvider(provider: AiProviderChoice) {
        prefs.edit().putString(KEY_AI_PROVIDER, provider.id).apply()
        _selectedAiProvider.value = provider
    }

    fun saveSettings(
        geminiKeys: String,
        deepSeekKey: String,
        kimiKey: String,
        claudeKey: String,
        openAiKey: String,
        provider: AiProviderChoice,
        selectedGeminiModel: String = _selectedUserGeminiModel.value,
        discoveredGeminiModels: List<GeminiModelOption>? = null,
        selectedDeepSeekModel: String = _selectedDeepSeekModel.value,
        discoveredDeepSeekModels: List<GeminiModelOption>? = null,
        selectedKimiModel: String = _selectedKimiModel.value,
        discoveredKimiModels: List<GeminiModelOption>? = null,
        selectedClaudeModel: String = _selectedClaudeModel.value,
        discoveredClaudeModels: List<GeminiModelOption>? = null,
        selectedOpenAiModel: String = _selectedOpenAiModel.value,
        discoveredOpenAiModels: List<GeminiModelOption>? = null
    ) {
        val cleanGemini = sanitizeRawGeminiKeys(geminiKeys)
        val cleanDeepSeek = sanitizeKey(deepSeekKey)
        val cleanKimi = sanitizeKey(kimiKey)
        val cleanClaude = sanitizeKey(claudeKey)
        val cleanOpenAi = sanitizeKey(openAiKey)

        val effGeminiModel = if (cleanGemini.isBlank()) STANDARD_GEMINI_MODEL else sanitizeModelId(selectedGeminiModel)
        val effDeepSeekModel = if (cleanDeepSeek.isBlank()) DEFAULT_DEEPSEEK_MODEL else sanitizeProviderModelId(selectedDeepSeekModel, DEFAULT_DEEPSEEK_MODEL)
        val effKimiModel = if (cleanKimi.isBlank()) DEFAULT_KIMI_MODEL else sanitizeProviderModelId(selectedKimiModel, DEFAULT_KIMI_MODEL)
        val effClaudeModel = if (cleanClaude.isBlank()) DEFAULT_CLAUDE_MODEL else sanitizeProviderModelId(selectedClaudeModel, DEFAULT_CLAUDE_MODEL)
        val effOpenAiModel = if (cleanOpenAi.isBlank()) DEFAULT_OPENAI_MODEL else sanitizeProviderModelId(selectedOpenAiModel, DEFAULT_OPENAI_MODEL)

        val editor = prefs.edit()
            .putString(KEY_GEMINI_KEYS, cleanGemini)
            .putString(KEY_SELECTED_GEMINI_MODEL, effGeminiModel)
            .putString(KEY_DEEPSEEK_KEY, cleanDeepSeek)
            .putString(KEY_SELECTED_DEEPSEEK_MODEL, effDeepSeekModel)
            .putString(KEY_KIMI_KEY, cleanKimi)
            .putString(KEY_SELECTED_KIMI_MODEL, effKimiModel)
            .putString(KEY_CLAUDE_KEY, cleanClaude)
            .putString(KEY_SELECTED_CLAUDE_MODEL, effClaudeModel)
            .putString(KEY_OPENAI_KEY, cleanOpenAi)
            .putString(KEY_SELECTED_OPENAI_MODEL, effOpenAiModel)
            .putString(KEY_AI_PROVIDER, provider.id)

        fun applyModelList(
            hasKey: Boolean,
            list: List<GeminiModelOption>?,
            prefKey: String,
            flow: MutableStateFlow<List<GeminiModelOption>>
        ) {
            if (!hasKey) {
                editor.remove(prefKey)
                flow.value = emptyList()
            } else if (list != null) {
                val cleanList = cleanDistinctModels(list)
                editor.putString(prefKey, encodeModelOptions(cleanList))
                flow.value = cleanList
            }
        }

        applyModelList(cleanGemini.isNotBlank(), discoveredGeminiModels, KEY_DISCOVERED_GEMINI_MODELS, _discoveredGeminiModels)
        applyModelList(cleanDeepSeek.isNotBlank(), discoveredDeepSeekModels, KEY_DISCOVERED_DEEPSEEK_MODELS, _discoveredDeepSeekModels)
        applyModelList(cleanKimi.isNotBlank(), discoveredKimiModels, KEY_DISCOVERED_KIMI_MODELS, _discoveredKimiModels)
        applyModelList(cleanClaude.isNotBlank(), discoveredClaudeModels, KEY_DISCOVERED_CLAUDE_MODELS, _discoveredClaudeModels)
        applyModelList(cleanOpenAi.isNotBlank(), discoveredOpenAiModels, KEY_DISCOVERED_OPENAI_MODELS, _discoveredOpenAiModels)

        editor.apply()

        _userGeminiKeys.value = cleanGemini
        _selectedUserGeminiModel.value = effGeminiModel
        _userDeepSeekKey.value = cleanDeepSeek
        _selectedDeepSeekModel.value = effDeepSeekModel
        _userKimiKey.value = cleanKimi
        _selectedKimiModel.value = effKimiModel
        _userClaudeKey.value = cleanClaude
        _selectedClaudeModel.value = effClaudeModel
        _userOpenAiKey.value = cleanOpenAi
        _selectedOpenAiModel.value = effOpenAiModel
        _selectedAiProvider.value = provider

        ApiKeyRotator.invalidateAndReset()
    }

    fun clearAllKeys() {
        prefs.edit()
            .remove(KEY_GEMINI_KEYS)
            .remove(KEY_SELECTED_GEMINI_MODEL)
            .remove(KEY_DISCOVERED_GEMINI_MODELS)
            .remove(KEY_DEEPSEEK_KEY)
            .remove(KEY_SELECTED_DEEPSEEK_MODEL)
            .remove(KEY_DISCOVERED_DEEPSEEK_MODELS)
            .remove(KEY_KIMI_KEY)
            .remove(KEY_SELECTED_KIMI_MODEL)
            .remove(KEY_DISCOVERED_KIMI_MODELS)
            .remove(KEY_CLAUDE_KEY)
            .remove(KEY_SELECTED_CLAUDE_MODEL)
            .remove(KEY_DISCOVERED_CLAUDE_MODELS)
            .remove(KEY_OPENAI_KEY)
            .remove(KEY_SELECTED_OPENAI_MODEL)
            .remove(KEY_DISCOVERED_OPENAI_MODELS)
            .apply()

        _userGeminiKeys.value = ""
        _selectedUserGeminiModel.value = STANDARD_GEMINI_MODEL
        _discoveredGeminiModels.value = emptyList()
        _userDeepSeekKey.value = ""
        _selectedDeepSeekModel.value = DEFAULT_DEEPSEEK_MODEL
        _discoveredDeepSeekModels.value = emptyList()
        _userKimiKey.value = ""
        _selectedKimiModel.value = DEFAULT_KIMI_MODEL
        _discoveredKimiModels.value = emptyList()
        _userClaudeKey.value = ""
        _selectedClaudeModel.value = DEFAULT_CLAUDE_MODEL
        _discoveredClaudeModels.value = emptyList()
        _userOpenAiKey.value = ""
        _selectedOpenAiModel.value = DEFAULT_OPENAI_MODEL
        _discoveredOpenAiModels.value = emptyList()

        ApiKeyRotator.invalidateAndReset()
    }

    companion object {
        const val STANDARD_GEMINI_MODEL = "gemini-3.6-flash"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"
        const val DEFAULT_KIMI_MODEL = "moonshot-v1-8k"
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-5"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        private const val PREFS_NAME = "sharkmentor_user_ai_prefs"

        private const val KEY_GEMINI_KEYS = "user_gemini_keys"
        private const val KEY_SELECTED_GEMINI_MODEL = "selected_user_gemini_model"
        private const val KEY_DISCOVERED_GEMINI_MODELS = "discovered_gemini_models"

        private const val KEY_DEEPSEEK_KEY = "user_deepseek_key"
        private const val KEY_SELECTED_DEEPSEEK_MODEL = "selected_deepseek_model"
        private const val KEY_DISCOVERED_DEEPSEEK_MODELS = "discovered_deepseek_models"

        private const val KEY_KIMI_KEY = "user_kimi_key"
        private const val KEY_SELECTED_KIMI_MODEL = "selected_kimi_model"
        private const val KEY_DISCOVERED_KIMI_MODELS = "discovered_kimi_models"

        private const val KEY_CLAUDE_KEY = "user_claude_key"
        private const val KEY_SELECTED_CLAUDE_MODEL = "selected_claude_model"
        private const val KEY_DISCOVERED_CLAUDE_MODELS = "discovered_claude_models"

        private const val KEY_OPENAI_KEY = "user_openai_key"
        private const val KEY_SELECTED_OPENAI_MODEL = "selected_openai_model"
        private const val KEY_DISCOVERED_OPENAI_MODELS = "discovered_openai_models"

        private const val KEY_AI_PROVIDER = "selected_ai_provider"

        @Volatile
        private var INSTANCE: UserAiPreferences? = null

        fun initialize(context: Context): UserAiPreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserAiPreferences(context.applicationContext).also { INSTANCE = it }
            }

        fun getInstance(context: Context): UserAiPreferences = initialize(context)

        fun getOrNull(context: Context? = null): UserAiPreferences? {
            if (context != null) return initialize(context)
            INSTANCE?.let { return it }
            val resolvedApp = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()
            return resolvedApp?.let { initialize(it) }
        }

        fun resolveEffectiveGeminiModel(context: Context? = null, forKey: String? = null): String =
            getOrNull(context)?.resolveEffectiveGeminiModel(forKey) ?: STANDARD_GEMINI_MODEL

        fun sanitizeModelId(raw: String): String =
            raw.trim().removePrefix("models/").removePrefix("/").ifBlank { STANDARD_GEMINI_MODEL }

        fun sanitizeProviderModelId(raw: String, fallback: String): String =
            raw.trim().removePrefix("models/").removePrefix("/").ifBlank { fallback }

        fun sanitizeKey(raw: String): String =
            raw.replace("\r", "")
                .replace("\n", "")
                .replace("\"", "")
                .replace("'", "")
                .trim()

        fun sanitizeRawGeminiKeys(raw: String): String =
            parseGeminiKeyList(raw).joinToString(", ")

        fun parseGeminiKeyList(raw: String): List<String> =
            raw.split(",", "\n", "\r", ";", " ", "\t")
                .map { sanitizeKey(it) }
                .filter { it.isNotBlank() && it != "null" }
                .distinct()

        private fun cleanDistinctModels(models: List<GeminiModelOption>): List<GeminiModelOption> =
            models.mapNotNull { opt ->
                val cleanId = opt.id.trim().removePrefix("models/").removePrefix("/")
                if (cleanId.isBlank()) null
                else opt.copy(id = cleanId, displayName = opt.displayName.ifBlank { cleanId })
            }.distinctBy { it.id }

        private fun encodeModelOptions(options: List<GeminiModelOption>): String {
            val arr = JSONArray()
            for (opt in options) {
                arr.put(
                    JSONObject()
                        .put("id", opt.id)
                        .put("displayName", opt.displayName)
                        .put("description", opt.description)
                )
            }
            return arr.toString()
        }

        private fun decodeModelOptions(raw: String): List<GeminiModelOption> {
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").trim()
                        if (id.isNotBlank()) {
                            add(
                                GeminiModelOption(
                                    id = id,
                                    displayName = obj.optString("displayName", id).ifBlank { id },
                                    description = obj.optString("description", "")
                                )
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
