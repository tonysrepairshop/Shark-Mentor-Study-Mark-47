package com.example.sharkmentor.ui.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.example.sharkmentor.data.focus.FocusSessionManager
import com.example.sharkmentor.core.service.AppBlockerService

class ToggleTimerActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val sessionManager = FocusSessionManager(context)
        val isRunning = sessionManager.isSessionActivelyRunning()

        if (isRunning) {
            // Stop/Pause Session
            sessionManager.endSession()
            val serviceIntent = Intent(context, AppBlockerService::class.java)
            context.stopService(serviceIntent)
        } else {
            // Start Default 25-min Quick Focus Session
            sessionManager.startSession(durationMinutes = 25, subject = "Genel Odak")
            val serviceIntent = Intent(context, AppBlockerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        // Trigger Instant Widget UI Refresh
        FocusTimerGlanceWidget().update(context, glanceId)
    }
}
