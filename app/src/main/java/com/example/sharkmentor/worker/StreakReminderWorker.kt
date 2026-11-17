package com.example.sharkmentor.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.example.sharkmentor.MainActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.concurrent.TimeUnit

class StreakReminderWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences("streak_prefs", Context.MODE_PRIVATE)
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        // Checking if already completed today
        val lastStreakDate = prefs.getString("lastStreakDate", "")
        if (lastStreakDate == todayStr) {
            return Result.success()
        }

        // If today's completed focus minutes == 0 AND questions solved == 0
        // (Assuming we read from a daily stats pref for this check)
        val statsPrefs = context.getSharedPreferences("daily_stats_$todayStr", Context.MODE_PRIVATE)
        val focusMinutes = statsPrefs.getInt("focus_minutes", 0)
        val questions = statsPrefs.getInt("questions", 0)

        if (focusMinutes == 0 && questions == 0) {
            showNotification()
        }

        return Result.success()
    }

    private fun showNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "streak_alerts"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Seri Hatırlatıcıları",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Günlük seri uyarıları"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Fallback icon
            .setContentTitle("Serin Tehlikede! \uD83D\uDD25")
            .setContentText("Günü boş geçme patron! 15 dakikalık tek bir odaklanmayla serini koruyabilirsin.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2001, notification)
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            // Calculate initial delay for 20:00
            val currentTime = Calendar.getInstance()
            val targetTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 20)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }

            if (currentTime.after(targetTime)) {
                targetTime.add(Calendar.DAY_OF_MONTH, 1)
            }

            val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

            val workRequest = PeriodicWorkRequestBuilder<StreakReminderWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "streak_reminder_worker",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }
}
