package com.example.sharkmentor.data.focus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions

class FocusSessionManager(private val context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("sharkmentor_focus_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_FOCUS_ACTIVE = "is_focus_active"
        private const val KEY_SESSION_END_TIMESTAMP = "session_end_timestamp"
        private const val KEY_SELECTED_SUBJECT = "session_subject"
    }

    fun startSession(durationMinutes: Int, subject: String = "Genel") {
        val endTimestamp = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        prefs.edit()
            .putBoolean(KEY_IS_FOCUS_ACTIVE, true)
            .putLong(KEY_SESSION_END_TIMESTAMP, endTimestamp)
            .putString(KEY_SELECTED_SUBJECT, subject)
            .apply()

        // Live Presence Sync
        try {
            val uid = GoogleServicesHelper.getCurrentUser(context = context)?.uid
            if (uid != null) {
                val db = GoogleServicesHelper.getFirebaseFirestore(context)
                if (db != null) {
                    val updates = hashMapOf<String, Any>(
                        "isStudying" to true,
                        "activeSubject" to subject,
                        "sessionStartTime" to FieldValue.serverTimestamp()
                    )
                    db.collection("students").document(uid)
                        .update(updates)
                        .addOnFailureListener {
                            try {
                                db.collection("students").document(uid)
                                    .set(updates, SetOptions.merge())
                            } catch (e: Throwable) {
                                Log.w("FocusSessionManager", "Presence sync fallback failed: ${e.message}")
                            }
                        }
                }
            }
        } catch (e: Throwable) {
            Log.w("FocusSessionManager", "Failed to start presence sync: ${e.message}")
        }
    }

    fun endSession() {
        prefs.edit()
            .putBoolean(KEY_IS_FOCUS_ACTIVE, false)
            .putLong(KEY_SESSION_END_TIMESTAMP, 0L)
            .apply()

        // Live Presence Sync Stop
        try {
            val uid = GoogleServicesHelper.getCurrentUser(context = context)?.uid
            if (uid != null) {
                val db = GoogleServicesHelper.getFirebaseFirestore(context)
                if (db != null) {
                    val updates = hashMapOf<String, Any>(
                        "isStudying" to false,
                        "activeSubject" to FieldValue.delete(),
                        "sessionStartTime" to FieldValue.delete()
                    )
                    db.collection("students").document(uid)
                        .update(updates)
                        .addOnFailureListener { e ->
                            Log.e("PresenceSync", "Failed to clear studying presence", e)
                        }
                }
            }
        } catch (e: Throwable) {
            Log.w("FocusSessionManager", "Failed to stop presence sync: ${e.message}")
        }
    }

    fun isSessionActivelyRunning(): Boolean {
        val isActive = prefs.getBoolean(KEY_IS_FOCUS_ACTIVE, false)
        val endTimestamp = prefs.getLong(KEY_SESSION_END_TIMESTAMP, 0L)
        
        // Auto-expire if past the end timestamp
        return if (isActive && System.currentTimeMillis() < endTimestamp) {
            true
        } else {
            if (isActive) endSession() // Clean up expired state
            false
        }
    }
}
