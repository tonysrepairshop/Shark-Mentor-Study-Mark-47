package com.example.sharkmentor.ui.plans.model

import androidx.annotation.Keep
import com.google.firebase.firestore.PropertyName
import java.util.UUID

@Keep
data class PlanItem(
    val id: String = UUID.randomUUID().toString(),
    val dayOfWeek: Int = 1, // 1: Pazartesi ... 7: Pazar
    val timeSlot: String = "", // e.g. "09:00 - 10:30"
    val subject: String = "", // e.g. "Matematik"
    val topic: String = "", // e.g. "Türev - Fiziksel Yorum"
    val targetQuestions: Int = 30,
    val focusStrategy: String = "", // e.g. "Soru Çözümü & Hata Analizi"
    @get:PropertyName("completed")
    @set:PropertyName("completed")
    var isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Keep
data class TopicCompetency(
    val id: String = "",
    val subject: String = "",
    val topicName: String = "",
    val level: Int = 1 // 1 (Başlangıç) to 5 (Usta)
)

@Keep
data class UserGoal(
    val title: String = "",
    val targetDate: String = "2026-10-25",
    val minTargetLevel: Int = 4
)

@Keep
data class AiWeeklyPlanResponse(
    val weeklyFocusSummary: String = "",
    val schedule: List<PlanItemResponse> = emptyList()
)

@Keep
data class PlanItemResponse(
    val dayOfWeek: Int = 1,
    val timeSlot: String = "",
    val subject: String = "",
    val topic: String = "",
    val targetQuestions: Int = 30,
    val focusStrategy: String = ""
)
