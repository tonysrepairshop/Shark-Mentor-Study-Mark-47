package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records a local deletion that must be propagated to Firestore.
 *
 * When a user deletes a session, target, plan, or resource from Room,
 * a row is inserted here so that [SyncWorker] can issue the corresponding
 * Firestore `document.delete()` on the next sync cycle.
 */
@Entity(tableName = "pending_deletions")
data class PendingDeletionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val entityId: String,        // Room local ID (used as Firestore doc ID)
    val collectionName: String,  // Firestore collection: "focus_sessions", "targets", "plans", "sources"
    val createdAt: Long = System.currentTimeMillis()
)
