package com.example.sharkmentor.domain.engine

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.example.sharkmentor.domain.util.DopaminePermissionHelper
import com.example.sharkmentor.service.blocker.StrictAppBlockerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DopamineFastManager @Inject constructor(
    private val context: Context,
    private val preferences: SharedPreferences
) {
    fun startFast(targetHours: Int): Boolean {
        if (!DopaminePermissionHelper.hasUsageAccess(context) || !DopaminePermissionHelper.hasOverlayPermission(context)) {
            return false
        }
        val endTime = System.currentTimeMillis() + (targetHours * 3600 * 1000L)
        preferences.edit()
            .putBoolean("is_fast_active", true)
            .putLong("fast_end_time", endTime)
            .apply()

        // Start real background blocker
        val serviceIntent = Intent(context, StrictAppBlockerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        return true
    }

    fun forfeitFast(): Boolean {
        val isFastActive = preferences.getBoolean("is_fast_active", false)
        val endTime = preferences.getLong("fast_end_time", 0L)

        context.stopService(Intent(context, StrictAppBlockerService::class.java))
        preferences.edit().putBoolean("is_fast_active", false).apply()

        // Penalty applied if forfeited before time
        return if (isFastActive && System.currentTimeMillis() < endTime) {
            applyPenalty()
            true // Penalty applied
        } else {
            false
        }
    }

    private fun applyPenalty() {
        // 1. Reset current streak to 0
        preferences.edit().putInt("current_streak", 0).apply()
    }
}
