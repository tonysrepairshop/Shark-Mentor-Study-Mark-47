package com.example.sharkmentor.worker

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.core.notification.SharkNotificationEngine
import com.example.sharkmentor.core.streak.WaterStreakMoodEngine
import com.example.sharkmentor.core.util.AppIconManager
import com.example.sharkmentor.core.widget.WidgetUpdateManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Duolingo-style Water Streak ("Su Serisi") periodic worker.
 *
 * Responsibilities:
 *  1. Refreshes [com.example.sharkmentor.widget.SharkStreakWidget] every 2 hours so the shark
 *     mascot on the user's home screen dynamically shifts moods throughout the day.
 *  2. Sends up to 4 deduplicated emotional reminder notifications when today's water drop
 *     (>= 20 min focus) has not been secured yet, matched with mood-appropriate Turkish copy
 *     and emojis (`💧`, `🦈`, `🥺`, `😱`, `😢`, `😤`, `🤯`, `😭`).
 *  3. Immediately suppresses all reminders once `isStreakDoneToday == true`.
 */
class StreakReminderWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appCtx = context.applicationContext
        SharkNotificationEngine.init(appCtx)

        val snapshot = runCatching { WidgetUpdateManager.loadSnapshot(appCtx) }
            .onFailure { Log.w(TAG, "Failed to load widget snapshot: ${it.message}") }
            .getOrNull()

        // Always keep home-screen widgets fresh with the current time-of-day mascot mood
        runCatching { WidgetUpdateManager.updateAllWidgets(appCtx) }

        if (snapshot == null) return Result.success()

        // Never nag once today's Water Drop is secured!
        if (snapshot.isStreakDoneToday) {
            return Result.success()
        }

        val now = LocalDateTime.now()
        val hour = now.hour
        val slotKey = resolveReminderSlot(hour) ?: return Result.success()

        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val notifPrefs = appCtx.getSharedPreferences(NOTIF_PREFS, Context.MODE_PRIVATE)
        val sentKey = "sent_${todayStr}_$slotKey"
        if (notifPrefs.getBoolean(sentKey, false)) {
            return Result.success()
        }

        val decision = WaterStreakMoodEngine.resolve(
            streakCount = snapshot.streakCount,
            isDoneToday = false,
            todayMinutes = snapshot.todayFocusMinutes,
            hourOfDay = hour,
            dayOfYear = now.dayOfYear,
            daysSinceLastStudy = snapshot.daysSinceLastStudy
        )

        val delivered = showWaterStreakNotification(
            context = appCtx,
            title = decision.notificationTitle,
            body = decision.notificationBody,
            mascotDrawableRes = decision.mood.widgetDrawableRes,
            notificationId = NOTIFICATION_ID_REMINDER
        )

        if (delivered) {
            // Prune stale keys from previous days while recording today's slot
            val editor = notifPrefs.edit()
            notifPrefs.all.keys
                .filter { it.startsWith("sent_") && !it.startsWith("sent_$todayStr") }
                .forEach { editor.remove(it) }
            editor.putBoolean(sentKey, true).apply()
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "StreakReminderWorker"
        private const val WORK_NAME = "shark_water_streak_worker"
        private const val NOTIF_PREFS = "water_streak_notif_prefs"
        private const val NOTIFICATION_ID_REMINDER = 2001
        private const val NOTIFICATION_ID_CELEBRATION = 2002

        private val MILESTONE_DAYS = setOf(1, 3, 7, 14, 21, 30, 50, 75, 100, 150, 200, 365)

        /**
         * Four curated Duolingo-style windows during waking hours:
         *  - midday  (12..14): curious / shy gentle nudge
         *  - evening (17..19): shocked / crying reminder
         *  - night   (21..22): furious / panic urgent push
         *  - final   (23):     tantrum last-chance alert
         */
        internal fun resolveReminderSlot(hourOfDay: Int): String? = when (hourOfDay) {
            in 12..14 -> "midday"
            in 17..19 -> "evening"
            in 21..22 -> "night"
            23 -> "final"
            else -> null
        }

        fun schedule(context: Context) {
            val appCtx = context.applicationContext
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<StreakReminderWorker>(
                2,
                TimeUnit.HOURS,
                30,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            runCatching {
                WorkManager.getInstance(appCtx).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
            }.onFailure {
                Log.w(TAG, "Failed to schedule water streak worker: ${it.message}")
            }
        }

        /**
         * Shows a celebratory Water Streak notification when the student secures today's
         * water drop for the first time (especially on streak milestones).
         */
        fun showWaterDropSecuredNotification(context: Context, streakCount: Int, todayMinutes: Int) {
            val appCtx = context.applicationContext
            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val notifPrefs = appCtx.getSharedPreferences(NOTIF_PREFS, Context.MODE_PRIVATE)
            val celebKey = "celebrated_$todayStr"
            if (notifPrefs.getBoolean(celebKey, false)) return

            val now = LocalDateTime.now()
            val decision = WaterStreakMoodEngine.resolve(
                streakCount = streakCount,
                isDoneToday = true,
                todayMinutes = todayMinutes.toLong(),
                hourOfDay = now.hour,
                dayOfYear = now.dayOfYear,
                daysSinceLastStudy = 0
            )

            val isMilestone = streakCount in MILESTONE_DAYS
            val title = if (isMilestone) {
                "✨ $streakCount Günlük Su Serisi Rekoru! 💧"
            } else {
                decision.notificationTitle
            }
            val body = if (isMilestone) {
                "Müthiş istikrar! $streakCount gündür okyanusu canlı tutuyorsun. Bugünkü damla da cepte! 🦈💧✨"
            } else {
                decision.notificationBody
            }

            if (showWaterStreakNotification(
                    context = appCtx,
                    title = title,
                    body = body,
                    mascotDrawableRes = decision.mood.widgetDrawableRes,
                    notificationId = NOTIFICATION_ID_CELEBRATION
                )
            ) {
                notifPrefs.edit().putBoolean(celebKey, true).apply()
            }
        }

        private fun showWaterStreakNotification(
            context: Context,
            title: String,
            body: String,
            mascotDrawableRes: Int,
            notificationId: Int
        ): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }

            SharkNotificationEngine.init(context)
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return false

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_START_FOCUS", true)
                putExtra("EXTRA_TARGET_ROUTE", "home")
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val smallIconRes = runCatching {
                AppIconManager.getActiveIcon(context).notificationSmallIconRes
            }.getOrDefault(com.example.sharkmentor.R.drawable.ic_notification_original)

            val largeIconBitmap = runCatching {
                BitmapFactory.decodeResource(context.resources, mascotDrawableRes)
            }.getOrNull()

            val builder = NotificationCompat.Builder(context, SharkNotificationEngine.CHANNEL_WATER_STREAK)
                .setSmallIcon(smallIconRes)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            if (largeIconBitmap != null) {
                builder.setLargeIcon(largeIconBitmap)
            }

            return runCatching {
                notificationManager.notify(notificationId, builder.build())
                true
            }.getOrDefault(false)
        }
    }
}
