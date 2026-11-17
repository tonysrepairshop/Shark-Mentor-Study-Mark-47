package com.example.sharkmentor.core.util

import androidx.annotation.DrawableRes
import com.example.sharkmentor.R

object SharkVisualKit {
    @DrawableRes
    fun getBreedDrawable(key: String?): Int = when (key?.lowercase()?.trim()) {
        "hammerhead" -> R.drawable.ic_shark_hammerhead
        "spiny" -> R.drawable.ic_shark_spiny
        "great_white", "greatwhite" -> R.drawable.ic_shark_great_white
        "megalodon" -> R.drawable.ic_shark_megalodon
        else -> R.drawable.ic_shark_original
    }

    // Map League Tiers/Points to Shark Breeds
    @DrawableRes
    fun getTierBadge(points: Int): Int = when { // points = minutes
        points >= 2700 -> R.drawable.ic_shark_megalodon // Büyük Beyaz (45h)
        points >= 1800 -> R.drawable.ic_shark_great_white // Kaplan (30h)
        points >= 1200 -> R.drawable.ic_shark_hammerhead // Çekiçbaş (20h)
        points >= 600  -> R.drawable.ic_shark_spiny // Mako (10h)
        else           -> R.drawable.ic_shark_original // Camgöz
    }
}
