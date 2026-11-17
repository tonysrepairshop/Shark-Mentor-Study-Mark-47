package com.example.sharkmentor.core.blocker

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the set of app package names blacklisted by the user for focus blocking.
 *
 * Uses [SharedPreferences] so no new Gradle dependencies are required.
 * The actual blocking mechanism (Accessibility/UsageStats service loop) is a Phase 7
 * concern — this class only handles storage and change notification.
 *
 * Initialise in [com.example.sharkmentor.SharkMentorApplication.onCreate]:
 * ```kotlin
 * BlockerPreferences.initialize(this)
 * ```
 */
class BlockerPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _blacklist = MutableStateFlow<Set<String>>(
        prefs.getStringSet(KEY_BLACKLIST, emptySet())?.toSet() ?: emptySet()
    )
    /** Current set of blacklisted package names. */
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    /** Replaces the entire blacklist in a single atomic write. */
    fun setBlacklist(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLACKLIST, packages).apply()
        _blacklist.value = packages.toSet()           // defensive copy
    }

    /** Adds a single package to the blacklist. */
    fun addPackage(packageName: String) = setBlacklist(_blacklist.value + packageName)

    /** Removes a single package from the blacklist. */
    fun removePackage(packageName: String) = setBlacklist(_blacklist.value - packageName)

    companion object {
        private const val PREFS_NAME   = "sharkmentor_blocker_prefs"
        private const val KEY_BLACKLIST = "blacklisted_packages"

        @Volatile private var INSTANCE: BlockerPreferences? = null

        fun initialize(context: Context): BlockerPreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlockerPreferences(context.applicationContext).also { INSTANCE = it }
            }

        val instance: BlockerPreferences
            get() = INSTANCE
                ?: error("BlockerPreferences.initialize(context) must be called in Application.onCreate()")
    }
}
