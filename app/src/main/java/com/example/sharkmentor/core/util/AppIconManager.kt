package com.example.sharkmentor.core.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.example.sharkmentor.R

enum class SharkAppIcon(
    val key: String,
    val title: String,
    val aliasClass: String, // MUST NOT BE NULL
    @DrawableRes val iconRes: Int,
    @DrawableRes val previewDrawableRes: Int,
    @DrawableRes val notificationSmallIconRes: Int
) {
    ORIGINAL(
        key = "original",
        title = "Orijinal Shark",
        aliasClass = ".OriginalAlias",
        iconRes = R.mipmap.ic_launcher_original,
        previewDrawableRes = R.drawable.ic_shark_original,
        notificationSmallIconRes = R.drawable.ic_notification_original
    ),
    HAMMERHEAD(
        key = "hammerhead",
        title = "Çekiç Başlı",
        aliasClass = ".HammerheadAlias",
        iconRes = R.mipmap.ic_launcher_hammerhead,
        previewDrawableRes = R.drawable.ic_shark_hammerhead,
        notificationSmallIconRes = R.drawable.ic_notification_hammerhead
    ),
    SPINY(
        key = "spiny",
        title = "Camgöz",
        aliasClass = ".SpinyAlias",
        iconRes = R.mipmap.ic_launcher_spiny,
        previewDrawableRes = R.drawable.ic_shark_spiny,
        notificationSmallIconRes = R.drawable.ic_notification_spiny
    ),
    GREAT_WHITE(
        key = "great_white",
        title = "Büyük Beyaz",
        aliasClass = ".GreatWhiteAlias",
        iconRes = R.mipmap.ic_launcher_great_white,
        previewDrawableRes = R.drawable.ic_shark_great_white,
        notificationSmallIconRes = R.drawable.ic_notification_great_white
    ),
    MEGALODON(
        key = "megalodon",
        title = "Megalodon",
        aliasClass = ".MegalodonAlias",
        iconRes = R.mipmap.ic_launcher_megalodon,
        previewDrawableRes = R.drawable.ic_shark_megalodon,
        notificationSmallIconRes = R.drawable.ic_notification_megalodon
    );

    companion object {
        fun fromKey(key: String?): SharkAppIcon =
            entries.firstOrNull { it.key == key } ?: ORIGINAL
    }
}

object AppIconManager {
    private const val PREFS_NAME = "app_icon_preferences"
    private const val KEY_ACTIVE_ICON = "active_shark_icon"

    fun getActiveIcon(context: Context): SharkAppIcon {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SharkAppIcon.fromKey(prefs.getString(KEY_ACTIVE_ICON, SharkAppIcon.ORIGINAL.key))
    }

    fun setActiveIcon(context: Context, targetIcon: SharkAppIcon) {
        val packageManager = context.packageManager
        val packageName = context.packageName

        // 1. Ensure MainActivity itself is ALWAYS enabled
        val mainComponent = ComponentName(packageName, "$packageName.MainActivity")
        if (packageManager.getComponentEnabledSetting(mainComponent) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            packageManager.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        // 2. Persist selection
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_ICON, targetIcon.key)
            .apply()

        // 3. Toggle only the aliases
        SharkAppIcon.entries.forEach { icon ->
            val component = ComponentName(packageName, "$packageName${icon.aliasClass}")
            val targetState = if (icon == targetIcon) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            if (packageManager.getComponentEnabledSetting(component) != targetState) {
                packageManager.setComponentEnabledSetting(
                    component,
                    targetState,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
