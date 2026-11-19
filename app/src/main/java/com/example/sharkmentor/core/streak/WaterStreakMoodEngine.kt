package com.example.sharkmentor.core.streak

import androidx.annotation.DrawableRes
import com.example.sharkmentor.R

/**
 * 14 distinct Duolingo-style emotional states for the Water Streak ("Su Serisi") Home Screen Widget
 * and scheduled notifications.
 *
 * STRICT ASSET RULE:
 *  - Every enum entry maps EXCLUSIVELY to one of the 14 hand-drawn mascot drawables
 *    (`R.drawable.mascot_shark_*`) and is ONLY used on widgets.
 *  - The default 3D app launcher icon (`R.drawable.ic_app_icon_default`) is NEVER used here.
 */
enum class WaterStreakMood(
    val id: String,
    @DrawableRes val widgetDrawableRes: Int,
    val emoji: String,
    val badgeLabel: String,
    val accentColorHex: Long,
    val cardTintHex: Long
) {
    HAPPY(
        id = "happy",
        widgetDrawableRes = R.drawable.mascot_shark_happy,
        emoji = "💧",
        badgeLabel = "Su Serisi Güvende",
        accentColorHex = 0xFF38BDF8,
        cardTintHex = 0xFF0A2A4A
    ),
    ECSTATIC(
        id = "ecstatic",
        widgetDrawableRes = R.drawable.mascot_shark_ecstatic,
        emoji = "✨",
        badgeLabel = "Efsane Akış",
        accentColorHex = 0xFFFACC15,
        cardTintHex = 0xFF1A254B
    ),
    LOVE(
        id = "love",
        widgetDrawableRes = R.drawable.mascot_shark_love,
        emoji = "💙",
        badgeLabel = "Harika Disiplin",
        accentColorHex = 0xFFF472B6,
        cardTintHex = 0xFF28173A
    ),
    PROUD(
        id = "proud",
        widgetDrawableRes = R.drawable.mascot_shark_proud,
        emoji = "🦈",
        badgeLabel = "Okyanus Lideri",
        accentColorHex = 0xFF2EC4B6,
        cardTintHex = 0xFF082E3A
    ),
    SMIRK(
        id = "smirk",
        widgetDrawableRes = R.drawable.mascot_shark_smirk,
        emoji = "😏",
        badgeLabel = "Rekor Zamanı",
        accentColorHex = 0xFF60A5FA,
        cardTintHex = 0xFF0F2544
    ),
    CURIOUS(
        id = "curious",
        widgetDrawableRes = R.drawable.mascot_shark_curious,
        emoji = "🤔",
        badgeLabel = "Bugün Başladın mı?",
        accentColorHex = 0xFF38BDF8,
        cardTintHex = 0xFF0C233F
    ),
    SHY(
        id = "shy",
        widgetDrawableRes = R.drawable.mascot_shark_shy,
        emoji = "🥺",
        badgeLabel = "Bir Damla Yeter",
        accentColorHex = 0xFFFB7185,
        cardTintHex = 0xFF1F1B3A
    ),
    SLEEPY(
        id = "sleepy",
        widgetDrawableRes = R.drawable.mascot_shark_sleepy,
        emoji = "😴",
        badgeLabel = "Sakin Sabah",
        accentColorHex = 0xFF93C5FD,
        cardTintHex = 0xFF0C1E38
    ),
    SHOCKED(
        id = "shocked",
        widgetDrawableRes = R.drawable.mascot_shark_shocked,
        emoji = "😱",
        badgeLabel = "Akşam Oldu!",
        accentColorHex = 0xFFF59E0B,
        cardTintHex = 0xFF2B2111
    ),
    EXHAUSTED(
        id = "exhausted",
        widgetDrawableRes = R.drawable.mascot_shark_exhausted,
        emoji = "🥀",
        badgeLabel = "Susuz Kaldık",
        accentColorHex = 0xFF94A3B8,
        cardTintHex = 0xFF182233
    ),
    CRYING(
        id = "crying",
        widgetDrawableRes = R.drawable.mascot_shark_crying,
        emoji = "😢",
        badgeLabel = "Seri Riskte",
        accentColorHex = 0xFF60A5FA,
        cardTintHex = 0xFF13223C
    ),
    FURIOUS(
        id = "furious",
        widgetDrawableRes = R.drawable.mascot_shark_furious,
        emoji = "😤",
        badgeLabel = "Son Uyarı!",
        accentColorHex = 0xFFF97316,
        cardTintHex = 0xFF331914
    ),
    PANIC(
        id = "panic",
        widgetDrawableRes = R.drawable.mascot_shark_panic,
        emoji = "🤯",
        badgeLabel = "Seri Kuruyor!",
        accentColorHex = 0xFFEF4444,
        cardTintHex = 0xFF3A1219
    ),
    TANTRUM(
        id = "tantrum",
        widgetDrawableRes = R.drawable.mascot_shark_tantrum,
        emoji = "😭",
        badgeLabel = "Acil Kurtarma!",
        accentColorHex = 0xFFEF4444,
        cardTintHex = 0xFF3D0E1B
    )
}

data class WaterStreakMoodDecision(
    val mood: WaterStreakMood,
    val widgetHeadline: String,
    val widgetSubtitle: String,
    val notificationTitle: String,
    val notificationBody: String
)

/**
 * Pure JVM-testable emotional state resolver for SharkMentor's Duolingo-style Water Streak system.
 */
object WaterStreakMoodEngine {

    /**
     * Resolves the exact shark mascot emotion, widget copy, and notification copy based on:
     * - [streakCount]: current continuous water streak days
     * - [isDoneToday]: whether today's >=20 min water drop goal is already earned
     * - [todayMinutes]: total focused minutes today
     * - [hourOfDay]: 0..23 local hour
     * - [dayOfYear]: 1..366 used to vary messages naturally day-to-day
     * - [daysSinceLastStudy]: 0 if studied today, 1 if studied yesterday, >=2 if streak lapsed
     */
    fun resolve(
        streakCount: Int,
        isDoneToday: Boolean,
        todayMinutes: Long,
        hourOfDay: Int,
        dayOfYear: Int = 1,
        daysSinceLastStudy: Int = if (isDoneToday) 0 else if (streakCount > 0) 1 else 2
    ): WaterStreakMoodDecision {
        val h = hourOfDay.coerceIn(0, 23)
        val safeStreak = streakCount.coerceAtLeast(0)
        val variant = (dayOfYear + h / 3).coerceAtLeast(0)

        val mood = when {
            isDoneToday -> when {
                h >= 23 || h <= 4 -> WaterStreakMood.SLEEPY
                isMilestoneStreak(safeStreak) || todayMinutes >= 90L -> WaterStreakMood.ECSTATIC
                safeStreak >= 7 && variant % 2 == 0 -> WaterStreakMood.PROUD
                safeStreak >= 3 && variant % 3 == 0 -> WaterStreakMood.LOVE
                todayMinutes >= 45L && variant % 4 == 1 -> WaterStreakMood.SMIRK
                else -> WaterStreakMood.HAPPY
            }

            // Streak broken or never started and no activity yet
            safeStreak == 0 && daysSinceLastStudy >= 2 -> when {
                h < 10 -> WaterStreakMood.SLEEPY
                h < 14 -> WaterStreakMood.CURIOUS
                h < 17 -> WaterStreakMood.SHY
                h < 20 -> WaterStreakMood.EXHAUSTED
                h < 22 -> WaterStreakMood.CRYING
                else -> WaterStreakMood.PANIC
            }

            // Active streak (or brand new day after yesterday) NOT yet secured today!
            else -> when {
                h < 9 -> WaterStreakMood.SLEEPY
                h < 12 -> WaterStreakMood.SMIRK
                h < 15 -> WaterStreakMood.CURIOUS
                h < 18 -> WaterStreakMood.SHY
                h < 20 -> WaterStreakMood.SHOCKED
                h == 20 -> WaterStreakMood.CRYING
                h == 21 -> WaterStreakMood.FURIOUS
                h == 22 -> WaterStreakMood.PANIC
                else -> WaterStreakMood.TANTRUM
            }
        }

        return buildDecision(mood, safeStreak, todayMinutes, isDoneToday, variant)
    }

    fun isMilestoneStreak(streakCount: Int): Boolean =
        streakCount in setOf(3, 7, 14, 21, 30, 50, 75, 100, 150, 200, 365)

    fun buildDecision(
        mood: WaterStreakMood,
        streakCount: Int,
        todayMinutes: Long,
        isDoneToday: Boolean,
        variant: Int = 0
    ): WaterStreakMoodDecision {
        val streakLabel = if (streakCount > 0) "$streakCount günlük su serin" else "su serin"
        val remainingMins = (20L - todayMinutes).coerceAtLeast(1L)

        return when (mood) {
            WaterStreakMood.HAPPY -> {
                val lines = listOf(
                    "Bugünün damlası tamam! 💧" to "Harika yüzüyorsun, ritmi hiç bozma!",
                    "Su serisi canlı ve taze! 💧" to "Bugün $todayMinutes dk odaklandın, şahane!"
                )
                val (head, sub) = lines[variant % lines.size]
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = head,
                    widgetSubtitle = sub,
                    notificationTitle = "💧 Su Serisi Tamamlandı! 🦈",
                    notificationBody = "$streakCount günlük su serini bugün de canlı tuttun! Böyle devam şampiyon 💧✨"
                )
            }

            WaterStreakMood.ECSTATIC -> {
                val head = if (isMilestoneStreak(streakCount)) {
                    "$streakCount. Gün Efsanesi! ✨"
                } else {
                    "Gözlerim parlıyor! ✨"
                }
                val sub = "Bugün $todayMinutes dk ile okyanusu salladın!"
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = head,
                    widgetSubtitle = sub,
                    notificationTitle = "✨ EFSANE SU SERİSİ: $streakCount Gün! 💧🦈",
                    notificationBody = "Gözlerim kamaştı! $streakCount günlük su serisiyle okyanusta fırtına estiriyorsun ✨💧"
                )
            }

            WaterStreakMood.LOVE -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "Sana hayran kaldım! 💙",
                    widgetSubtitle = "$streakCount gündür damla damla büyüyorsun!",
                    notificationTitle = "💙 Bu Disipline Bayıldım! 💧",
                    notificationBody = "$streakCount günlük su serinle kalbimi fethettin! Birlikte zirveye yüzüyoruz 🦈💙"
                )
            }

            WaterStreakMood.PROUD -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "İşte gerçek şampiyon! 🦈",
                    widgetSubtitle = "$streakCount günlük seriyle ligde parlıyorsun!",
                    notificationTitle = "🦈 Okyanusun Patronu Sensin! 💧",
                    notificationBody = "$streakCount günlük su serisi tam bir ustalık eseri. Rakiplerin arkandan bakıyor! 😎💧"
                )
            }

            WaterStreakMood.SMIRK -> {
                val head = if (isDoneToday) "Daha fazlası gelir mi? 😏" else "Bugünkü rekoru kıralım mı? 😏"
                val sub = if (isDoneToday) {
                    "Ligde öne geçmek için bir seans daha!"
                } else {
                    "Sadece $remainingMins dk odaklan, damlayı kap!"
                }
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = head,
                    widgetSubtitle = sub,
                    notificationTitle = "😏 Hadi Bakalım, Hazır mısın? 💧",
                    notificationBody = "Bugünkü su damlanı erkenden kapıp $streakLabel için fark atmaya ne dersin? 🦈💧"
                )
            }

            WaterStreakMood.CURIOUS -> {
                val lines = listOf(
                    "Bugün ne zaman başlıyoruz? 🤔" to "Sadece $remainingMins dk ile damlanı kazan!",
                    "Masa seni bekliyor... 🤔" to "Kısa bir odak seansı harika gider!"
                )
                val (head, sub) = lines[variant % lines.size]
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = head,
                    widgetSubtitle = sub,
                    notificationTitle = "🤔 Bugünkü Su Damlan Nerede? 💧",
                    notificationBody = "Henüz bugünün damlasını eklemedik! Sadece $remainingMins dakikalık odakla $streakLabel ışıldasın 🦈💧"
                )
            }

            WaterStreakMood.SHY -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "Şey... 20 dk çalışsak mı? 🥺",
                    widgetSubtitle = "Su serimizi kuru bırakmayalım lütfen...",
                    notificationTitle = "🥺 Küçük Bir Ricam Var... 💧",
                    notificationBody = "Şey... $streakLabel için bugün sadece $remainingMins dk odaklanabilir miyiz? Seni bekliyorum 🦈💧"
                )
            }

            WaterStreakMood.SLEEPY -> {
                val head = if (isDoneToday) "Huzurlu uykular... 😴" else "Günaydın, uyanıyor muyuz? 😴"
                val sub = if (isDoneToday) {
                    "Bugünkü damla cepte, yarın devam! 💧"
                } else {
                    "Güne 1 damla odakla başlamak harika olur!"
                }
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = head,
                    widgetSubtitle = sub,
                    notificationTitle = "😴 Günaydın! Su Serisi Seni Bekliyor 💧",
                    notificationBody = "Gözlerimi yeni açtım ama aklım $streakLabel içinde! Güne zinde bir odak seansıyla başla 🦈💧"
                )
            }

            WaterStreakMood.SHOCKED -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "NE?! Hâlâ çalışmadın mı?! 😱",
                    widgetSubtitle = "Akşam oldu, $remainingMins dk ile hemen kurtar!",
                    notificationTitle = "😱 AKŞAM OLDU! Damla Hâlâ Boş! 💧",
                    notificationBody = "Gözlerime inanamıyorum! $streakLabel bugün hâlâ susuz kaldı. Hemen $remainingMins dk odaklan! 🦈⚡"
                )
            }

            WaterStreakMood.EXHAUSTED -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "Kuruyup kaldım burada... 🥀",
                    widgetSubtitle = "Yeni bir su serisi başlat, beni canlandır!",
                    notificationTitle = "🥀 Okyanusum Kurudu... 💧",
                    notificationBody = "Bir süredir görüşemiyoruz, iyice susuz kaldım... Bugün 20 dk odaklanıp yepyeni bir su serisi başlatalım mı? 🦈💧"
                )
            }

            WaterStreakMood.CRYING -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "Su serimiz kuruyor... 😢",
                    widgetSubtitle = "Sadece $remainingMins dk ayır, gözyaşlarım dinsin!",
                    notificationTitle = "😢 Gözyaşlarımla Okyanus Doldu... 💧",
                    notificationBody = "$streakLabel gece yarısı sıfırlanmasın... Lütfen sadece $remainingMins dk odaklan, geri dön! 🦈😢"
                )
            }

            WaterStreakMood.FURIOUS -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "KÖPEKBALIĞI KIZIYOR! 😤",
                    widgetSubtitle = "Solungaçlarımdan duman çıktı! Hemen derse!",
                    notificationTitle = "😤 SOLUNGAÇLARIMDAN DUMAN ÇIKIYOR! 💧",
                    notificationBody = "Saat ilerledi ve $streakLabel tehlikede! Telefonu bırak, $remainingMins dk odaklan ve seriyi kurtar! 🦈😤💧"
                )
            }

            WaterStreakMood.PANIC -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "PANİK! SERİ ERİYOR! 🤯",
                    widgetSubtitle = "Gece yarısına az kaldı! Hemen $remainingMins dk!",
                    notificationTitle = "🤯 KRİTİK ALARM: Su Serin Kuruyor! 💧",
                    notificationBody = "Kafayı yemek üzereyim! Gece yarısına çok az kaldı, $streakLabel için hemen $remainingMins dk odaklan! 🦈🚨"
                )
            }

            WaterStreakMood.TANTRUM -> {
                WaterStreakMoodDecision(
                    mood = mood,
                    widgetHeadline = "YERLERDE YUVARLANIYORUM! 😭",
                    widgetSubtitle = "SON DAKİKALAR! $streakLabel gitmeden tıkla!",
                    notificationTitle = "😭 YERLERDE TEPİNİYORUM! SON ŞANS! 💧",
                    notificationBody = "Gece yarısı geliyor! $streakLabel gözlerimin önünde eriyip gitmesin, hemen odak sayacını başlat! 🦈🆘💧"
                )
            }
        }
    }
}
