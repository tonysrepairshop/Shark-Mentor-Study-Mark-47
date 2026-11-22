package com.example.sharkmentor.core.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachingAndCampTest {

    @Test
    fun testStudentTargetContextHeadlineAndRecommendedGoal() {
        val ctx = StudentTargetContext(
            examType = "YKS Sayısal",
            studentGrade = "12. Sınıf",
            targetUniversity = "İTÜ",
            targetDepartment = "Bilgisayar Müh.",
            targetRanking = 3500
        )
        assertTrue(ctx.hasConcreteTarget)
        assertEquals("YKS Sayısal • İTÜ Bilgisayar Müh. • #3.500", ctx.compassHeadline())
        assertEquals(330, ctx.recommendDailyMinutes(isWeekend = false))
        assertEquals(390, ctx.recommendDailyMinutes(isWeekend = true))
    }

    @Test
    fun testComputeDailyGoalStatusDeficitAndOvertime() {
        // 160 mins worked + 0 running vs 240 mins target -> -1 sa 20 dk remaining
        val deficit = CoachingEngine.computeDailyGoalStatus(
            todayCompletedMinutes = 160,
            activeRunningSeconds = 0L,
            targetMinutes = 240
        )
        assertFalse(deficit.isGoalReached)
        assertFalse(deficit.isOvertime)
        assertEquals(80, deficit.remainingMinutes)
        assertEquals("-1 sa 20 dk", deficit.formattedDeltaBadge())

        // 240 mins worked + 45 mins active running vs 240 mins target -> +45 dk overtime
        val overtime = CoachingEngine.computeDailyGoalStatus(
            todayCompletedMinutes = 240,
            activeRunningSeconds = 45L * 60L,
            targetMinutes = 240
        )
        assertTrue(overtime.isGoalReached)
        assertTrue(overtime.isOvertime)
        assertEquals(45, overtime.overtimeMinutes)
        assertEquals("+45 dk", overtime.formattedDeltaBadge())
    }

    @Test
    fun testWeeklyVarianceAdviceDetectsLowWednesdayAndHighThursday() {
        val ledger = listOf(
            DailyGoalLedgerEntry("2026-11-16", 1, "Pazartesi", 240, 240),
            DailyGoalLedgerEntry("2026-11-17", 2, "Salı", 235, 240),
            DailyGoalLedgerEntry("2026-11-18", 3, "Çarşamba", 150, 240), // -90m deficit
            DailyGoalLedgerEntry("2026-11-19", 4, "Perşembe", 290, 240), // +50m surplus
            DailyGoalLedgerEntry("2026-11-20", 5, "Cuma", 240, 240),
            DailyGoalLedgerEntry("2026-11-21", 6, "Cumartesi", 300, 300),
            DailyGoalLedgerEntry("2026-11-22", 7, "Pazar", 120, 300)
        )
        val target = StudentTargetContext(
            examType = "YKS 2026",
            targetTitle = "ODTÜ Elektrik-Elektronik",
            targetRanking = 2000
        )
        val advice = CoachingEngine.computeWeeklyVarianceAdvice(ledger, target)
        assertNotNull(advice)
        assertEquals("Çarşamba", advice!!.lowDayLabel)
        assertEquals(90, advice.lowDeficitMinutes)
        assertEquals("Perşembe", advice.highDayLabel)
        assertEquals(50, advice.highSurplusMinutes)
        assertTrue(advice.conciseMessage.contains("Çarşamba"))
        assertTrue(advice.conciseMessage.contains("Perşembe"))
    }

    @Test
    fun testFocusTitleFormattingAndLiveLeagueStatus() {
        val formatted = CoachingEngine.buildFormattedFocusSubject("AYT", "Türev Test 4")
        assertEquals("AYT • Türev Test 4", formatted)

        val now = 1_000_000_000L
        val presence = LiveFocusPresence(
            isStudying = true,
            activeSubject = formatted,
            startedAtMillis = now - (42L * 60_000L)
        )
        assertEquals(42, presence.elapsedMinutes(now))
        assertEquals("Masada • AYT • Türev Test 4 (42 dk)", presence.formattedLeagueStatus(now))
    }

    @Test
    fun testYouTubeUrlParsingAndNoCookieEmbedBuilder() {
        val (v1, p1) = BookCampManager.parseYouTubeUrlIds(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890ABCDEF"
        )
        assertEquals("dQw4w9WgXcQ", v1)
        assertEquals("PL1234567890ABCDEF", p1)

        val (v2, p2) = BookCampManager.parseYouTubeUrlIds("https://youtu.be/abcDEF12345")
        assertEquals("abcDEF12345", v2)
        assertEquals("", p2)

        val ep = CampDayEpisode(
            dayNumber = 5,
            topicTitle = "Türev Alma Kuralları",
            videoId = v1,
            playlistId = p1
        )
        assertTrue(ep.buildInAppEmbedUrl().startsWith("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
        assertTrue(ep.buildExternalYouTubeUrl().contains("youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}
