package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.AgentMemoryEntity

@Dao
interface AgentMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: AgentMemoryEntity)

    @Query("SELECT * FROM agent_memories WHERE expiresAt IS NULL OR expiresAt > :currentTime ORDER BY createdAt DESC")
    suspend fun getActiveMemories(currentTime: Long = System.currentTimeMillis()): List<AgentMemoryEntity>

    // 10-Day Auto-Purge Protocol:
    @Query("DELETE FROM agent_memories WHERE expiresAt IS NOT NULL AND expiresAt <= :currentTime")
    suspend fun purgeExpiredMemories(currentTime: Long = System.currentTimeMillis())

    @Query("DELETE FROM agent_memories WHERE personaKey = :personaKey")
    suspend fun clearPersonaMemory(personaKey: String)
}
