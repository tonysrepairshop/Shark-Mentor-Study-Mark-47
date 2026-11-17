package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_memories")
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val personaKey: String,          // COACH, ZEN_CALM, GOAL_SETTER, etc.
    val memoryType: String,          // DECISION, WEAKNESS, GOAL_STATE, EMOTIONAL_LOG
    val content: String,             // Compressed semantic summary (< 200 chars)
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null      // null = Permanent; non-null = Epoch millis for TTL
)
