package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "net_projections")
data class NetProjectionEntity(
    @PrimaryKey val id: Int = 1, // Single-row cache
    val tytCurrentNet: Float,
    val tytProjectedNet: Float,
    val tytTargetNet: Float,
    val tytNetGap: Float,
    val aytCurrentNet: Float,
    val aytProjectedNet: Float,
    val aytTargetNet: Float,
    val aytNetGap: Float,
    val priorityTopicsJson: String, // Serialized list of recommended topics
    val strategicSummary: String,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val isDeleted: Boolean = false,
    val cloudId: String? = null
)
