package com.example.sharkmentor.domain.model.prediction

import androidx.compose.runtime.Immutable

enum class ExamCategory { TYT, AYT }

data class StudentTopicMastery(
    val topicName: String,
    val subjectName: String,
    val masteryScore: Int // 0 to 100 percentage
)

data class TopicWeight(
    val category: ExamCategory = ExamCategory.TYT,
    val subjectName: String,
    val topicName: String,
    val averageOsymQuestions: Float
)

data class NetGapRecommendation(
    val subjectName: String,
    val topicName: String,
    val potentialNetBoost: Float,
    val priorityScore: Int // 1 (Highest priority) to 5
)

@Immutable
data class NetProjectionResult(
    val category: ExamCategory,
    val currentAverageNet: Float,
    val targetNet: Float,
    val projectedNet: Float,
    val netGap: Float, // targetNet - projectedNet (positive means behind target)
    val momentumScore: Float, // Growth rate per month (e.g., +2.4 net/month)
    val unlockedNetCapacity: Float, // Potential gain from unmastered high-yield topics
    val topPriorityTopics: List<NetGapRecommendation> = emptyList(),
    val diagnosticSummary: String
)

@Immutable
data class NetWizardUiState(
    val isLoading: Boolean = true,
    val tytProjection: NetProjectionResult? = null,
    val aytProjection: NetProjectionResult? = null,
    val weeklyComplianceRatio: Float = 1.0f,
    val error: String? = null
)
