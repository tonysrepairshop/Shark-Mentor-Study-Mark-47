package com.example.sharkmentor.data.model

import com.google.firebase.Timestamp

data class Meeting(
    val id: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val coachId: String = "",
    val institutionId: String? = null,
    val appointmentTimestamp: Timestamp = Timestamp.now(),
    val requestNote: String? = null,
    val status: String = "AUTO_CONFIRMED",
    val evaluationNotes: String = ""
)
