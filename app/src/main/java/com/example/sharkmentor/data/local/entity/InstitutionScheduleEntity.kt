package com.example.sharkmentor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a fixed institutional class slot (school / dershane timetable).
 * Synced from Firestore: users/{uid}/institution_schedule
 * Used by [SchedulePlannerEngine] to inject hard "no-study" constraints.
 */
@Entity(tableName = "institution_schedule")
data class InstitutionScheduleEntity(
    @PrimaryKey val id: String,
    /** 1 = Pazartesi … 7 = Pazar */
    val dayOfWeek: Int,
    /** "HH:mm" format, e.g. "09:00" */
    val startTime: String,
    /** "HH:mm" format, e.g. "12:00" */
    val endTime: String,
    /** Subject taught at this institution slot, e.g. "Matematik" */
    val subject: String,
    /** "SCHOOL" or "DERSHANE" */
    val type: String = "DERSHANE",
    /** Teacher's name, e.g. "Ö.YLM" */
    val teacherName: String = "",
    /** Period index for tabular view, e.g. 1, 2, 3... */
    val period: Int = 0,
    val isSynced: Boolean = false,
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val cloudId: String? = null
)
