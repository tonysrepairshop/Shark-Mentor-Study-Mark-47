package com.example.sharkmentor.core.blocker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Lightweight data class representing an installed user application.
 *
 * @property label       Human-readable application name (e.g. "Instagram").
 * @property packageName Android package identifier (e.g. "com.instagram.android").
 * @property appInfo     The original ApplicationInfo to load the icon.
 */
data class AppInfo(
    val label      : String,
    val packageName: String,
    val appInfo    : ApplicationInfo
)

/**
 * Helper object for [android.app.usage.UsageStatsManager]-related operations.
 *
 * ## Why PACKAGE_USAGE_STATS?
 * The blocking foundation needs to detect when the user switches to a blacklisted
 * app in order to interrupt it.  [AppOpsManager.OPSTR_GET_USAGE_STATS] is a
 * "privileged" op that must be granted manually by the user through System Settings.
 *
 * ## Blocking service (Phase 7)
 * The actual blocking loop (polled via [android.app.usage.UsageStatsManager] or
 * an [android.accessibilityservice.AccessibilityService]) is intentionally deferred
 * to a future sprint.  This object only covers prerequisite permission handling and
 * the installed-apps inventory needed for [AppSelectionDialog].
 */
object UsageStatsHelper {

    /**
     * Returns `true` if the user has granted [AppOpsManager.OPSTR_GET_USAGE_STATS]
     * to this app.  No runtime permission dialog — it requires a manual grant via
     * Settings > Apps > Special app access > Usage access.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode   = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Navigates the user to the system "Usage access" settings page so they can
     * manually grant [AppOpsManager.OPSTR_GET_USAGE_STATS].
     */
    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Returns `true` if the user has granted [Settings.canDrawOverlays]
     * to this app.
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Navigates the user to the system "Overlay" settings page.
     */
    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Returns a sorted list of **user-installed** apps (system apps excluded).
     * Each entry contains the human-readable label and the package name.
     */
    fun getInstalledUserApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        val criticalBlacklist = setOf(
            context.packageName, // SharkMentor itself
            "com.android.settings",
            "com.android.systemui",
            "com.android.phone",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        return installedApps.mapNotNull { appInfo ->
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            val isLaunchable = launchIntent != null
            val isNotCritical = !criticalBlacklist.contains(appInfo.packageName)

            if (isLaunchable && isNotCritical) {
                AppInfo(
                    label = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    appInfo = appInfo
                )
            } else null
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Returns the package name of the app currently in the foreground, or null if it cannot be determined.
     */
    fun getForegroundPackage(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60 // Look back 1 minute max
        
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var lastForegroundPackage: String? = null
        
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundPackage = event.packageName
            }
        }
        return lastForegroundPackage
    }
}
