package com.example.sharkmentor.data.remote

import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
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

    private fun sanitizeKey(key: String): String {
        return key.trim().replace("\"", "").replace("'", "")
    }

    private fun sanitizeModel(modelName: String): String {
        return MODEL_NAME
    }

    private fun createGenerativeModel(
        modelName: String,
        apiKey: String,
        systemInstruction: String? = null,
        isJsonMode: Boolean = false
    ): GenerativeModel {
        val cleanKey = sanitizeKey(apiKey)
        val cleanModel = sanitizeModel(modelName)
        val config = if (isJsonMode) {
            generationConfig {
                responseMimeType = "application/json"
                temperature = 0.2f
            }
        } else null

        val sysInstruction = if (!systemInstruction.isNullOrBlank()) {
            content { text(systemInstruction) }
        } else null

        return GenerativeModel(
            modelName = cleanModel,
            apiKey = cleanKey,
            generationConfig = config,
            systemInstruction = sysInstruction,
            requestOptions = RequestOptions(timeout = 60_000L)
        )
    }

    suspend fun generateJsonResponse(
        prompt: String,
        activeApiKey: String = ApiKeyRotator.getActiveKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Throwable? = null
        val modelName = MODEL_NAME
        val maxRetries = 3
        var currentDelay = 1500L

        for (attempt in 1..maxRetries) {
            try {
                val currentKey = ApiKeyRotator.getActiveKey().ifBlank { activeApiKey }.ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
                Log.d("GeminiRepository", "Dispatching JSON request to model: $modelName (Attempt $attempt)...")
                val model = createGenerativeModel(
                    modelName = modelName,
                    apiKey = currentKey,
                    isJsonMode = true
                )
                val response = model.generateContent(prompt)
                val jsonText = response.text
                if (!jsonText.isNullOrBlank()) {
                    Log.d("GeminiRepository", "Success on model: $modelName")
                    return@withContext Result.success(jsonText)
                } else {
                    lastException = Exception("Model boş yanıt döndürdü ($modelName).")
                }
            } catch (e: Exception) {
                val rootCause = e.cause?.message ?: e.message
                Log.e("GeminiRepository", "JSON Call failed on $modelName: ${e.javaClass.simpleName} | Root:$rootCause", e)
                lastException = e
                
                if (ApiKeyRotator.shouldRotateKey(e)) {
                    Log.w("GeminiRepository", "Quota limit reached on current key. Rotating key...")
                    ApiKeyRotator.rotateToNextKey()
                }

                val isNetworkError = e is java.net.ConnectException || 
                    e is java.net.UnknownHostException || 
                    e is java.net.SocketTimeoutException ||
                    rootCause?.contains("enetunreach", ignoreCase = true) == true || 
                    rootCause?.contains("network is unreachable", ignoreCase = true) == true ||
                    rootCause?.contains("failed to connect", ignoreCase = true) == true ||
                    rootCause?.contains("unknownhostexception", ignoreCase = true) == true ||
                    rootCause?.contains("no address associated with hostname", ignoreCase = true) == true
                
                if (isNetworkError) {
                    Log.e("GeminiRepository", "Network is down. Aborting JSON request.")
                    return@withContext Result.failure(Exception("Lütfen internet bağlantınızı kontrol edin. Ağ erişilemiyor."))
                }

                val isUnavailable = e.message?.contains("503") == true || 
                                    e.message?.contains("high demand", ignoreCase = true) == true ||
                                    e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
                
                if (isUnavailable && attempt < maxRetries) {
                    Log.i("GeminiRepository", "Server busy (503). Retrying in ${currentDelay}ms...")
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay *= 2
                } else if (attempt == maxRetries && isUnavailable) {
                    lastException = Exception("Yapay zeka sunucularında anlık yoğunluk yaşanıyor (503). Lütfen birkaç saniye sonra tekrar deneyin.")
                }
            }
        }
        Result.failure(lastException ?: Exception("JSON yanıtı alınamadı."))
    }

    suspend fun generateResponse(
        prompt: String,
        activeApiKey: String = ApiKeyRotator.getActiveKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() },
        systemInstruction: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Throwable? = null
        val modelName = MODEL_NAME
        val maxRetries = 3
        var currentDelay = 1500L

        for (attempt in 1..maxRetries) {
            try {
                val currentKey = ApiKeyRotator.getActiveKey().ifBlank { activeApiKey }.ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
                Log.d("GeminiRepository", "Dispatching request to model: $modelName (Attempt $attempt)...")
                val model = createGenerativeModel(
                    modelName = modelName,
                    apiKey = currentKey,
                    systemInstruction = systemInstruction,
                    isJsonMode = false
                )
                val response = model.generateContent(prompt)
                val text = response.text
                if (text != null) {
                    Log.d("GeminiRepository", "Success on model: $modelName")
                    return@withContext Result.success(text)
                } else {
                    lastException = Exception("Model boş yanıt döndürdü ($modelName).")
                }
            } catch (e: Exception) {
                val rootCause = e.cause?.message ?: e.message
                Log.e("GeminiRepository", "Call failed on $modelName: ${e.javaClass.simpleName} | Root:$rootCause", e)
                lastException = e
                
                if (ApiKeyRotator.shouldRotateKey(e)) {
                    Log.w("GeminiRepository", "Quota limit reached on current key. Rotating key...")
                    ApiKeyRotator.rotateToNextKey()
                }

                val isNetworkError = e is java.net.ConnectException || 
                    e is java.net.UnknownHostException || 
                    e is java.net.SocketTimeoutException ||
                    rootCause?.contains("enetunreach", ignoreCase = true) == true || 
                    rootCause?.contains("network is unreachable", ignoreCase = true) == true ||
                    rootCause?.contains("failed to connect", ignoreCase = true) == true ||
                    rootCause?.contains("unknownhostexception", ignoreCase = true) == true ||
                    rootCause?.contains("no address associated with hostname", ignoreCase = true) == true
                
                if (isNetworkError) {
                    Log.e("GeminiRepository", "Network is down. Aborting request.")
                    return@withContext Result.failure(Exception("Lütfen internet bağlantınızı kontrol edin. Ağ erişilemiyor."))
                }

                val isUnavailable = e.message?.contains("503") == true || 
                                    e.message?.contains("high demand", ignoreCase = true) == true ||
                                    e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
                
                if (isUnavailable && attempt < maxRetries) {
                    Log.i("GeminiRepository", "Server busy (503). Retrying in ${currentDelay}ms...")
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay *= 2
                } else if (attempt == maxRetries && isUnavailable) {
                    lastException = Exception("Yapay zeka sunucularında anlık yoğunluk yaşanıyor (503). Lütfen birkaç saniye sonra tekrar deneyin.")
                }
            }
        }
        Result.failure(lastException ?: Exception("AI yanıtı alınamadı."))
    }
}
