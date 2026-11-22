package com.example.sharkmentor.ui.screens.notes

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks real foreground editing/viewing time spent per note (in seconds),
 * persisted durably in SharedPreferences so Note Statistics show true elapsed time.
 */
object NoteTimeTracker {
    private const val PREFS_NAME = "shark_note_active_time_prefs"
    private const val KEY_PREFIX = "note_seconds_"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTotalSeconds(context: Context, noteId: String): Long {
        if (noteId.isBlank()) return 0L
        return runCatching {
            prefs(context).getLong(KEY_PREFIX + noteId, 0L).coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    fun addSeconds(context: Context, noteId: String, deltaSeconds: Long): Long {
        if (noteId.isBlank() || deltaSeconds <= 0L) return getTotalSeconds(context, noteId)
        val p = prefs(context)
        val key = KEY_PREFIX + noteId
        val next = (p.getLong(key, 0L).coerceAtLeast(0L)) + deltaSeconds
        p.edit().putLong(key, next).apply()
        return next
    }

    fun copyDuration(context: Context, sourceNoteId: String, targetNoteId: String) {
        if (sourceNoteId.isBlank() || targetNoteId.isBlank()) return
        val secs = getTotalSeconds(context, sourceNoteId)
        if (secs > 0L) {
            prefs(context).edit().putLong(KEY_PREFIX + targetNoteId, secs).apply()
        }
    }

    fun clearNote(context: Context, noteId: String) {
        if (noteId.isBlank()) return
        prefs(context).edit().remove(KEY_PREFIX + noteId).apply()
    }

    fun formatDuration(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val seconds = safe % 60L
        return when {
            hours > 0L && minutes > 0L -> "$hours sa $minutes dk"
            hours > 0L -> "$hours sa"
            minutes > 0L && seconds > 0L -> "$minutes dk $seconds sn"
            minutes > 0L -> "$minutes dk"
            else -> "$seconds sn"
        }
    }
}
