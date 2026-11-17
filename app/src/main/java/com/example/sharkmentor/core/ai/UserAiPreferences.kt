package com.example.sharkmentor.core.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * SharedPreferences-backed singleton holding user-supplied AI API keys (with fallback to BuildConfig)
 * and preferred AI provider / power mode selections.
 */
class UserAiPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _userGeminiKeys = MutableStateFlow(
        sanitizeRawGeminiKeys(prefs.getString(KEY_GEMINI_KEYS, "").orEmpty())
    )
    val userGeminiKeys: StateFlow<String> = _userGeminiKeys.asStateFlow()

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

    fun getUserDeepSeekKey(): String = sanitizeKey(_userDeepSeekKey.value)

    fun getUserKimiKey(): String = sanitizeKey(_userKimiKey.value)

    fun getSelectedProvider(): AiProviderChoice = _selectedAiProvider.value

    fun getPreferredPowerMode(): PreferredPowerMode = _preferredPowerMode.value

    fun setUserGeminiKeys(raw: String) {
        val cleaned = sanitizeRawGeminiKeys(raw)
        prefs.edit().putString(KEY_GEMINI_KEYS, cleaned).apply()
        _userGeminiKeys.value = cleaned
        ApiKeyRotator.invalidateAndReset()
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
        powerMode: PreferredPowerMode
    ) {
        val cleanGemini = sanitizeRawGeminiKeys(geminiKeys)
        val cleanDeepSeek = sanitizeKey(deepSeekKey)
        val cleanKimi = sanitizeKey(kimiKey)
        prefs.edit()
            .putString(KEY_GEMINI_KEYS, cleanGemini)
            .putString(KEY_DEEPSEEK_KEY, cleanDeepSeek)
            .putString(KEY_KIMI_KEY, cleanKimi)
            .putString(KEY_AI_PROVIDER, provider.id)
            .putString(KEY_POWER_MODE, powerMode.id)
            .apply()
        _userGeminiKeys.value = cleanGemini
        _userDeepSeekKey.value = cleanDeepSeek
        _userKimiKey.value = cleanKimi
        _selectedAiProvider.value = provider
        _preferredPowerMode.value = powerMode
        ApiKeyRotator.invalidateAndReset()
    }

    fun clearAllKeys() {
        prefs.edit()
            .remove(KEY_GEMINI_KEYS)
            .remove(KEY_DEEPSEEK_KEY)
            .remove(KEY_KIMI_KEY)
            .apply()
        _userGeminiKeys.value = ""
        _userDeepSeekKey.value = ""
        _userKimiKey.value = ""
        ApiKeyRotator.invalidateAndReset()
    }

    companion object {
        private const val PREFS_NAME = "sharkmentor_user_ai_prefs"
        private const val KEY_GEMINI_KEYS = "user_gemini_keys"
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
    }
}
