package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "training_questions")
data class TrainingQuestionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val folderId: String,
    val questionText: String,
    val imageUri: String? = null,
    val correctAnswer: String = "",
    val userAnswer: String = "",
    val isSolved: Boolean = false,
    val reviewCount: Int = 0,
    val isSynced: Boolean = false,
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val cloudId: String? = null
)
