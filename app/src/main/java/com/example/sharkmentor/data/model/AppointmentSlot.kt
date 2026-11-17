package com.example.sharkmentor.data.model

import java.util.Calendar
import java.util.Date

data class TimeSlotDefinition(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
) {
    val displayLabel: String
        get() = "%02d:%02d - %02d:%02d".format(startHour, startMinute, endHour, endMinute)
}

val STANDARD_DAILY_SLOTS = listOf(
    TimeSlotDefinition(9, 0, 9, 45),
    TimeSlotDefinition(10, 0, 10, 45),
    TimeSlotDefinition(11, 0, 11, 45),
    // 12:00 - 12:30 Yemek Molası
    TimeSlotDefinition(12, 30, 13, 15),
    TimeSlotDefinition(13, 30, 14, 15),
    TimeSlotDefinition(14, 30, 15, 15),
    TimeSlotDefinition(15, 30, 16, 15),
    TimeSlotDefinition(16, 30, 17, 15),
    TimeSlotDefinition(17, 15, 18, 0)
)

data class DayOption(
    val date: Date,
    val dayName: String,
    val formattedDate: String,
    val isOffDay: Boolean
)
