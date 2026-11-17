package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "plan_folders")
data class PlanFolderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String, // e.g., "12 Eyl - 19 Eyl Haftalık Plan"
    val createdAt: Long = System.currentTimeMillis(),
    val endTimestamp: Long = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000), // +7 days
    val isArchived: Boolean = false,
    val source: String = "MANUAL" // "AI_PLANNER", "AI_SCANNER", "MANUAL"
)
