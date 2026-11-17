package com.example.sharkmentor.core.ai

import android.content.Context
import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import java.util.concurrent.atomic.AtomicInteger

object ApiKeyRotator {

    private val buildConfigKeys: List<String> by lazy {
        val raw = BuildConfig.GEMINI_API_KEYS
        val parsed = raw.split(",")
            .map { it.trim().replace("\"", "").replace("'", "") }
            .filter { it.isNotBlank() && (it.startsWith("AQ.") || it.startsWith("AIza") || it.length >= 20) }

        if (parsed.isNotEmpty()) parsed else {
            val fallback = BuildConfig.GEMINI_API_KEY.trim().replace("\"", "").replace("'", "")
            if (fallback.isNotBlank()) listOf(fallback) else emptyList()
        }
    }

    private val currentIndex = AtomicInteger(0)

    @Volatile
    private var lastUserKeySignature: String? = null

    private fun resolveEffectiveKeys(context: Context? = null): List<String> {
        val prefs = UserAiPreferences.getOrNull(context)
        val userKeys = prefs?.getParsedUserGeminiKeys().orEmpty()
        val signature = userKeys.joinToString("|")
        if (lastUserKeySignature != signature) {
            lastUserKeySignature = signature
            currentIndex.set(0)
        }
        return if (userKeys.isNotEmpty()) {
            (userKeys + buildConfigKeys).distinct()
        } else {
            buildConfigKeys
        }
    }

    fun hasCustomUserGeminiKeys(context: Context? = null): Boolean =
        UserAiPreferences.getOrNull(context)?.getParsedUserGeminiKeys()?.isNotEmpty() == true

    fun getActiveKey(): String = getActiveKey(null)

    fun getActiveKey(context: Context?): String {
        val keys = resolveEffectiveKeys(context)
        if (keys.isEmpty()) {
            return BuildConfig.GEMINI_API_KEY.trim().replace("\"", "").replace("'", "")
        }
        val idx = Math.abs(currentIndex.get()) % keys.size
        return keys[idx]
    }

    fun rotateToNextKey(): String = rotateToNextKey(null)

    fun rotateToNextKey(context: Context?): String {
        val keys = resolveEffectiveKeys(context)
        if (keys.size <= 1) return getActiveKey(context)
        val nextIdx = Math.abs(currentIndex.incrementAndGet()) % keys.size
        Log.w("ApiKeyRotator", "Quota exceeded. Rotated to Account Index: $nextIdx")
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
            throwable is InvalidAPIKeyException) {
            return true
        }

        val fullError = "${throwable.javaClass.simpleName} ${throwable.message} ${throwable.cause?.message} ${throwable.localizedMessage}".lowercase()
        
        // Explicitly ignore network and host resolution errors.
        if (fullError.contains("enetunreach") ||
            fullError.contains("network is unreachable") ||
            fullError.contains("failed to connect") ||
            fullError.contains("unknownhostexception") ||
            fullError.contains("connectexception") ||
            fullError.contains("sockettimeoutexception") ||
            fullError.contains("software caused connection abort") ||
            fullError.contains("no address associated with hostname") ||
            throwable is java.net.ConnectException ||
            throwable is java.net.UnknownHostException ||
            throwable is java.net.SocketTimeoutException) {
            return false
        }

        // Explicitly ignore 404 and model availability errors
        if (fullError.contains("404") || 
            fullError.contains("not_found") || 
            fullError.contains("no longer available") ||
            fullError.contains("not found for api version")) {
            return false
        }

        // Rotate on quota, rate limit, key authorization issues, 503 overload, or unknown exceptions
        return fullError.contains("429") || 
               fullError.contains("quota") || 
               fullError.contains("resource_exhausted") ||
               fullError.contains("rate_limit") ||
               fullError.contains("unregistered") ||
               fullError.contains("permission_denied") ||
               fullError.contains("invalid") ||
               fullError.contains("503") ||
               fullError.contains("overloaded") ||
               fullError.contains("unavailable") ||
               fullError.contains("serverexception") ||
               fullError.contains("unknownexception") ||
               fullError.contains("unexpected happened")
    }

    fun isQuotaExceeded(throwable: Throwable): Boolean = shouldRotateKey(throwable)
}
