package com.example.sharkmentor.data.topbar

import android.content.Context
import android.content.SharedPreferences
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.GeminiAiFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class TopBarTextSource(val label: String) {
    AI_SUGGESTIONS("AI Önerileri"),
    CUSTOM_TEXTS("Kendi Metinlerim"),
    AI_QUOTES("AI Motivasyon")
}

enum class TopBarAnimationType(val label: String) {
    NONE("Kapalı"),
    TYPEWRITER("Daktilo"),
    FLIP_BOX("Dönen Kutu"),
    FADE("Yumuşak Geçiş"),
    SLIDE_VERTICAL("Yukarı Kaydır"),
    SCALE_FADE("Büyüterek Geç")
}

enum class TopBarFontOption(val label: String) {
    DEFAULT("Varsayılan"),
    SANS_SERIF("Modern Sans"),
    SERIF("Klasik Serif"),
    MONOSPACE("Daktilo Mono")
}

data class TopBarSettings(
    val textSource: TopBarTextSource = TopBarTextSource.AI_SUGGESTIONS,
    val animationType: TopBarAnimationType = TopBarAnimationType.FADE,
    val fontOption: TopBarFontOption = TopBarFontOption.DEFAULT,
    val customTextColorArgb: Long? = null,
    val customTexts: List<String> = listOf(""),
    val aiQuotesTopic: String = "",
    val aiQuotes: List<String> = emptyList(),
    val hideBubbles: Boolean = false,
    val hideTopBarMenu: Boolean = false,
    val hideDuringFocus: Boolean = false
)

object FocusSessionState {
    private val _isAnyFocusRunning = MutableStateFlow(false)
    val isAnyFocusRunning: StateFlow<Boolean> = _isAnyFocusRunning.asStateFlow()

    fun setRunning(running: Boolean) {
        _isAnyFocusRunning.value = running
    }
}

class TopBarPreferences private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<TopBarSettings> = _settings.asStateFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun loadSettings(): TopBarSettings {
        val sourceName = prefs.getString(KEY_TEXT_SOURCE, TopBarTextSource.AI_SUGGESTIONS.name)
        val animName = prefs.getString(KEY_ANIMATION_TYPE, TopBarAnimationType.FADE.name)
        val fontName = prefs.getString(KEY_FONT_OPTION, TopBarFontOption.DEFAULT.name)

        val textSource = runCatching { TopBarTextSource.valueOf(sourceName ?: "") }
            .getOrDefault(TopBarTextSource.AI_SUGGESTIONS)
        val animationType = runCatching { TopBarAnimationType.valueOf(animName ?: "") }
            .getOrDefault(TopBarAnimationType.FADE)
        val fontOption = runCatching { TopBarFontOption.valueOf(fontName ?: "") }
            .getOrDefault(TopBarFontOption.DEFAULT)

        val colorArgb = if (prefs.contains(KEY_CUSTOM_COLOR)) {
            prefs.getLong(KEY_CUSTOM_COLOR, 0L)
        } else {
            null
        }

        val customTexts = readStringList(KEY_CUSTOM_TEXTS)
            .take(MAX_CUSTOM_TEXTS)
            .ifEmpty { listOf("") }

        val aiQuotesTopic = prefs.getString(KEY_AI_QUOTES_TOPIC, "").orEmpty()
        val aiQuotes = readStringList(KEY_AI_QUOTES).take(MAX_AI_QUOTES)

        return TopBarSettings(
            textSource = textSource,
            animationType = animationType,
            fontOption = fontOption,
            customTextColorArgb = colorArgb,
            customTexts = customTexts,
            aiQuotesTopic = aiQuotesTopic,
            aiQuotes = aiQuotes,
            hideBubbles = prefs.getBoolean(KEY_HIDE_BUBBLES, false),
            hideTopBarMenu = prefs.getBoolean(KEY_HIDE_TOP_BAR_MENU, false),
            hideDuringFocus = prefs.getBoolean(KEY_HIDE_DURING_FOCUS, false)
        )
    }

    private fun readStringList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { index -> arr.optString(index, "") }
        }.getOrDefault(emptyList())
    }

    private fun writeStringList(editor: SharedPreferences.Editor, key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        editor.putString(key, arr.toString())
    }

    fun setTextSource(source: TopBarTextSource) {
        prefs.edit().putString(KEY_TEXT_SOURCE, source.name).apply()
        _settings.value = _settings.value.copy(textSource = source)
    }

    fun setAnimationType(type: TopBarAnimationType) {
        prefs.edit().putString(KEY_ANIMATION_TYPE, type.name).apply()
        _settings.value = _settings.value.copy(animationType = type)
    }

    fun setFontOption(font: TopBarFontOption) {
        prefs.edit().putString(KEY_FONT_OPTION, font.name).apply()
        _settings.value = _settings.value.copy(fontOption = font)
    }

    fun setCustomTextColorArgb(colorArgb: Long?) {
        val editor = prefs.edit()
        if (colorArgb == null) {
            editor.remove(KEY_CUSTOM_COLOR)
        } else {
            editor.putLong(KEY_CUSTOM_COLOR, colorArgb)
        }
        editor.apply()
        _settings.value = _settings.value.copy(customTextColorArgb = colorArgb)
    }

    fun setCustomTexts(texts: List<String>) {
        val normalized = texts.take(MAX_CUSTOM_TEXTS).ifEmpty { listOf("") }
        prefs.edit().also { writeStringList(it, KEY_CUSTOM_TEXTS, normalized) }.apply()
        _settings.value = _settings.value.copy(customTexts = normalized)
    }

    fun setHideBubbles(hide: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_BUBBLES, hide).apply()
        _settings.value = _settings.value.copy(hideBubbles = hide)
    }

    fun setHideTopBarMenu(hide: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_TOP_BAR_MENU, hide).apply()
        _settings.value = _settings.value.copy(hideTopBarMenu = hide)
    }

    fun setHideDuringFocus(hide: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_DURING_FOCUS, hide).apply()
        _settings.value = _settings.value.copy(hideDuringFocus = hide)
    }

    suspend fun fetchMotivationalQuotes(topic: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanTopic = topic.trim()
        if (cleanTopic.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Konu boş olamaz."))
        }

        var apiKey = ApiKeyRotator.getActiveKey(appContext)
              val maxKeyAttempts = ApiKeyRotator.getKeyCount(appContext).coerceAtLeast(1)  

        val prompt = buildString {
            append("\"$cleanTopic\" filmine, dizisine veya kişisine ait en bilinen 10 motivasyon cümlesini ")
            append("Türkçe olarak ve üst barda gösterilebilecek kısa satırlar halinde yaz. ")
            append("Numaralandırma, madde işareti, tırnak işareti veya açıklama ekleme; ")
            append("her satırda yalnızca 1 cümle olacak şekilde toplam 10 satır döndür.")
        }

val models = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite")
        var lastError: Throwable? = null
        var fallbackQuotes: List<String> = emptyList()
        for (model in models) {
            for (attempt in 0 until maxKeyAttempts) { var rotated = false
                for (useSearch in listOf(true, false)) {
                    val res = requestQuotesFromGemini(apiKey, model, prompt, useGoogleSearch = useSearch)
                    if (res.isSuccess) {
                        ApiKeyRotator.reportKeySuccess(apiKey)
                        val quotes = res.getOrThrow().take(10)
                        if (quotes.size >= 5) { saveQuotesAndActivate(cleanTopic, quotes); return@withContext Result.success(quotes) }
                        if (quotes.size > fallbackQuotes.size) fallbackQuotes = quotes
                        } else {lastError = res.exceptionOrNull(); val msg = lastError?.message.orEmpty()
                    if (msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 429")) { ApiKeyRotator.reportKeyFailure(apiKey); apiKey = ApiKeyRotator.rotateToNextKey(appContext); rotated = true; break }
                    } } if (!rotated) break } } if (fallbackQuotes.isNotEmpty()) { saveQuotesAndActivate(cleanTopic, fallbackQuotes); return@withContext Result.success(fallbackQuotes) }
                        
                

        Result.failure(lastError ?: IllegalStateException("Motivasyon sözleri alınamadı."))
    }

    private fun saveQuotesAndActivate(topic: String, quotes: List<String>) {
        prefs.edit().apply {
            putString(KEY_AI_QUOTES_TOPIC, topic)
            writeStringList(this, KEY_AI_QUOTES, quotes)
            putString(KEY_TEXT_SOURCE, TopBarTextSource.AI_QUOTES.name)
        }.apply()

        _settings.value = _settings.value.copy(
            aiQuotesTopic = topic,
            aiQuotes = quotes,
            textSource = TopBarTextSource.AI_QUOTES
        )
    }

    private fun resolveGeminiApiKey(): String {
        return ApiKeyRotator.getActiveKey(appContext)
    }

    private fun requestQuotesFromGemini(
        apiKey: String,
        model: String,
        prompt: String,
        useGoogleSearch: Boolean
    ): Result<List<String>> = runCatching {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val bodyJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    }
                )
            )
            if (useGoogleSearch) {
                put(
                    "tools",
                    JSONArray().put(JSONObject().put("google_search", JSONObject()))
                )
            }
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 1024)
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $responseText")
            }
            val parsed = parseQuotesFromResponse(responseText)
            if (parsed.isEmpty()) {
                throw IllegalStateException("Boş yanıt alındı.")
            }
            parsed
        }
    }

    private fun parseQuotesFromResponse(responseText: String): List<String> {
        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates") ?: return emptyList()
        if (candidates.length() == 0) return emptyList()
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts") ?: return emptyList()

        val combined = buildString {
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                if (text.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(text)
                }
            }
        }

        return combined
            .lines()
            .map { line ->
                line.trim()
                    .replace(Regex("^\\d+[.)\\-:\\s]+"), "")
                    .replace(Regex("^[-•*]\\s*"), "")
                    .trim('"', '“', '”', '\'', ' ')
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_AI_QUOTES)
    }

    companion object {
        private const val PREFS_NAME = "shark_topbar_prefs"
        private const val KEY_TEXT_SOURCE = "text_source"
        private const val KEY_ANIMATION_TYPE = "animation_type"
        private const val KEY_FONT_OPTION = "font_option"
        private const val KEY_CUSTOM_COLOR = "custom_text_color_argb"
        private const val KEY_CUSTOM_TEXTS = "custom_texts"
        private const val KEY_AI_QUOTES_TOPIC = "ai_quotes_topic"
        private const val KEY_AI_QUOTES = "ai_quotes"
        private const val KEY_HIDE_BUBBLES = "hide_bubbles"
        private const val KEY_HIDE_TOP_BAR_MENU = "hide_top_bar_menu"
        private const val KEY_HIDE_DURING_FOCUS = "hide_during_focus"

        const val MAX_CUSTOM_TEXTS = 10
        const val MAX_AI_QUOTES = 10

        @Volatile
        private var INSTANCE: TopBarPreferences? = null

        fun getInstance(context: Context): TopBarPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TopBarPreferences(context).also { INSTANCE = it }
            }
        }
    }
}
