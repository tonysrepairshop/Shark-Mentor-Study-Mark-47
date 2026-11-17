package com.example.sharkmentor.core.scoring

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Calculates the daily focus score for SharkMentor.
 *
 * ## Focus-time guarantee
 * The [focusMinutes] parameter MUST only contain minutes spent in
 * [com.example.sharkmentor.core.timer.TimerPhase.FOCUSING].  Break time
 * ([TimerPhase.MANDATORY_BREAK]) is **never** included because:
 *  - [com.example.sharkmentor.core.service.FocusTimerService] saves a
 *    [com.example.sharkmentor.data.local.entity.FocusSessionEntity] only
 *    when a focus phase ends — break durations are never persisted.
 *  - The score calculation is therefore strictly a function of real focus
 *    work, task completion, and sensor lift discipline.
 *
 * ## Scoring breakdown (total 10.0 pts)
 * | Category            | Max pts | Formula                                    |
 * |---------------------|---------|-------------------------------------------|
 * | Focus Time          | 4.5     | min(focusMinutes / 300, 1) × 4.5           |
 * | Task Completion     | 3.0     | (completedTasks / totalTasks) × 3.0        |
 * | Phone Lift Penalty  | 1.5     | 1.5 − (totalLifts × 0.3), floor 0          |
 * | Screen Discipline   | 1.0     | (1 − screenOnMin/focusMin) × 1.0           |
 */
object DailyScoreCalculator {

    fun getTodayBounds(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endOfDay = calendar.timeInMillis

        return Pair(startOfDay, endOfDay)
    }

    /**
     * Calculates the daily focus score out of 10.0.
     *
     * @param focusMinutes         Total minutes in FOCUSING phase today (break time excluded).
     * @param completedTasks       Number of tasks completed today.
     * @param totalTasks           Total number of tasks due today.
     * @param totalLifts           Total phone lifts recorded across all focus sessions today.
     * @param totalScreenOnMinutes Total minutes the screen was ON during focus sessions today.
     * @return Score between 0.0 and 10.0, rounded to 1 decimal place.
     */
    fun calculate(
        focusMinutes        : Int,
        completedTasks      : Int,
        totalTasks          : Int,
        totalLifts          : Int,
        totalScreenOnMinutes: Int,
    ): Float {
        // Zero-session guard: if nothing was done today, score is 0.0 — the default
        // discipline points (phone lift: 1.5 + screen: 1.0) must NOT be awarded
        // on days where the user never started a session.
        if (focusMinutes == 0 && completedTasks == 0) return 0.0f

        // 1. Focus Time (max 4.5 pts) — target: 300 minutes
        val focusScore = minOf(focusMinutes / 300f, 1f) * 4.5f

        // 2. Task Completion (max 3.0 pts)
        val taskScore = if (totalTasks > 0) {
            (completedTasks.toFloat() / totalTasks) * 3.0f
        } else {
            3.0f // Full points if no tasks defined
        }

        // 3. Phone Lift Discipline (max 1.5 pts) — 0.3 deducted per lift
        val liftScore = (1.5f - (totalLifts * 0.3f)).coerceAtLeast(0.0f)

        // 4. Screen Discipline (max 1.0 pts)
        // Rewards keeping the screen off during focus sessions.
        // Penalises based on percentage of focus time the screen was on.
        val screenScore = if (focusMinutes > 0) {
            (1.0f - (totalScreenOnMinutes.toFloat() / focusMinutes).coerceIn(0f, 1f)) * 1.0f
        } else {
            1.0f
        }

        val rawScore = (focusScore + taskScore + liftScore + screenScore).coerceIn(0f, 10f)
        return (rawScore * 10).roundToInt() / 10f
    }
}
