package com.example.sharkmentor.core.badges

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Science
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
import com.google.firebase.firestore.SetOptions
import java.util.Calendar

@Immutable
data class SharkBadgeDef(
    val id: String,
    val title: String,
    val emoji: String,
    val description: String,
    val conditionLabel: String,
    val primaryColorHex: Long,
    val secondaryColorHex: Long,
    val icon: ImageVector
)

object SharkBadgeCatalog {

    const val MAX_SHOWCASE_BADGES = 3

    private const val PREFS_NAME = "shark_badges_prefs"
    private const val KEY_SHOWCASE_IDS = "showcase_badge_ids"
    private const val KEY_UNLOCKED_IDS = "unlocked_badge_ids"
    private const val KEY_MENTOR_POINTS = "mentor_points"
    private const val KEY_APPROVED_SOLUTIONS = "approved_solutions_count"
    private const val KEY_SUBJECT_COUNTS = "approved_subject_counts"

    const val BADGE_ILK_IPUCU = "ilk_ipucu"
    const val BADGE_HIZIR_ACIL = "hizir_acil"
    const val BADGE_GECE_NOBETCISI = "gece_nobetcisi"
    const val BADGE_KONU_DOKTORU = "konu_doktoru"
    const val BADGE_GRUP_KAPTANI = "grup_kaptani"
    const val BADGE_YKS_CERRAHI = "yks_cerrahi"
    const val BADGE_SHARK_EFSANESI = "shark_efsanesi"

    val ALL_BADGES: List<SharkBadgeDef> = listOf(
        SharkBadgeDef(
            id = BADGE_ILK_IPUCU,
            title = "İlk İpucu",
            emoji = "💡",
            description = "Soru sohbetinde ilk çözüm katkını veya paylaşımını yapan öncü Mentor.",
            conditionLabel = "1 çözüm gönder veya 1 soru paylaş",
            primaryColorHex = 0xFF0284C7,
            secondaryColorHex = 0xFF38BDF8,
            icon = Icons.Default.Lightbulb
        ),
        SharkBadgeDef(
            id = BADGE_HIZIR_ACIL,
            title = "Hızır Acil",
            emoji = "⚡",
            description = "İlk 35 dakikada %100 Altın Dilim ödülüyle onaylı soru çözen hız ustası.",
            conditionLabel = "Altın Dilim (%100) içinde 1 onaylı çözüm",
            primaryColorHex = 0xFFD97706,
            secondaryColorHex = 0xFFFBBF24,
            icon = Icons.Default.Bolt
        ),
        SharkBadgeDef(
            id = BADGE_GECE_NOBETCISI,
            title = "Gece Nöbetçisi",
            emoji = "🦉",
            description = "Gece 23:00 - 05:00 arasında bile arkadaşlarını yalnız bırakmayan baykuş.",
            conditionLabel = "23:00–05:00 arası soru paylaş veya çöz",
            primaryColorHex = 0xFF4338CA,
            secondaryColorHex = 0xFF818CF8,
            icon = Icons.Default.Psychology
        ),
        SharkBadgeDef(
            id = BADGE_KONU_DOKTORU,
            title = "Konu Doktoru",
            emoji = "🩺",
            description = "Aynı branşta üst üste nitelikli çözümler üreten alan uzmanı.",
            conditionLabel = "Aynı dersten 5 onaylı çözüm üret",
            primaryColorHex = 0xFF0D9488,
            secondaryColorHex = 0xFF2DD4BF,
            icon = Icons.Default.Science
        ),
        SharkBadgeDef(
            id = BADGE_GRUP_KAPTANI,
            title = "Grup Kaptanı",
            emoji = "🧭",
            description = "Çalışma grubu kuran veya takım arkadaşlarını odak masasına toplayan lider.",
            conditionLabel = "1 çalışma grubu kur veya 3 davet gönder",
            primaryColorHex = 0xFF7C3AED,
            secondaryColorHex = 0xFFA78BFA,
            icon = Icons.Default.GroupAdd
        ),
        SharkBadgeDef(
            id = BADGE_YKS_CERRAHI,
            title = "YKS Cerrahı",
            emoji = "🎯",
            description = "10+ onaylı çözüm ve keskin disipliniyle zorlu sorulara neşter vuran usta.",
            conditionLabel = "Toplam 10 onaylı çözüm tamamla",
            primaryColorHex = 0xFFDC2626,
            secondaryColorHex = 0xFFF87171,
            icon = Icons.Default.TrackChanges
        ),
        SharkBadgeDef(
            id = BADGE_SHARK_EFSANESI,
            title = "Shark Efsanesi",
            emoji = "👑",
            description = "Topluluğun en güvenilen süper mentorlarından biri — zirvenin sahibi.",
            conditionLabel = "25 onaylı çözüm veya 1000 Mentor Puanı",
            primaryColorHex = 0xFFB45309,
            secondaryColorHex = 0xFFFDE047,
            icon = Icons.Default.Star
        )
    )

    private val BADGE_MAP: Map<String, SharkBadgeDef> = ALL_BADGES.associateBy { it.id }

    fun findById(id: String?): SharkBadgeDef? {
        if (id.isNullOrBlank()) return null
        return BADGE_MAP[id.trim()]
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getShowcaseBadgeIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SHOWCASE_IDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && BADGE_MAP.containsKey(it) }
            .distinct()
            .take(MAX_SHOWCASE_BADGES)
    }

    fun saveShowcaseBadgeIds(context: Context, badgeIds: List<String>): List<String> {
        val cleaned = badgeIds
            .map { it.trim() }
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
        return stored.filter { BADGE_MAP.containsKey(it) }.toSet()
    }

    fun unlockBadge(context: Context, badgeId: String): Set<String> {
        if (!BADGE_MAP.containsKey(badgeId)) return getUnlockedBadgeIds(context)
        val current = getUnlockedBadgeIds(context).toMutableSet()
        val added = current.add(badgeId)
        if (added) {
            prefs(context).edit().putStringSet(KEY_UNLOCKED_IDS, current).apply()
            val showcase = getShowcaseBadgeIds(context)
            if (showcase.size < MAX_SHOWCASE_BADGES && !showcase.contains(badgeId)) {
                saveShowcaseBadgeIds(context, showcase + badgeId)
            }
        }
        return current
    }

    fun mergeUnlockedBadges(context: Context, remoteUnlocked: Collection<String>): Set<String> {
        val merged = (getUnlockedBadgeIds(context) + remoteUnlocked.filter { BADGE_MAP.containsKey(it) }).toSet()
        prefs(context).edit().putStringSet(KEY_UNLOCKED_IDS, merged).apply()
        return merged
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
        if (next >= 1000) {
            unlockBadge(context, BADGE_SHARK_EFSANESI)
        }
        return next
    }

    fun recordApprovedSolutionLocally(
        context: Context,
        subject: String,
        isGoldTier: Boolean,
        createdAtMillis: Long = System.currentTimeMillis()
    ): Set<String> {
        val p = prefs(context)
        val totalSolutions = p.getInt(KEY_APPROVED_SOLUTIONS, 0) + 1
        p.edit().putInt(KEY_APPROVED_SOLUTIONS, totalSolutions).apply()

        unlockBadge(context, BADGE_ILK_IPUCU)
        if (isGoldTier) {
            unlockBadge(context, BADGE_HIZIR_ACIL)
        }
        if (isNightShiftHour(createdAtMillis)) {
            unlockBadge(context, BADGE_GECE_NOBETCISI)
        }

        val cleanSubj = subject.trim().lowercase().ifBlank { "genel" }
        val subjectMap = parseCountMap(p.getString(KEY_SUBJECT_COUNTS, "").orEmpty()).toMutableMap()
        val nextSubjCount = (subjectMap[cleanSubj] ?: 0) + 1
        subjectMap[cleanSubj] = nextSubjCount
        p.edit().putString(KEY_SUBJECT_COUNTS, serializeCountMap(subjectMap)).apply()

        if (nextSubjCount >= 5) {
            unlockBadge(context, BADGE_KONU_DOKTORU)
        }
        if (totalSolutions >= 10) {
            unlockBadge(context, BADGE_YKS_CERRAHI)
        }
        if (totalSolutions >= 25 || getMentorPoints(context) >= 1000) {
            unlockBadge(context, BADGE_SHARK_EFSANESI)
        }
        return getUnlockedBadgeIds(context)
    }

    fun evaluateAutomaticUnlocks(
        context: Context,
        mentorPoints: Int,
        approvedSolutions: Int,
        isGroupCreator: Boolean,
        activityTimestampMillis: Long? = null
    ): Set<String> {
        if (approvedSolutions >= 1) unlockBadge(context, BADGE_ILK_IPUCU)
        if (isGroupCreator) unlockBadge(context, BADGE_GRUP_KAPTANI)
        if (approvedSolutions >= 10) unlockBadge(context, BADGE_YKS_CERRAHI)
        if (approvedSolutions >= 25 || mentorPoints >= 1000) unlockBadge(context, BADGE_SHARK_EFSANESI)
        if (activityTimestampMillis != null && isNightShiftHour(activityTimestampMillis)) {
            unlockBadge(context, BADGE_GECE_NOBETCISI)
        }
        return getUnlockedBadgeIds(context)
    }

    fun isNightShiftHour(timestampMillis: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 5
    }

    fun syncShowcaseAndBadgesToCloud(
        context: Context,
        uid: String,
        showcaseIds: List<String> = getShowcaseBadgeIds(context),
        unlockedIds: Set<String> = getUnlockedBadgeIds(context),
        mentorPoints: Int = getMentorPoints(context)
    ) {
        if (uid.isBlank() || uid == "local_student" || uid == "student_default") return
        val db = GoogleServicesHelper.getFirebaseFirestore(context) ?: return
        val cleanShowcase = showcaseIds.filter { BADGE_MAP.containsKey(it) }.distinct().take(MAX_SHOWCASE_BADGES)
        val cleanUnlocked = unlockedIds.filter { BADGE_MAP.containsKey(it) }.distinct()

        val studentUpdate = mapOf(
            "showcaseBadges" to cleanShowcase,
            "unlockedBadges" to cleanUnlocked,
            "mentorPoints" to mentorPoints
        )
        runCatching {
            db.collection("students").document(uid)
                .set(studentUpdate, SetOptions.merge())
        }.onFailure {
            Log.w("SharkBadgeCatalog", "Failed syncing badges to students/$uid: ${it.message}")
        }

        val leagueUpdate = mapOf(
            "showcaseBadges" to cleanShowcase,
            "mentorPoints" to mentorPoints
        )
        runCatching {
            db.collection("institutions").document("GLOBAL_INDIVIDUAL")
                .collection("weeklyLeague").document(uid)
                .set(leagueUpdate, SetOptions.merge())
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
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val startColor = Color(badge.primaryColorHex)
    val endColor = Color(badge.secondaryColorHex)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 10.dp else 12.dp),
        color = startColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, startColor.copy(alpha = 0.42f))
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
                text = if (compact) badge.title else "${badge.emoji} ${badge.title}",
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

@Composable
fun SharkBadgeShowcaseRow(
    badgeIds: List<String>,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val badges = badgeIds.mapNotNull { SharkBadgeCatalog.findById(it) }.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
    if (badges.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badges.forEach { badge ->
            SharkBadgeChip(
                badge = badge,
                compact = compact
            )
        }
    }
}
