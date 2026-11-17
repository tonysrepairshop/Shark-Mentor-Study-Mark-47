package com.example.sharkmentor.data.local.preferences

import android.content.Context
import android.content.SharedPreferences

class UserPreferences private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isOnboarded: Boolean
        get() = prefs.getBoolean(KEY_IS_ONBOARDED, false)
        private set(value) = prefs.edit().putBoolean(KEY_IS_ONBOARDED, value).apply()

    var studentName: String?
        get() = prefs.getString(KEY_STUDENT_NAME, null)
        private set(value) = prefs.edit().putString(KEY_STUDENT_NAME, value).apply()

    var institutionId: String?
        get() = prefs.getString(KEY_INSTITUTION_ID, null)
        private set(value) = prefs.edit().putString(KEY_INSTITUTION_ID, value).apply()

    var isIndividual: Boolean
        get() = prefs.getBoolean(KEY_IS_INDIVIDUAL, true)
        private set(value) = prefs.edit().putBoolean(KEY_IS_INDIVIDUAL, value).apply()

    var blockedApps: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED_APPS, value).apply()

    var countdownTargetDateMillis: Long
        get() = prefs.getLong(KEY_COUNTDOWN_TARGET_DATE, 0L)
        set(value) = prefs.edit().putLong(KEY_COUNTDOWN_TARGET_DATE, value).apply()

    var countdownExamTitle: String?
        get() = prefs.getString(KEY_COUNTDOWN_EXAM_TITLE, null)
        set(value) = prefs.edit().putString(KEY_COUNTDOWN_EXAM_TITLE, value).apply()

    var countdownUpdatedAt: Long
        get() = prefs.getLong(KEY_COUNTDOWN_UPDATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_COUNTDOWN_UPDATED_AT, value).apply()

    fun completeOnboardingAsIndividual(name: String) {
        studentName = name
        isIndividual = true
        institutionId = null
        isOnboarded = true
    }

    fun completeOnboardingWithInstitution(name: String, code: String) {
        studentName = name
        isIndividual = false
        institutionId = code
        isOnboarded = true
    }

    companion object {
        private const val PREFS_NAME = "user_preferences"
        private const val KEY_IS_ONBOARDED = "is_onboarded"
        private const val KEY_STUDENT_NAME = "student_name"
        private const val KEY_INSTITUTION_ID = "institution_id"
        private const val KEY_IS_INDIVIDUAL = "is_individual"
        private const val KEY_BLOCKED_APPS = "blocked_apps"
        private const val KEY_COUNTDOWN_TARGET_DATE = "countdown_target_date"
        private const val KEY_COUNTDOWN_EXAM_TITLE = "countdown_exam_title"
        private const val KEY_COUNTDOWN_UPDATED_AT = "countdown_updated_at"

        @Volatile
        private var INSTANCE: UserPreferences? = null

        fun getInstance(context: Context): UserPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
