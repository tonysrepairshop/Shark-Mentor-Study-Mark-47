package com.example.sharkmentor.core.theme

import android.content.Context
import com.example.sharkmentor.core.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeManager {
    private const val PREFS_NAME = "shark_theme_prefs"
    private const val KEY_ZEN_MODE = "is_zen_mode_enabled"

    private val _isZenMode = MutableStateFlow(false)
    val isZenMode: StateFlow<Boolean> = _isZenMode.asStateFlow()

    // Backwards-compatibility alias for isZenMinimalist
    val isZenMinimalist: StateFlow<Boolean> = isZenMode

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ZEN_MODE, false)
        _isZenMode.value = enabled
        runCatching { ThemePreferences.instance.setZenMode(enabled) }
    }

    fun toggleZenMode(context: Context) {
        setZenMode(context, !_isZenMode.value)
    }

    fun setZenMode(context: Context, enabled: Boolean) {
        _isZenMode.value = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ZEN_MODE, enabled).apply()
        runCatching { ThemePreferences.instance.setZenMode(enabled) }
    }

    fun toggleZenMinimalist() {
        _isZenMode.value = !_isZenMode.value
        runCatching { ThemePreferences.instance.setZenMode(_isZenMode.value) }
    }

    fun setZenMinimalist(enabled: Boolean) {
        _isZenMode.value = enabled
        runCatching { ThemePreferences.instance.setZenMode(enabled) }
    }
}
