package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TargetType {
    LONG_TERM, SHORT_TERM
}

enum class TargetPeriod {
    SIX_MONTH, MONTHLY, WEEKLY, DAILY
}

@Entity(
    tableName = "targets",
    indices = [
        androidx.room.Index(value = ["type"]),
        androidx.room.Index(value = ["createdAt"])
    ]
)
data class TargetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String,
    val type: TargetType,
    val period: TargetPeriod,
    val deadlineMillis: Long,
    val progress: Float = 0f,
    val isCompleted: Boolean = false,
    val targetQuestions: Int = 0,
    val solvedQuestions: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    // ── Coach Dispatch Fields ────────────────────────────────────────────
    val date: String = "",                        // YYYY-MM-DD from web dispatch
    val isAssignedByCoach: Boolean = false,        // true when origin = "WEB"
    val coachNote: String? = null,                 // optional coach message
    val priority: String = "NORMAL",               // "HIGH", "NORMAL", "LOW"
    val category: String = "CUSTOM",
    val targetValue: String? = null,
    // ── Cloud Sync Metadata ──────────────────────────────────────────────
    val isSynced: Boolean = false,
    val isDeleted: Boolean = false,
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val cloudId: String? = null,
    val institutionId: String? = null,
    val assignedCoachId: String? = null
)
