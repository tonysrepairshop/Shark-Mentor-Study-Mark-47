package com.example.sharkmentor.core.ai

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class GeminiChatTurn(
    val role: String,
    val text: String
)

data class GeminiConnectionTestResult(
    val success: Boolean,
    val summary: String,
    val details: List<String> = emptyList()
)

data class GeminiModelDiscoveryResult(
    val success: Boolean,
    val models: List<GeminiModelOption> = emptyList(),
    val summary: String
)

/**
 * Helper for:
 * - Discovering supported Gemini models for a user's Google AI Studio API key (`v1beta/models`)
 * - Testing Gemini keys cleanly using Mark 42's `GenerativeModel` SDK pipeline
 * - Extracting clean JSON strings from model output
 */
object GeminiRestClient {

    private const val TAG = "GeminiRestClient"
    const val MODEL_ID = UserAiPreferences.STANDARD_GEMINI_MODEL
    private const val MODELS_LIST_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Queries AI Studio's `/v1beta/models` endpoint using ONLY the user-entered key(s)
     * and returns Gemini models that support `generateContent`.
     */
    suspend fun fetchSupportedModels(apiKeysRaw: String): GeminiModelDiscoveryResult = withContext(Dispatchers.IO) {
        val userKeys = UserAiPreferences.parseGeminiKeyList(apiKeysRaw)
        if (userKeys.isEmpty()) {
            return@withContext GeminiModelDiscoveryResult(
                success = false,
                models = emptyList(),
                summary = "Model listelemek için önce kendi Gemini API anahtarınızı girin."
            )
        }

        var lastError: String? = null
        for ((idx, key) in userKeys.withIndex()) {
            try {
                val request = Request.Builder()
                    .url(MODELS_LIST_URL)
                    .addHeader("x-goog-api-key", key)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val rawBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    if (!response.isSuccessful) {
                        val errMsg = extractHttpErrorSummary(response.code, rawBody)
                        lastError = "Anahtar #${idx + 1} (${maskKey(key)}): $errMsg"
                        Log.w(TAG, "Model discovery failed: $lastError")
                        return@use
                    }

                    val parsed = parseModelsJson(rawBody)
                    if (parsed.isNotEmpty()) {
                        return@withContext GeminiModelDiscoveryResult(
                            success = true,
                            models = parsed,
                            summary = "${parsed.size} desteklenen model bulundu."
                        )
                    } else {
                        lastError = "Bu anahtar için uygun Gemini modeli bulunamadı."
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (e is UnknownHostException || e is ConnectException) {
                    return@withContext GeminiModelDiscoveryResult(
                        success = false,
                        models = emptyList(),
                        summary = "İnternet bağlantısı kurulamadı."
                    )
                }
                lastError = e.localizedMessage?.take(140) ?: e.javaClass.simpleName
                Log.w(TAG, "Model discovery exception on key #${idx + 1}: $lastError", e)
            }
        }

        GeminiModelDiscoveryResult(
            success = false,
            models = emptyList(),
            summary = lastError ?: "Modeller listelenemedi."
        )
    }

    /**
     * Tests candidate keys using Mark 42's `GenerativeModel` SDK:
     * - If [candidateUserKeysRaw] is non-blank, tests those keys with [candidateModelId] (or current custom selection).
     * - If blank, tests the built-in 3 default keys strictly with `gemini-3.6-flash`.
     */
    suspend fun testApiConnection(
        context: Context? = null,
        candidateUserKeysRaw: String? = null,
        candidateModelId: String? = null
    ): GeminiConnectionTestResult = withContext(Dispatchers.IO) {
        val parsedUser = candidateUserKeysRaw?.let { UserAiPreferences.parseGeminiKeyList(it) }.orEmpty()
        val usingCustomKeys = parsedUser.isNotEmpty()
        val candidateKeys = if (usingCustomKeys) {
            parsedUser
        } else if (candidateUserKeysRaw != null) {
            ApiKeyRotator.getBuildConfigKeys()
        } else {
            ApiKeyRotator.getAllEffectiveKeys(context)
        }

        if (candidateKeys.isEmpty()) {
            return@withContext GeminiConnectionTestResult(
                success = false,
                summary = "Tanımlı Gemini API anahtarı bulunamadı.",
                details = listOf("Ayarlardan bir anahtar girin veya derleme anahtarlarını kontrol edin.")
            )
        }

        val perKeyDiagnostics = mutableListOf<String>()
        for ((idx, key) in candidateKeys.withIndex()) {
            val masked = maskKey(key)
            val modelToTest = if (usingCustomKeys) {
                UserAiPreferences.sanitizeModelId(
                    candidateModelId ?: UserAiPreferences.getOrNull(context)?.getSelectedUserGeminiModel().orEmpty()
                )
            } else {
                MODEL_ID
            }

            val attempt = runCatching {
                val model = GenerativeModel(
                    modelName = modelToTest,
                    apiKey = UserAiPreferences.sanitizeKey(key),
                    generationConfig = generationConfig {
                        temperature = 0.2f
                        maxOutputTokens = 128
                        topP = 0.8f
                    },
                    requestOptions = RequestOptions(timeout = 25_000L)
                )
                val response = model.generateContent("Sadece OK yaz.")
                val text = response.text?.trim().orEmpty()
                if (text.isBlank()) {
                    throw IllegalStateException("Boş yanıt döndü.")
                }
                text
            }

            if (attempt.isSuccess) {
                val note = if (perKeyDiagnostics.isNotEmpty()) {
                    perKeyDiagnostics + "Anahtar #${idx + 1} ($masked): OK ($modelToTest)"
                } else {
                    listOf("Anahtar #${idx + 1} ($masked) aktif ($modelToTest).")
                }
                return@withContext GeminiConnectionTestResult(
                    success = true,
                    summary = "Bağlantı başarılı ($modelToTest • Anahtar #${idx + 1} $masked)",
                    details = note
                )
            } else {
                val err = attempt.exceptionOrNull()
                if (err is CancellationException) throw err
                val msg = (err?.localizedMessage ?: err?.message ?: err?.javaClass?.simpleName ?: "Bilinmeyen hata")
                    .take(160)
                perKeyDiagnostics.add("Anahtar #${idx + 1} ($masked • $modelToTest): $msg")
            }
        }

        GeminiConnectionTestResult(
            success = false,
            summary = "Tüm Gemini anahtarları başarısız oldu (${candidateKeys.size} anahtar denendi).",
            details = perKeyDiagnostics
        )
    }

    private fun parseModelsJson(rawJson: String): List<GeminiModelOption> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("models") ?: return emptyList()
        val parsed = mutableListOf<GeminiModelOption>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawName = obj.optString("name", "").trim()
            val id = rawName.removePrefix("models/").removePrefix("/").trim()
            if (id.isBlank()) continue
            if (!id.startsWith("gemini", ignoreCase = true)) continue

            val lowerId = id.lowercase()
            if (lowerId.contains("embedding") ||
                lowerId.contains("aqa") ||
                lowerId.contains("imagen") ||
                lowerId.contains("veo") ||
                lowerId.contains("tts") ||
                lowerId.contains("robotics")
            ) {
                continue
            }

            val methodsArr = obj.optJSONArray("supportedGenerationMethods")
            if (methodsArr != null && methodsArr.length() > 0) {
                var supportsGenerate = false
                for (m in 0 until methodsArr.length()) {
                    if (methodsArr.optString(m).equals("generateContent", ignoreCase = true)) {
                        supportsGenerate = true
                        break
                    }
                }
                if (!supportsGenerate) continue
            }

            val displayName = obj.optString("displayName", id).ifBlank { id }
            val description = obj.optString("description", "").take(100)
            parsed.add(
                GeminiModelOption(
                    id = id,
                    displayName = displayName,
                    description = description
                )
            )
        }

        return parsed
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<GeminiModelOption> { it.id.contains("3.8") }
                    .thenByDescending { it.id.contains("3.6") }
                    .thenByDescending { it.id.contains("3.") }
                    .thenByDescending { it.id.contains("2.5") }
                    .thenByDescending { it.id.contains("flash", ignoreCase = true) }
                    .thenBy { it.id }
            )
    }

    private fun extractHttpErrorSummary(code: Int, rawBody: String): String {
        val parsedMsg = runCatching {
            val errObj = JSONObject(rawBody).optJSONObject("error")
            errObj?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val cleanMsg = parsedMsg?.take(140) ?: rawBody.take(140).ifBlank { "HTTP $code" }
        return "HTTP $code: $cleanMsg"
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

    private fun maskKey(key: String): String {
        val clean = UserAiPreferences.sanitizeKey(key)
        return if (clean.length <= 8) "***" else "${clean.take(3)}...${clean.takeLast(4)}"
    }
}
