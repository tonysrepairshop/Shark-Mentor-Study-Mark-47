package com.example.sharkmentor.core.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Manages dynamic launcher icon switching via activity-alias components.
 *
 * Strategy:
 *  - The app has 4 launcher aliases declared in AndroidManifest.xml:
 *    DefaultSharkAlias (blue/teal, enabled by default), MidnightSharkAlias,
 *    GoldSharkAlias, CrimsonSharkAlias.
 *  - Exactly ONE alias is enabled at any time. Switching enables the chosen
 *    alias and disables all others using DONT_KILL_APP so the app stays alive.
 *  - The current selection is persisted in SharedPreferences.
 */
object AppIconManager {

    private const val PREFS_NAME = "app_icon_prefs"
    private const val KEY_CURRENT_ICON = "current_icon_variant"

    enum class IconVariant(
        val aliasName: String,
        val displayName: String,
        val drawableRes: Int,
        val bgHex: Long,
        val accentHex: Long
    ) {
        DEFAULT(
            aliasName = "com.example.sharkmentor.GreatWhiteAlias",
            displayName = "Büyük Beyaz",
            drawableRes = com.example.sharkmentor.R.drawable.ic_shark_great_white,
            bgHex = 0xFF0B1E33,
            accentHex = 0xFF2EC4B6
        ),
        HAMMERHEAD(
            aliasName = "com.example.sharkmentor.HammerheadAlias",
            displayName = "Çekiç Balığı",
            drawableRes = com.example.sharkmentor.R.drawable.ic_shark_hammerhead,
            bgHex = 0xFF1E3A5F,
            accentHex = 0xFF4FC3F7
        ),
        MEGALODON(
            aliasName = "com.example.sharkmentor.MegalodonAlias",
            displayName = "Megalodon",
            drawableRes = com.example.sharkmentor.R.drawable.ic_shark_megalodon,
            bgHex = 0xFF2A0000,
            accentHex = 0xFFFF4444
        ),
        SPINY(
            aliasName = "com.example.sharkmentor.SpinySharkAlias",
            displayName = "Camgöz",
            drawableRes = com.example.sharkmentor.R.drawable.ic_shark_spiny,
            bgHex = 0xFF3B2A00,
            accentHex = 0xFFFFD700
        )
    }

    /** Switch the launcher icon to the given [variant]. */
    fun switchIcon(context: Context, variant: IconVariant) {
        val pm = context.packageManager

        // Enable chosen alias, disable all others
        IconVariant.entries.forEach { v ->
            val component = ComponentName(context, v.aliasName)
            val newState = if (v == variant)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            try {
                pm.setComponentEnabledSetting(
                    component,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                // Alias may not exist in older build — silently skip
                android.util.Log.w("AppIconManager", "Could not toggle ${v.aliasName}: ${e.message}")
            }
        }

        // Persist the selection
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CURRENT_ICON, variant.name)
            .apply()
    }

    /** Returns the currently persisted [IconVariant]. Defaults to [IconVariant.DEFAULT]. */
    fun getCurrentVariant(context: Context): IconVariant {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_ICON, IconVariant.DEFAULT.name)
        return IconVariant.entries.find { it.name == name } ?: IconVariant.DEFAULT
    }
}
