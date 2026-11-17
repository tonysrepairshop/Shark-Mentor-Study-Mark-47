package com.example.sharkmentor.ui.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.core.service.FocusTimerService
import com.example.sharkmentor.core.timer.TimerMode
import com.example.sharkmentor.core.timer.TimerPhase
import com.example.sharkmentor.core.widget.WidgetUpdateManager
import com.example.sharkmentor.data.focus.FocusSessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class ToggleTimerActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val currentState = FocusTimerService.timerStateFlow.value
        val sessionManager = FocusSessionManager(context)

        when (currentState.timerPhase) {
            TimerPhase.FOCUSING -> {
                val pauseIntent = Intent(context, FocusTimerService::class.java).apply {
                    action = FocusTimerService.ACTION_PAUSE
                }
                runCatching { context.startService(pauseIntent) }
                withTimeoutOrNull(600L) {
                    FocusTimerService.timerStateFlow.first { it.timerPhase == TimerPhase.PAUSED }
                }
            }

            TimerPhase.PAUSED -> {
                val resumeIntent = Intent(context, FocusTimerService::class.java).apply {
                    action = FocusTimerService.ACTION_RESUME
                }
                runCatching { context.startService(resumeIntent) }
                withTimeoutOrNull(600L) {
                    FocusTimerService.timerStateFlow.first { it.timerPhase == TimerPhase.FOCUSING }
                }
            }

            TimerPhase.MANDATORY_BREAK -> {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("EXTRA_TARGET_ROUTE", "home")
                }
                runCatching { context.startActivity(openIntent) }
            }

            TimerPhase.IDLE -> {
                val modeToStart = currentState.currentMode.takeIf { it != TimerMode.BREAK } ?: TimerMode.POMODORO
                val startIntent = Intent(context, FocusTimerService::class.java).apply {
                    action = FocusTimerService.ACTION_START
                    putExtra(FocusTimerService.EXTRA_MODE, modeToStart.name)
                    putExtra(FocusTimerService.EXTRA_POMODORO_MINUTES, currentState.customPomodoroDurationMinutes)
                    putExtra(FocusTimerService.EXTRA_BREAK_MINUTES, currentState.breakDurationMinutes)
                    putExtra(FocusTimerService.EXTRA_SUBJECT, "Genel Odak")
                }

                val started = runCatching {
                    context.startForegroundService(startIntent)
                }.isSuccess

                if (started) {
                    val durationMin = if (modeToStart == TimerMode.BLOCK_90) 90 else currentState.customPomodoroDurationMinutes
                    sessionManager.startSession(durationMinutes = durationMin, subject = "Genel Odak")
                    withTimeoutOrNull(800L) {
                        FocusTimerService.timerStateFlow.first { it.timerPhase == TimerPhase.FOCUSING }
                    }
                } else {
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("EXTRA_START_FOCUS", true)
                    }
                    runCatching { context.startActivity(openIntent) }
                }
            }
        }

        WidgetUpdateManager.updateAllWidgets(context)
    }
}
