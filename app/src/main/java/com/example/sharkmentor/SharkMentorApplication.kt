package com.example.sharkmentor

import android.app.Application
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.sharkmentor.core.blocker.BlockerPreferences
import com.example.sharkmentor.core.data.ThemePreferences
import com.example.sharkmentor.core.theme.ThemeManager
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.service.notification.NotificationChannels
import com.example.sharkmentor.service.sync.SyncWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SharkMentorApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // ── Fast Light Initialization on Main Thread ─────────────────────
        ThemePreferences.initialize(this)
        ThemeManager.init(this)
        SessionManager.init(this)
        BlockerPreferences.initialize(this)
        NotificationChannels.registerAll(this)

        // ── Offload Heavy/Fragile Startup Tasks to Background Thread ────
        appScope.launch {
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
