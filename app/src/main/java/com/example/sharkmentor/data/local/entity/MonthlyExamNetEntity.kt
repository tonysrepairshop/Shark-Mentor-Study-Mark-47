package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monthly_exam_nets")
data class MonthlyExamNetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val yearMonth: String, // Format: "2026-08"
    val examType: String,
    val netDetailsJson: String, // e.g. "TYT: 92 Net, AYT Mat: 34 Net, Fen: 31 Net"
    val notes: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val isDeleted: Boolean = false,
    val cloudId: String? = null
)
