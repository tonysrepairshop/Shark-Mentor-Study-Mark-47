package com.example.sharkmentor.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Lightweight accelerometer wrapper that classifies the device as stationary
 * or in motion using a sliding-window variance test.
 *
 * ## Algorithm
 * 1. Collect [WINDOW_SIZE] consecutive magnitude samples from TYPE_ACCELEROMETER.
 * 2. Compute the sample variance of the window.
 * 3. Emit `true` (stationary) when variance < [VARIANCE_THRESHOLD]; `false` otherwise.
 *
 * This approach is robust against brief micro-vibrations (vibration motor, table
 * knocks) because the variance only drops below the threshold when the device has
 * been completely still for the entire window (~[WINDOW_SIZE] × SENSOR_DELAY_NORMAL ≈ 4 s).
 *
 * @param context Any context; the application context is captured internally.
 */
class AfkSensorManager(context: Context) {

    private val sensorManager: SensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // ── Public state ─────────────────────────────────────────────────────────

    private val _isStationary = MutableStateFlow(false)

    /**
     * `true`  → device has been stationary for the last [WINDOW_SIZE] samples.
     * `false` → device is moving or sensor data is insufficient.
     */
    val isStationary: StateFlow<Boolean> = _isStationary.asStateFlow()

    private val _phoneLiftCount = MutableStateFlow(0)
    val phoneLiftCount: StateFlow<Int> = _phoneLiftCount.asStateFlow()

    // ── Internal state ───────────────────────────────────────────────────────

    private val samples     = ArrayDeque<Float>(WINDOW_SIZE + 1)
    private var isRegistered = false

    // ── Sensor callback ──────────────────────────────────────────────────────

    private val listener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Euclidean magnitude of the acceleration vector
            val magnitude = sqrt(x * x + y * y + z * z)

            samples.addLast(magnitude)
            if (samples.size > WINDOW_SIZE) samples.removeFirst()

            if (samples.size == WINDOW_SIZE) {
                val mean     = samples.average().toFloat()
                val variance = samples
                    .map { sample -> val d = sample - mean; d * d }
                    .average()
                    .toFloat()
                
                val isCurrentlyStationary = variance < VARIANCE_THRESHOLD
                if (_isStationary.value && !isCurrentlyStationary) {
                    _phoneLiftCount.value += 1
                }
                _isStationary.value = isCurrentlyStationary
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Start listening to the accelerometer.
     * Safe to call multiple times — duplicate registrations are suppressed.
     */
    fun start() {
        if (isRegistered || accelerometer == null) return
        sensorManager.registerListener(
            listener,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL,   // ~5 Hz — sufficient for AFK detection
        )
        isRegistered = true
    }

    /**
     * Stop listening, clear the sample window, and reset [isStationary] to `false`.
     * Safe to call when already stopped.
     */
    fun stop() {
        if (!isRegistered) return
        sensorManager.unregisterListener(listener)
        samples.clear()
        _isStationary.value = false
        isRegistered = false
    }

    /**
     * Reset the phone lift counter for a new session.
     */
    fun resetLiftCount() {
        _phoneLiftCount.value = 0
    }

    // ── Constants ────────────────────────────────────────────────────────────

    private companion object {
        /** Number of samples in the sliding window (~4 s at SENSOR_DELAY_NORMAL). */
        const val WINDOW_SIZE        = 20

        /**
         * Variance threshold below which the device is classified as stationary.
         * Units: (m/s²)².
         *
         * Empirical reference:
         *  - Device flat on desk:      variance ≈ 0.001 – 0.01
         *  - Device held in hand:      variance ≈ 0.05  – 0.30
         *  - Device being moved/shaken: variance > 0.5
         */
        const val VARIANCE_THRESHOLD = 0.08f
    }
}
