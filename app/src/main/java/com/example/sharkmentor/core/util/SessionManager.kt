package com.example.sharkmentor.core.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {
    private const val TAG = "SHARK_DEBUG"
    private const val PREFS_NAME = "shark_session_prefs"
    private const val KEY_IS_LINKED = "is_linked_to_institution"

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning.asStateFlow()

    private val _isBreakActive = MutableStateFlow(false)
    val isBreakActive: StateFlow<Boolean> = _isBreakActive.asStateFlow()

    private val _blockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _isLinkedToInstitution = MutableStateFlow(false)
    val isLinkedToInstitution: StateFlow<Boolean> = _isLinkedToInstitution.asStateFlow()

    private val _userMode = MutableStateFlow("study")
    val userMode: StateFlow<String> = _userMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isLinkedToInstitution.value = prefs.getBoolean(KEY_IS_LINKED, false)
        _userMode.value = prefs.getString("userMode", "study") ?: "study"
    }

    fun setUserMode(context: Context, mode: String) {
        _userMode.value = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("userMode", mode).apply()
    }

    fun setLinkedToInstitution(context: Context, linked: Boolean) {
        Log.d(TAG, "[SessionManager] setLinkedToInstitution -> $linked")
        _isLinkedToInstitution.value = linked
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_LINKED, linked).apply()
    }

    fun setTimerRunning(running: Boolean) {
        Log.d(TAG, "[SessionManager] setTimerRunning called -> $running")
        _isTimerRunning.value = running
        if (running) {
            _isBreakActive.value = false
        }
    }

    fun setBreakActive(active: Boolean) {
        _isBreakActive.value = active
        Log.d(TAG, "[SessionManager] setBreakActive -> $active")
        if (active) {
            _isTimerRunning.value = false
        }
    }

    fun updateBlockedPackages(packages: Set<String>) {
        Log.d(TAG, "[SessionManager] updateBlockedPackages called -> Count: ${packages.size}, Packages:$packages")
        _blockedPackages.value = packages
    }
}
