package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "concrete_targets")
data class ConcreteTargetEntity(
    @PrimaryKey val id: Int = 1, // Single active macro target
    val examType: String, // e.g. "YKS", "SAT", "IMAT", "AP", "ABITUR"
    val targetTitle: String, // e.g. "Yıldız Teknik Üniv. Bilgisayar Müh."
    val targetScoreOrRank: String, // e.g. "8.500 Sıralama" or "510 Puan"
    val aiAnalysisSummary: String, // AI-generated benchmark: required net breakdown, score type, difficulty
    val targetRanking: Int? = null,
    val targetUniversity: String? = null,
    val targetDepartment: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
