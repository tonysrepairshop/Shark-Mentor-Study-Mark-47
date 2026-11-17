package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "focus_sessions",
    indices = [androidx.room.Index(value = ["completedAt"])]
)
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val modeName: String,
    val durationSeconds: Long,
    val completedAt: Long = System.currentTimeMillis(),
    val afkScore: Float = 10.0f,
    val phoneLiftCount: Int = 0,
    val screenOnMinutes: Int = 0,
    // ── Cloud Sync Metadata ──────────────────────────────────────────────
    val isSynced: Boolean = false,
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val cloudId: String? = null,
    val institutionId: String? = null,
    val assignedCoachId: String? = null
)
