package com.example.sharkmentor.core.ai

import android.content.Context
import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import java.util.concurrent.atomic.AtomicInteger

object ApiKeyRotator {

    private fun cleanKey(raw: String): String =
        raw.replace("\r", "")
            .replace("\n", "")
            .replace("\"", "")
            .replace("'", "")
            .trim()

    private fun isUsableKeyCandidate(key: String): Boolean {
        if (key.isBlank() || key.equals("null", ignoreCase = true)) return false
        if (key.startsWith("YOUR_", ignoreCase = true) || key.startsWith("<")) return false
        return key.startsWith("AQ.") || key.startsWith("AIza") || key.length >= 16
    }

    private val cachedBuildConfigKeys: List<String> by lazy {
        val fromMulti = BuildConfig.GEMINI_API_KEYS
            .split(",", "\n", "\r", ";", " ", "\t")
            .map { cleanKey(it) }
        val fromSingles = listOf(
            BuildConfig.GEMINI_API_KEY,
            BuildConfig.GEMINI_API_KEY_1,
            BuildConfig.GEMINI_API_KEY_2,
            BuildConfig.GEMINI_API_KEY_3
        ).map { cleanKey(it) }

        (fromMulti + fromSingles)
            .filter { isUsableKeyCandidate(it) }
            .distinct()
    }

    private val currentIndex = AtomicInteger(0)

    @Volatile
    private var lastUserKeySignature: String? = null

    fun getBuildConfigKeys(): List<String> = cachedBuildConfigKeys

    fun getAllEffectiveKeys(context: Context? = null): List<String> =
        resolveEffectiveKeys(context)

    fun hasAnyGeminiKey(context: Context? = null): Boolean =
        resolveEffectiveKeys(context).isNotEmpty()

    private fun resolveEffectiveKeys(context: Context? = null): List<String> {
        val prefs = UserAiPreferences.getOrNull(context)
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
        UserAiPreferences.getOrNull(context)?.getParsedUserGeminiKeys()?.isNotEmpty() == true

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
            return cleanKey(BuildConfig.GEMINI_API_KEY)
        }
        val idx = Math.abs(currentIndex.get()) % keys.size
        return keys[idx]
    }

    fun rotateToNextKey(): String = rotateToNextKey(null)

    fun rotateToNextKey(context: Context?): String {
        val keys = resolveEffectiveKeys(context)
        if (keys.size <= 1) return getActiveKey(context)
        val nextIdx = Math.abs(currentIndex.incrementAndGet()) % keys.size
        Log.w("ApiKeyRotator", "Rotating Gemini API key to index: $nextIdx / ${keys.size}")
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

    fun shouldRotateKey(throwable: Throwable): Boolean {
        if (throwable is QuotaExceededException ||
            throwable is InvalidAPIKeyException
        ) {
            return true
        }

        val fullError = "${throwable.javaClass.simpleName} ${throwable.message} ${throwable.cause?.message} ${throwable.localizedMessage}".lowercase()

        // Explicitly ignore pure offline network and DNS resolution errors.
        if (fullError.contains("enetunreach") ||
            fullError.contains("network is unreachable") ||
            fullError.contains("failed to connect") ||
            fullError.contains("unknownhostexception") ||
            fullError.contains("connectexception") ||
            fullError.contains("software caused connection abort") ||
            fullError.contains("no address associated with hostname") ||
            throwable is java.net.ConnectException ||
            throwable is java.net.UnknownHostException
        ) {
            return false
        }

        // Explicitly ignore 404 model availability errors
        if (fullError.contains("404") ||
            fullError.contains("not_found") ||
            fullError.contains("no longer available") ||
            fullError.contains("not found for api version")
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
            fullError.contains("unavailable") ||
            fullError.contains("serverexception") ||
            fullError.contains("unknownexception") ||
            fullError.contains("unexpected happened") ||
            fullError.contains("serialization") ||
            fullError.contains("missingfield")
    }

    fun isQuotaExceeded(throwable: Throwable): Boolean = shouldRotateKey(throwable)
}
