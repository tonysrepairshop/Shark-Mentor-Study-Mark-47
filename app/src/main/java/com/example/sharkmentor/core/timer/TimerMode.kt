package com.example.sharkmentor.core.timer

/**
 * The four focus-timer modes available in SharkMentor.
 *
 * @property displayName     Turkish label shown in the UI and notification.
 * @property initialMillis   Default starting duration in ms.
 *                           POMODORO and BREAK use [TimerState.customPomodoroDurationMinutes]
 *                           and [TimerState.breakDurationMinutes] instead of this value at
 *                           runtime — [initialMillis] is kept as the compile-time fallback.
 * @property isCountdown     true → counts down to zero; false → counts up.
 * @property trackAfk        Whether the accelerometer AFK guard is active for this mode.
 *                           All focus modes now track AFK so [phoneLiftCount] is recorded
 *                           consistently across History and Analytics screens.
 */
enum class TimerMode(
    val displayName  : String,
    val initialMillis: Long,
    val isCountdown  : Boolean,
    val trackAfk     : Boolean,
) {

    /**
     * Freely counts up — no duration limit.
     * Every 90 minutes a vibration + notification prompts the user to confirm presence.
     * If the user does not respond within 10 minutes, the stopwatch is auto-paused.
     * AFK tracking is enabled so phone lifts are recorded in history.
     */
    STOPWATCH(
        displayName   = "Kronometre",
        initialMillis = 0L,
        isCountdown   = false,
        trackAfk      = true,   // ← enabled: lift count recorded for all modes
    ),

    /**
     * 90-minute deep-focus block with hardware accelerometer AFK guard.
     * After 2 consecutive completed sessions a mandatory [BREAK] is enforced.
     */
    BLOCK_90(
        displayName   = "90 Dk Derin Odak",
        initialMillis = 90L * 60L * 1_000L,
        isCountdown   = true,
        trackAfk      = true,
    ),

    /**
     * Fully customisable focus countdown (30–300 minutes, default 50).
     * Duration is driven by [TimerState.customPomodoroDurationMinutes] at runtime.
     * After 2 consecutive completed sessions a mandatory [BREAK] is enforced.
     * AFK tracking is enabled so phone lifts are recorded in history.
     */
    POMODORO(
        displayName   = "Pomodoro",
        initialMillis = 50L * 60L * 1_000L,   // fallback; real value from TimerState
        isCountdown   = true,
        trackAfk      = true,   // ← enabled: universal lift-count tracking
    ),

    /**
     * Mandatory restorative break (5–20 minutes, default 10).
     * Auto-triggered after 2 consecutive [BLOCK_90] or [POMODORO] completions.
     * Duration driven by [TimerState.breakDurationMinutes] at runtime.
     */
    BREAK(
        displayName   = "Zorunlu Mola",
        initialMillis = 10L * 60L * 1_000L,   // fallback; real value from TimerState
        isCountdown   = true,
        trackAfk      = false,
    ),
}
