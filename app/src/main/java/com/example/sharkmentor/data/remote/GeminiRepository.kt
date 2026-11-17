package com.example.sharkmentor.data.remote

import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.GeminiAiFactory
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
        Log.d("GeminiRepository", "Dispatching JSON request via Mark 42 SDK pipeline...")
        GeminiAiFactory.generateContentWithFailover(
            systemInstruction = "",
            userPrompt = prompt,
            responseJson = true,
            temperature = 0.2f,
            maxOutputTokens = 8192,
            timeoutMs = 75_000L
        ).mapCatching { result ->
            val jsonText = result.text.trim()
            if (jsonText.isBlank()) throw Exception("Model boş yanıt döndürdü (${result.modelUsed}).")
            Log.d("GeminiRepository", "Success on model: ${result.modelUsed} (key #${result.usedKeyIndex + 1})")
            jsonText
        }
    }

    suspend fun generateResponse(
        prompt: String,
        activeApiKey: String = ApiKeyRotator.getActiveKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() },
        systemInstruction: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.d("GeminiRepository", "Dispatching text request via Mark 42 SDK pipeline...")
        GeminiAiFactory.generateContentWithFailover(
            systemInstruction = systemInstruction.orEmpty(),
            userPrompt = prompt,
            responseJson = false,
            temperature = 0.35f,
            maxOutputTokens = 6144,
            timeoutMs = 75_000L
        ).mapCatching { result ->
            val text = result.text.trim()
            if (text.isBlank()) throw Exception("Model boş yanıt döndürdü (${result.modelUsed}).")
            Log.d("GeminiRepository", "Success on model: ${result.modelUsed} (key #${result.usedKeyIndex + 1})")
            text
        }
    }
}
