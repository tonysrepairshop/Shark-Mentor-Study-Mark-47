package com.example.sharkmentor.ui.components

import com.example.sharkmentor.data.weather.HourlyForecastItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherHourlyWindowTest {

    private fun build72HourForecast(startDate: LocalDateTime): List<HourlyForecastItem> {
        val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val labelFormatter = DateTimeFormatter.ofPattern("HH:mm")
        return (0 until 72).map { offset ->
            val dt = startDate.plusHours(offset.toLong())
            HourlyForecastItem(
                hourLabel = dt.format(labelFormatter),
                tempCelsius = 10.0 + (offset % 15),
                weatherCode = 0,
                isoTime = dt.format(isoFormatter)
            )
        }
    }

    @Test
    fun testMiddayReturnsExactMinus2ToPlus10Hours() {
        val hourly = build72HourForecast(LocalDateTime.of(2026, 11, 21, 0, 0))
        val now = LocalDateTime.of(2026, 11, 22, 14, 35)

        val window = filterWeatherHourlyWindow(hourly, now)

        assertEquals(13, window.size)
        assertEquals(
            listOf(
                "12:00", "13:00", "14:00", "15:00", "16:00", "17:00",
                "18:00", "19:00", "20:00", "21:00", "22:00", "23:00", "00:00"
            ),
            window.map { it.hourLabel }
        )
        assertEquals("2026-11-22T12:00", window.first().isoTime)
        assertEquals("2026-11-23T00:00", window.last().isoTime)
    }

    @Test
    fun testEarlyMorningMidnightRolloverIncludesPreviousDay() {
        val hourly = build72HourForecast(LocalDateTime.of(2026, 11, 21, 0, 0))
        val now = LocalDateTime.of(2026, 11, 22, 0, 20)

        val window = filterWeatherHourlyWindow(hourly, now)

        assertEquals(13, window.size)
        assertEquals(
            listOf(
                "22:00", "23:00", "00:00", "01:00", "02:00", "03:00",
                "04:00", "05:00", "06:00", "07:00", "08:00", "09:00", "10:00"
            ),
            window.map { it.hourLabel }
        )
        assertEquals("2026-11-21T22:00", window.first().isoTime)
        assertEquals("2026-11-22T10:00", window.last().isoTime)
    }

    @Test
    fun testLateNightMidnightRolloverIncludesNextDay() {
        val hourly = build72HourForecast(LocalDateTime.of(2026, 11, 21, 0, 0))
        val now = LocalDateTime.of(2026, 11, 22, 23, 50)

        val window = filterWeatherHourlyWindow(hourly, now)

        assertEquals(13, window.size)
        assertEquals(
            listOf(
                "21:00", "22:00", "23:00", "00:00", "01:00", "02:00",
                "03:00", "04:00", "05:00", "06:00", "07:00", "08:00", "09:00"
            ),
            window.map { it.hourLabel }
        )
        assertEquals("2026-11-22T21:00", window.first().isoTime)
        assertEquals("2026-11-23T09:00", window.last().isoTime)
    }

    @Test
    fun testLegacyCacheFallbackWithoutIsoTimeOrdersChronologically() {
        val legacyHourly = (0 until 24).map { h ->
            HourlyForecastItem(
                hourLabel = String.format("%02d:00", h),
                tempCelsius = 12.0 + h * 0.5,
                weatherCode = 1,
                isoTime = ""
            )
        }
        val now = LocalDateTime.of(2026, 11, 22, 1, 15)

        val window = filterWeatherHourlyWindow(legacyHourly, now)

        assertEquals(13, window.size)
        assertEquals(
            listOf(
                "23:00", "00:00", "01:00", "02:00", "03:00", "04:00",
                "05:00", "06:00", "07:00", "08:00", "09:00", "10:00", "11:00"
            ),
            window.map { it.hourLabel }
        )
    }

    @Test
    fun testEmptyHourlyReturnsEmpty() {
        assertTrue(filterWeatherHourlyWindow(emptyList(), LocalDateTime.of(2026, 11, 22, 12, 0)).isEmpty())
    }
}
