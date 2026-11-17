package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "active_study_plans")
data class ActivePlanEntity(
    @PrimaryKey val id: String = "CURRENT_ACTIVE_PLAN",
    val title: String,
    val strategyNote: String,
    val totalTargetQuestions: Int,
    val updatedAtTimestamp: Long
)

@Entity(
    tableName = "study_plan_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ActivePlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("planId"), Index("dayName")]
)
data class StudyTaskEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val planId: String = "CURRENT_ACTIVE_PLAN",
    val dayName: String, // e.g. "Pazartesi", "Salı"
    val dayIndex: Int,   // 0 for Pazartesi, 6 for Pazar
    val time: String,
    val subject: String,
    val topic: String,
    val goal: String,
    val isCompleted: Boolean = false
)
