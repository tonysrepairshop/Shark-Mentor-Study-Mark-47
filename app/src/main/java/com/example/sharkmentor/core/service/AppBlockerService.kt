package com.example.sharkmentor.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.R
import com.example.sharkmentor.core.blocker.BlockOverlayActivity
import com.example.sharkmentor.core.util.AppIconManager
import com.example.sharkmentor.core.timer.TimerPhase
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.data.focus.FocusSessionManager
import com.example.sharkmentor.data.local.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppBlockerService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var sessionManager: FocusSessionManager

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShowing = false

    companion object {
        private const val CHANNEL_ID = "sharkmentor_blocker_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = FocusSessionManager(this)
        usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        Log.d("SHARK_DEBUG", "[AppBlockerService] Service Created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                val timerState = FocusTimerService.timerStateFlow.value
                val isStudying = timerState.timerPhase == TimerPhase.FOCUSING || sessionManager.isSessionActivelyRunning() || SessionManager.isTimerRunning.value
                val userBlockedAppsList = UserPreferences.getInstance(this@AppBlockerService).blockedApps.ifEmpty { SessionManager.blockedPackages.value }
                val foregroundPackage = getForegroundPackageName(usageStatsManager)

                Log.d(
                    "SHARK_DEBUG",
                    "[AppBlockerService] Foreground App: $foregroundPackage | " +
                    "TimerRunning: $isStudying | " +
                    "BlockedCount: ${userBlockedAppsList.size} | " +
                    "CanDrawOverlay: ${Settings.canDrawOverlays(this@AppBlockerService)}"
                )

                if (isStudying && userBlockedAppsList.isNotEmpty()) {
                    if (foregroundPackage != null && foregroundPackage != packageName && userBlockedAppsList.contains(foregroundPackage)) {
                        Log.d("SHARK_DEBUG", "[AppBlockerService] BLOCK TRIGGERED for $foregroundPackage!")
                        if (Settings.canDrawOverlays(this@AppBlockerService)) {
                            withContext(Dispatchers.Main) {
                                showBlockOverlay(foregroundPackage)
                            }
                        } else {
                            val blockIntent = Intent(this@AppBlockerService, BlockOverlayActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(blockIntent)
                        }
                    } else if (isOverlayShowing && (foregroundPackage == packageName || !userBlockedAppsList.contains(foregroundPackage))) {
                        withContext(Dispatchers.Main) {
                            hideBlockOverlay()
                        }
                    }
                } else if (isOverlayShowing) {
                    withContext(Dispatchers.Main) {
                        hideBlockOverlay()
                    }
                }

                delay(800) // Poll every 800ms
            }
        }
    }

    private fun getForegroundPackageName(usm: UsageStatsManager): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 2500
        val usageEvents = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var lastForegroundApp: String? = null

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundApp = event.packageName
            }
        }
        return lastForegroundApp
    }

    private fun showBlockOverlay(blockedAppPackage: String) {
        if (isOverlayShowing) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val view = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E60B1B2B"))
            
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(60, 60, 60, 60)
            }

            val icon = TextView(context).apply {
                text = "🦈 🔒"
                textSize = 42f
                gravity = Gravity.CENTER
            }

            val title = TextView(context).apply {
                text = "Odak Seansı Devam Ediyor!"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 20, 0, 10)
            }

            val desc = TextView(context).apply {
                text = "Bu uygulama masa başında çalışma seansınız bitene kadar SharkMentor kalkanı tarafından engellenmiştir."
                textSize = 14f
                setTextColor(Color.parseColor("#CBF3F0"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            }

            val btn = Button(context).apply {
                text = "Masaya Geri Dön"
                setBackgroundColor(Color.parseColor("#2EC4B6"))
                setTextColor(Color.BLACK)
                setOnClickListener {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(intent)
                    hideBlockOverlay()
                }
            }

            container.addView(icon)
            container.addView(title)
            container.addView(desc)
            container.addView(btn)
            addView(container)
        }

        try {
            Log.d("SHARK_DEBUG", "[Overlay] Attempting to add overlay view via WindowManager...")
            overlayView = view
            windowManager?.addView(overlayView, params)
            isOverlayShowing = true
            Log.d("SHARK_DEBUG", "[Overlay] SUCCESS: Overlay view added to WindowManager.")
        } catch (e: Exception) {
            Log.e("SHARK_DEBUG", "[Overlay] FAILED: WindowManager.addView threw exception", e)
        }
    }

    private fun hideBlockOverlay() {
        if (isOverlayShowing && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
                Log.d("SHARK_DEBUG", "[Overlay] Overlay removed.")
            } catch (e: Exception) {
                Log.e("SHARK_DEBUG", "[Overlay] Failed to remove overlay", e)
            }
            overlayView = null
            isOverlayShowing = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideBlockOverlay()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SharkMentor Odak Kalkanı",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Uygulama engelleyici ve odak koruma servisi"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activeShark = AppIconManager.getActiveIcon(this)
        val largeIconBitmap = BitmapFactory.decodeResource(resources, activeShark.iconRes)
        val smallIconRes = if (activeShark.notificationSmallIconRes != 0) activeShark.notificationSmallIconRes else R.drawable.ic_notification_original

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SharkMentor Odak Kalkanı Aktif")
            .setContentText("Masa başındasınız, dikkat dağıtıcı uygulamalar engelleniyor.")
            .setSmallIcon(smallIconRes)
            .setLargeIcon(largeIconBitmap)
            .setColor(getColor(R.color.shark_blue_primary))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
}
