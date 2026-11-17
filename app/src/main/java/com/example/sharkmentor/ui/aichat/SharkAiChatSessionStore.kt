package com.example.sharkmentor.ui.aichat

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.sharkmentor.data.repository.AiModelPower
import com.example.sharkmentor.data.repository.SharkAiEngine
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class PersistedAiChatMessageDto(
    val id: String = "",
    val sender: String = "user",
    val text: String = "",
    val personaTag: String = "shark_coach",
    val timestampMillis: Long = 0L,
    val planSummaryCard: String? = null,
    val planFolderId: String? = null,
    val isPlanCard: Boolean = false,
    val attachmentUriString: String? = null,
    val attachmentName: String? = null
) {
    fun toDomain(): AiChatMessage = AiChatMessage(
        id = id,
        sender = sender,
        text = text,
        personaTag = personaTag,
        timestampMillis = timestampMillis,
        planSummaryCard = planSummaryCard,
        planFolderId = planFolderId,
        isPlanCard = isPlanCard,
        attachmentUri = attachmentUriString?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() },
        attachmentName = attachmentName
    )

    companion object {
        fun fromDomain(msg: AiChatMessage): PersistedAiChatMessageDto = PersistedAiChatMessageDto(
            id = msg.id,
            sender = msg.sender,
            text = msg.text,
            personaTag = msg.personaTag,
            timestampMillis = msg.timestampMillis,
            planSummaryCard = msg.planSummaryCard,
            planFolderId = msg.planFolderId,
            isPlanCard = msg.isPlanCard,
            attachmentUriString = msg.attachmentUri?.toString(),
            attachmentName = msg.attachmentName
        )
    }
}

data class PersistedChatSessionSnapshot(
    val messages: List<AiChatMessage> = emptyList(),
    val currentPersona: AiPersona = AiPersona.SHARK_COACH,
    val modelPower: AiModelPower = AiModelPower.FLASH,
    val draftInputText: String = "",
    val lockedEngine: SharkAiEngine? = null
)

/**
 * Singleton persistent store for Shark AI chat sessions.
 * Preserves chat messages, persona, model power ("Hızlı" / "Detaylı"), and draft input
 * across tab switches (`SharkScreen` / bottom navigation) and app restarts.
 */
object SharkAiChatSessionStore {
    private const val PREFS_NAME = "shark_ai_chat_session_store"
    private const val KEY_MESSAGES_JSON = "messages_json"
    private const val KEY_PERSONA_ID = "persona_id"
    private const val KEY_MODEL_POWER_ID = "model_power_id"
    private const val KEY_DRAFT_INPUT = "draft_input"
    private const val KEY_LOCKED_ENGINE_ID = "locked_engine_id"
    private const val MAX_PERSISTED_MESSAGES = 60

    private val gson = Gson()

    @Volatile
    private var inMemorySnapshot: PersistedChatSessionSnapshot? = null

    @Synchronized
    fun restoreSession(context: Context): PersistedChatSessionSnapshot {
        inMemorySnapshot?.let { return it }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_MESSAGES_JSON, null)
        val restoredMessages: List<AiChatMessage> = if (!rawJson.isNullOrBlank()) {
            runCatching {
                val listType = object : TypeToken<List<PersistedAiChatMessageDto>>() {}.type
                val dtos: List<PersistedAiChatMessageDto> = gson.fromJson(rawJson, listType) ?: emptyList()
                dtos.map { it.toDomain() }
            }.getOrElse { e ->
                Log.w("SharkAiChatSessionStore", "Failed to parse persisted messages: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        val personaId = prefs.getString(KEY_PERSONA_ID, AiPersona.SHARK_COACH.id) ?: AiPersona.SHARK_COACH.id
        val restoredPersona = AiPersona.entries.firstOrNull { it.id == personaId } ?: AiPersona.SHARK_COACH

        val powerId = prefs.getString(KEY_MODEL_POWER_ID, AiModelPower.FLASH.id) ?: AiModelPower.FLASH.id
        val restoredPower = AiModelPower.entries.firstOrNull { it.id == powerId } ?: AiModelPower.FLASH

        val draftInput = prefs.getString(KEY_DRAFT_INPUT, "") ?: ""
        val lockedEngineId = prefs.getString(KEY_LOCKED_ENGINE_ID, null)
        val restoredLockedEngine = lockedEngineId?.let { id ->
            SharkAiEngine.entries.firstOrNull { it.id == id }
        }

        return PersistedChatSessionSnapshot(
            messages = restoredMessages,
            currentPersona = restoredPersona,
            modelPower = restoredPower,
            draftInputText = draftInput,
            lockedEngine = restoredLockedEngine
        ).also { inMemorySnapshot = it }
    }

    @Synchronized
    fun saveMessagesAndPersona(
        context: Context,
        messages: List<AiChatMessage>,
        persona: AiPersona
    ) {
        val trimmed = messages.takeLast(MAX_PERSISTED_MESSAGES)
        val current = inMemorySnapshot ?: restoreSession(context)
        val updated = current.copy(messages = trimmed, currentPersona = persona)
        inMemorySnapshot = updated

        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dtos = trimmed.map { PersistedAiChatMessageDto.fromDomain(it) }
            prefs.edit()
                .putString(KEY_MESSAGES_JSON, gson.toJson(dtos))
                .putString(KEY_PERSONA_ID, persona.id)
                .apply()
        }.onFailure { e ->
            Log.w("SharkAiChatSessionStore", "Failed to persist chat messages: ${e.message}")
        }
    }

    @Synchronized
    fun saveModelPower(context: Context, modelPower: AiModelPower) {
        val current = inMemorySnapshot ?: restoreSession(context)
        inMemorySnapshot = current.copy(modelPower = modelPower)

        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_MODEL_POWER_ID, modelPower.id).apply()
        }
    }

    @Synchronized
    fun saveDraftState(
        context: Context,
        draftInputText: String,
        lockedEngine: SharkAiEngine?
    ) {
        val current = inMemorySnapshot ?: restoreSession(context)
        inMemorySnapshot = current.copy(draftInputText = draftInputText, lockedEngine = lockedEngine)

        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_DRAFT_INPUT, draftInputText)
                .putString(KEY_LOCKED_ENGINE_ID, lockedEngine?.id)
                .apply()
        }
    }

    @Synchronized
    fun clearSession(context: Context, keepModelPower: Boolean = true) {
        val currentPower = inMemorySnapshot?.modelPower ?: AiModelPower.FLASH
        val fresh = PersistedChatSessionSnapshot(
            messages = emptyList(),
            currentPersona = AiPersona.SHARK_COACH,
            modelPower = if (keepModelPower) currentPower else AiModelPower.FLASH,
            draftInputText = "",
            lockedEngine = null
        )
        inMemorySnapshot = fresh

        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_MESSAGES_JSON)
                .putString(KEY_PERSONA_ID, AiPersona.SHARK_COACH.id)
                .putString(KEY_DRAFT_INPUT, "")
                .remove(KEY_LOCKED_ENGINE_ID)
                .apply()
        }
    }
}
