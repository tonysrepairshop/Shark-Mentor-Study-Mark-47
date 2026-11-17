package com.example.sharkmentor.core.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

object WidgetUpdateManager {

    suspend fun syncWidgetData(
        context: Context,
        masteryScore: Int,
        todayFocusMinutes: Long,
        pendingReviews: Int
    ) {
        val prefs = context.getSharedPreferences("shark_widget_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("pref_mastery_score", masteryScore)
            .putLong("pref_today_focus_mins", todayFocusMinutes)
            .putInt("pref_pending_reviews", pendingReviews)
            .apply()

        // Trigger instant glance update
        SharkMentorWidget().updateAll(context)
    }
}
