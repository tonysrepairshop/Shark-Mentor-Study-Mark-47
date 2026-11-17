package com.example.sharkmentor.service.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.sharkmentor.service.blocker.StrictAppBlockerService

class DeskActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE_RESUME = "com.example.sharkmentor.ACTION_PAUSE_RESUME"
        const val ACTION_STOP_DESK = "com.example.sharkmentor.ACTION_STOP_DESK"
        const val ACTION_VERIFY_PRESENCE = "com.example.sharkmentor.ACTION_VERIFY_PRESENCE"
        const val NOTIFICATION_ID_VERIFICATION = 9002
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent?.action) {
            ACTION_PAUSE_RESUME -> {
                val serviceIntent = Intent(context, StrictAppBlockerService::class.java).apply {
                    action = StrictAppBlockerService.ACTION_TOGGLE_PAUSE
                }
                context.startService(serviceIntent)
            }

            ACTION_STOP_DESK -> {
                val serviceIntent = Intent(context, StrictAppBlockerService::class.java).apply {
                    action = StrictAppBlockerService.ACTION_STOP_SERVICE
                }
                context.startService(serviceIntent)
            }

            // Fix for 90-minute verification notification dismissal
            ACTION_VERIFY_PRESENCE -> {
                // 1. Immediately dismiss the verification notification
                notificationManager.cancel(NOTIFICATION_ID_VERIFICATION)

                // 2. Notify the service/timer to reset verification timer
                val serviceIntent = Intent(context, StrictAppBlockerService::class.java).apply {
                    action = StrictAppBlockerService.ACTION_RESET_VERIFICATION
                }
                context.startService(serviceIntent)
            }
        }
    }
}
