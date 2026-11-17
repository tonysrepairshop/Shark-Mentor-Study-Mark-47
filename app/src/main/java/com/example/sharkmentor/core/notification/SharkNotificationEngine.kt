package com.example.sharkmentor.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object SharkNotificationEngine {
    const val CHANNEL_TIMER_DESK = "shark_focus_desk"
    const val CHANNEL_SYNC_BACKGROUND = "shark_silent_sync"
    const val CHANNEL_ALERTS_COACH = "shark_alerts"

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val deskChannel = NotificationChannel(
            CHANNEL_TIMER_DESK,
            "Masa Odak Sayacı",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aktif ders ve masa odak seansı görsel zamanlayıcısı"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val syncChannel = NotificationChannel(
            CHANNEL_SYNC_BACKGROUND,
            "Arka Plan Eşitleme",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Arka plan veri eşitleme ve kayıt bildirimleri"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS_COACH,
            "Koç Mesajları & Bildirimler",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Koç mesajları, mola ve seans bitiş uyarıları"
            enableVibration(true)
        }

        manager.createNotificationChannels(listOf(deskChannel, syncChannel, alertsChannel))
    }
}
