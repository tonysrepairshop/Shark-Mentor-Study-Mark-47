package com.example.sharkmentor.core.analytics

import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.data.local.entity.ResourceEntity
import com.example.sharkmentor.data.local.entity.TopicGoalEntity

data class SubjectMastery(
    val subjectName: String,
    val score: Int, // 0 - 100
    val topicAverage: Float, // 1.0 - 5.0
    val resourceProgress: Float, // 0.0 - 1.0
    val errorBankHealth: Float // 0.0 - 1.0
)

data class OverallMasteryReport(
    val overallScore: Int, // 0 - 100
    val subjectBreakdown: List<SubjectMastery>,
    val totalFocusMinutes: Long,
    val pendingReviewCount: Int
)

object MasteryScoreEngine {

    fun calculate(
        topics: List<TopicGoalEntity>,
        resources: List<ResourceEntity>,
        errorQuestions: List<ErrorQuestionEntity>,
        totalFocusMinutes: Long,
        recentReflectionAvg: Float? = null
    ): OverallMasteryReport {
        // ABSOLUTE ZERO CHECK: If user has zero active data, return pure 0%
        val hasAnyRatedTopic = topics.any { it.currentLevel > 0 }
        val hasAnyResourceProgress = resources.any { it.completedUnits > 0 }
        val hasAnyErrorQuestions = errorQuestions.isNotEmpty()
        val hasAnyFocus = totalFocusMinutes > 0

        if (!hasAnyRatedTopic && !hasAnyResourceProgress && !hasAnyErrorQuestions && !hasAnyFocus) {
            val emptySubjects = topics.map { it.subject }.distinct().map {
                SubjectMastery(
                    subjectName = it,
                    score = 0,
                    topicAverage = 0f,
                    resourceProgress = 0f,
                    errorBankHealth = 0f
                )
            }
            return OverallMasteryReport(
                overallScore = 0,
                subjectBreakdown = emptySubjects,
                totalFocusMinutes = totalFocusMinutes,
                pendingReviewCount = 0
            )
        }

        val subjects = (topics.map { it.subject } + 
                        resources.map { it.subject } + 
                        errorQuestions.map { it.subject }).distinct()

        val breakdown = subjects.map { subject ->
            val subjectTopics = topics.filter { it.subject.equals(subject, ignoreCase = true) }
            val subjectResources = resources.filter { it.subject.equals(subject, ignoreCase = true) }
            val subjectErrors = errorQuestions.filter { it.subject.equals(subject, ignoreCase = true) }

            // 1. Topic Confidence Factor (Weight: 50%) -> (Avg Level / 5.0) * 100
            val topicScore = if (subjectTopics.isNotEmpty()) {
                val avgLevel = subjectTopics.map { it.currentLevel }.average().toFloat()
                (avgLevel / 5f).coerceIn(0f, 1f) * 100f
            } else 0f

            // 2. Resource Completion Factor (Weight: 30%)
            val resourceScore = if (subjectResources.isNotEmpty()) {
                val totalUnits = subjectResources.sumOf { it.totalUnits }.coerceAtLeast(1)
                val completedUnits = subjectResources.sumOf { it.completedUnits }
                (completedUnits.toFloat() / totalUnits.toFloat()).coerceIn(0f, 1f) * 100f
            } else 0f

            // 3. Error Bank Health (Weight: 20%) -> Only give points if user actually has questions and resolved them
            val errorHealth = if (subjectErrors.isNotEmpty()) {
                val resolvedCount = subjectErrors.count { it.reps >= 3 }
                (resolvedCount.toFloat() / subjectErrors.size.toFloat()) * 100f
            } else 0f

            // Weighted Composite Score
            val compositeScore = (topicScore * 0.50f) + (resourceScore * 0.30f) + (errorHealth * 0.20f)
            
            val totalUnits = subjectResources.sumOf { it.totalUnits }
            val completedUnits = subjectResources.sumOf { it.completedUnits }

            SubjectMastery(
                subjectName = subject,
                score = compositeScore.toInt().coerceIn(0, 100),
                topicAverage = if (subjectTopics.isNotEmpty()) subjectTopics.map { it.currentLevel }.average().toFloat() else 0f,
                resourceProgress = if (totalUnits > 0) completedUnits.toFloat() / totalUnits.toFloat() else 0f,
                errorBankHealth = errorHealth / 100f
            )
        }.sortedByDescending { it.score }

        // Overall Score Calculation
        val avgSubjectScore = if (breakdown.isNotEmpty()) {
            breakdown.map { it.score }.average().toFloat()
        } else 0f

        // Focus Score (Benchmark: 1200 mins = 100%)
        val focusScore = (totalFocusMinutes.toFloat() / 1200f).coerceIn(0f, 1f) * 100f

        // Reflection Score
        val reflectionScore = if (recentReflectionAvg != null && recentReflectionAvg > 0f) {
            (recentReflectionAvg / 10f) * 100f
        } else 0f

        val finalOverall = (avgSubjectScore * 0.70f) + (focusScore * 0.20f) + (reflectionScore * 0.10f)

        val dueCount = errorQuestions.count { it.state == 0 || it.dueDateMillis <= System.currentTimeMillis() }

        return OverallMasteryReport(
            overallScore = finalOverall.toInt().coerceIn(0, 100),
            subjectBreakdown = breakdown,
            totalFocusMinutes = totalFocusMinutes,
            pendingReviewCount = dueCount
        )
    }
}
