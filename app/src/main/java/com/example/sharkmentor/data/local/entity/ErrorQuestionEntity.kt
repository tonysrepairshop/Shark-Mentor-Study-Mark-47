package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "error_questions")
data class ErrorQuestionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subject: String,
    val questionImageUri: String,
    val solutionImageUri: String? = null,
    val topic: String = "Genel",
    val stability: Double = 0.4,
    val difficulty: Double = 5.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: Int = 0, // 0 = New, 1 = Learning, 2 = Review, 3 = Relearning
    val lastReviewMillis: Long? = null,
    val dueDateMillis: Long = System.currentTimeMillis(),
    val isTwinGenerated: Boolean = false,
    val twinQuestionJson: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    // ── Cloud Sync Metadata ──────────────────────────────────────────────
    val isSynced: Boolean = false,
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val cloudId: String? = null,
    val institutionId: String? = null,
    val assignedCoachId: String? = null,
    // ── AI-Generated Sub-Topic Tag ───────────────────────────────────────
    /** Gemini Vision–detected sub-topic (e.g. "Sinir Sistemi"). Null = not yet tagged. */
    val aiTopic: String? = null
)
