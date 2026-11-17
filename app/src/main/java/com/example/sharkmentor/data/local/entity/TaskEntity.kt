package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = TargetEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["targetId"])]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetId: Long,
    val folderId: String? = null,
    val dayOfWeek: Int = 1,
    val scheduledTime: String = "09:00",
    val title: String,
    val isCompleted: Boolean = false,
    val orderIndex: Int,
    // ── Cloud Sync Metadata ──────────────────────────────────────────────
    val isSynced: Boolean = false,
    val institutionId: String? = null
)
