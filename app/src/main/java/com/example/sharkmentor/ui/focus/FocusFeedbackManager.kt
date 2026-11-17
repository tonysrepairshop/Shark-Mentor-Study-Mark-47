package com.example.sharkmentor.ui.focus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class FocusFeedbackManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun onSessionCompleted() {
        triggerHaptics()
        triggerSafeSound()
    }

    private fun triggerHaptics() {
        if (!vibrator.hasVibrator()) return

        // Smooth double-pulse vibration pattern
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 150, 100, 250)
            val amplitudes = intArrayOf(0, 180, 0, 220)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 150, 100, 250), -1)
        }
    }

    private fun triggerSafeSound() {
        // STRICT CHECK: Never play audio if device is in SILENT or VIBRATE mode (Library safety)
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            return
        }

        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notificationUri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
        } catch (_: Exception) {
            // Failsafe silent fallback
        }
    }
}
