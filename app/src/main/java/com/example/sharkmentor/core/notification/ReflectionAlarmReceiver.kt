package com.example.sharkmentor.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.R
import com.example.sharkmentor.core.util.AppIconManager

class ReflectionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Günlük Bilişsel Değerlendirme",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("EXTRA_OPEN_REFLECTION", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeShark = AppIconManager.getActiveIcon(context)
        val largeIconBitmap = BitmapFactory.decodeResource(context.resources, activeShark.iconRes)
        val smallIconRes = if (activeShark.notificationSmallIconRes != 0) activeShark.notificationSmallIconRes else R.drawable.ic_notification_original

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setLargeIcon(largeIconBitmap)
            .setColor(context.getColor(R.color.shark_blue_primary))
            .setContentTitle("Bugünün verimini konuşalım 🦈")
            .setContentText("Bugünkü çalışma odak ve verimini 1-10 arası puanla, mentorunu bilgilendir.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "reflection_channel"
        const val NOTIFICATION_ID = 101
    }
}
