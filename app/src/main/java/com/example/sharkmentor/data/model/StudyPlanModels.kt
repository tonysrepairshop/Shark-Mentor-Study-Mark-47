package com.example.sharkmentor.data.model

import com.google.firebase.firestore.PropertyName

data class TaskItem(
    @get:PropertyName("goal") @set:PropertyName("goal") var goal: String = "",
    @get:PropertyName("subject") @set:PropertyName("subject") var subject: String = "",
    @get:PropertyName("time") @set:PropertyName("time") var time: String = "",
    @get:PropertyName("topic") @set:PropertyName("topic") var topic: String = "",
    @get:PropertyName("isCompleted") @set:PropertyName("isCompleted") var isCompleted: Boolean = false
)

data class DayItem(
    @get:PropertyName("day") @set:PropertyName("day") var day: String = "",
    @get:PropertyName("tasks") @set:PropertyName("tasks") var tasks: List<TaskItem> = emptyList()
)

data class ActiveStudyPlan(
    @get:PropertyName("title") @set:PropertyName("title") var title: String = "",
    @get:PropertyName("strategyNote") @set:PropertyName("strategyNote") var strategyNote: String = "",
    @get:PropertyName("days") @set:PropertyName("days") var days: List<DayItem> = emptyList()
)
