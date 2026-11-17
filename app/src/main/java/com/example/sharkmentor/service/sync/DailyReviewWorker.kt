package com.example.sharkmentor.service.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.graphics.BitmapFactory
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.R
import com.example.sharkmentor.core.util.AppIconManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DailyReviewWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        showDailyReviewNotification(applicationContext)
        return Result.success()
    }

    private fun showDailyReviewNotification(context: Context) {
        val channelId = "daily_review_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Gün Değerlendirmesi Hatırlatıcı",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("EXTRA_OPEN_REFLECTION", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeShark = AppIconManager.getActiveIcon(context)
        val largeIconBitmap = BitmapFactory.decodeResource(context.resources, activeShark.iconRes)
        val smallIconRes = if (activeShark.notificationSmallIconRes != 0) activeShark.notificationSmallIconRes else R.drawable.ic_notification_original

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconRes)
            .setLargeIcon(largeIconBitmap)
            .setColor(context.getColor(R.color.shark_blue_primary))
            .setContentTitle("Bugünkü Çalışman Nasıl Geçti? 🦈")
            .setContentText("Günün değerlendirmesini yapmak ve verilerini kaydetmek için dokun.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(2001, notification)
    }

    companion object {
        fun scheduleDailyReview(context: Context, hour: Int, minute: Int) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val delayMillis = target.timeInMillis - now.timeInMillis
            val reviewRequest = PeriodicWorkRequestBuilder<DailyReviewWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "DailyReviewNotification",
                ExistingPeriodicWorkPolicy.UPDATE,
                reviewRequest
            )
        }
    }
}
