package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val folderId: String = "default_folder",
    val title: String,
    val subject: String = "Ders",         // Never defaults to "Genel"
    val topic: String = "",               // Topic/subtopic displayed as subtext on cards
    val dayOfWeek: Int = 1,              // 1 = Pazartesi, 7 = Pazar
    val scheduledTime: String = "09:00", // "HH:mm - HH:mm" format
    val targetQuestions: Int = 30,       // Minimum 30, never 0
    val isCompleted: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
