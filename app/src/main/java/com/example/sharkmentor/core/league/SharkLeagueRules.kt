package com.example.sharkmentor.core.league

import com.example.sharkmentor.ui.viewmodel.LeagueEntry
import com.example.sharkmentor.ui.viewmodel.SharkTier

/**
 * Pure-JVM deterministic rules for Shark League tiers, Top 3 podium highlights,
 * and Bottom 5 weekly relegation (demotes 1 tier if a lower tier exists; repeats Camgöz otherwise).
 */
enum class LeagueRankZone {
    TOP_1,
    TOP_2,
    TOP_3,
    SAFE,
    RELEGATION_DROP,
    RELEGATION_REPEAT;

    val isTopThree: Boolean
        get() = this == TOP_1 || this == TOP_2 || this == TOP_3

    val isRelegationZone: Boolean
        get() = this == RELEGATION_DROP || this == RELEGATION_REPEAT
}

object SharkLeagueRules {
    const val TOP_HIGHLIGHT_COUNT = 3
    const val RELEGATION_BOTTOM_COUNT = 5

    /** Total number of Shark League tiers (Camgöz -> Megalodon). */
    val TOTAL_LEAGUES: Int = SharkTier.entries.size

    /** Ordered from highest prestige (Megalodon) to entry league (Camgöz). */
    val LADDER_HIGH_TO_LOW: List<SharkTier> = SharkTier.entries.reversed()

    fun lowerTier(tier: SharkTier): SharkTier? {
        val idx = tier.ordinal
        return if (idx > 0) SharkTier.entries[idx - 1] else null
    }

    fun higherTier(tier: SharkTier): SharkTier? {
        val idx = tier.ordinal
        return if (idx < SharkTier.entries.lastIndex) SharkTier.entries[idx + 1] else null
    }

    /**
     * Target tier when relegated from [tier]:
     * drops one level if a lower tier exists, otherwise repeats [SharkTier.CAMGOZ].
     */
    fun relegatedTargetTier(tier: SharkTier): SharkTier = lowerTier(tier) ?: SharkTier.CAMGOZ

    fun weeklyRequirementLabel(tier: SharkTier): String = when (tier) {
        SharkTier.CAMGOZ -> "0 - 28 sa / hafta"
        SharkTier.MAKO -> "28+ sa / hafta"
        SharkTier.CEKICBAS -> "49+ sa / hafta"
        SharkTier.KAPLAN -> "70+ sa / hafta"
        SharkTier.BUYUK_BEYAZ -> "84+ sa / hafta"
        SharkTier.MEGALODON -> "105+ sa / hafta"
    }

    fun parseTierOrNull(raw: String?): SharkTier? {
        val clean = raw?.trim()?.uppercase().orEmpty()
        if (clean.isBlank()) return null
        return SharkTier.entries.firstOrNull { it.name == clean }
    }

    /**
     * Returns the 1-based rank where the Bottom-5 relegation zone begins for a league of size [totalInTier].
     *
     * Rules:
     * - Top 3 ([TOP_HIGHLIGHT_COUNT]) students are ALWAYS protected on the podium and never relegated.
     * - If `totalInTier <= 3`, returns `null` (no relegation zone).
     * - Otherwise returns `maxOf(4, totalInTier - 4)`, covering the bottom 5 students (or all non-podium
     *   students when `totalInTier` is between 4 and 8).
     */
    fun relegationStartRank(totalInTier: Int): Int? {
        if (totalInTier <= TOP_HIGHLIGHT_COUNT) return null
        return maxOf(TOP_HIGHLIGHT_COUNT + 1, totalInTier - RELEGATION_BOTTOM_COUNT + 1)
    }

    fun resolveZoneForRank(rank: Int, totalInTier: Int, tier: SharkTier): LeagueRankZone {
        if (rank <= 0 || totalInTier <= 0) return LeagueRankZone.SAFE
        return when (rank) {
            1 -> LeagueRankZone.TOP_1
            2 -> LeagueRankZone.TOP_2
            3 -> LeagueRankZone.TOP_3
            else -> {
                val startRank = relegationStartRank(totalInTier)
                if (startRank != null && rank >= startRank) {
                    if (lowerTier(tier) != null) LeagueRankZone.RELEGATION_DROP
                    else LeagueRankZone.RELEGATION_REPEAT
                } else {
                    LeagueRankZone.SAFE
                }
            }
        }
    }

    /**
     * Resolves the student's active league tier taking into account both weekly focus progress
     * ([earnedTier]) and any weekly settlement placement ([assignedTier]) for [currentWeekKey].
     */
    fun resolveEffectiveTier(
        earnedTier: SharkTier,
        assignedTier: SharkTier?,
        relegationWeekKey: String?,
        currentWeekKey: String,
        lastWeekOutcome: String? = null
    ): SharkTier {
        if (assignedTier == null || relegationWeekKey.isNullOrBlank() || relegationWeekKey != currentWeekKey) {
            return earnedTier
        }
        return if (lastWeekOutcome.equals("RELEGATED", ignoreCase = true)) {
            assignedTier
        } else {
            if (earnedTier.ordinal >= assignedTier.ordinal) earnedTier else assignedTier
        }
    }

    fun sortLeagueEntries(entries: List<LeagueEntry>): List<LeagueEntry> {
        return entries.sortedWith(
            compareByDescending<LeagueEntry> { it.effectiveLeagueSeconds }
                .thenByDescending { it.weeklyFocusSeconds }
                .thenByDescending { it.mentorPoints }
                .thenBy { it.studentId }
        )
    }

    fun groupEntriesByTier(entries: List<LeagueEntry>): Map<SharkTier, List<LeagueEntry>> {
        val grouped = entries.groupBy { it.tier }
        return buildMap {
            for (tier in SharkTier.entries) {
                put(tier, sortLeagueEntries(grouped[tier].orEmpty()))
            }
        }
    }

    fun relegationBannerText(tier: SharkTier?): String {
        if (tier == null) {
            return "DÜŞME HATTI (Son 5) — Alt lige düşer (alt lig yoksa aynı lig tekrar)"
        }
        val lower = lowerTier(tier)
        return if (lower != null) {
            "DÜŞME HATTI (Son 5) — Hafta sonunda ${lower.label} ligine düşer"
        } else {
            "TEKRAR HATTI (Son 5) — Alt lig yok, ${tier.label} ligini tekrar eder"
        }
    }
}
