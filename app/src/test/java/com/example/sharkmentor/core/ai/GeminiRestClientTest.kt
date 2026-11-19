package com.example.sharkmentor.core.ai

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException

class GeminiRestClientTest {

    @Before
    fun setUp() {
        UserAiPreferences.resetForTesting()
        GeminiRestClient.clearEndpointCacheForTesting()
    }

    @After
    fun tearDown() {
        UserAiPreferences.resetForTesting()
        GeminiRestClient.clearEndpointCacheForTesting()
    }

    @Test
    fun resolveEndpointsForKey_routesVertexFirstForAqAndAiStudioOnlyForAiza() {
        val aqKey = "AQ.AbC1234567890_test_key"
        val aqEndpoints = GeminiRestClient.resolveEndpointsForKey(aqKey)
        assertEquals(
            listOf(GeminiEndpointType.VERTEX_AI_EXPRESS, GeminiEndpointType.GOOGLE_AI_STUDIO),
            aqEndpoints
        )

        // Once an AQ. key succeeds on AI Studio, subsequent calls route to AI Studio first.
        GeminiRestClient.recordEndpointSuccess(aqKey, GeminiEndpointType.GOOGLE_AI_STUDIO)
        assertEquals(
            listOf(GeminiEndpointType.GOOGLE_AI_STUDIO, GeminiEndpointType.VERTEX_AI_EXPRESS),
            GeminiRestClient.resolveEndpointsForKey(aqKey)
        )

        val aizaEndpoints = GeminiRestClient.resolveEndpointsForKey("AIzaSyDummyKeyForUnitTest12345")
        assertEquals(
            listOf(GeminiEndpointType.GOOGLE_AI_STUDIO),
            aizaEndpoints
        )
    }

    @Test
    fun mergeEndpointErrors_preserves404FromAiStudioAndRetainsBothEndpointDiagnostics() {
        val vertex403 = GeminiApiException(
            message = "Agent Platform API has not been used in project 137114597839",
            httpCode = 403,
            apiStatus = "PERMISSION_DENIED",
            endpoint = GeminiEndpointType.VERTEX_AI_EXPRESS,
            modelName = "gemini-3.6-flash"
        )
        val studio404 = GeminiApiException(
            message = "models/gemini-3.6-flash is not found for API version v1beta",
            httpCode = 404,
            apiStatus = "NOT_FOUND",
            endpoint = GeminiEndpointType.GOOGLE_AI_STUDIO,
            modelName = "gemini-3.6-flash",
            usedEndpointFallback = true
        )

        val merged = GeminiRestClient.mergeEndpointErrors(vertex403, studio404, "gemini-3.6-flash")
        assertEquals(404, merged.httpCode)
        assertTrue(merged.isModelNotFound)
        assertTrue(merged.message.contains("Vertex AI Express"))
        assertTrue(merged.message.contains("Google AI Studio"))
        assertTrue(merged.message.contains("137114597839"))
        assertTrue(merged.message.contains("not found"))
    }

    @Test
    fun buildGenerateUrl_stripsPrefixesAndConstructsRestAndSseUrls() {
        val vertexSync = GeminiRestClient.buildGenerateUrl(
            endpoint = GeminiEndpointType.VERTEX_AI_EXPRESS,
            modelName = "publishers/google/models/gemini-3.6-flash",
            streaming = false
        )
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-3.6-flash:generateContent",
            vertexSync
        )

        val vertexSse = GeminiRestClient.buildGenerateUrl(
            endpoint = GeminiEndpointType.VERTEX_AI_EXPRESS,
            modelName = "gemini-3.6-flash",
            streaming = true
        )
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-3.6-flash:streamGenerateContent?alt=sse",
            vertexSse
        )

        val studioSse = GeminiRestClient.buildGenerateUrl(
            endpoint = GeminiEndpointType.GOOGLE_AI_STUDIO,
            modelName = "models/gemini-2.5-flash",
            streaming = true
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            studioSse
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100",
            GeminiRestClient.buildListModelsUrl(100)
        )
    }

    @Test
    fun buildRequestBody_neverContainsFrequencyOrPresencePenalty_andIncludesToolsSystemAndThoughtSignature() {
        val json = GeminiRestClient.buildRequestBody(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(
                        GeminiPart.Text("Haftalık program hazırla"),
                        GeminiPart.InlineData(
                            mimeType = "image/jpeg",
                            base64Data = "AQID",
                            thoughtSignature = "sig_inline_1"
                        )
                    )
                )
            ),
            systemInstruction = "Doğrudan yanıt ver.",
            generationConfig = GeminiGenerationConfig(
                temperature = 0.25f,
                maxOutputTokens = 4096,
                topP = 0.85f,
                responseMimeType = "application/json"
            ),
            tools = listOf(
                GeminiFunctionDeclaration(
                    name = "addStudyPlanItem",
                    description = "Plan ekler",
                    parameters = mapOf(
                        "subject" to GeminiSchemaProperty("STRING", "Ders"),
                        "dayOfWeek" to GeminiSchemaProperty("INTEGER", "Gün")
                    ),
                    requiredParameters = listOf("subject", "dayOfWeek")
                )
            )
        )

        assertFalse(json.contains("frequencyPenalty"))
        assertFalse(json.contains("presencePenalty"))

        val root = JsonParser.parseString(json).asJsonObject
        val genCfg = root.getAsJsonObject("generationConfig")
        assertNotNull(genCfg)
        assertFalse(genCfg.has("frequencyPenalty"))
        assertFalse(genCfg.has("presencePenalty"))
        assertEquals(4096, genCfg.get("maxOutputTokens").asInt)
        assertEquals("application/json", genCfg.get("responseMimeType").asString)

        val sysText = root.getAsJsonObject("systemInstruction")
            .getAsJsonArray("parts")[0].asJsonObject
            .get("text").asString
        assertEquals("Doğrudan yanıt ver.", sysText)

        val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals("sig_inline_1", parts[1].asJsonObject.get("thoughtSignature").asString)

        val declName = root.getAsJsonArray("tools")[0].asJsonObject
            .getAsJsonArray("functionDeclarations")[0].asJsonObject
            .get("name").asString
        assertEquals("addStudyPlanItem", declName)
    }

    @Test
    fun parseGenerateContentJson_excludesThoughtPartsFromText_ignoresUnknownFields_andPreservesThoughtSignature() {
        val rawGemini3Json = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "thought": true,
                        "text": "Gizli iç düşünme adımı...",
                        "thoughtSignature": "sig_thought_only"
                      },
                      {
                        "text": "Sorunun cevabı C şıkkıdır.",
                        "thoughtSignature": "sig_answer_1",
                        "unknownGemini3Field": { "nested": true }
                      },
                      {
                        "thoughtSignature": "sig_fc_99",
                        "functionCall": {
                          "name": "addStudyPlanItem",
                          "args": {
                            "subject": "Matematik",
                            "topic": "Türev",
                            "dayOfWeek": 1,
                            "targetQuestions": 35
                          }
                        }
                      }
                    ]
                  },
                  "finishReason": "STOP",
                  "avgLogprobs": -0.123
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 42,
                "candidatesTokenCount": 18,
                "thoughtsTokenCount": 120
              },
              "modelVersion": "gemini-3.6-flash"
            }
        """.trimIndent()

        val parsed = GeminiRestClient.parseGenerateContentJson(
            jsonString = rawGemini3Json,
            modelName = "gemini-3.6-flash",
            endpoint = GeminiEndpointType.VERTEX_AI_EXPRESS
        )

        assertEquals("Sorunun cevabı C şıkkıdır.", parsed.text)
        assertFalse(parsed.text.contains("Gizli iç düşünme"))
        assertEquals("Gizli iç düşünme adımı...", parsed.thinkingText)
        assertEquals("STOP", parsed.finishReason)
        assertEquals(1, parsed.functionCalls.size)
        val call = parsed.functionCalls[0]
        assertEquals("addStudyPlanItem", call.name)
        assertEquals("Matematik", call.args["subject"])
        assertEquals("35", call.args["targetQuestions"])
        assertEquals("sig_fc_99", call.thoughtSignature)
    }

    @Test
    fun parseGenerateContentJson_throwsGeminiApiExceptionOnErrorObject() {
        val errorJson = """
            {
              "error": {
                "code": 404,
                "message": "Publisher Model not found.",
                "status": "NOT_FOUND"
              }
            }
        """.trimIndent()

        try {
            GeminiRestClient.parseGenerateContentJson(errorJson, modelName = "gemini-3.6-flash")
            org.junit.Assert.fail("Expected GeminiApiException")
        } catch (ex: GeminiApiException) {
            assertEquals(404, ex.httpCode)
            assertTrue(ex.isModelNotFound)
            assertFalse(ex.isQuotaOrRateLimit)
        }
    }

    @Test
    fun parseSseLines_accumulatesOnlyVisibleTextAndSupportsBackToBackEventsAndDone() {
        val sseLines = sequenceOf(
            """data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"Adım 1 düşünce"}]}}]}""",
            "",
            """data: {"candidates":[{"content":{"parts":[{"text":"Merhaba "}]}}]}""",
            """data: {"candidates":[{"content":{"parts":[{"text":"öğrenci!"}]},"finishReason":"STOP"}]}""",
            "",
            "data: [DONE]"
        )

        val partialUpdates = mutableListOf<String>()
        val thinkingUpdates = mutableListOf<String>()

        val result = GeminiRestClient.parseSseLines(
            lines = sseLines,
            modelName = "gemini-3.6-flash",
            endpoint = GeminiEndpointType.VERTEX_AI_EXPRESS,
            onPartialText = { partialUpdates.add(it) },
            onThinkingText = { thinkingUpdates.add(it) }
        )

        assertEquals("Merhaba öğrenci!", result.text)
        assertEquals("Adım 1 düşünce", result.thinkingText)
        assertEquals("STOP", result.finishReason)
        assertEquals(listOf("Merhaba ", "Merhaba öğrenci!"), partialUpdates)
        assertEquals(listOf("Adım 1 düşünce"), thinkingUpdates)
    }

    @Test
    fun normalizeContents_dropsBlankAndThoughtTurns_mergesConsecutive_andEndsOnUser() {
        val input = listOf(
            GeminiContent(role = "model", parts = listOf(GeminiPart.Text("Önde gelen model mesajı"))),
            GeminiContent(role = "user", parts = listOf(GeminiPart.Text("Soru 1"))),
            GeminiContent(role = "user", parts = listOf(GeminiPart.Text("Soru 1 devam"))),
            GeminiContent(role = "assistant", parts = listOf(GeminiPart.Text("   "))),
            GeminiContent(
                role = "assistant",
                parts = listOf(
                    GeminiPart.Text("Gizli thought", isThought = true),
                    GeminiPart.Text("Cevap 1")
                )
            ),
            GeminiContent(role = "user", parts = listOf(GeminiPart.Text("Soru 2"))),
            GeminiContent(role = "model", parts = listOf(GeminiPart.Text("Sonda kalan model mesajı")))
        )

        val normalized = GeminiRestClient.normalizeContents(input)

        assertEquals(3, normalized.size)
        assertEquals("user", normalized[0].role)
        assertEquals(2, normalized[0].parts.size)
        assertEquals("model", normalized[1].role)
        assertEquals(1, normalized[1].parts.size)
        assertEquals("Cevap 1", (normalized[1].parts[0] as GeminiPart.Text).text)
        assertEquals("user", normalized[2].role)
        assertEquals("Soru 2", (normalized[2].parts[0] as GeminiPart.Text).text)
    }

    @Test
    fun userAiPreferences_enforcesBuiltinGemini36Flash_allowsCustomOverride_andResetsOnClear() {
        assertFalse(UserAiPreferences.hasCustomKey())
        assertEquals("gemini-3.6-flash", UserAiPreferences.getSelectedUserGeminiModel())
        assertEquals("gemini-3.6-flash", UserAiPreferences.resolveEffectiveModel("gemini-2.5-pro"))

        UserAiPreferences.setCustomGeminiConfig("AIzaSyCustomUserTestKey12345", "gemini-2.5-pro")
        assertTrue(UserAiPreferences.hasCustomKey())
        assertEquals("gemini-2.5-pro", UserAiPreferences.getSelectedUserGeminiModel())
        assertEquals("gemini-2.5-pro", UserAiPreferences.resolveEffectiveModel("gemini-3.6-flash"))

        UserAiPreferences.setSelectedUserGeminiModel("models/gemini-3.8-flash")
        assertEquals("gemini-3.8-flash", UserAiPreferences.getSelectedUserGeminiModel())
        assertTrue(UserAiPreferences.getEffectiveSelectableModels().isNotEmpty())

        UserAiPreferences.clearCustomGeminiConfig()
        assertFalse(UserAiPreferences.hasCustomKey())
        assertEquals("gemini-3.6-flash", UserAiPreferences.getSelectedUserGeminiModel())
        assertEquals(listOf("gemini-3.6-flash"), UserAiPreferences.getEffectiveSelectableModels())
    }

    @Test
    fun parseModelsListJson_filtersGenerateContentGeminiModels() {
        val sampleResponse = """
            {
              "models": [
                {
                  "name": "models/gemini-3.6-flash",
                  "displayName": "Gemini 3.6 Flash",
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                  "name": "models/embedding-001",
                  "displayName": "Embedding 001",
                  "supportedGenerationMethods": ["embedContent"]
                },
                {
                  "name": "models/gemini-2.5-pro",
                  "displayName": "Gemini 2.5 Pro",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
        """.trimIndent()

        val models = GeminiRestClient.parseModelsListJson(sampleResponse)
        assertEquals(2, models.size)
        assertEquals("gemini-3.6-flash", models[0].id)
        assertEquals("gemini-2.5-pro", models[1].id)
    }

    @Test
    fun apiKeyRotator_collectsDeduplicatedKeys_masksSafely_andRotatesOnlyOnNon404NonOfflineErrors() {
        val collected = ApiKeyRotator.collectBuiltInKeysFromStrings(
            csvKeys = "\"AQ.KeyOneTestValue12345\", 'AIzaKeyTwoTestValue12345', null",
            individualKeys = listOf("AQ.KeyOneTestValue12345", "AIzaKeyThreeTestValue1234", "")
        )
        assertEquals(
            listOf("AQ.KeyOneTestValue12345", "AIzaKeyTwoTestValue12345", "AIzaKeyThreeTestValue1234"),
            collected
        )

        val maskedBuiltIn = ApiKeyRotator.maskApiKey("AQ.KeyOneTestValue12345", slotIndex = 0)
        assertTrue(maskedBuiltIn.startsWith("Anahtar #1"))
        assertFalse(maskedBuiltIn.contains("KeyOneTestValue"))

        val ex404 = GeminiApiException("Model not found", httpCode = 404, apiStatus = "NOT_FOUND")
        assertFalse(ApiKeyRotator.shouldRotateKey(ex404))

        val ex429 = GeminiApiException("Quota exceeded", httpCode = 429, apiStatus = "RESOURCE_EXHAUSTED")
        assertTrue(ApiKeyRotator.shouldRotateKey(ex429))

        val ex503 = GeminiApiException("Overloaded", httpCode = 503, apiStatus = "UNAVAILABLE")
        assertTrue(ApiKeyRotator.shouldRotateKey(ex503))

        val ex401 = GeminiApiException("Bad key", httpCode = 401, apiStatus = "UNAUTHENTICATED")
        assertTrue(ApiKeyRotator.shouldRotateKey(ex401))

        assertFalse(ApiKeyRotator.shouldRotateKey(UnknownHostException("offline")))
    }
}
