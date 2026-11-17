package com.example.sharkmentor.data.remote

import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.GeminiRestClient
import com.example.sharkmentor.core.ai.GeminiThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiRepository @Inject constructor() {

    companion object {
        const val MODEL_NAME = "gemini-3.6-flash"
        const val ACTIVE_MODEL_NAME = MODEL_NAME
    }

    suspend fun generateJsonResponse(
        prompt: String,
        activeApiKey: String = ApiKeyRotator.getActiveKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.d("GeminiRepository", "Dispatching JSON request to model: $MODEL_NAME...")
        GeminiRestClient.generateWithFailover(
            systemInstruction = "",
            userPrompt = prompt,
            responseJson = true,
            maxOutputTokens = 8192,
            thinkingLevel = GeminiThinkingLevel.LOW,
            timeoutMs = 75_000L
        ).mapCatching { result ->
            val jsonText = result.text.trim()
            if (jsonText.isBlank()) throw Exception("Model boş yanıt döndürdü ($MODEL_NAME).")
            Log.d("GeminiRepository", "Success on model: $MODEL_NAME (${result.gatewayUsed})")
            jsonText
        }
    }

    suspend fun generateResponse(
        prompt: String,
        activeApiKey: String = ApiKeyRotator.getActiveKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() },
        systemInstruction: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.d("GeminiRepository", "Dispatching text request to model: $MODEL_NAME...")
        GeminiRestClient.generateWithFailover(
            systemInstruction = systemInstruction.orEmpty(),
            userPrompt = prompt,
            responseJson = false,
            maxOutputTokens = 6144,
            thinkingLevel = GeminiThinkingLevel.LOW,
            timeoutMs = 75_000L
        ).mapCatching { result ->
            val text = result.text.trim()
            if (text.isBlank()) throw Exception("Model boş yanıt döndürdü ($MODEL_NAME).")
            Log.d("GeminiRepository", "Success on model: $MODEL_NAME (${result.gatewayUsed})")
            text
        }
    }
}
