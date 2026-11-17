package com.example.sharkmentor.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import com.example.sharkmentor.core.service.ZenAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ZenSound(val id: String, val displayName: String, val rawResName: String, val emoji: String = "") {
    NONE("none", "Kapalı", "", ""),
    RAIN("rain", "Yağmur", "zen_rain", "🌧️"),
    FOREST("forest", "Orman", "zen_forest", "🌲"),
    OCEAN("ocean", "Okyanus", "zen_ocean", "🌊"),
    TRAIN("train", "Tren Yolculuğu", "zen_train", "🚂"),
    CAFE("cafe", "Kafe Uğultusu", "zen_cafe", "☕"),
    FIRE("fire", "Kamp Ateşi", "zen_fire", "🔥"),
    AIRPLANE("airplane", "Uçak Kabini", "zen_airplane", "✈️"),
    WATERFALL("waterfall", "Şelale", "zen_waterfall", "🌊"),
    CHICAGO_WALK("chicago_walk", "Chicago'da Yürüyüş", "zen_chicago_walk", "🚶"),
    LONDON_RAIN("london_rain", "Londra'da Yağmur", "zen_london_rain", "🌧️"),
    NORTHWEST_RAIN("northwest_rain", "Northwest'te Yağmur", "zen_northwest_rain", "🌧️");

    companion object {
        fun fromId(id: String?): ZenSound {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NONE
        }
    }
}

object ZenAudioManager {
    private const val TAG = "SHARK_ZEN_AUDIO"

    private val _currentSound = MutableStateFlow(ZenSound.NONE)
    val currentSound: StateFlow<ZenSound> = _currentSound.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var activePlayer: MediaPlayer? = null
    private var standbyPlayer: MediaPlayer? = null
    private var crossfadeJob: Job? = null
    private var currentResId: Int = 0

    fun toggleSound(context: Context, sound: ZenSound) {
        if (_currentSound.value == sound) {
            if (_isPlaying.value) {
                stopSound(context)
            } else {
                resumeSound(context)
            }
        } else {
            playSound(context, sound)
        }
    }

    private fun createPlayer(context: Context, resId: Int, initialVolume: Float = 1.0f): MediaPlayer? {
        return try {
            MediaPlayer.create(context, resId)?.apply {
                setVolume(initialVolume, initialVolume)
                isLooping = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ZenAudio] Player oluşturma hatası", e)
            null
        }
    }

    fun playSound(context: Context, sound: ZenSound) {
        if (sound == ZenSound.NONE) {
            stopSound(context)
            return
        }

        // Cleanly dispose existing player without stopping the foreground service
        try {
            activePlayer?.apply {
                if (isPlaying) pause()
                stop()
                reset()
                release()
            }
        } catch (_: Exception) {}
        activePlayer = null

        var resId = context.resources.getIdentifier(sound.rawResName, "raw", context.packageName)
        if (resId == 0 && sound == ZenSound.OCEAN) {
            resId = context.resources.getIdentifier("zen_ocen", "raw", context.packageName)
        }

        if (resId == 0) {
            Log.w(TAG, "[ZenAudio] Ses dosyası bulunamadı: res/raw/${sound.rawResName}. Statik gürültü çalınmayacak.")
            stopSound(context)
            return
        }

        currentResId = resId
        val player = createPlayer(context, resId, 1.0f)
        if (player != null) {
            activePlayer = player
            try {
                player.start()
                _currentSound.value = sound
                _isPlaying.value = true
                Log.d(TAG, "[ZenAudio] Başlatıldı: ${sound.displayName}")

                // Safely start or update foreground service with media notification
                try {
                    ZenAudioService.start(context, sound)
                } catch (se: Exception) {
                    Log.w(TAG, "[ZenAudio] ZenAudioService başlatılamadı: ${se.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[ZenAudio] Çalma hatası", e)
                stopSound(context)
            }
        } else {
            stopSound(context)
        }
    }

    fun pauseSound(context: Context? = null) {
        if (!_isPlaying.value) return
        crossfadeJob?.cancel()
        crossfadeJob = null
        try {
            if (activePlayer?.isPlaying == true) {
                activePlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ZenAudio] Pause error", e)
        }
        _isPlaying.value = false
        context?.let { ZenAudioService.notifyStateChanged(it) }
    }

    fun resumeSound(context: Context) {
        val player = activePlayer
        if (player != null) {
            try {
                player.start()
                _isPlaying.value = true
                ZenAudioService.notifyStateChanged(context)
            } catch (e: Exception) {
                Log.e(TAG, "[ZenAudio] Resume error", e)
                playSound(context, _currentSound.value)
            }
        } else if (_currentSound.value != ZenSound.NONE) {
            playSound(context, _currentSound.value)
        }
    }

    fun stopSound(context: Context? = null) {
        _currentSound.value = ZenSound.NONE
        _isPlaying.value = false
        crossfadeJob?.cancel()
        crossfadeJob = null

        try {
            activePlayer?.apply {
                if (isPlaying) pause()
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ZenAudio] Active player release error", e)
        }
        activePlayer = null

        try {
            standbyPlayer?.apply {
                if (isPlaying) pause()
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ZenAudio] Standby player release error", e)
        }
        standbyPlayer = null

        context?.let {
            val audioManager = it.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
            try {
                ZenAudioService.stop(it)
            } catch (_: Exception) {}
        }
        stopAudio()
    }

    private val audioLock = Any()
    private var audioTrack: AudioTrack? = null

    suspend fun playSound(audioData: ShortArray) = withContext(Dispatchers.Default) {
        synchronized(audioLock) {
            val track = audioTrack ?: return@withContext
            if (track.state != AudioTrack.STATE_INITIALIZED || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                return@withContext
            }

            try {
                var written = 0
                while (written < audioData.size && isActive) {
                    val result = track.write(audioData, written, audioData.size - written)
                    if (result <= 0) break
                    written += result
                }
            } catch (e: IllegalStateException) {
                Log.w("ZenAudioManager", "AudioTrack was released during write: ${e.message}")
            } catch (e: Exception) {
                Log.e("ZenAudioManager", "Audio playback error", e)
            }
        }
    }

    fun stopAudio() {
        synchronized(audioLock) {
            try {
                audioTrack?.apply {
                    if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                        stop()
                    }
                    release()
                }
            } catch (e: Exception) {
                Log.w("ZenAudioManager", "Error stopping track", e)
            } finally {
                audioTrack = null
            }
        }
    }
}
