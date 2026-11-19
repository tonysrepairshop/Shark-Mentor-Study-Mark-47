package com.example.sharkmentor

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.sharkmentor.core.ai.UserAiPreferences
import com.example.sharkmentor.core.blocker.BlockerPreferences
import com.example.sharkmentor.core.data.ThemePreferences
import com.example.sharkmentor.core.theme.ThemeManager
import com.example.sharkmentor.core.util.AppIconManager
import com.example.sharkmentor.core.util.AvatarHelper
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.data.local.preferences.UserPreferences
import com.example.sharkmentor.service.notification.NotificationChannels
import com.example.sharkmentor.service.sync.SyncWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SharkMentorApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO)
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()

        // ── Fast Light Initialization on Main Thread ─────────────────────
        ThemePreferences.initialize(this)
        UserAiPreferences.initialize(this)
        ThemeManager.init(this)
        SessionManager.init(this)
        AppIconManager.init(this)
        AvatarHelper.init(this)
        UserPreferences.getInstance(this)
        BlockerPreferences.initialize(this)
        NotificationChannels.registerAll(this)

        // Apply any deferred launcher icon switch cleanly when user leaves the app
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
                    AppIconManager.applyPendingIconIfNeeded(applicationContext)
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // ── Offload Heavy/Fragile Startup Tasks to Background Thread ────
        appScope.launch {
            runCatching {
                com.example.sharkmentor.core.util.PdfLibraryManager.init(applicationContext)
            }.onFailure { e ->
                Log.w("SharkMentorApp", "PdfLibraryManager init bypassed: ${e.message}")
            }

            // 2. Initialize Firebase & Clear Firestore persistence asynchronously (never on Main Thread!)
            try {
                FirebaseApp.initializeApp(applicationContext)
                if (GoogleServicesHelper.isGooglePlayServicesAvailable(applicationContext)) {
                    val firestore = GoogleServicesHelper.getFirebaseFirestore(applicationContext)
                    firestore?.clearPersistence()
                    Log.d("SharkMentorApp", "Firestore cache cleared in background.")
                } else {
                    Log.w("SharkMentorApp", "Google Play Services unavailable. Skipping Firestore persistence clearance.")
                }
            } catch (e: Throwable) {
                Log.w("SharkMentorApp", "Firebase / Firestore persistence clearance bypassed: ${e.message}")
            }

            // 3. Enqueue periodic cloud sync
            try {
                SyncWorker.enqueuePeriodicSync(applicationContext)
            } catch (e: Throwable) {
                Log.w("SharkMentorApp", "Periodic sync enqueue bypassed: ${e.message}")
            }
        }
    }

    companion object {

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE focus_sessions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        modeName TEXT NOT NULL,
                        durationSeconds INTEGER NOT NULL,
                        completedAt INTEGER NOT NULL,
                        afkScore REAL NOT NULL DEFAULT 10.0,
                        phoneLiftCount INTEGER NOT NULL DEFAULT 0,
                        screenOnMinutes INTEGER NOT NULL DEFAULT 0,
                        isSynced INTEGER NOT NULL DEFAULT 0,
                        lastModifiedMillis INTEGER NOT NULL DEFAULT 0,
                        cloudId TEXT,
                        institutionId TEXT,
                        assignedCoachId TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO focus_sessions_new (id, modeName, durationSeconds, completedAt, afkScore, phoneLiftCount, screenOnMinutes)
                    SELECT id, modeName, durationMinutes * 60, completedAt, CAST(afkScore AS REAL), phoneLiftCount, 0
                    FROM focus_sessions
                """.trimIndent())
                db.execSQL("DROP TABLE focus_sessions")
                db.execSQL("ALTER TABLE focus_sessions_new RENAME TO focus_sessions")
            }
        }
    }
}
