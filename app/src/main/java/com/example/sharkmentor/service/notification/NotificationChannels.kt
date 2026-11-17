package com.example.sharkmentor.service.notification

import android.content.Context
import com.example.sharkmentor.core.notification.SharkNotificationEngine

object NotificationChannels {
    const val CHANNEL_DESK_ID = SharkNotificationEngine.CHANNEL_TIMER_DESK
    const val CHANNEL_BREAK_ID = SharkNotificationEngine.CHANNEL_ALERTS_COACH
    const val CHANNEL_SYSTEM_ID = SharkNotificationEngine.CHANNEL_ALERTS_COACH

    fun registerAll(context: Context) {
        SharkNotificationEngine.init(context)
    }
}
