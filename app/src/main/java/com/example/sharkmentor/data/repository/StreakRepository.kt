package com.example.sharkmentor.data.repository

import android.content.Context
import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.FocusSessionEntity
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class StreakState(
    val streakCount: Int = 0,
    val streakFreezeAvailable: Int = 0,
    val lastStreakDate: String = "",
    val wasFrozenToday: Boolean = false,
    val daysSinceLastStudy: Int = 0
)

class StreakRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _streakState = MutableStateFlow(readStateFromPrefs())
    val streakState: StateFlow<StreakState> = _streakState.asStateFlow()

    private fun readStateFromPrefs(): StreakState {
        val lastDateStr = prefs.getString(KEY_LAST_DATE, "") ?: ""
        val daysSince = computeDaysSince(lastDateStr, LocalDate.now())
        return StreakState(
            streakCount = prefs.getInt(KEY_STREAK_COUNT, 0),
            streakFreezeAvailable = prefs.getInt(KEY_FREEZE_AVAILABLE, 0),
            lastStreakDate = lastDateStr,
            daysSinceLastStudy = daysSince
        )
    }

    /**
     * Evaluates today's Water Streak ("Su Serisi") progress.
     * Criteria: at least 20 minutes of focus OR 20 logged questions today.
     * Returns true ONLY when today's water drop is newly secured.
     */
    fun evaluateStreak(completedMinutes: Int, questionsLogged: Int): Boolean {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val state = readStateFromPrefs()
        _streakState.value = state
        if (state.lastStreakDate == todayStr) {
            return false
        }

        if (completedMinutes >= MIN_DAILY_STREAK_MINUTES || questionsLogged >= MIN_DAILY_STREAK_QUESTIONS) {
            var newStreak = state.streakCount
            var newFreeze = state.streakFreezeAvailable
            var wasFrozen = false

            if (state.lastStreakDate.isNotBlank()) {
                val lastDate = runCatching {
                    LocalDate.parse(state.lastStreakDate, DateTimeFormatter.ISO_LOCAL_DATE)
                }.getOrNull()

                if (lastDate != null) {
                    val daysBetween = ChronoUnit.DAYS.between(lastDate, today)
                    when {
                        daysBetween == 1L -> newStreak = (newStreak + 1).coerceAtLeast(1)
                        daysBetween > 1L -> {
                            val missedDays = daysBetween - 1
                            if (newFreeze >= missedDays) {
                                newFreeze -= missedDays.toInt()
                                newStreak = (newStreak + 1).coerceAtLeast(1)
                                wasFrozen = true
                            } else {
                                newStreak = 1
                                newFreeze = 0
                            }
                        }
                        daysBetween <= 0L -> newStreak = newStreak.coerceAtLeast(1)
                    }
                } else {
                    newStreak = 1
                }
            } else {
                newStreak = 1
            }

            // Earn a streak freeze automatically every 7 continuous days (capped at 3)
            if (newStreak > 0 && newStreak % 7 == 0 && newFreeze < 3) {
                newFreeze += 1
            }

            val newState = StreakState(
                streakCount = newStreak,
                streakFreezeAvailable = newFreeze,
                lastStreakDate = todayStr,
                wasFrozenToday = wasFrozen,
                daysSinceLastStudy = 0
            )

            persistState(newState, syncCloud = true)
            return true
        }

        return false
    }

    /**
     * Reconciles Water Streak against Room [FocusSessionEntity] history so existing study
     * sessions always reflect accurately in widgets, league profile, and notifications
     * without making extra Firestore writes when nothing changed.
     */
    fun syncFromRoomSessions(
        allSessions: List<FocusSessionEntity>,
        liveTodayMinutes: Long = 0L
    ): StreakState {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val zone = ZoneId.systemDefault()

        val minutesByDate = HashMap<LocalDate, Long>()
        for (session in allSessions) {
            val date = Instant.ofEpochMilli(session.completedAt).atZone(zone).toLocalDate()
            minutesByDate[date] = (minutesByDate[date] ?: 0L) + (session.durationSeconds / 60L)
        }
        if (liveTodayMinutes > 0L) {
            minutesByDate[today] = maxOf(minutesByDate[today] ?: 0L, liveTodayMinutes)
        }

        val completedDates = minutesByDate
            .filterValues { it >= MIN_DAILY_STREAK_MINUTES }
            .keys
            .sortedDescending()

        val existing = readStateFromPrefs()
        val lastStudyDateFromSessions = completedDates.firstOrNull()

        // Compute continuous streak ending today or yesterday
        var computedStreak = 0
        if (completedDates.isNotEmpty()) {
            val completedSet = completedDates.toHashSet()
            var cursor = if (completedSet.contains(today)) today else today.minusDays(1)
            while (completedSet.contains(cursor)) {
                computedStreak += 1
                cursor = cursor.minusDays(1)
            }
        }

        val existingLastDate = runCatching {
            if (existing.lastStreakDate.isNotBlank()) {
                LocalDate.parse(existing.lastStreakDate, DateTimeFormatter.ISO_LOCAL_DATE)
            } else null
        }.getOrNull()

        val todayCompletedInSessions = (minutesByDate[today] ?: 0L) >= MIN_DAILY_STREAK_MINUTES
        val existingStillValid = existingLastDate != null &&
            ChronoUnit.DAYS.between(existingLastDate, today) <= (1L + existing.streakFreezeAvailable.toLong())

        val finalStreak = when {
            todayCompletedInSessions -> maxOf(
                computedStreak,
                if (existing.lastStreakDate == todayStr) existing.streakCount
                else if (existingStillValid) existing.streakCount + 1
                else 1
            )
            computedStreak > 0 -> maxOf(computedStreak, if (existingStillValid) existing.streakCount else 0)
            existingStillValid -> existing.streakCount
            else -> 0
        }

        val finalLastDate: LocalDate? = listOfNotNull(
            if (todayCompletedInSessions) today else null,
            lastStudyDateFromSessions,
            existingLastDate
        ).maxOrNull()

        val finalLastDateStr = finalLastDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: ""
        val daysSince = if (finalLastDate != null) {
            ChronoUnit.DAYS.between(finalLastDate, today).toInt().coerceAtLeast(0)
        } else {
            // Check if user ever had ANY study session (even < 20 min)
            val anyStudyDate = minutesByDate.keys.maxOrNull()
            if (anyStudyDate != null) {
                ChronoUnit.DAYS.between(anyStudyDate, today).toInt().coerceAtLeast(0)
            } else 0
        }

        val resolvedState = StreakState(
            streakCount = finalStreak,
            streakFreezeAvailable = existing.streakFreezeAvailable,
            lastStreakDate = finalLastDateStr,
            wasFrozenToday = existing.wasFrozenToday,
            daysSinceLastStudy = daysSince
        )

        val changed = resolvedState.streakCount != existing.streakCount ||
            resolvedState.lastStreakDate != existing.lastStreakDate
        if (changed) {
            persistState(resolvedState, syncCloud = (finalLastDateStr == todayStr && existing.lastStreakDate != todayStr))
        } else {
            _streakState.value = resolvedState
        }
        return resolvedState
    }

    /**
     * Convenience suspend helper called from FocusSessionRecorder when a checkpoint or
     * finalized focus session is persisted to Room.
     */
    suspend fun evaluateFromRoomAfterFocus(liveTodayMinutes: Long = 0L): Boolean {
        val before = readStateFromPrefs()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val allSessions = runCatching {
            SharkMentorDatabase.getDatabase(appContext).sessionDao().getAllSessionsRaw()
        }.getOrDefault(emptyList())
        val after = syncFromRoomSessions(allSessions, liveTodayMinutes)
        return before.lastStreakDate != todayStr && after.lastStreakDate == todayStr
    }

    private fun persistState(state: StreakState, syncCloud: Boolean) {
        prefs.edit()
            .putInt(KEY_STREAK_COUNT, state.streakCount)
            .putInt(KEY_FREEZE_AVAILABLE, state.streakFreezeAvailable)
            .putString(KEY_LAST_DATE, state.lastStreakDate)
            .apply()
        _streakState.value = state
        if (syncCloud) {
            syncToFirestore(state)
        }
    }

    private fun computeDaysSince(lastDateStr: String, today: LocalDate): Int {
        if (lastDateStr.isBlank()) return 0
        return runCatching {
            val parsed = LocalDate.parse(lastDateStr, DateTimeFormatter.ISO_LOCAL_DATE)
            ChronoUnit.DAYS.between(parsed, today).toInt().coerceAtLeast(0)
        }.getOrDefault(0)
    }

    private fun syncToFirestore(state: StreakState) {
        val user = GoogleServicesHelper.getCurrentUser(context = appContext)
        val uid = user?.uid ?: return
        val db = GoogleServicesHelper.getFirebaseFirestore(appContext) ?: return

        val data = hashMapOf(
            "streakCount" to state.streakCount,
            "streakFreezeAvailable" to state.streakFreezeAvailable,
            "lastStreakDate" to state.lastStreakDate
        )

        db.collection("users").document(uid).set(data, SetOptions.merge())
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to sync water streak to Firestore: ${e.message}")
            }
    }

    companion object {
        private const val TAG = "StreakRepository"
        private const val PREFS_NAME = "streak_prefs"
        private const val KEY_STREAK_COUNT = "streakCount"
        private const val KEY_FREEZE_AVAILABLE = "streakFreezeAvailable"
        private const val KEY_LAST_DATE = "lastStreakDate"
        const val MIN_DAILY_STREAK_MINUTES = 20
        const val MIN_DAILY_STREAK_QUESTIONS = 20
    }
}
