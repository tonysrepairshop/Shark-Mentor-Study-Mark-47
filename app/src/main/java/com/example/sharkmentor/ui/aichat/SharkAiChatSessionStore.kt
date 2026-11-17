package com.example.sharkmentor.ui.aichat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.sharkmentor.core.ai.UserAiPreferences
import com.example.sharkmentor.data.repository.SharkAiEngine
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

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

data class PersistedChatSessionDto(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Yeni Sohbet",
    val messages: List<PersistedAiChatMessageDto> = emptyList(),
    val personaId: String = AiPersona.SHARK_COACH.id,
    val draftInputText: String = "",
    val lockedEngineId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun toDomain(): PersistedChatSession {
        val domainMessages = messages.map { it.toDomain() }
        val resolvedPersona = AiPersona.entries.firstOrNull { it.id == personaId } ?: AiPersona.SHARK_COACH
        val resolvedEngine = lockedEngineId?.let { id -> SharkAiEngine.entries.firstOrNull { it.id == id } }
        val resolvedTitle = SharkAiChatSessionStore.deriveSessionTitle(domainMessages, title)
        return PersistedChatSession(
            id = id.ifBlank { UUID.randomUUID().toString() },
            title = resolvedTitle,
            messages = domainMessages,
            currentPersona = resolvedPersona,
            draftInputText = draftInputText,
            lockedEngine = resolvedEngine,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )
    }

    companion object {
        fun fromDomain(session: PersistedChatSession): PersistedChatSessionDto = PersistedChatSessionDto(
            id = session.id,
            title = SharkAiChatSessionStore.deriveSessionTitle(session.messages, session.title),
            messages = session.messages
                .takeLast(SharkAiChatSessionStore.MAX_PERSISTED_MESSAGES_PER_SESSION)
                .map { PersistedAiChatMessageDto.fromDomain(it) },
            personaId = session.currentPersona.id,
            draftInputText = session.draftInputText,
            lockedEngineId = session.lockedEngine?.id,
            createdAtMillis = session.createdAtMillis,
            updatedAtMillis = session.updatedAtMillis
        )
    }
}

@Immutable
data class PersistedChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Yeni Sohbet",
    val messages: List<AiChatMessage> = emptyList(),
    val currentPersona: AiPersona = AiPersona.SHARK_COACH,
    val draftInputText: String = "",
    val lockedEngine: SharkAiEngine? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val userAndAiMessageCount: Int
        get() = messages.count { it.sender == "user" || it.sender == "ai" }

    val lastMessagePreview: String
        get() {
            val lastRelevant = messages.lastOrNull {
                (it.sender == "user" || it.sender == "ai") && it.text.isNotBlank()
            } ?: messages.lastOrNull { it.text.isNotBlank() }

            if (lastRelevant == null) {
                return "Mesaj yok"
            }

            val cleaned = lastRelevant.text
                .replace("#ACTION:.*".toRegex(), "")
                .replace("```[\\s\\S]*?```".toRegex(), "")
                .replace("\\s+".toRegex(), " ")
                .trim()

            if (cleaned.isBlank()) {
                return lastRelevant.attachmentName ?: "Ekli dosya"
            }
            return if (cleaned.length > 68) "${cleaned.take(68).trimEnd()}…" else cleaned
        }
}

data class PersistedChatSessionSnapshot(
    val activeSessionId: String = "",
    val title: String = "Yeni Sohbet",
    val messages: List<AiChatMessage> = emptyList(),
    val currentPersona: AiPersona = AiPersona.SHARK_COACH,
    val draftInputText: String = "",
    val lockedEngine: SharkAiEngine? = null,
    val sessions: List<PersistedChatSession> = emptyList()
)

/**
 * Multi-session persistent store for Shark AI chat sessions.
 * Supports creating, selecting, and deleting multiple conversations (`List<PersistedChatSession>` + `activeSessionId`)
 * and preserves them across tab switches (`SharkScreen` / bottom navigation) and app restarts.
 */
object SharkAiChatSessionStore {
    private const val PREFS_NAME = "shark_ai_chat_session_store"
    private const val KEY_MULTI_SESSIONS_JSON = "multi_sessions_json"
    private const val KEY_ACTIVE_SESSION_ID = "active_session_id"

    // Legacy single-session keys for seamless migration
    private const val KEY_MESSAGES_JSON = "messages_json"
    private const val KEY_PERSONA_ID = "persona_id"
    private const val KEY_DRAFT_INPUT = "draft_input"
    private const val KEY_LOCKED_ENGINE_ID = "locked_engine_id"

    const val MAX_PERSISTED_MESSAGES_PER_SESSION = 60
    private const val MAX_PERSISTED_SESSIONS = 30
    private const val MAX_TITLE_LENGTH = 36

    private val gson = Gson()

    @Volatile
    private var inMemorySessions: MutableList<PersistedChatSession>? = null

    @Volatile
    private var inMemoryActiveSessionId: String? = null

    fun deriveSessionTitle(messages: List<AiChatMessage>, currentTitle: String = "Yeni Sohbet"): String {
        val firstUserMsg = messages.firstOrNull { it.sender == "user" && it.text.isNotBlank() }
        if (firstUserMsg != null) {
            val cleaned = firstUserMsg.text
                .replace("#ACTION:.*".toRegex(), "")
                .replace("\\s+".toRegex(), " ")
                .trim()
            if (cleaned.isNotBlank()) {
                return if (cleaned.length > MAX_TITLE_LENGTH) {
                    "${cleaned.take(MAX_TITLE_LENGTH).trimEnd()}…"
                } else {
                    cleaned
                }
            }
        }
        val firstAttachment = messages.firstOrNull { it.sender == "user" && !it.attachmentName.isNullOrBlank() }
        if (firstAttachment != null) {
            val attName = firstAttachment.attachmentName!!.trim()
            return if (attName.length > MAX_TITLE_LENGTH) "${attName.take(MAX_TITLE_LENGTH)}…" else attName
        }
        return currentTitle.ifBlank { "Yeni Sohbet" }
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (inMemorySessions != null && !inMemoryActiveSessionId.isNullOrBlank()) {
            return
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawMultiJson = prefs.getString(KEY_MULTI_SESSIONS_JSON, null)
        val loadedList = mutableListOf<PersistedChatSession>()

        if (!rawMultiJson.isNullOrBlank()) {
            runCatching {
                val listType = object : TypeToken<List<PersistedChatSessionDto>>() {}.type
                val dtos: List<PersistedChatSessionDto> = gson.fromJson(rawMultiJson, listType) ?: emptyList()
                loadedList.addAll(dtos.map { it.toDomain() })
            }.onFailure { e ->
                Log.w("SharkAiChatSessionStore", "Failed to parse multi_sessions_json: ${e.message}")
            }
        } else {
            // Migrate legacy single-session data if present
            val legacyMessagesJson = prefs.getString(KEY_MESSAGES_JSON, null)
            val legacyMessages: List<AiChatMessage> = if (!legacyMessagesJson.isNullOrBlank()) {
                runCatching {
                    val listType = object : TypeToken<List<PersistedAiChatMessageDto>>() {}.type
                    val dtos: List<PersistedAiChatMessageDto> = gson.fromJson(legacyMessagesJson, listType) ?: emptyList()
                    dtos.map { it.toDomain() }
                }.getOrElse { emptyList() }
            } else {
                emptyList()
            }

            val personaId = prefs.getString(KEY_PERSONA_ID, AiPersona.SHARK_COACH.id) ?: AiPersona.SHARK_COACH.id
            val restoredPersona = AiPersona.entries.firstOrNull { it.id == personaId } ?: AiPersona.SHARK_COACH
            val draftInput = prefs.getString(KEY_DRAFT_INPUT, "") ?: ""
            val lockedEngineId = prefs.getString(KEY_LOCKED_ENGINE_ID, null)
            val restoredLockedEngine = lockedEngineId?.let { id -> SharkAiEngine.entries.firstOrNull { it.id == id } }

            val migratedSession = PersistedChatSession(
                id = UUID.randomUUID().toString(),
                title = deriveSessionTitle(legacyMessages, "Yeni Sohbet"),
                messages = legacyMessages,
                currentPersona = restoredPersona,
                draftInputText = draftInput,
                lockedEngine = restoredLockedEngine
            )
            loadedList.add(migratedSession)
        }

        if (loadedList.isEmpty()) {
            loadedList.add(PersistedChatSession())
        }

        val sorted = loadedList
            .sortedByDescending { it.updatedAtMillis }
            .take(MAX_PERSISTED_SESSIONS)
            .toMutableList()

        val savedActiveId = prefs.getString(KEY_ACTIVE_SESSION_ID, null)
        val resolvedActiveId = sorted.firstOrNull { it.id == savedActiveId }?.id ?: sorted.first().id

        inMemorySessions = sorted
        inMemoryActiveSessionId = resolvedActiveId
        flushToPrefs(context)
    }

    @Synchronized
    private fun flushToPrefs(context: Context) {
        val list = inMemorySessions ?: return
        val activeId = inMemoryActiveSessionId ?: list.firstOrNull()?.id ?: ""
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dtos = list.take(MAX_PERSISTED_SESSIONS).map { PersistedChatSessionDto.fromDomain(it) }
            prefs.edit()
                .putString(KEY_MULTI_SESSIONS_JSON, gson.toJson(dtos))
                .putString(KEY_ACTIVE_SESSION_ID, activeId)
                .apply()
        }.onFailure { e ->
            Log.w("SharkAiChatSessionStore", "Failed to flush multi-sessions to prefs: ${e.message}")
        }
    }

    @Synchronized
    private fun buildSnapshot(context: Context): PersistedChatSessionSnapshot {
        ensureLoaded(context)
        val list = inMemorySessions!!
        val active = list.firstOrNull { it.id == inMemoryActiveSessionId } ?: list.first()
        inMemoryActiveSessionId = active.id
        return PersistedChatSessionSnapshot(
            activeSessionId = active.id,
            title = active.title,
            messages = active.messages,
            currentPersona = active.currentPersona,
            draftInputText = active.draftInputText,
            lockedEngine = active.lockedEngine,
            sessions = list.sortedByDescending { it.updatedAtMillis }
        )
    }

    @Synchronized
    fun restoreSession(context: Context): PersistedChatSessionSnapshot {
        return buildSnapshot(context)
    }

    @Synchronized
    fun getAllSessions(context: Context): List<PersistedChatSession> {
        ensureLoaded(context)
        return inMemorySessions!!.sortedByDescending { it.updatedAtMillis }
    }

    @Synchronized
    fun saveMessagesAndPersona(
        context: Context,
        messages: List<AiChatMessage>,
        persona: AiPersona
    ): PersistedChatSessionSnapshot {
        ensureLoaded(context)
        val list = inMemorySessions!!
        val activeIdx = list.indexOfFirst { it.id == inMemoryActiveSessionId }.let { if (it == -1) 0 else it }
        val current = list[activeIdx]
        val trimmed = messages.takeLast(MAX_PERSISTED_MESSAGES_PER_SESSION)
        val newTitle = deriveSessionTitle(trimmed, current.title)
        val now = System.currentTimeMillis()
        val updated = current.copy(
            title = newTitle,
            messages = trimmed,
            currentPersona = persona,
            updatedAtMillis = if (trimmed.size != current.messages.size || trimmed.lastOrNull()?.text != current.messages.lastOrNull()?.text) now else current.updatedAtMillis
        )
        list[activeIdx] = updated
        inMemorySessions = list.sortedByDescending { it.updatedAtMillis }.toMutableList()
        flushToPrefs(context)
        return buildSnapshot(context)
    }

    @Synchronized
    fun saveDraftState(
        context: Context,
        draftInputText: String,
        lockedEngine: SharkAiEngine?
    ): PersistedChatSessionSnapshot {
        ensureLoaded(context)
        val list = inMemorySessions!!
        val activeIdx = list.indexOfFirst { it.id == inMemoryActiveSessionId }.let { if (it == -1) 0 else it }
        list[activeIdx] = list[activeIdx].copy(
            draftInputText = draftInputText,
            lockedEngine = lockedEngine
        )
        flushToPrefs(context)
        return buildSnapshot(context)
    }

    private fun isSessionUnused(session: PersistedChatSession): Boolean =
        session.messages.none { it.sender == "user" || it.sender == "ai" }

    /**
     * Creates and activates a clean new chat session ("Yeni Sohbet").
     * - If the active session has no user/AI conversation yet, resets its messages, persona,
     *   draft input, and locked engine in place.
     * - Otherwise preserves the active conversation and opens/activates a clean session.
     */
    @Synchronized
    fun createNewSession(context: Context): PersistedChatSessionSnapshot {
        ensureLoaded(context)
        val list = inMemorySessions!!
        val activeIdx = list.indexOfFirst { it.id == inMemoryActiveSessionId }.let { if (it == -1) 0 else it }
        val currentActive = list[activeIdx]
        val now = System.currentTimeMillis()

        if (isSessionUnused(currentActive)) {
            val resetActive = currentActive.copy(
                title = "Yeni Sohbet",
                messages = emptyList(),
                currentPersona = AiPersona.SHARK_COACH,
                draftInputText = "",
                lockedEngine = null,
                updatedAtMillis = now
            )
            list[activeIdx] = resetActive
            inMemoryActiveSessionId = resetActive.id
        } else {
            val existingEmptyIdx = list.indexOfFirst { it.id != currentActive.id && isSessionUnused(it) }
            if (existingEmptyIdx != -1) {
                val resetEmpty = list[existingEmptyIdx].copy(
                    title = "Yeni Sohbet",
                    messages = emptyList(),
                    currentPersona = AiPersona.SHARK_COACH,
                    draftInputText = "",
                    lockedEngine = null,
                    updatedAtMillis = now
                )
                list[existingEmptyIdx] = resetEmpty
                inMemoryActiveSessionId = resetEmpty.id
            } else {
                val freshSession = PersistedChatSession(
                    id = UUID.randomUUID().toString(),
                    title = "Yeni Sohbet",
                    messages = emptyList(),
                    currentPersona = AiPersona.SHARK_COACH,
                    draftInputText = "",
                    lockedEngine = null,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
                list.add(0, freshSession)
                if (list.size > MAX_PERSISTED_SESSIONS) {
                    list.subList(MAX_PERSISTED_SESSIONS, list.size).clear()
                }
                inMemoryActiveSessionId = freshSession.id
            }
        }

        inMemorySessions = list.sortedByDescending { it.updatedAtMillis }.toMutableList()
        flushToPrefs(context)
        return buildSnapshot(context)
    }

    /**
     * Switches the active chat session to [sessionId].
     */
    @Synchronized
    fun selectSession(context: Context, sessionId: String): PersistedChatSessionSnapshot {
        ensureLoaded(context)
        val list = inMemorySessions!!
        val target = list.firstOrNull { it.id == sessionId }
        if (target != null) {
            inMemoryActiveSessionId = target.id
            flushToPrefs(context)
        }
        return buildSnapshot(context)
    }

    /**
     * Deletes the chat session with [sessionId].
     * - If the deleted session was active and other sessions remain, switches to the most recent remaining session.
     * - If no sessions remain, automatically creates a clean default session.
     */
    @Synchronized
    fun deleteSession(context: Context, sessionId: String): PersistedChatSessionSnapshot {
        ensureLoaded(context)
        val list = inMemorySessions!!

        list.removeAll { it.id == sessionId }

        if (list.isEmpty()) {
            val now = System.currentTimeMillis()
            val defaultSession = PersistedChatSession(
                id = UUID.randomUUID().toString(),
                title = "Yeni Sohbet",
                messages = emptyList(),
                currentPersona = AiPersona.SHARK_COACH,
                draftInputText = "",
                lockedEngine = null,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            list.add(defaultSession)
            inMemoryActiveSessionId = defaultSession.id
        } else if (inMemoryActiveSessionId == sessionId || list.none { it.id == inMemoryActiveSessionId }) {
            val mostRecent = list.maxByOrNull { it.updatedAtMillis } ?: list.first()
            inMemoryActiveSessionId = mostRecent.id
        }

        inMemorySessions = list.sortedByDescending { it.updatedAtMillis }.toMutableList()
        flushToPrefs(context)
        return buildSnapshot(context)
    }

    /**
     * Backward-compatible alias for creating/switching to a fresh session.
     */
    @Synchronized
    fun clearSession(context: Context): PersistedChatSessionSnapshot {
        return createNewSession(context)
    }
}
