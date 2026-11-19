package com.example.sharkmentor.core.streak

import com.example.sharkmentor.R
import com.example.sharkmentor.worker.StreakReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterStreakMoodEngineTest {

    @Test
    fun allFourteenMoodsHaveUniqueDrawablesAndNeverUseDefaultAppIcon() {
        val moods = WaterStreakMood.entries
        assertEquals("Expected exactly 14 hand-drawn mascot moods", 14, moods.size)

        val uniqueDrawables = moods.map { it.widgetDrawableRes }.toSet()
        assertEquals("Every mascot mood should map to a distinct drawable", 14, uniqueDrawables.size)

        moods.forEach { mood ->
            assertNotEquals(
                "Default 3D app icon must NEVER be used in WaterStreakMood widgets",
                R.drawable.ic_app_icon_default,
                mood.widgetDrawableRes
            )
            assertTrue("Badge label must not be blank for ${mood.name}", mood.badgeLabel.isNotBlank())
            assertTrue("Emoji must not be blank for ${mood.name}", mood.emoji.isNotBlank())
            assertFalse("Flame emoji must not be used in ${mood.name}", mood.emoji.contains("🔥"))
        }
    }

    @Test
    fun everyMoodProducesCleanTurkishCopyWithZeroFireEmojis() {
        WaterStreakMood.entries.forEach { mood ->
            for (variant in 0..3) {
                val decision = WaterStreakMoodEngine.buildDecision(
                    mood = mood,
                    streakCount = 7,
                    todayMinutes = 25L,
                    isDoneToday = mood in setOf(
                        WaterStreakMood.HAPPY,
                        WaterStreakMood.ECSTATIC,
                        WaterStreakMood.LOVE,
                        WaterStreakMood.PROUD
                    ),
                    variant = variant
                )
                assertTrue("Headline blank for $mood", decision.widgetHeadline.isNotBlank())
                assertTrue("Subtitle blank for $mood", decision.widgetSubtitle.isNotBlank())
                assertTrue("Notif title blank for $mood", decision.notificationTitle.isNotBlank())
                assertTrue("Notif body blank for $mood", decision.notificationBody.isNotBlank())

                val allText = listOf(
                    decision.widgetHeadline,
                    decision.widgetSubtitle,
                    decision.notificationTitle,
                    decision.notificationBody
                ).joinToString(" ")
                assertFalse("No fire emoji allowed in water streak copy for $mood", allText.contains("🔥"))
            }
        }
    }

    @Test
    fun resolveCoversAllFourteenMoodsAcrossTimeAndStreakStates() {
        val reached = mutableSetOf<WaterStreakMood>()

        // Done today across different streaks (milestone + non-milestone), minutes, hours, and days
        for (streak in listOf(1, 2, 4, 7, 8, 14, 30)) {
            for (mins in listOf(20L, 50L, 100L)) {
                for (hour in listOf(2, 10, 16, 23)) {
                    for (day in 1..12) {
                        reached += WaterStreakMoodEngine.resolve(
                            streakCount = streak,
                            isDoneToday = true,
                            todayMinutes = mins,
                            hourOfDay = hour,
                            dayOfYear = day,
                            daysSinceLastStudy = 0
                        ).mood
                    }
                }
            }
        }

        // Pending today with active streak across all 24 hours
        for (hour in 0..23) {
            reached += WaterStreakMoodEngine.resolve(
                streakCount = 5,
                isDoneToday = false,
                todayMinutes = 5L,
                hourOfDay = hour,
                dayOfYear = 10,
                daysSinceLastStudy = 1
            ).mood
        }

        // Lapsed / broken streak across all 24 hours
        for (hour in 0..23) {
            reached += WaterStreakMoodEngine.resolve(
                streakCount = 0,
                isDoneToday = false,
                todayMinutes = 0L,
                hourOfDay = hour,
                dayOfYear = 10,
                daysSinceLastStudy = 3
            ).mood
        }

        assertEquals(
            "All 14 WaterStreakMood states should be reachable by WaterStreakMoodEngine.resolve",
            WaterStreakMood.entries.toSet(),
            reached
        )
    }

    @Test
    fun urgencyEscalatesThroughoutDayWhenTodayDropIsMissing() {
        val morning = WaterStreakMoodEngine.resolve(streakCount = 4, isDoneToday = false, todayMinutes = 0L, hourOfDay = 8)
        val midday = WaterStreakMoodEngine.resolve(streakCount = 4, isDoneToday = false, todayMinutes = 0L, hourOfDay = 13)
        val evening = WaterStreakMoodEngine.resolve(streakCount = 4, isDoneToday = false, todayMinutes = 0L, hourOfDay = 19)
        val lateNight = WaterStreakMoodEngine.resolve(streakCount = 4, isDoneToday = false, todayMinutes = 0L, hourOfDay = 21)
        val finalHour = WaterStreakMoodEngine.resolve(streakCount = 4, isDoneToday = false, todayMinutes = 0L, hourOfDay = 23)

        assertEquals(WaterStreakMood.SLEEPY, morning.mood)
        assertEquals(WaterStreakMood.CURIOUS, midday.mood)
        assertEquals(WaterStreakMood.SHOCKED, evening.mood)
        assertEquals(WaterStreakMood.FURIOUS, lateNight.mood)
        assertEquals(WaterStreakMood.TANTRUM, finalHour.mood)
    }

    @Test
    fun reminderSlotsCoverOnlyConfiguredWakingWindows() {
        assertNull(StreakReminderWorker.resolveReminderSlot(7))
        assertNull(StreakReminderWorker.resolveReminderSlot(10))
        assertEquals("midday", StreakReminderWorker.resolveReminderSlot(12))
        assertEquals("midday", StreakReminderWorker.resolveReminderSlot(14))
        assertNull(StreakReminderWorker.resolveReminderSlot(15))
        assertEquals("evening", StreakReminderWorker.resolveReminderSlot(18))
        assertNull(StreakReminderWorker.resolveReminderSlot(20))
        assertEquals("night", StreakReminderWorker.resolveReminderSlot(21))
        assertEquals("night", StreakReminderWorker.resolveReminderSlot(22))
        assertEquals("final", StreakReminderWorker.resolveReminderSlot(23))
        assertNotNull(StreakReminderWorker.resolveReminderSlot(17))
    }
}
