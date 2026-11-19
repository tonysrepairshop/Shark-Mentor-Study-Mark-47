package com.example.sharkmentor.core.league

import com.example.sharkmentor.ui.viewmodel.LeagueEntry
import com.example.sharkmentor.ui.viewmodel.SharkTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharkLeagueRulesTest {

    @Test
    fun ladderAndTotalLeagues_matchSixSharkTiersHighToLow() {
        assertEquals(6, SharkLeagueRules.TOTAL_LEAGUES)
        assertEquals(
            listOf(
                SharkTier.MEGALODON,
                SharkTier.BUYUK_BEYAZ,
                SharkTier.KAPLAN,
                SharkTier.CEKICBAS,
                SharkTier.MAKO,
                SharkTier.CAMGOZ
            ),
            SharkLeagueRules.LADDER_HIGH_TO_LOW
        )
    }

    @Test
    fun lowerHigherAndRelegatedTargetTier_stepByOneOrRepeatCamgoz() {
        assertNull(SharkLeagueRules.higherTier(SharkTier.MEGALODON))
        assertEquals(SharkTier.BUYUK_BEYAZ, SharkLeagueRules.lowerTier(SharkTier.MEGALODON))
        assertEquals(SharkTier.BUYUK_BEYAZ, SharkLeagueRules.relegatedTargetTier(SharkTier.MEGALODON))

        assertEquals(SharkTier.CEKICBAS, SharkLeagueRules.higherTier(SharkTier.MAKO))
        assertEquals(SharkTier.CAMGOZ, SharkLeagueRules.lowerTier(SharkTier.MAKO))
        assertEquals(SharkTier.CAMGOZ, SharkLeagueRules.relegatedTargetTier(SharkTier.MAKO))

        assertEquals(SharkTier.MAKO, SharkLeagueRules.higherTier(SharkTier.CAMGOZ))
        assertNull(SharkLeagueRules.lowerTier(SharkTier.CAMGOZ))
        assertEquals(SharkTier.CAMGOZ, SharkLeagueRules.relegatedTargetTier(SharkTier.CAMGOZ))
    }

    @Test
    fun relegationStartRank_protectsTopThreeAndCoversBottomFive() {
        assertNull(SharkLeagueRules.relegationStartRank(0))
        assertNull(SharkLeagueRules.relegationStartRank(1))
        assertNull(SharkLeagueRules.relegationStartRank(3))
        assertEquals(4, SharkLeagueRules.relegationStartRank(4))
        assertEquals(4, SharkLeagueRules.relegationStartRank(5))
        assertEquals(4, SharkLeagueRules.relegationStartRank(8))
        assertEquals(6, SharkLeagueRules.relegationStartRank(10))
        assertEquals(16, SharkLeagueRules.relegationStartRank(20))
    }

    @Test
    fun resolveZoneForRank_marksTop3PodiumAndBottom5RelegationOrRepeat() {
        // 10 students in Mako: 1..3 podium, 4..5 safe, 6..10 relegation drop (to Camgöz)
        assertEquals(LeagueRankZone.TOP_1, SharkLeagueRules.resolveZoneForRank(1, 10, SharkTier.MAKO))
        assertEquals(LeagueRankZone.TOP_2, SharkLeagueRules.resolveZoneForRank(2, 10, SharkTier.MAKO))
        assertEquals(LeagueRankZone.TOP_3, SharkLeagueRules.resolveZoneForRank(3, 10, SharkTier.MAKO))
        assertTrue(SharkLeagueRules.resolveZoneForRank(3, 10, SharkTier.MAKO).isTopThree)
        assertFalse(SharkLeagueRules.resolveZoneForRank(3, 10, SharkTier.MAKO).isRelegationZone)

        assertEquals(LeagueRankZone.SAFE, SharkLeagueRules.resolveZoneForRank(4, 10, SharkTier.MAKO))
        assertEquals(LeagueRankZone.SAFE, SharkLeagueRules.resolveZoneForRank(5, 10, SharkTier.MAKO))

        for (rank in 6..10) {
            val zone = SharkLeagueRules.resolveZoneForRank(rank, 10, SharkTier.MAKO)
            assertEquals(LeagueRankZone.RELEGATION_DROP, zone)
            assertTrue(zone.isRelegationZone)
            assertFalse(zone.isTopThree)
        }

        // 10 students in Camgöz: bottom 5 repeat Camgöz instead of dropping
        assertEquals(LeagueRankZone.SAFE, SharkLeagueRules.resolveZoneForRank(5, 10, SharkTier.CAMGOZ))
        for (rank in 6..10) {
            val zone = SharkLeagueRules.resolveZoneForRank(rank, 10, SharkTier.CAMGOZ)
            assertEquals(LeagueRankZone.RELEGATION_REPEAT, zone)
            assertTrue(zone.isRelegationZone)
        }

        // 3 students in Megalodon: nobody relegated (Top 3 protected)
        for (rank in 1..3) {
            assertFalse(SharkLeagueRules.resolveZoneForRank(rank, 3, SharkTier.MEGALODON).isRelegationZone)
        }
    }

    @Test
    fun resolveEffectiveTier_honorsRelegatedTierDuringSettledWeekAndAllowsSafePromotion() {
        val week = "2026-W47"

        // Relegated student stays in demoted tier during the settled week even if rolling 7-day hours are still warm
        assertEquals(
            SharkTier.MAKO,
            SharkLeagueRules.resolveEffectiveTier(
                earnedTier = SharkTier.CEKICBAS,
                assignedTier = SharkTier.MAKO,
                relegationWeekKey = week,
                currentWeekKey = week,
                lastWeekOutcome = "RELEGATED"
            )
        )

        // SAFE / TOP_3 student keeps at least settled tier and can promote higher during the week
        assertEquals(
            SharkTier.KAPLAN,
            SharkLeagueRules.resolveEffectiveTier(
                earnedTier = SharkTier.KAPLAN,
                assignedTier = SharkTier.CEKICBAS,
                relegationWeekKey = week,
                currentWeekKey = week,
                lastWeekOutcome = "SAFE"
            )
        )
        assertEquals(
            SharkTier.CEKICBAS,
            SharkLeagueRules.resolveEffectiveTier(
                earnedTier = SharkTier.MAKO,
                assignedTier = SharkTier.CEKICBAS,
                relegationWeekKey = week,
                currentWeekKey = week,
                lastWeekOutcome = "TOP_3"
            )
        )

        // Stale week key falls back to earnedTier
        assertEquals(
            SharkTier.KAPLAN,
            SharkLeagueRules.resolveEffectiveTier(
                earnedTier = SharkTier.KAPLAN,
                assignedTier = SharkTier.MAKO,
                relegationWeekKey = "2026-W46",
                currentWeekKey = week,
                lastWeekOutcome = "RELEGATED"
            )
        )
    }

    @Test
    fun groupEntriesByTier_returnsAllSixTiersSortedDescendingByEffectiveSeconds() {
        val entries = listOf(
            LeagueEntry(studentId = "s1", displayName = "Ali", weeklyFocusSeconds = 10_000L, tier = SharkTier.CAMGOZ),
            LeagueEntry(studentId = "s2", displayName = "Ayşe", weeklyFocusSeconds = 20_000L, weeklyBonusSeconds = 3_600L, tier = SharkTier.CAMGOZ),
            LeagueEntry(studentId = "s3", displayName = "Can", weeklyFocusSeconds = 110_000L, tier = SharkTier.MAKO)
        )

        val grouped = SharkLeagueRules.groupEntriesByTier(entries)
        assertEquals(6, grouped.size)
        assertEquals(listOf("s2", "s1"), grouped[SharkTier.CAMGOZ]?.map { it.studentId })
        assertEquals(listOf("s3"), grouped[SharkTier.MAKO]?.map { it.studentId })
        assertTrue(grouped[SharkTier.MEGALODON].orEmpty().isEmpty())
        assertTrue(grouped[SharkTier.BUYUK_BEYAZ].orEmpty().isEmpty())
        assertTrue(grouped[SharkTier.KAPLAN].orEmpty().isEmpty())
        assertTrue(grouped[SharkTier.CEKICBAS].orEmpty().isEmpty())
    }
}
