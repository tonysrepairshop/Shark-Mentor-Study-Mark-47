package com.example.sharkmentor.core.ai

import android.content.Context
import android.util.Log
import com.example.sharkmentor.BuildConfig
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

object ApiKeyRotator {

    private fun cleanKey(raw: String?): String =
        raw.orEmpty()
            .replace("\r", "")
            .replace("\n", "")
            .replace("\"", "")
            .replace("'", "")
            .trim()

    private fun isUsableKeyCandidate(key: String): Boolean {
        if (key.isBlank() || key.equals("null", ignoreCase = true)) return false
        if (key.startsWith("YOUR_", ignoreCase = true) || key.startsWith("<")) return false
        return key.startsWith("AQ.") || key.startsWith("AIza") || key.length >= 16
    }

    private fun safeLogW(msg: String) {
        runCatching { Log.w("ApiKeyRotator", msg) }
    }

    /**
     * Pure-JVM helper for collecting, cleaning, and deduplicating built-in Gemini keys
     * from comma/whitespace separated strings plus individual slot keys.
     */
    fun collectBuiltInKeysFromStrings(
        csvKeys: String,
        individualKeys: List<String>
    ): List<String> {
        val fromCsv = csvKeys
            .split(",", "\n", "\r", ";", " ", "\t")
            .map { cleanKey(it) }
        val fromSingles = individualKeys.map { cleanKey(it) }
        return (fromCsv + fromSingles)
            .filter { isUsableKeyCandidate(it) }
            .distinct()
    }

    private val cachedBuildConfigKeys: List<String> by lazy {
        runCatching {
            collectBuiltInKeysFromStrings(
                csvKeys = BuildConfig.GEMINI_API_KEYS,
                individualKeys = listOf(
                    BuildConfig.GEMINI_API_KEY,
                    BuildConfig.GEMINI_API_KEY_1,
                    BuildConfig.GEMINI_API_KEY_2,
                    BuildConfig.GEMINI_API_KEY_3
                )
            )
        }.getOrDefault(emptyList())
    }

    private val currentIndex = AtomicInteger(0)

    @Volatile
    private var lastUserKeySignature: String? = null

    fun getBuildConfigKeys(): List<String> = cachedBuildConfigKeys

    fun getAllBuiltInKeys(): List<String> = cachedBuildConfigKeys

    fun getAllEffectiveKeys(context: Context? = null): List<String> =
        resolveEffectiveKeys(context)

    fun hasAnyGeminiKey(context: Context? = null): Boolean =
        resolveEffectiveKeys(context).isNotEmpty()

    private fun resolveEffectiveKeys(context: Context? = null): List<String> {
        val prefs = runCatching { UserAiPreferences.getOrNull(context) }.getOrNull()
        val userKeys = prefs?.getParsedUserGeminiKeys()
            .orEmpty()
            .map { cleanKey(it) }
            .filter { isUsableKeyCandidate(it) }
        val signature = userKeys.joinToString("|")
        if (lastUserKeySignature != signature) {
            lastUserKeySignature = signature
            currentIndex.set(0)
        }
        return if (userKeys.isNotEmpty()) {
            (userKeys + cachedBuildConfigKeys).distinct()
        } else {
            cachedBuildConfigKeys
        }
    }

    fun hasCustomUserGeminiKeys(context: Context? = null): Boolean =
        runCatching { UserAiPreferences.getOrNull(context)?.getParsedUserGeminiKeys()?.isNotEmpty() == true }
            .getOrDefault(false)

    fun getActiveKeyIndex(): Int {
        val keys = resolveEffectiveKeys(null)
        if (keys.isEmpty()) return 0
        return Math.abs(currentIndex.get()) % keys.size
    }

    fun getStartIndex(context: Context? = null): Int {
        val keys = resolveEffectiveKeys(context)
        if (keys.isEmpty()) return 0
        return Math.abs(currentIndex.get()) % keys.size
    }

    fun markCurrentIndex(index: Int) {
        if (index >= 0) {
            currentIndex.set(index)
        }
    }

    fun getActiveKey(): String = getActiveKey(null)

    fun getActiveKey(context: Context?): String {
        val keys = resolveEffectiveKeys(context)
        if (keys.isEmpty()) {
            return runCatching { cleanKey(BuildConfig.GEMINI_API_KEY) }.getOrDefault("")
        }
        val idx = Math.abs(currentIndex.get()) % keys.size
        return keys[idx]
    }

    fun rotateToNextKey(): String = rotateToNextKey(null)

    fun rotateToNextKey(context: Context?): String {
        val keys = resolveEffectiveKeys(context)
        if (keys.size <= 1) return getActiveKey(context)
        val nextIdx = Math.abs(currentIndex.incrementAndGet()) % keys.size
        safeLogW("Rotating Gemini API key to index: $nextIdx / ${keys.size}")
        return keys[nextIdx]
    }

    fun resetToPrimary() {
        currentIndex.set(0)
    }

    fun invalidateAndReset() {
        lastUserKeySignature = null
        currentIndex.set(0)
    }

    fun getKeyCount(): Int = getKeyCount(null)

    fun getKeyCount(context: Context?): Int = resolveEffectiveKeys(context).size

    fun getKeySlotLabel(slotIndex: Int): String = "Anahtar #${slotIndex + 1}"

    fun maskApiKey(apiKey: String, slotIndex: Int? = null): String {
        val clean = cleanKey(apiKey)
        val masked = if (clean.length <= 8) "***" else "${clean.take(3)}...${clean.takeLast(4)}"
        return if (slotIndex != null) {
            "${getKeySlotLabel(slotIndex)} ($masked)"
        } else {
            masked
        }
    }

    fun shouldRotateKey(throwable: Throwable): Boolean {
        if (throwable is UnknownHostException || throwable is ConnectException) {
            return false
        }

        if (throwable is GeminiApiException) {
            if (throwable.isModelNotFound) return false
            return throwable.isQuotaOrRateLimit ||
                throwable.isAuthOrKeyError ||
                throwable.isOverloadOrTransient
        }

        val fullError = "${throwable.javaClass.simpleName} ${throwable.message} ${throwable.cause?.message} ${throwable.localizedMessage}".lowercase()

        // Explicitly ignore pure offline network and DNS resolution errors.
        if (fullError.contains("enetunreach") ||
            fullError.contains("network is unreachable") ||
            fullError.contains("failed to connect") ||
            fullError.contains("unknownhostexception") ||
            fullError.contains("connectexception") ||
            fullError.contains("software caused connection abort") ||
            fullError.contains("no address associated with hostname")
        ) {
            return false
        }

        // Explicitly ignore 404 model availability errors
        if (fullError.contains("404") ||
            fullError.contains("not_found") ||
            fullError.contains("no longer available") ||
            fullError.contains("not found for api version") ||
            fullError.contains("is not supported")
        ) {
            return false
        }

        return fullError.contains("401") ||
            fullError.contains("403") ||
            fullError.contains("429") ||
            fullError.contains("500") ||
            fullError.contains("502") ||
            fullError.contains("503") ||
            fullError.contains("unauthenticated") ||
            fullError.contains("access_token_type_unsupported") ||
            fullError.contains("credentials_missing") ||
            fullError.contains("api_key") ||
            fullError.contains("service_blocked") ||
            fullError.contains("blocked") ||
            fullError.contains("expired") ||
            fullError.contains("revoked") ||
            fullError.contains("quota") ||
            fullError.contains("resource_exhausted") ||
            fullError.contains("rate_limit") ||
            fullError.contains("unregistered") ||
            fullError.contains("permission_denied") ||
            fullError.contains("invalid") ||
            fullError.contains("overloaded") ||
            fullError.contains("unavailable")
    }

    fun isQuotaExceeded(throwable: Throwable): Boolean = shouldRotateKey(throwable)
}
