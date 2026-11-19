package com.example.sharkmentor.core.util

import androidx.annotation.DrawableRes
import com.example.sharkmentor.R
import com.example.sharkmentor.ui.viewmodel.SharkTier

object SharkVisualKit {
    @DrawableRes
    fun getBreedDrawable(key: String?): Int = when (key?.lowercase()?.trim()) {
        "camgoz", "spiny" -> R.drawable.ic_shark_camgoz
        "mako", "original" -> R.drawable.ic_shark_mako
        "cekicbas", "hammerhead" -> R.drawable.ic_shark_cekicbas
        "kaplan", "tiger" -> R.drawable.ic_shark_kaplan
        "buyuk_beyaz", "great_white", "greatwhite" -> R.drawable.ic_shark_buyuk_beyaz
        "megalodon" -> R.drawable.ic_shark_megalodon
        else -> R.drawable.ic_shark_mako
    }

    @DrawableRes
    fun getTierBadge(tier: SharkTier): Int = when (tier) {
        SharkTier.CAMGOZ -> R.drawable.ic_shark_camgoz
        SharkTier.MAKO -> R.drawable.ic_shark_mako
        SharkTier.CEKICBAS -> R.drawable.ic_shark_cekicbas
        SharkTier.KAPLAN -> R.drawable.ic_shark_kaplan
        SharkTier.BUYUK_BEYAZ -> R.drawable.ic_shark_buyuk_beyaz
        SharkTier.MEGALODON -> R.drawable.ic_shark_megalodon
    }

    // Map League Tiers/Points to Shark Breeds (points = haftalık efektif dakika)
    @DrawableRes
    fun getTierBadge(points: Int): Int = when {
        points >= 6300 -> R.drawable.ic_shark_megalodon   // Megalodon (105 sa / >15 sa/gün)
        points >= 5040 -> R.drawable.ic_shark_buyuk_beyaz // Büyük Beyaz (84 sa / 12 sa/gün)
        points >= 4200 -> R.drawable.ic_shark_kaplan      // Kaplan (70 sa / 10 sa/gün)
        points >= 2940 -> R.drawable.ic_shark_cekicbas    // Çekiçbaş (49 sa / 7 sa/gün)
        points >= 1680 -> R.drawable.ic_shark_mako        // Mako (28 sa / >4 sa/gün)
        else           -> R.drawable.ic_shark_camgoz      // Camgöz
    }
}

