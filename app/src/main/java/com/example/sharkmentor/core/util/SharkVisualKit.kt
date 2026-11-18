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
    fun getTierBadge(points: Int): Int = when { // points = haftalık efektif dakika
        points >= 6300 -> R.drawable.ic_shark_megalodon   // Megalodon (105 sa / >15 sa/gün)
        points >= 5040 -> R.drawable.ic_shark_great_white // Büyük Beyaz (84 sa / 12 sa/gün)
        points >= 4200 -> R.drawable.ic_shark_great_white // Kaplan (70 sa / 10 sa/gün)
        points >= 2940 -> R.drawable.ic_shark_hammerhead  // Çekiçbaş (49 sa / 7 sa/gün)
        points >= 1680 -> R.drawable.ic_shark_spiny       // Mako (28 sa / >4 sa/gün)
        else           -> R.drawable.ic_shark_original    // Camgöz
    }
}
