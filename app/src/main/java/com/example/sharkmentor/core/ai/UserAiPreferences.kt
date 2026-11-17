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

enum class AiProviderChoice(val id: String, val label: String) {
    AUTO("auto", "Otomatik"),
    GEMINI("gemini", "Gemini"),
    DEEPSEEK("deepseek", "DeepSeek"),
    KIMI("kimi", "Kimi");

    companion object {
        fun fromId(raw: String?): AiProviderChoice =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                ?: AUTO
    }
}

enum class PreferredPowerMode(val id: String, val label: String) {
    FLASH("flash", "Hızlı"),
    PRO("pro", "Detaylı");

    companion object {
        fun fromId(raw: String?): PreferredPowerMode =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                ?: FLASH
    }
}

/**
 * SharedPreferences-backed singleton holding user-supplied AI API keys (with fallback to BuildConfig),
 * custom key model selection (standard built-in keys strictly stay on gemini-3.6-flash),
 * and preferred AI provider / power mode selections.
 */
class UserAiPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private val _userDeepSeekKey = MutableStateFlow(
        sanitizeKey(prefs.getString(KEY_DEEPSEEK_KEY, "").orEmpty())
    )
    val userDeepSeekKey: StateFlow<String> = _userDeepSeekKey.asStateFlow()

    private val _userKimiKey = MutableStateFlow(
        sanitizeKey(prefs.getString(KEY_KIMI_KEY, "").orEmpty())
    )
    val userKimiKey: StateFlow<String> = _userKimiKey.asStateFlow()

    private val _selectedAiProvider = MutableStateFlow(
        AiProviderChoice.fromId(prefs.getString(KEY_AI_PROVIDER, AiProviderChoice.AUTO.id))
    )
    val selectedAiProvider: StateFlow<AiProviderChoice> = _selectedAiProvider.asStateFlow()

    private val _preferredPowerMode = MutableStateFlow(
        PreferredPowerMode.fromId(prefs.getString(KEY_POWER_MODE, PreferredPowerMode.FLASH.id))
    )
    val preferredPowerMode: StateFlow<PreferredPowerMode> = _preferredPowerMode.asStateFlow()

    fun getUserGeminiKeysRaw(): String = _userGeminiKeys.value

    fun getParsedUserGeminiKeys(): List<String> = parseGeminiKeyList(_userGeminiKeys.value)

    fun getSelectedUserGeminiModel(): String = _selectedUserGeminiModel.value

    fun getDiscoveredGeminiModels(): List<GeminiModelOption> = _discoveredGeminiModels.value

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

    fun getUserDeepSeekKey(): String = sanitizeKey(_userDeepSeekKey.value)

    fun getUserKimiKey(): String = sanitizeKey(_userKimiKey.value)

    fun getSelectedProvider(): AiProviderChoice = _selectedAiProvider.value

    fun getPreferredPowerMode(): PreferredPowerMode = _preferredPowerMode.value

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
        val distinctModels = models
            .mapNotNull { opt ->
                val cleanId = opt.id.trim().removePrefix("models/").removePrefix("/")
                if (cleanId.isBlank()) null else opt.copy(id = cleanId, displayName = opt.displayName.ifBlank { cleanId })
            }
            .distinctBy { it.id }
        prefs.edit().putString(KEY_DISCOVERED_GEMINI_MODELS, encodeModelOptions(distinctModels)).apply()
        _discoveredGeminiModels.value = distinctModels
    }

    fun setUserDeepSeekKey(key: String) {
        val cleaned = sanitizeKey(key)
        prefs.edit().putString(KEY_DEEPSEEK_KEY, cleaned).apply()
        _userDeepSeekKey.value = cleaned
    }

    fun setUserKimiKey(key: String) {
        val cleaned = sanitizeKey(key)
        prefs.edit().putString(KEY_KIMI_KEY, cleaned).apply()
        _userKimiKey.value = cleaned
    }

    fun setSelectedAiProvider(provider: AiProviderChoice) {
        prefs.edit().putString(KEY_AI_PROVIDER, provider.id).apply()
        _selectedAiProvider.value = provider
    }

    fun setPreferredPowerMode(mode: PreferredPowerMode) {
        prefs.edit().putString(KEY_POWER_MODE, mode.id).apply()
        _preferredPowerMode.value = mode
    }

    fun saveSettings(
        geminiKeys: String,
        deepSeekKey: String,
        kimiKey: String,
        provider: AiProviderChoice,
        powerMode: PreferredPowerMode,
        selectedGeminiModel: String = _selectedUserGeminiModel.value,
        discoveredModels: List<GeminiModelOption>? = null
    ) {
        val cleanGemini = sanitizeRawGeminiKeys(geminiKeys)
        val cleanDeepSeek = sanitizeKey(deepSeekKey)
        val cleanKimi = sanitizeKey(kimiKey)
        val cleanModel = if (cleanGemini.isBlank()) STANDARD_GEMINI_MODEL else sanitizeModelId(selectedGeminiModel)

        val editor = prefs.edit()
            .putString(KEY_GEMINI_KEYS, cleanGemini)
            .putString(KEY_DEEPSEEK_KEY, cleanDeepSeek)
            .putString(KEY_KIMI_KEY, cleanKimi)
            .putString(KEY_AI_PROVIDER, provider.id)
            .putString(KEY_POWER_MODE, powerMode.id)
            .putString(KEY_SELECTED_GEMINI_MODEL, cleanModel)

        if (cleanGemini.isBlank()) {
            editor.remove(KEY_DISCOVERED_GEMINI_MODELS)
            _discoveredGeminiModels.value = emptyList()
        } else if (discoveredModels != null) {
            val cleanList = discoveredModels.distinctBy { it.id }
            editor.putString(KEY_DISCOVERED_GEMINI_MODELS, encodeModelOptions(cleanList))
            _discoveredGeminiModels.value = cleanList
        }

        editor.apply()
        _userGeminiKeys.value = cleanGemini
        _selectedUserGeminiModel.value = cleanModel
        _userDeepSeekKey.value = cleanDeepSeek
        _userKimiKey.value = cleanKimi
        _selectedAiProvider.value = provider
        _preferredPowerMode.value = powerMode
        ApiKeyRotator.invalidateAndReset()
    }

    fun clearAllKeys() {
        prefs.edit()
            .remove(KEY_GEMINI_KEYS)
            .remove(KEY_SELECTED_GEMINI_MODEL)
            .remove(KEY_DISCOVERED_GEMINI_MODELS)
            .remove(KEY_DEEPSEEK_KEY)
            .remove(KEY_KIMI_KEY)
            .apply()
        _userGeminiKeys.value = ""
        _selectedUserGeminiModel.value = STANDARD_GEMINI_MODEL
        _discoveredGeminiModels.value = emptyList()
        _userDeepSeekKey.value = ""
        _userKimiKey.value = ""
        ApiKeyRotator.invalidateAndReset()
    }

    companion object {
        const val STANDARD_GEMINI_MODEL = "gemini-3.6-flash"
        private const val PREFS_NAME = "sharkmentor_user_ai_prefs"
        private const val KEY_GEMINI_KEYS = "user_gemini_keys"
        private const val KEY_SELECTED_GEMINI_MODEL = "selected_user_gemini_model"
        private const val KEY_DISCOVERED_GEMINI_MODELS = "discovered_gemini_models"
        private const val KEY_DEEPSEEK_KEY = "user_deepseek_key"
        private const val KEY_KIMI_KEY = "user_kimi_key"
        private const val KEY_AI_PROVIDER = "selected_ai_provider"
        private const val KEY_POWER_MODE = "preferred_power_mode"

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
