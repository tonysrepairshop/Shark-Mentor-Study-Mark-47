package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "resources")
data class ResourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val subject: String,
    val totalUnits: Int,
    val completedUnits: Int = 0,
    val unitType: String = "Test", // Test, Sayfa, Bölüm
    val updatedAtMillis: Long = System.currentTimeMillis(),
    // ── Cloud Sync Metadata ──────────────────────────────────────────────
    val colorHex: String? = null,
    val isAssignedByCoach: Boolean = false,
    val isSynced: Boolean = false,
    val isDeleted: Boolean = false,
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val cloudId: String? = null,
    val institutionId: String? = null,
    val assignedCoachId: String? = null
)
