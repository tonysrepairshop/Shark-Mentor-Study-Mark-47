package com.example.sharkmentor.core.timer

import androidx.compose.runtime.Immutable

/**
 * Explicit lifecycle phase of the timer engine.
 *
 * Used by [FocusTimerService] to drive state transitions and by the UI to
 * enforce the mandatory-break protocol (lock the "Başlat" button).
 *
 * - [IDLE]             : No session is active. Initial / post-stop state.
 * - [FOCUSING]         : A focus session is actively ticking.
 * - [PAUSED]           : The focus session has been manually paused.
 * - [MANDATORY_BREAK]  : A focus session finished; the mandatory break countdown
 *                        is running. The "Başlat" button is disabled for all
 *                        non-BREAK modes until the break completes.
 */
enum class TimerPhase {
    IDLE,
    FOCUSING,
    PAUSED,
    MANDATORY_BREAK,
}

/**
 * Immutable snapshot of the entire timer engine.
 *
 * Produced by [com.example.sharkmentor.core.service.FocusTimerService] every second
 * and consumed by [com.example.sharkmentor.ui.viewmodel.TimerViewModel], which merges it
 * with the AFK sensor reading before exposing it to the UI.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Core timing fields (timestamp-delta model)
 * ──────────────────────────────────────────────────────────────────────────────
 * The timer now uses wall-clock deltas via [android.os.SystemClock.elapsedRealtime]
 * instead of an accumulated tick counter.  This means the timer NEVER loses sync
 * when the screen is off or the device enters Android Doze mode.
 *
 * @property timeElapsedMillis        Milliseconds elapsed since the current segment started,
 *                                    computed as: `elapsedRealtime() − startTimestampElapsed + pausedAccumulatedMillis`.
 * @property totalDurationMillis      Target duration for countdown modes (0 for STOPWATCH).
 * @property timerPhase               Explicit lifecycle state — drives UI enforcement.
 * @property startTimestampElapsed    [android.os.SystemClock.elapsedRealtime] captured when the
 *                                    current ticking segment started (start or resume).
 *                                    0L when the timer is IDLE or PAUSED.
 * @property pausedAccumulatedMillis  Millis accumulated across all previous ticking segments
 *                                    (i.e. before the current resume point).
 * @property currentMode              Active [TimerMode].
 * @property isAfkStationary          Device is stationary — injected by ViewModel from [AfkSensorManager].
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Legacy boolean helpers (kept for backward-compat with UI and service logic)
 * ──────────────────────────────────────────────────────────────────────────────
 * @property isRunning  True when a session is active (FOCUSING or MANDATORY_BREAK).
 * @property isPaused   True when the session is manually paused.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Customisation / persistence fields
 * ──────────────────────────────────────────────────────────────────────────────
 * @property customPomodoroDurationMinutes  User-selected POMODORO session length (30..300, default 50).
 * @property breakDurationMinutes           User-selected BREAK length (5..20, default 10).
 * @property completedConsecutiveSessions   Number of BLOCK_90 or POMODORO sessions completed in a row
 *                                          without a break.  Resets to 0 when a BREAK starts.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Presence-check fields (STOPWATCH mode)
 * ──────────────────────────────────────────────────────────────────────────────
 * @property isPresenceCheckActive        True while the 10-minute confirmation window is open.
 * @property presenceCheckRemainingMillis Countdown for the grace period (600_000 → 0 ms).
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Mandatory-break flag
 * ──────────────────────────────────────────────────────────────────────────────
 * @property isMandatoryBreakRequired  Set when 2 consecutive sessions trigger an auto-BREAK.
 *                                     Also true whenever [timerPhase] == [TimerPhase.MANDATORY_BREAK].
 */

@Immutable
data class TimerState(
    // ── Core timing (timestamp-delta model) ──────────────────────────────────
    val timeElapsedMillis       : Long        = 0L,
    val totalDurationMillis     : Long        = 0L,
    val timerPhase              : TimerPhase  = TimerPhase.IDLE,
    val startTimestampElapsed   : Long        = 0L,
    val pausedAccumulatedMillis : Long        = 0L,
    val currentMode             : TimerMode   = TimerMode.STOPWATCH,
    val isAfkStationary         : Boolean     = false,

    // ── Customisation / persistence ───────────────────────────────────────────
    val customPomodoroDurationMinutes: Int = 50,
    val breakDurationMinutes         : Int = 10,
    val completedConsecutiveSessions : Int = 0,

    // ── Presence check (STOPWATCH only) ───────────────────────────────────────
    val isPresenceCheckActive        : Boolean = false,
    val presenceCheckRemainingMillis : Long    = 0L,

    // ── Mandatory break flag ──────────────────────────────────────────────────
    val isMandatoryBreakRequired     : Boolean = false,

    // ── AFK stats & Active Subject ────────────────────────────────────────────
    val phoneLiftCount               : Int     = 0,
    val activeSubject                : String  = "Genel",
) {

    // ── Legacy boolean helpers (derived from timerPhase) ─────────────────────

    /** True when a session is active — either FOCUSING or in MANDATORY_BREAK. */
    val isRunning: Boolean
        get() = timerPhase == TimerPhase.FOCUSING || timerPhase == TimerPhase.MANDATORY_BREAK

    /** True when the session is manually paused. */
    val isPaused: Boolean
        get() = timerPhase == TimerPhase.PAUSED

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Remaining millis for countdown modes.
     * Returns 0 when the timer has finished; never negative.
     */
    val timeRemainingMillis: Long
        get() = maxOf(0L, totalDurationMillis - timeElapsedMillis)

    /**
     * The value the user sees:
     * - Countdown modes ([isCountdown] = true) → time remaining
     * - STOPWATCH → time elapsed
     */
    val displayMillis: Long
        get() = if (currentMode.isCountdown) timeRemainingMillis else timeElapsedMillis

    /**
     * Progress fraction [0.0, 1.0] for the circular ring.
     *
     * - STOPWATCH : wraps once per hour — the ring acts as an analogue minute hand.
     * - All countdown modes : elapsed / total (fills as the session progresses).
     */
    val progress: Float
        get() = when {
            currentMode == TimerMode.STOPWATCH -> {
                val hourMs = 60L * 60L * 1_000L
                (timeElapsedMillis % hourMs).toFloat() / hourMs.toFloat()
            }
            totalDurationMillis <= 0L -> 0f
            else -> (timeElapsedMillis.toFloat() / totalDurationMillis).coerceIn(0f, 1f)
        }

    /** Timer is actively ticking (FOCUSING or MANDATORY_BREAK — not PAUSED or IDLE). */
    val isTicking: Boolean
        get() = timerPhase == TimerPhase.FOCUSING || timerPhase == TimerPhase.MANDATORY_BREAK
}
