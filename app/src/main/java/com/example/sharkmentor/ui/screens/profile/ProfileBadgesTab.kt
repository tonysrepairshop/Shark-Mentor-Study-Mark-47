package com.example.sharkmentor.ui.screens.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharkmentor.core.badges.BadgeCategory as CoreBadgeCategory
import com.example.sharkmentor.core.badges.BadgeProgressInfo
import com.example.sharkmentor.core.badges.BadgeTier
import com.example.sharkmentor.core.badges.SharkBadgeCatalog
import com.example.sharkmentor.core.badges.SharkBadgeChip
import com.example.sharkmentor.core.badges.SharkBadgeDef
import com.example.sharkmentor.core.badges.SharkBadgeSymbolIcon
import java.util.Locale

/**
 * Rozet kategori filtreleri ("Tümü", "Matematik/Geo", "Fizik", "Kimya", "Biyoloji",
 * "Türkçe/Edebiyat", "Sosyal/Dil", "Mentorluk").
 */
enum class ProfileBadgeCategoryFilter(
    val label: String,
    val emoji: String
) {
    ALL("Tümü", "🏅"),
    MATH_GEO(CoreBadgeCategory.MATH_GEO.shortTitle, CoreBadgeCategory.MATH_GEO.emoji),
    PHYSICS(CoreBadgeCategory.PHYSICS.shortTitle, CoreBadgeCategory.PHYSICS.emoji),
    CHEMISTRY(CoreBadgeCategory.CHEMISTRY.shortTitle, CoreBadgeCategory.CHEMISTRY.emoji),
    BIOLOGY(CoreBadgeCategory.BIOLOGY.shortTitle, CoreBadgeCategory.BIOLOGY.emoji),
    TURKISH_LIT(CoreBadgeCategory.TURKISH_LIT.shortTitle, CoreBadgeCategory.TURKISH_LIT.emoji),
    SOCIAL_LANG(CoreBadgeCategory.SOCIAL_LANG.shortTitle, CoreBadgeCategory.SOCIAL_LANG.emoji),
    MENTORSHIP(CoreBadgeCategory.MENTORSHIP.shortTitle, CoreBadgeCategory.MENTORSHIP.emoji)
}

/**
 * Kazanım durumu filtresi.
 */
enum class BadgeStatusFilter(val label: String) {
    ALL("Tümü"),
    UNLOCKED_ONLY("Sadece Kazanılanlar"),
    IN_PROGRESS("Devam Edenler")
}

/**
 * Profil Ekranı — "Rozetler" Sekmesi.
 * - Üst Özet Kartı: Toplam kazanılan rozet, Megalodon/Altın/Gümüş/Bronz sayıları, Mentor Puanı,
 *   5'li vitrin önizlemesi ve "Vitrini Düzenle (Maks. 5)" butonu.
 * - Yatay kaydırılabilir kategori FilterChip çubuğu + durum filtreleri + konu arama kutusu.
 * - 2 sütunlu ızgara rozet kartları (özel sembol madalyonu, başlık, aktif kademe/kilit,
 *   LinearProgressIndicator ve sayısal ilerleme).
 * - Tıklanan rozet için 4 kademe yol haritalı ve doğrudan Vitrine Ekle/Çıkar destekli detay penceresi.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileBadgesTab(
    unlockedBadgeIds: Set<String>,
    showcaseBadgeIds: List<String>,
    badgeProgress: Map<String, Int>,
    mentorPoints: Int,
    onOpenShowcaseEditor: () -> Unit,
    onUpdateShowcaseIds: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allBadges = SharkBadgeCatalog.ALL_BADGES

    val badgeProgressItems = remember(allBadges, badgeProgress, unlockedBadgeIds) {
        allBadges.map { badge ->
            val rawCount = badgeProgress[badge.id] ?: 0
            badge.progressFor(
                rawCount = rawCount,
                isLegacyUnlocked = rawCount <= 0 && unlockedBadgeIds.contains(badge.id)
            )
        }
    }

    val effectiveUnlockedIds = remember(badgeProgressItems) {
        badgeProgressItems
            .filter { it.unlockedTier != null }
            .map { it.badge.id }
            .toSet()
    }

    val tierCounts = remember(badgeProgressItems) {
        var megalodon = 0
        var gold = 0
        var silver = 0
        var bronze = 0
        badgeProgressItems.forEach { info ->
            when (info.unlockedTier) {
                BadgeTier.MEGALODON -> megalodon++
                BadgeTier.GOLD -> gold++
                BadgeTier.SILVER -> silver++
                BadgeTier.BRONZE -> bronze++
                null -> Unit
            }
        }
        TierBreakdownCounts(
            megalodon = megalodon,
            gold = gold,
            silver = silver,
            bronze = bronze
        )
    }

    var selectedCategory by rememberSaveable { mutableStateOf(ProfileBadgeCategoryFilter.ALL) }
    var selectedStatusFilter by rememberSaveable { mutableStateOf(BadgeStatusFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var inspectedBadgeInfo by remember { mutableStateOf<BadgeProgressInfo?>(null) }

    val filteredBadgeItems = remember(
        badgeProgressItems,
        selectedCategory,
        selectedStatusFilter,
        searchQuery
    ) {
        val normalizedQuery = normalizeTurkishSearch(searchQuery)
        badgeProgressItems.filter { info ->
            val badgeFilterCategory = resolveBadgeCategory(info.badge)
            val categoryMatch = selectedCategory == ProfileBadgeCategoryFilter.ALL ||
                badgeFilterCategory == selectedCategory

            val statusMatch = when (selectedStatusFilter) {
                BadgeStatusFilter.ALL -> true
                BadgeStatusFilter.UNLOCKED_ONLY -> info.unlockedTier != null
                BadgeStatusFilter.IN_PROGRESS -> info.unlockedTier != BadgeTier.MEGALODON
            }

            val queryMatch = if (normalizedQuery.isBlank()) {
                true
            } else {
                val haystack = normalizeTurkishSearch(
                    "${info.badge.id} ${info.badge.title} ${info.badge.description} ${info.badge.conditionLabel} ${info.badge.symbolText} ${info.badge.category.title} ${badgeFilterCategory.label}"
                )
                haystack.contains(normalizedQuery)
            }

            categoryMatch && statusMatch && queryMatch
        }
    }

    val badgeRows = remember(filteredBadgeItems) {
        filteredBadgeItems.chunked(2)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Üst Özet Kartı: Toplam Rozet, Megalodon/Altın/Gümüş/Bronz, Mentor Puanı & Vitrini Düzenle
        item(key = "badges_summary_header") {
            BadgesSummaryHeaderCard(
                totalUnlocked = effectiveUnlockedIds.size,
                totalCatalogSize = allBadges.size,
                tierCounts = tierCounts,
                mentorPoints = mentorPoints,
                showcaseBadgeIds = showcaseBadgeIds,
                badgeProgress = badgeProgress,
                onOpenShowcaseEditor = onOpenShowcaseEditor
            )
        }

        // 2. Arama Kutusu + Kategori FilterChip Çubuğu + Durum Filtreleri
        item(key = "badges_filter_controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Konu veya rozet ara (örn. Türev, İntegral, Optik, Paragraf)...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Rozet Ara",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Aramayı Temizle"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Yatay kaydırılabilir Kategori FilterChip çubuğu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProfileBadgeCategoryFilter.entries.forEach { category ->
                        val isSelected = selectedCategory == category
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = category },
                            label = {
                                Text(
                                    text = "${category.emoji} ${category.label}",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                // Durum Filtresi: Tümü / Sadece Kazanılanlar / Devam Edenler + Sonuç Sayısı
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BadgeStatusFilter.entries.forEach { statusFilter ->
                        val isSelected = selectedStatusFilter == statusFilter
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedStatusFilter = statusFilter },
                            label = {
                                Text(
                                    text = statusFilter.label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.5.sp
                                )
                            }
                        )
                    }

                    Text(
                        text = "${filteredBadgeItems.size} rozet gösteriliyor",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        // 3. 2 Sütunlu Izgara Rozet Kartları
        if (badgeRows.isEmpty()) {
            item(key = "empty_badges_state") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Arama veya filtreye uygun rozet bulunamadı",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Farklı bir ders kategorisi seçebilir veya arama kutusunu temizleyebilirsin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(
                items = badgeRows,
                key = { row -> row.joinToString("_") { it.badge.id } }
            ) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { progressInfo ->
                        val isShowcased = showcaseBadgeIds.contains(progressInfo.badge.id)
                        BadgeGridCard(
                            progressInfo = progressInfo,
                            isShowcased = isShowcased,
                            onClick = { inspectedBadgeInfo = progressInfo },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    // 4. Rozete Tıklayınca Açılan Detay Penceresi (ModalBottomSheet)
    val activeInspected = inspectedBadgeInfo?.let { current ->
        badgeProgressItems.find { it.badge.id == current.badge.id } ?: current
    }
    if (activeInspected != null) {
        val isInShowcase = showcaseBadgeIds.contains(activeInspected.badge.id)
        BadgeDetailModalSheet(
            progressInfo = activeInspected,
            isShowcased = isInShowcase,
            showcaseCount = showcaseBadgeIds.size,
            onDismiss = { inspectedBadgeInfo = null },
            onToggleShowcase = {
                val badgeId = activeInspected.badge.id
                if (activeInspected.unlockedTier == null) {
                    Toast.makeText(
                        context,
                        "Bu rozeti vitrine eklemek için önce Bronz kademeyi açmalısın.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@BadgeDetailModalSheet
                }

                val currentList = showcaseBadgeIds.toMutableList()
                if (currentList.contains(badgeId)) {
                    currentList.remove(badgeId)
                    onUpdateShowcaseIds(currentList)
                    Toast.makeText(
                        context,
                        "\"${activeInspected.badge.title}\" vitrinden çıkarıldı.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    if (currentList.size >= SharkBadgeCatalog.MAX_SHOWCASE_BADGES) {
                        Toast.makeText(
                            context,
                            "Vitrine en fazla ${SharkBadgeCatalog.MAX_SHOWCASE_BADGES} rozet ekleyebilirsin. Önce bir rozeti çıkar.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        currentList.add(badgeId)
                        onUpdateShowcaseIds(currentList)
                        Toast.makeText(
                            context,
                            "\"${activeInspected.badge.title}\" vitrine eklendi! (${currentList.size}/${SharkBadgeCatalog.MAX_SHOWCASE_BADGES})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}

private data class TierBreakdownCounts(
    val megalodon: Int,
    val gold: Int,
    val silver: Int,
    val bronze: Int
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgesSummaryHeaderCard(
    totalUnlocked: Int,
    totalCatalogSize: Int,
    tierCounts: TierBreakdownCounts,
    mentorPoints: Int,
    showcaseBadgeIds: List<String>,
    badgeProgress: Map<String, Int>,
    onOpenShowcaseEditor: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Rozet Koleksiyonu & Mentorluk",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "$totalUnlocked / $totalCatalogSize Rozet Açıldı • $mentorPoints Mentor Puanı",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = onOpenShowcaseEditor,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Vitrini Düzenle (Maks. 5)",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Genel koleksiyon ilerleme çubuğu
            val overallFraction = (totalUnlocked.toFloat() / totalCatalogSize.coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { overallFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // 4 Kademe Sayısal Dağılım Kutucukları (Megalodon / Altın / Gümüş / Bronz)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TierCounterStatBox(
                    emoji = BadgeTier.MEGALODON.emoji,
                    label = "Megalodon",
                    count = tierCounts.megalodon,
                    color = Color(BadgeTier.MEGALODON.primaryColorHex),
                    modifier = Modifier.weight(1f)
                )
                TierCounterStatBox(
                    emoji = BadgeTier.GOLD.emoji,
                    label = "Altın",
                    count = tierCounts.gold,
                    color = Color(BadgeTier.GOLD.primaryColorHex),
                    modifier = Modifier.weight(1f)
                )
                TierCounterStatBox(
                    emoji = BadgeTier.SILVER.emoji,
                    label = "Gümüş",
                    count = tierCounts.silver,
                    color = Color(BadgeTier.SILVER.primaryColorHex),
                    modifier = Modifier.weight(1f)
                )
                TierCounterStatBox(
                    emoji = BadgeTier.BRONZE.emoji,
                    label = "Bronz",
                    count = tierCounts.bronze,
                    color = Color(BadgeTier.BRONZE.primaryColorHex),
                    modifier = Modifier.weight(1f)
                )
            }

            // Aktif 5 Rozet Vitrini Özeti
            if (showcaseBadgeIds.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Aktif Vitrinin (${showcaseBadgeIds.size}/${SharkBadgeCatalog.MAX_SHOWCASE_BADGES})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        showcaseBadgeIds.mapNotNull { SharkBadgeCatalog.findById(it) }.forEach { badge ->
                            val info = badge.progressFor(
                                rawCount = badgeProgress[badge.id] ?: 0,
                                isLegacyUnlocked = true
                            )
                            SharkBadgeChip(
                                badge = badge,
                                tier = info.unlockedTier ?: BadgeTier.BRONZE,
                                compact = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TierCounterStatBox(
    emoji: String,
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.38f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "$emoji $count",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 2 Sütunlu Izgara İçindeki Tekil Rozet Kartı:
 * - Özel sembol madalyonu (SharkBadgeSymbolIcon)
 * - Rozet başlığı + kategori etiketi
 * - Aktif kademe rozeti veya kilit ikonu
 * - LinearProgressIndicator ilerleme çubuğu + sayısal ilerleme (örn. 4 / 15)
 */
@Composable
private fun BadgeGridCard(
    progressInfo: BadgeProgressInfo,
    isShowcased: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badge = progressInfo.badge
    val unlockedTier = progressInfo.unlockedTier
    val isUnlocked = unlockedTier != null
    val primaryColor = Color(badge.primaryColorHex)
    val tierColor = unlockedTier?.let { Color(it.primaryColorHex) } ?: primaryColor
    val category = remember(badge.id) { resolveBadgeCategory(badge) }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (isUnlocked) {
            primaryColor.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = if (isShowcased) 1.8.dp else 1.dp,
            color = when {
                isShowcased -> tierColor
                isUnlocked -> tierColor.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Üst Satır: Özel Sembol Madalyonu + Kademe / Kilit Rozeti
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                SharkBadgeSymbolIcon(
                    badge = badge,
                    size = 46.dp,
                    tier = unlockedTier,
                    isLocked = !isUnlocked
                )

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (unlockedTier != null) {
                            tierColor.copy(alpha = 0.20f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        border = BorderStroke(
                            0.8.dp,
                            if (unlockedTier != null) tierColor.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            if (unlockedTier != null) {
                                Text(
                                    text = "${unlockedTier.emoji} ${unlockedTier.shortLabel}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = tierColor
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Kilitli",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Kilitli",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (isShowcased) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFF59E0B).copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = "★ Vitrinde",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                }
            }

            // Rozet Başlığı & Kategori Etiketi
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = badge.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${category.emoji} ${category.label}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // İlerleme Çubuğu (LinearProgressIndicator) ve Sayısal İlerleme (4 / 15)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { progressInfo.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isUnlocked) tierColor else primaryColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${progressInfo.count} / ${progressInfo.nextTarget}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (isUnlocked) tierColor else primaryColor
                    )

                    Text(
                        text = if (progressInfo.nextTier != null) {
                            "Hedef: ${progressInfo.nextTier.emoji}"
                        } else {
                            "🦈 Maks!"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 3. Rozete Tıklayınca Açılan Detay Penceresi (ModalBottomSheet):
 * - Büyük sembol rozet madalyonu (f'(x), ∫ vb.) ve başlık + kategori etiketi
 * - Kazanım açıklaması ve net hedef cümlesi (ör. "Türev'den 15 soru doğru çöz")
 * - Net sayısal ilerleme + kalın ilerleme çubuğu ("İlerleme: 4 / 15 (Kalan: 11 soru)")
 * - 4 Kademe yol haritası satırı: 🥉 Bronz • 🥈 Gümüş • 🥇 Altın • 🦈 Megalodon
 * - Rozet açıksa doğrudan "Vitrine Ekle" / "Vitrinden Çıkar" aksiyon butonu
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BadgeDetailModalSheet(
    progressInfo: BadgeProgressInfo,
    isShowcased: Boolean,
    showcaseCount: Int,
    onDismiss: () -> Unit,
    onToggleShowcase: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val badge = progressInfo.badge
    val unlockedTier = progressInfo.unlockedTier
    val nextTier = progressInfo.nextTier
    val isUnlocked = unlockedTier != null
    val primaryColor = Color(badge.primaryColorHex)
    val activeColor = unlockedTier?.let { Color(it.primaryColorHex) } ?: primaryColor
    val category = remember(badge.id) { resolveBadgeCategory(badge) }
    val thresholds = if (badge.thresholds.size >= 4) badge.thresholds else SharkBadgeCatalog.DEFAULT_TOPIC_THRESHOLDS
    val remainingToNext = (progressInfo.nextTarget - progressInfo.count).coerceAtLeast(0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Üst Satır: Büyük Sembol Madalyonu + Başlık + Kategori Etiketi + Kapat
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SharkBadgeSymbolIcon(
                    badge = badge,
                    size = 68.dp,
                    tier = unlockedTier,
                    isLocked = !isUnlocked
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = primaryColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "${category.emoji} ${category.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = primaryColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (unlockedTier != null) {
                                activeColor.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = if (unlockedTier != null) {
                                    "${unlockedTier.emoji} ${unlockedTier.label} Kademe"
                                } else {
                                    "🔒 Kilitli"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (unlockedTier != null) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = "${badge.emoji} ${badge.title}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat"
                    )
                }
            }

            // Kazanım Açıklaması & Net Hedef Cümlesi
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = badge.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "🎯 Hedef: ${buildClearTargetSentence(badge, progressInfo)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = activeColor
                    )
                }
            }

            // Net Sayısal İlerleme + Kalın İlerleme Çubuğu
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            unlockedTier == null ->
                                "İlerleme: ${progressInfo.count} / ${progressInfo.nextTarget} (Kalan: $remainingToNext)"
                            nextTier != null ->
                                "${nextTier.label} kademeye: ${progressInfo.count} / ${progressInfo.nextTarget} (Kalan: $remainingToNext)"
                            else ->
                                "Megalodon Zirvesi: ${progressInfo.count} / ${progressInfo.nextTarget} (Tamamlandı!)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "%${(progressInfo.progressFraction * 100).toInt()}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = activeColor
                    )
                }

                LinearProgressIndicator(
                    progress = { progressInfo.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(11.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = activeColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // 4 Kademe Yol Haritası Satırı: 🥉 Bronz • 🥈 Gümüş • 🥇 Altın • 🦈 Megalodon
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "4 Kademe Yol Haritası",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tiers = listOf(
                        BadgeTier.BRONZE to thresholds[0],
                        BadgeTier.SILVER to thresholds[1],
                        BadgeTier.GOLD to thresholds[2],
                        BadgeTier.MEGALODON to thresholds[3]
                    )

                    tiers.forEach { (tier, targetCount) ->
                        val isTierCompleted = progressInfo.count >= targetCount ||
                            (unlockedTier != null && unlockedTier.level >= tier.level)
                        val isCurrentTarget = !isTierCompleted && nextTier == tier
                        val tierCol = Color(tier.primaryColorHex)

                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                isTierCompleted -> tierCol.copy(alpha = 0.20f)
                                isCurrentTarget -> tierCol.copy(alpha = 0.10f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                            border = BorderStroke(
                                width = if (isTierCompleted || isCurrentTarget) 1.5.dp else 1.dp,
                                color = when {
                                    isTierCompleted -> tierCol
                                    isCurrentTarget -> tierCol.copy(alpha = 0.65f)
                                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = tier.emoji,
                                        fontSize = 14.sp
                                    )
                                    if (isTierCompleted) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Tamamlandı",
                                            tint = tierCol,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = tier.shortLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.5.sp,
                                    color = if (isTierCompleted) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1
                                )
                                Text(
                                    text = "($targetCount)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp,
                                    color = if (isTierCompleted) tierCol else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Vitrine Ekle / Vitrinden Çıkar Aksiyon Butonu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(0.8f)
                ) {
                    Text("Kapat")
                }

                if (isUnlocked) {
                    Button(
                        onClick = onToggleShowcase,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isShowcased) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                activeColor
                            },
                            contentColor = if (isShowcased) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                Color.White
                            }
                        ),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Icon(
                            imageVector = if (isShowcased) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isShowcased) {
                                "Vitrinden Çıkar ($showcaseCount/5)"
                            } else {
                                "Vitrine Ekle ($showcaseCount/5)"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Bronz (${thresholds[0]}) ile Açılır",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private fun buildClearTargetSentence(
    badge: SharkBadgeDef,
    progressInfo: BadgeProgressInfo
): String {
    val nextTier = progressInfo.nextTier
    val target = progressInfo.nextTarget
    return if (nextTier != null) {
        "${nextTier.emoji} ${nextTier.label} kademe için ${badge.title} hedefinde toplam $target tamamla (${badge.conditionLabel})."
    } else {
        "🦈 Megalodon kademesine ulaştın! (${badge.conditionLabel})"
    }
}

/**
 * Rozetin ait olduğu ders/mentorluk kategorisini doğrudan katalog tanımından çözümler.
 */
private fun resolveBadgeCategory(badge: SharkBadgeDef): ProfileBadgeCategoryFilter =
    when (badge.category) {
        CoreBadgeCategory.MATH_GEO -> ProfileBadgeCategoryFilter.MATH_GEO
        CoreBadgeCategory.PHYSICS -> ProfileBadgeCategoryFilter.PHYSICS
        CoreBadgeCategory.CHEMISTRY -> ProfileBadgeCategoryFilter.CHEMISTRY
        CoreBadgeCategory.BIOLOGY -> ProfileBadgeCategoryFilter.BIOLOGY
        CoreBadgeCategory.TURKISH_LIT -> ProfileBadgeCategoryFilter.TURKISH_LIT
        CoreBadgeCategory.SOCIAL_LANG -> ProfileBadgeCategoryFilter.SOCIAL_LANG
        CoreBadgeCategory.MENTORSHIP -> ProfileBadgeCategoryFilter.MENTORSHIP
    }

private fun normalizeTurkishSearch(input: String): String =
    input.trim()
        .lowercase(Locale("tr"))
        .replace('ı', 'i')
        .replace('ğ', 'g')
        .replace('ü', 'u')
        .replace('ş', 's')
        .replace('ö', 'o')
        .replace('ç', 'c')
