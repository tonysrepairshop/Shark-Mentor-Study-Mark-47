package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "training_folders")
data class TrainingFolderEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val cloudId: String? = null
)
