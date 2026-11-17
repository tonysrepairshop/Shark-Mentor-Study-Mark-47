package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "topic_goals")
data class TopicGoalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subject: String,
    val topic: String,
    val currentLevel: Int = 0, // 0 to 5 mastery rating
    val targetLevel: Int = 5,
    val notes: String = "",
    val isSynced: Boolean = false,
    val isDeleted: Boolean = false,
    val institutionId: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
