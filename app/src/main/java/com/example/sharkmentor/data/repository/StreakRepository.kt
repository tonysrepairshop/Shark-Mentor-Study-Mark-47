package com.example.sharkmentor.data.repository

import android.content.Context
import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class StreakState(
    val streakCount: Int = 0,
    val streakFreezeAvailable: Int = 0,
    val lastStreakDate: String = "",
    val wasFrozenToday: Boolean = false
)

class StreakRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("streak_prefs", Context.MODE_PRIVATE)
    
    private val _streakState = MutableStateFlow(
        StreakState(
            streakCount = prefs.getInt("streakCount", 0),
            streakFreezeAvailable = prefs.getInt("streakFreezeAvailable", 0),
            lastStreakDate = prefs.getString("lastStreakDate", "") ?: ""
        )
    )
    val streakState: StateFlow<StreakState> = _streakState.asStateFlow()

    fun evaluateStreak(completedMinutes: Int, questionsLogged: Int): Boolean {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        val state = _streakState.value
        if (state.lastStreakDate == todayStr) {
            // Already preserved today
            return false
        }

        // Check if criteria met (>= 20 mins or >= 20 questions)
        if (completedMinutes >= 20 || questionsLogged >= 20) {
            var newStreak = state.streakCount
            var newFreeze = state.streakFreezeAvailable
            var wasFrozen = false

            if (state.lastStreakDate.isNotBlank()) {
                val lastDate = LocalDate.parse(state.lastStreakDate, DateTimeFormatter.ISO_LOCAL_DATE)
                val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(lastDate, today)

                if (daysBetween == 1L) {
                    newStreak += 1
                } else if (daysBetween > 1L) {
                    // Check if we can freeze
                    val missedDays = daysBetween - 1
                    if (newFreeze >= missedDays) {
                        newFreeze -= missedDays.toInt()
                        newStreak += 1
                        wasFrozen = true
                    } else {
                        // Reset
                        newStreak = 1
                        newFreeze = 0
                    }
                } else {
                    newStreak += 1
                }
            } else {
                newStreak = 1
            }

            val newState = StreakState(
                streakCount = newStreak,
                streakFreezeAvailable = newFreeze,
                lastStreakDate = todayStr,
                wasFrozenToday = wasFrozen
            )

            // Cache locally
            prefs.edit()
                .putInt("streakCount", newStreak)
                .putInt("streakFreezeAvailable", newFreeze)
                .putString("lastStreakDate", todayStr)
                .apply()

            _streakState.value = newState

            // Sync to firestore
            syncToFirestore(newState)
            return true
        }

        return false
    }

    private fun syncToFirestore(state: StreakState) {
        val user = GoogleServicesHelper.getCurrentUser(context = context)
        val uid = user?.uid ?: return

        val db = GoogleServicesHelper.getFirebaseFirestore(context) ?: return
        
        val data = hashMapOf(
            "streakCount" to state.streakCount,
            "streakFreezeAvailable" to state.streakFreezeAvailable,
            "lastStreakDate" to state.lastStreakDate
        )

        db.collection("users").document(uid).set(data, SetOptions.merge())
            .addOnFailureListener { e ->
                Log.e("StreakRepository", "Failed to sync streak to Firestore", e)
            }
    }
}
