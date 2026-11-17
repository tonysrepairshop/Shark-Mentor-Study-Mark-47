package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_reflections")
data class DailyReflectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateString: String, // Format: YYYY-MM-DD (Unique per day)
    val productivityScore: Int, // 1 - 10
    val reflectionNote: String, // Max 400 characters
    val createdAtMillis: Long = System.currentTimeMillis()
)
