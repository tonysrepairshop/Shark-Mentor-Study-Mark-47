package com.example.sharkmentor.data.repository

import com.example.sharkmentor.data.local.dao.AgentMemoryDao
import com.example.sharkmentor.data.local.entity.AgentMemoryEntity
import com.example.sharkmentor.ui.aichat.AiPersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentMemoryRepository(private val dao: AgentMemoryDao) {
    private val TEN_DAYS_MILLIS = 10L * 24 * 60 * 60 * 1000

    suspend fun recordInsight(persona: AiPersona, memoryType: String, content: String) = withContext(Dispatchers.IO) {
        val expiresAt = if (persona.isTemporaryMemory) {
            System.currentTimeMillis() + TEN_DAYS_MILLIS
        } else {
            null
        }

        dao.purgeExpiredMemories() // Clean stale records on write
        dao.insertMemory(
            AgentMemoryEntity(
                personaKey = persona.name,
                memoryType = memoryType,
                content = content,
                expiresAt = expiresAt
            )
        )
    }

    suspend fun buildSemanticSnapshot(): String = withContext(Dispatchers.IO) {
        dao.purgeExpiredMemories()
        val active = dao.getActiveMemories()
        if (active.isEmpty()) return@withContext "Hafıza: Yeni öğrenci, henüz geçmiş karar kaydı yok."

        active.joinToString(separator = " | ") { "[${it.personaKey}/${it.memoryType}]:${it.content}" }
    }
}
