package com.example.sharkmentor.service.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.core.util.SessionManager

class SharkBlockerAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShowing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d("SHARK_DEBUG", "[Accessibility] Service Connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // 1. Ignore events from our own app package to prevent the overlay from closing itself
        if (packageName == applicationContext.packageName) {
            return
        }

        val isStudying = SessionManager.isTimerRunning.value
        val isBreak = SessionManager.isBreakActive.value
        val blockedList = SessionManager.blockedPackages.value

        Log.d(
            "SHARK_DEBUG",
            "[Accessibility] Event Captured: $packageName | " +
            "TimerRunning: $isStudying | IsBreak: $isBreak | " +
            "BlockedCount: ${blockedList.size} | " +
            "IsBlocked: ${blockedList.contains(packageName)}"
        )

        // 2. Only block if studying AND not currently on break
        if (isStudying && !isBreak && blockedList.contains(packageName)) {
            Log.d("SHARK_DEBUG", "[Accessibility] Intercepting forbidden app: $packageName")
            
            // Minimize the blocked app
            performGlobalAction(GLOBAL_ACTION_HOME)
            
            // Show persistent blocker window
            showBlockOverlay(packageName)
        }
    }

    private fun showBlockOverlay(targetPackage: String) {
        if (isOverlayShowing) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F00B1B2B"))
            setPadding(48, 48, 48, 48)

            val iconSizePx = (48 * resources.displayMetrics.density).toInt()
            val icon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_lock_lock)
                setColorFilter(Color.parseColor("#2EC4B6"))
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                    gravity = Gravity.CENTER
                }
            }

            val title = TextView(context).apply {
                text = "Odak Seansı Kilitlendi"
                textSize = 22f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 12)
            }

            val desc = TextView(context).apply {
                text = "Bu uygulama çalışma seansınız süresince SharkMentor kalkanı tarafından engellenmiştir."
                textSize = 14f
                setTextColor(Color.parseColor("#CBF3F0"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 36)
            }

            val button = Button(context).apply {
                text = "Masaya Geri Dön"
                setBackgroundColor(Color.parseColor("#2EC4B6"))
                setTextColor(Color.BLACK)
                setOnClickListener {
                    hideBlockOverlay()
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                }
            }

            addView(icon)
            addView(title)
            addView(desc)
            addView(button)
        }

        try {
            Log.d("SHARK_DEBUG", "[Overlay] Attempting to add overlay view via WindowManager...")
            overlayView = layout
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        hideBlockOverlay()
    }
}
