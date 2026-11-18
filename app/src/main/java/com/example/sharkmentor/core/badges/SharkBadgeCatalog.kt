package com.example.sharkmentor.core.badges

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Locale

enum class BadgeTier(
    val level: Int,
    val label: String,
    val shortLabel: String,
    val emoji: String,
    val primaryColorHex: Long,
    val secondaryColorHex: Long
) {
    BRONZE(1, "Bronz", "Bronz", "🥉", 0xFFB45309, 0xFFF59E0B),
    SILVER(2, "Gümüş", "Gümüş", "🥈", 0xFF64748B, 0xFFCBD5E1),
    GOLD(3, "Altın", "Altın", "🥇", 0xFFD97706, 0xFFFDE047),
    MEGALODON(4, "Megalodon", "Megalodon", "🦈", 0xFF0284C7, 0xFF38BDF8)
}

@Immutable
data class BadgeProgressInfo(
    val badge: SharkBadgeDef,
    val count: Int,
    val unlockedTier: BadgeTier?,
    val nextTier: BadgeTier?,
    val currentThreshold: Int,
    val nextTarget: Int,
    val progressFraction: Float
)

@Immutable
data class SharkBadgeDef(
    val id: String,
    val title: String,
    val emoji: String,
    val description: String,
    val conditionLabel: String,
    val thresholds: List<Int>, // [BRONZE, SILVER, GOLD, MEGALODON]
    val primaryColorHex: Long,
    val secondaryColorHex: Long,
    val icon: ImageVector
) {
    fun progressFor(rawCount: Int, isLegacyUnlocked: Boolean = false): BadgeProgressInfo {
        val t = if (thresholds.size >= 4) thresholds else listOf(1, 5, 15, 30)
        val effectiveCount = if (isLegacyUnlocked && rawCount < t[0]) t[0] else rawCount.coerceAtLeast(0)

        return when {
            effectiveCount >= t[3] -> BadgeProgressInfo(
                badge = this,
                count = effectiveCount,
                unlockedTier = BadgeTier.MEGALODON,
                nextTier = null,
                currentThreshold = t[3],
                nextTarget = t[3],
                progressFraction = 1f
            )
            effectiveCount >= t[2] -> BadgeProgressInfo(
                badge = this,
                count = effectiveCount,
                unlockedTier = BadgeTier.GOLD,
                nextTier = BadgeTier.MEGALODON,
                currentThreshold = t[2],
                nextTarget = t[3],
                progressFraction = ((effectiveCount - t[2]).toFloat() / (t[3] - t[2]).coerceAtLeast(1)).coerceIn(0f, 1f)
            )
            effectiveCount >= t[1] -> BadgeProgressInfo(
                badge = this,
                count = effectiveCount,
                unlockedTier = BadgeTier.SILVER,
                nextTier = BadgeTier.GOLD,
                currentThreshold = t[1],
                nextTarget = t[2],
                progressFraction = ((effectiveCount - t[1]).toFloat() / (t[2] - t[1]).coerceAtLeast(1)).coerceIn(0f, 1f)
            )
            effectiveCount >= t[0] -> BadgeProgressInfo(
                badge = this,
                count = effectiveCount,
                unlockedTier = BadgeTier.BRONZE,
                nextTier = BadgeTier.SILVER,
                currentThreshold = t[0],
                nextTarget = t[1],
                progressFraction = ((effectiveCount - t[0]).toFloat() / (t[1] - t[0]).coerceAtLeast(1)).coerceIn(0f, 1f)
            )
            else -> BadgeProgressInfo(
                badge = this,
                count = effectiveCount,
                unlockedTier = null,
                nextTier = BadgeTier.BRONZE,
                currentThreshold = 0,
                nextTarget = t[0],
                progressFraction = (effectiveCount.toFloat() / t[0].coerceAtLeast(1)).coerceIn(0f, 1f)
            )
        }
    }
}

object SharkBadgeCatalog {

    const val MAX_SHOWCASE_BADGES = 5
    const val MAX_WEEKLY_BONUS_SECONDS = 9000L // 150 dakika

    private const val PREFS_NAME = "shark_badges_prefs"
    private const val KEY_SHOWCASE_IDS = "showcase_badge_ids"
    private const val KEY_UNLOCKED_IDS = "unlocked_badge_ids"
    private const val KEY_MENTOR_POINTS = "mentor_points"
    private const val KEY_BADGE_PROGRESS = "badge_progress_counts"
    private const val KEY_WEEKLY_BONUS_PREFIX = "weekly_bonus_secs_"
    private const val KEY_BLOCKED_UIDS = "blocked_user_ids"

    const val BADGE_ILK_SORU = "ilk_soru"
    const val BADGE_ILK_COZUM = "ilk_cozum"
    const val BADGE_HIZLI_KOPEKBALIGI = "hizli_kopekbaligi"
    const val BADGE_CAN_SIMIDI = "can_simidi"
    const val BADGE_SORU_CANAVARI = "soru_canavari"
    const val BADGE_SORU_AVCISI = "soru_avcisi"
    const val BADGE_MATEMATIK_CANAVARI = "matematik_canavari"
    const val BADGE_FIZIK_KASIFI = "fizik_kasifi"
    const val BADGE_KIMYA_SIMYACISI = "kimya_simyacisi"
    const val BADGE_BIYOLOJI_UZMANI = "biyoloji_uzmani"
    const val BADGE_PARAGRAF_AVCISI = "paragraf_avcisi"
    const val BADGE_SOSYAL_BILGE = "sosyal_bilge"

    val ALL_BADGES: List<SharkBadgeDef> = listOf(
        SharkBadgeDef(
            id = BADGE_ILK_SORU,
            title = "İlk Soru",
            emoji = "📸",
            description = "Hata Bankası'ndan sohbete ilk soruyu paylaşarak dayanışmayı başlatan öncü.",
            conditionLabel = "Soru paylaş (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFF0284C7,
            secondaryColorHex = 0xFF38BDF8,
            icon = Icons.Default.NoteAdd
        ),
        SharkBadgeDef(
            id = BADGE_ILK_COZUM,
            title = "İlk Çözüm",
            emoji = "✅",
            description = "Arkadaşının sorusuna onaylı çözüm üreterek ilk Mentor katkısını veren yıldız.",
            conditionLabel = "Onaylı çözüm (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFF059669,
            secondaryColorHex = 0xFF34D399,
            icon = Icons.Default.CheckCircle
        ),
        SharkBadgeDef(
            id = BADGE_HIZLI_KOPEKBALIGI,
            title = "Hızlı Köpekbalığı",
            emoji = "⚡",
            description = "Soru paylaşıldıktan sonraki ilk 0–15 dakika içinde onaylı çözüm gönderen hız uzmanı.",
            conditionLabel = "0–15 dk içinde onaylı çözüm (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFFD97706,
            secondaryColorHex = 0xFFFBBF24,
            icon = Icons.Default.Bolt
        ),
        SharkBadgeDef(
            id = BADGE_CAN_SIMIDI,
            title = "Can Simidi",
            emoji = "🛟",
            description = "60–105 dk aralığında süre bitmeden imdada yetişip soruyu kurtaran güvenilir mentor.",
            conditionLabel = "60–105 dk arası onaylı çözüm (1 / 3 / 10 / 20)",
            thresholds = listOf(1, 3, 10, 20),
            primaryColorHex = 0xFF0D9488,
            secondaryColorHex = 0xFF2DD4BF,
            icon = Icons.Default.Speed
        ),
        SharkBadgeDef(
            id = BADGE_SORU_CANAVARI,
            title = "Soru Canavarı",
            emoji = "🔥",
            description = "Çalışma gruplarında yüksek tempoda soru paylaşımı ve tartışma başlatan dinamo.",
            conditionLabel = "Toplam soru paylaşımı (5 / 15 / 35 / 75)",
            thresholds = listOf(5, 15, 35, 75),
            primaryColorHex = 0xFFDC2626,
            secondaryColorHex = 0xFFF87171,
            icon = Icons.Default.LocalFireDepartment
        ),
        SharkBadgeDef(
            id = BADGE_SORU_AVCISI,
            title = "Soru Avcısı",
            emoji = "🎯",
            description = "Zorlu soru akışında düzinelerce onaylı çözümle topluluğun bel kemiği olan usta.",
            conditionLabel = "Toplam onaylı çözüm (5 / 20 / 50 / 100)",
            thresholds = listOf(5, 20, 50, 100),
            primaryColorHex = 0xFFB45309,
            secondaryColorHex = 0xFFFDE047,
            icon = Icons.Default.TrackChanges
        ),
        SharkBadgeDef(
            id = BADGE_MATEMATIK_CANAVARI,
            title = "Türev/Matematik Canavarı",
            emoji = "📐",
            description = "Matematik, Geometri, Türev ve İntegral sorularında adım adım net çözümler üreten beyin.",
            conditionLabel = "Matematik/Geo çözümü (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFF4F46E5,
            secondaryColorHex = 0xFF818CF8,
            icon = Icons.Default.AutoGraph
        ),
        SharkBadgeDef(
            id = BADGE_FIZIK_KASIFI,
            title = "Fizik Kâşifi",
            emoji = "⚛️",
            description = "Kuvvet, hareket, elektrik ve modern fizik sorularını ışın hızıyla çözen kâşif.",
            conditionLabel = "Fizik onaylı çözümü (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFF0369A1,
            secondaryColorHex = 0xFF38BDF8,
            icon = Icons.Default.Star
        ),
        SharkBadgeDef(
            id = BADGE_KIMYA_SIMYACISI,
            title = "Kimya Simyacısı",
            emoji = "⚗️",
            description = "Tepkime, mol ve organik kimya sorularında denklemleri kusursuz kuran simyacı.",
            conditionLabel = "Kimya onaylı çözümü (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFF7C3AED,
            secondaryColorHex = 0xFFA78BFA,
            icon = Icons.Default.Science
        ),
        SharkBadgeDef(
            id = BADGE_BIYOLOJI_UZMANI,
            title = "Biyoloji Uzmanı",
            emoji = "🧬",
            description = "Sistemler, genetik ve ekoloji sorularında ezber bozan açıklamalar sunan uzman.",
            conditionLabel = "Biyoloji onaylı çözümü (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFF15803D,
            secondaryColorHex = 0xFF4ADE80,
            icon = Icons.Default.Psychology
        ),
        SharkBadgeDef(
            id = BADGE_PARAGRAF_AVCISI,
            title = "Paragraf Avcısı",
            emoji = "📖",
            description = "Türkçe, Paragraf ve Edebiyat sorularında ana düşünceyi saniyeler içinde yakalayan okuyucu.",
            conditionLabel = "Türkçe/Paragraf çözümü (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFFBE185D,
            secondaryColorHex = 0xFFF472B6,
            icon = Icons.Default.School
        ),
        SharkBadgeDef(
            id = BADGE_SOSYAL_BILGE,
            title = "Sosyal Bilge",
            emoji = "🌍",
            description = "Tarih, Coğrafya, Felsefe ve Din Kültürü sorularını berrak biçimde yorumlayan bilge.",
            conditionLabel = "Sosyal Bilimler çözümü (1 / 5 / 15 / 30)",
            thresholds = listOf(1, 5, 15, 30),
            primaryColorHex = 0xFF0F766E,
            secondaryColorHex = 0xFF5EEAD4,
            icon = Icons.Default.Public
        )
    )

    private val BADGE_MAP: Map<String, SharkBadgeDef> = ALL_BADGES.associateBy { it.id }

    fun findById(id: String?): SharkBadgeDef? {
        if (id.isNullOrBlank()) return null
        val normalizedId = id.trim().substringBefore(":")
        return BADGE_MAP[normalizedId]
    }

    fun subjectBadgeIdFor(subject: String): String? {
        val lower = subject.trim()
            .lowercase(Locale("tr"))
            .replace('ı', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
        return when {
            lower.contains("mat") ||
                lower.contains("turev") ||
                lower.contains("integral") ||
                lower.contains("geometri") ||
                lower.contains("geo") ||
                lower.contains("sayisal") -> BADGE_MATEMATIK_CANAVARI

            lower.contains("fizik") -> BADGE_FIZIK_KASIFI
            lower.contains("kimya") -> BADGE_KIMYA_SIMYACISI
            lower.contains("biyoloji") || lower.contains("biyo") -> BADGE_BIYOLOJI_UZMANI
            lower.contains("turkce") ||
                lower.contains("paragraf") ||
                lower.contains("edebiyat") ||
                lower.contains("dil bilgisi") -> BADGE_PARAGRAF_AVCISI

            lower.contains("tarih") ||
                lower.contains("cografya") ||
                lower.contains("felsefe") ||
                lower.contains("din") ||
                lower.contains("sosyal") ||
                lower.contains("inkilap") -> BADGE_SOSYAL_BILGE

            else -> null
        }
    }

    fun currentIsoWeekKey(nowMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(Locale.US).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = nowMillis
        }
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        val month = cal.get(Calendar.MONTH)
        var year = cal.get(Calendar.YEAR)
        if (month == Calendar.JANUARY && week >= 52) {
            year -= 1
        } else if (month == Calendar.DECEMBER && week == 1) {
            year += 1
        }
        return String.format(Locale.US, "%04d-W%02d", year, week)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getShowcaseBadgeIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SHOWCASE_IDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim().substringBefore(":") }
            .filter { it.isNotBlank() && BADGE_MAP.containsKey(it) }
            .distinct()
            .take(MAX_SHOWCASE_BADGES)
    }

    fun saveShowcaseBadgeIds(context: Context, badgeIds: List<String>): List<String> {
        val cleaned = badgeIds
            .map { it.trim().substringBefore(":") }
            .filter { it.isNotBlank() && BADGE_MAP.containsKey(it) }
            .distinct()
            .take(MAX_SHOWCASE_BADGES)
        prefs(context).edit()
            .putString(KEY_SHOWCASE_IDS, cleaned.joinToString(","))
            .apply()
        return cleaned
    }

    fun getUnlockedBadgeIds(context: Context): Set<String> {
        val stored = prefs(context).getStringSet(KEY_UNLOCKED_IDS, emptySet()).orEmpty()
        val fromProgress = getBadgeProgressCounts(context)
            .filter { (id, count) ->
                val def = BADGE_MAP[id]
                def != null && count >= (def.thresholds.firstOrNull() ?: 1)
            }
            .keys
        return (stored.map { it.substringBefore(":") }.filter { BADGE_MAP.containsKey(it) } + fromProgress).toSet()
    }

    fun getBadgeProgressCounts(context: Context): Map<String, Int> {
        val raw = prefs(context).getString(KEY_BADGE_PROGRESS, "").orEmpty()
        return parseCountMap(raw).filterKeys { BADGE_MAP.containsKey(it) }
    }

    private fun saveBadgeProgressCounts(context: Context, map: Map<String, Int>) {
        val clean = map.filter { (k, v) -> BADGE_MAP.containsKey(k) && v > 0 }
        prefs(context).edit()
            .putString(KEY_BADGE_PROGRESS, serializeCountMap(clean))
            .apply()
    }

    fun parseRemoteProgressMap(raw: Any?): Map<String, Int> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val result = mutableMapOf<String, Int>()
        map.forEach { (k, v) ->
            val key = k?.toString()?.trim().orEmpty()
            val count = (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull()
            if (BADGE_MAP.containsKey(key) && count != null && count > 0) {
                result[key] = count
            }
        }
        return result
    }

    fun mergeBadgeProgress(context: Context, remote: Map<String, Int>): Map<String, Int> {
        val local = getBadgeProgressCounts(context).toMutableMap()
        val unlockedNow = getUnlockedBadgeIds(context).toMutableSet()
        remote.forEach { (id, remoteCount) ->
            if (!BADGE_MAP.containsKey(id)) return@forEach
            val nextCount = maxOf(local[id] ?: 0, remoteCount)
            if (nextCount > 0) {
                local[id] = nextCount
                val firstThreshold = BADGE_MAP[id]?.thresholds?.firstOrNull() ?: 1
                if (nextCount >= firstThreshold) {
                    unlockedNow.add(id)
                }
            }
        }
        saveBadgeProgressCounts(context, local)
        prefs(context).edit().putStringSet(KEY_UNLOCKED_IDS, unlockedNow).apply()
        autoPopulateShowcaseIfRoom(context, unlockedNow)
        return local
    }

    fun incrementBadgeProgressLocally(
        context: Context,
        badgeId: String,
        delta: Int = 1
    ): Map<String, Int> {
        val def = BADGE_MAP[badgeId] ?: return getBadgeProgressCounts(context)
        val currentMap = getBadgeProgressCounts(context).toMutableMap()
        val nextCount = ((currentMap[badgeId] ?: 0) + delta).coerceAtLeast(0)
        currentMap[badgeId] = nextCount
        saveBadgeProgressCounts(context, currentMap)
        if (nextCount >= (def.thresholds.firstOrNull() ?: 1)) {
            unlockBadge(context, badgeId)
        }
        return currentMap
    }

    fun unlockBadge(context: Context, badgeId: String): Set<String> {
        val cleanId = badgeId.trim().substringBefore(":")
        val def = BADGE_MAP[cleanId] ?: return getUnlockedBadgeIds(context)
        val current = getUnlockedBadgeIds(context).toMutableSet()
        val added = current.add(cleanId)
        val counts = getBadgeProgressCounts(context).toMutableMap()
        val minThresh = def.thresholds.firstOrNull() ?: 1
        if ((counts[cleanId] ?: 0) < minThresh) {
            counts[cleanId] = minThresh
            saveBadgeProgressCounts(context, counts)
        }
        if (added) {
            prefs(context).edit().putStringSet(KEY_UNLOCKED_IDS, current).apply()
            autoPopulateShowcaseIfRoom(context, listOf(cleanId))
        }
        return current
    }

    fun mergeUnlockedBadges(context: Context, remoteUnlocked: Collection<String>): Set<String> {
        val cleanRemote = remoteUnlocked.map { it.trim().substringBefore(":") }.filter { BADGE_MAP.containsKey(it) }
        val merged = (getUnlockedBadgeIds(context) + cleanRemote).toSet()
        prefs(context).edit().putStringSet(KEY_UNLOCKED_IDS, merged).apply()
        return merged
    }

    private fun autoPopulateShowcaseIfRoom(context: Context, candidateIds: Collection<String>) {
        val currentShowcase = getShowcaseBadgeIds(context).toMutableList()
        var changed = false
        for (id in candidateIds) {
            if (currentShowcase.size >= MAX_SHOWCASE_BADGES) break
            if (BADGE_MAP.containsKey(id) && !currentShowcase.contains(id)) {
                currentShowcase.add(id)
                changed = true
            }
        }
        if (changed) {
            saveShowcaseBadgeIds(context, currentShowcase)
        }
    }

    fun getMentorPoints(context: Context): Int =
        prefs(context).getInt(KEY_MENTOR_POINTS, 0).coerceAtLeast(0)

    fun saveMentorPoints(context: Context, points: Int): Int {
        val safe = points.coerceAtLeast(0)
        prefs(context).edit().putInt(KEY_MENTOR_POINTS, safe).apply()
        return safe
    }

    fun addMentorPoints(context: Context, delta: Int): Int {
        val next = (getMentorPoints(context) + delta).coerceAtLeast(0)
        prefs(context).edit().putInt(KEY_MENTOR_POINTS, next).apply()
        return next
    }

    fun getWeeklyBonusSeconds(
        context: Context,
        weekKey: String = currentIsoWeekKey()
    ): Long = prefs(context).getLong("$KEY_WEEKLY_BONUS_PREFIX$weekKey", 0L).coerceAtLeast(0L)

    fun saveWeeklyBonusSeconds(
        context: Context,
        seconds: Long,
        weekKey: String = currentIsoWeekKey()
    ): Long {
        val safe = seconds.coerceAtLeast(0L)
        prefs(context).edit().putLong("$KEY_WEEKLY_BONUS_PREFIX$weekKey", safe).apply()
        return safe
    }

    fun addWeeklyBonusSeconds(
        context: Context,
        deltaSeconds: Long,
        weekKey: String = currentIsoWeekKey()
    ): Long {
        val next = (getWeeklyBonusSeconds(context, weekKey) + deltaSeconds).coerceAtLeast(0L)
        prefs(context).edit().putLong("$KEY_WEEKLY_BONUS_PREFIX$weekKey", next).apply()
        return next
    }

    fun parseWeeklyBonusForWeek(rawField: Any?, weekKey: String): Long {
        val map = rawField as? Map<*, *> ?: return 0L
        return ((map[weekKey] as? Number)?.toLong() ?: map[weekKey]?.toString()?.toLongOrNull() ?: 0L)
            .coerceAtLeast(0L)
    }

    fun recordQuestionSharedLocally(
        context: Context,
        @Suppress("UNUSED_PARAMETER") createdAtMillis: Long = System.currentTimeMillis()
    ): Set<String> {
        incrementBadgeProgressLocally(context, BADGE_ILK_SORU, 1)
        incrementBadgeProgressLocally(context, BADGE_SORU_CANAVARI, 1)
        return getUnlockedBadgeIds(context)
    }

    fun recordApprovedSolutionLocally(
        context: Context,
        subject: String,
        solveElapsedMs: Long,
        @Suppress("UNUSED_PARAMETER") createdAtMillis: Long = System.currentTimeMillis()
    ): Set<String> {
        incrementBadgeProgressLocally(context, BADGE_ILK_COZUM, 1)
        incrementBadgeProgressLocally(context, BADGE_SORU_AVCISI, 1)

        if (solveElapsedMs in 0L..(15L * 60L * 1_000L)) {
            incrementBadgeProgressLocally(context, BADGE_HIZLI_KOPEKBALIGI, 1)
        }
        if (solveElapsedMs in (60L * 60L * 1_000L)..(105L * 60L * 1_000L)) {
            incrementBadgeProgressLocally(context, BADGE_CAN_SIMIDI, 1)
        }

        subjectBadgeIdFor(subject)?.let { subjBadgeId ->
            incrementBadgeProgressLocally(context, subjBadgeId, 1)
        }
        return getUnlockedBadgeIds(context)
    }

    fun evaluateAutomaticUnlocks(
        context: Context,
        approvedSolutions: Int,
        badgeProgress: Map<String, Int> = emptyMap()
    ): Set<String> {
        if (badgeProgress.isNotEmpty()) {
            mergeBadgeProgress(context, badgeProgress)
        }
        if (approvedSolutions > 0) {
            val current = getBadgeProgressCounts(context).toMutableMap()
            var changed = false
            if ((current[BADGE_ILK_COZUM] ?: 0) < approvedSolutions) {
                current[BADGE_ILK_COZUM] = approvedSolutions
                changed = true
            }
            if ((current[BADGE_SORU_AVCISI] ?: 0) < approvedSolutions) {
                current[BADGE_SORU_AVCISI] = approvedSolutions
                changed = true
            }
            if (changed) {
                saveBadgeProgressCounts(context, current)
                mergeBadgeProgress(context, current)
            }
        }
        return getUnlockedBadgeIds(context)
    }

    fun syncShowcaseAndBadgesToCloud(
        context: Context,
        uid: String,
        showcaseIds: List<String> = getShowcaseBadgeIds(context),
        unlockedIds: Set<String> = getUnlockedBadgeIds(context),
        mentorPoints: Int = getMentorPoints(context),
        badgeProgress: Map<String, Int> = getBadgeProgressCounts(context),
        weeklyBonusSeconds: Long = getWeeklyBonusSeconds(context),
        weekKey: String = currentIsoWeekKey()
    ) {
        if (uid.isBlank() || uid == "local_student" || uid == "student_default") return
        val db = GoogleServicesHelper.getFirebaseFirestore(context) ?: return
        val cleanShowcase = showcaseIds
            .map { it.trim().substringBefore(":") }
            .filter { BADGE_MAP.containsKey(it) }
            .distinct()
            .take(MAX_SHOWCASE_BADGES)
        val cleanUnlocked = unlockedIds
            .map { it.trim().substringBefore(":") }
            .filter { BADGE_MAP.containsKey(it) }
            .distinct()
        val cleanProgress = badgeProgress.filter { (k, v) -> BADGE_MAP.containsKey(k) && v > 0 }
        val safeBonus = weeklyBonusSeconds.coerceAtLeast(0L)

        val studentUpdate = hashMapOf<String, Any>(
            "showcaseBadges" to cleanShowcase,
            "unlockedBadges" to cleanUnlocked,
            "badgeProgress" to cleanProgress,
            "mentorPoints" to mentorPoints,
            "weeklyBonusByWeek" to mapOf(weekKey to safeBonus)
        )
        runCatching {
            db.collection("students").document(uid)
                .set(studentUpdate, SetOptions.merge())
        }.onFailure {
            Log.w("SharkBadgeCatalog", "Failed syncing badges to students/$uid: ${it.message}")
        }

        val leagueUpdate = hashMapOf<String, Any>(
            "showcaseBadges" to cleanShowcase,
            "badgeProgress" to cleanProgress,
            "mentorPoints" to mentorPoints,
            "weeklyBonusByWeek" to mapOf(weekKey to safeBonus)
        )
        runCatching {
            db.collection("institutions").document("GLOBAL_INDIVIDUAL")
                .collection("weeklyLeague").document(uid)
                .set(leagueUpdate, SetOptions.merge())
        }
    }

    // ─── Moderation: Block & Report Helpers ──────────────────────────────────

    fun getBlockedUserIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCKED_UIDS, emptySet()).orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    fun mergeBlockedUserIds(context: Context, remoteBlocked: Collection<String>): Set<String> {
        val merged = (getBlockedUserIds(context) + remoteBlocked.map { it.trim() }.filter { it.isNotBlank() }).toSet()
        prefs(context).edit().putStringSet(KEY_BLOCKED_UIDS, merged).apply()
        return merged
    }

    suspend fun setUserBlocked(
        context: Context,
        myUid: String,
        targetUid: String,
        blocked: Boolean
    ): Set<String> {
        val cleanTarget = targetUid.trim()
        if (cleanTarget.isBlank() || cleanTarget == myUid) return getBlockedUserIds(context)
        val updated = getBlockedUserIds(context).toMutableSet().apply {
            if (blocked) add(cleanTarget) else remove(cleanTarget)
        }
        prefs(context).edit().putStringSet(KEY_BLOCKED_UIDS, updated).apply()

        val db = GoogleServicesHelper.getFirebaseFirestore(context)
        if (db != null && myUid.isNotBlank() && myUid != "local_student" && myUid != "student_default") {
            runCatching {
                val fieldUpdate = if (blocked) {
                    FieldValue.arrayUnion(cleanTarget)
                } else {
                    FieldValue.arrayRemove(cleanTarget)
                }
                db.collection("students").document(myUid)
                    .set(mapOf("blockedUserIds" to fieldUpdate), SetOptions.merge())
                    .await()
            }.onFailure {
                Log.w("SharkBadgeCatalog", "Blocked users cloud sync warning: ${it.message}")
            }
        }
        return updated
    }

    suspend fun submitUserReport(
        context: Context,
        reporterUid: String,
        reporterName: String,
        targetUid: String,
        targetName: String,
        reason: String,
        details: String,
        source: String
    ): Boolean {
        val db = GoogleServicesHelper.getFirebaseFirestore(context) ?: return false
        if (reporterUid.isBlank() || reporterUid == "local_student" || reporterUid == "student_default") {
            return false
        }
        val cleanTarget = targetUid.trim()
        if (cleanTarget.isBlank()) return false
        return runCatching {
            val now = System.currentTimeMillis()
            val payload = hashMapOf<String, Any>(
                "reporterUid" to reporterUid,
                "reporterName" to reporterName.ifBlank { "Öğrenci" },
                "reportedUid" to cleanTarget,
                "reportedName" to targetName.ifBlank { "Öğrenci" },
                "reason" to reason.ifBlank { "Uygunsuz davranış" },
                "details" to details.trim(),
                "source" to source,
                "status" to "OPEN",
                "createdAtMillis" to now,
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.collection("user_reports").add(payload).await()
            true
        }.getOrElse {
            Log.w("SharkBadgeCatalog", "submitUserReport failed: ${it.message}")
            false
        }
    }

    private fun parseCountMap(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val count = parts[1].trim().toIntOrNull()
                if (key.isNotBlank() && count != null) key to count else null
            } else null
        }.toMap()
    }

    private fun serializeCountMap(map: Map<String, Int>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }
}

@Composable
fun SharkBadgeChip(
    badge: SharkBadgeDef,
    tier: BadgeTier? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeTier = tier ?: BadgeTier.BRONZE
    val tierColor = Color(activeTier.primaryColorHex)
    val startColor = Color(badge.primaryColorHex)
    val endColor = Color(badge.secondaryColorHex)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 10.dp else 12.dp),
        color = startColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tierColor.copy(alpha = 0.52f))
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 7.dp else 10.dp,
                vertical = if (compact) 3.dp else 5.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 16.dp else 20.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(startColor, endColor))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = badge.icon,
                    contentDescription = badge.title,
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 10.dp else 12.dp)
                )
            }
            Text(
                text = if (compact) "${activeTier.emoji} ${badge.title}" else "${activeTier.emoji} ${badge.title} (${activeTier.shortLabel})",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 10.sp else 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharkBadgeShowcaseRow(
    badgeIds: List<String>,
    compact: Boolean = false,
    badgeProgress: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val badges = badgeIds
        .mapNotNull { SharkBadgeCatalog.findById(it) }
        .distinctBy { it.id }
        .take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
    if (badges.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
    ) {
        badges.forEach { badge ->
            val progressInfo = badge.progressFor(
                rawCount = badgeProgress[badge.id] ?: 0,
                isLegacyUnlocked = true
            )
            SharkBadgeChip(
                badge = badge,
                tier = progressInfo.unlockedTier ?: BadgeTier.BRONZE,
                compact = compact
            )
        }
    }
}
