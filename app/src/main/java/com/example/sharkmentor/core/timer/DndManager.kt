package com.example.sharkmentor.core.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

class DndManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun isDndPermissionGranted(): Boolean {
        return notificationManager.isNotificationPolicyAccessGranted
    }

    fun requestDndPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Toast.makeText(context, "Lütfen Derin Odak için Rahatsız Etmeyin iznini etkinleştirin.", Toast.LENGTH_LONG).show()
    }

    fun enableDeepFocusDnd() {
        try {
            if (isDndPermissionGranted()) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disableDnd() {
        try {
            if (isDndPermissionGranted()) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
