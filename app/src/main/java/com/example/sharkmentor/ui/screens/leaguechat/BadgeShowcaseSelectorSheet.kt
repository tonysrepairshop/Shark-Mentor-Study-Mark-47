package com.example.sharkmentor.ui.screens.leaguechat

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

internal enum class BadgeFilterCategory(val label: String) {
    ALL("Tümü"),
    MATEMATIK(CoreBadgeCategory.MATH_GEO.shortTitle),
    FIZIK(CoreBadgeCategory.PHYSICS.shortTitle),
    KIMYA(CoreBadgeCategory.CHEMISTRY.shortTitle),
    BIYOLOJI(CoreBadgeCategory.BIOLOGY.shortTitle),
    TURKCE_EDEB(CoreBadgeCategory.TURKISH_LIT.shortTitle),
    SOSYAL_DIL(CoreBadgeCategory.SOCIAL_LANG.shortTitle),
    MENTORLUK(CoreBadgeCategory.MENTORSHIP.shortTitle)
}

internal fun normalizeBadgeText(raw: String): String =
    raw.trim()
        .lowercase(Locale("tr"))
        .replace('ı', 'i')
        .replace('ğ', 'g')
        .replace('ü', 'u')
        .replace('ş', 's')
        .replace('ö', 'o')
        .replace('ç', 'c')
        .replace('â', 'a')
        .replace('î', 'i')
        .replace('û', 'u')

internal fun resolveBadgeCategory(badge: SharkBadgeDef): BadgeFilterCategory =
    when (badge.category) {
        CoreBadgeCategory.MATH_GEO -> BadgeFilterCategory.MATEMATIK
        CoreBadgeCategory.PHYSICS -> BadgeFilterCategory.FIZIK
        CoreBadgeCategory.CHEMISTRY -> BadgeFilterCategory.KIMYA
        CoreBadgeCategory.BIOLOGY -> BadgeFilterCategory.BIYOLOJI
        CoreBadgeCategory.TURKISH_LIT -> BadgeFilterCategory.TURKCE_EDEB
        CoreBadgeCategory.SOCIAL_LANG -> BadgeFilterCategory.SOSYAL_DIL
        CoreBadgeCategory.MENTORSHIP -> BadgeFilterCategory.MENTORLUK
    }

private fun readableTierTextColor(tier: BadgeTier?, fallback: Color, surfaceColor: Color): Color {
    val isDark = surfaceColor.luminance() < 0.5f
    if (tier == null) {
        return if (isDark && fallback.luminance() < 0.35f) Color(0xFF93C5FD) else fallback
    }
    return if (isDark) Color(tier.secondaryColorHex) else Color(tier.primaryColorHex)
}

internal fun safeThresholdsFor(badge: SharkBadgeDef): List<Int> =
    if (badge.thresholds.size >= 4) badge.thresholds.take(4) else SharkBadgeCatalog.DEFAULT_TOPIC_THRESHOLDS

@Composable
internal fun BadgeSymbolMedallion(
    badge: SharkBadgeDef,
    unlockedTier: BadgeTier?,
    isUnlocked: Boolean = true,
    size: Dp = 46.dp,
    modifier: Modifier = Modifier
) {
    val startColor = Color(badge.primaryColorHex)

    Box(modifier = modifier.size(size)) {
        SharkBadgeSymbolIcon(
            badge = badge,
            modifier = Modifier.matchParentSize(),
            size = size,
            tier = unlockedTier,
            isLocked = !isUnlocked
        )

        if (size >= 38.dp) {
            Surface(
                shape = CircleShape,
                color = if (isUnlocked) {
                    unlockedTier?.let { Color(it.primaryColorHex) } ?: startColor
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (unlockedTier != null) {
                        Text(
                            text = unlockedTier.shortLabel.take(1),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = badge.icon,
                            contentDescription = null,
                            tint = if (isUnlocked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BadgeTierDetailDialog(
    badge: SharkBadgeDef,
    rawCount: Int,
    isLegacyUnlocked: Boolean = false,
    ownerLabel: String? = null,
    isSelectedInShowcase: Boolean? = null,
    onToggleShowcase: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val thresholds = remember(badge.id, badge.thresholds) { safeThresholdsFor(badge) }
    val progress = remember(badge.id, rawCount, isLegacyUnlocked) {
        badge.progressFor(rawCount = rawCount, isLegacyUnlocked = isLegacyUnlocked)
    }
    val category = remember(badge.id, badge.title) { resolveBadgeCategory(badge) }
    val activeTier = progress.unlockedTier
    val surfaceColor = MaterialTheme.colorScheme.surface
    val accentColor = activeTier?.let { Color(it.primaryColorHex) } ?: Color(badge.primaryColorHex)
    val accentTextColor = readableTierTextColor(activeTier, accentColor, surfaceColor)
    val tiers = listOf(BadgeTier.BRONZE, BadgeTier.SILVER, BadgeTier.GOLD, BadgeTier.MEGALODON)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surfaceColor,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BadgeSymbolMedallion(
                    badge = badge,
                    unlockedTier = activeTier,
                    isUnlocked = activeTier != null,
                    size = 50.dp
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = badge.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (activeTier != null) {
                                accentColor.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = activeTier?.shortLabel ?: "Kilitli",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (activeTier != null) accentTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Kazanma Koşulu: ${badge.conditionLabel}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = { progress.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = accentTextColor,
                            trackColor = surfaceColor
                        )
                        val progressPrefix = if (!ownerLabel.isNullOrBlank()) {
                            "$ownerLabel İlerlemesi"
                        } else {
                            "İlerlemeniz"
                        }
                        Text(
                            text = if (progress.nextTier != null) {
                                "$progressPrefix: ${progress.count}/${progress.nextTarget} • Sonraki: ${progress.nextTier.shortLabel}"
                            } else {
                                "$progressPrefix: ${progress.count}/${progress.nextTarget} • Megalodon Zirvesi"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = accentTextColor
                        )
                        if (isLegacyUnlocked && rawCount <= 0) {
                            Text(
                                text = "Vitrin rozeti aktif (detaylı sayaç senkronu bekleniyor)",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Kademe Gereksinimleri",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    tiers.forEachIndexed { idx, tier ->
                        val targetValue = thresholds[idx]
                        val tierReached = progress.count >= targetValue ||
                            ((progress.unlockedTier?.level ?: 0) >= tier.level)
                        val tierColor = Color(tier.primaryColorHex)
                        val tierTextColor = readableTierTextColor(tier, tierColor, surfaceColor)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = if (tierReached) {
                                tierColor.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (tierReached) tierTextColor.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = tierTextColor,
                                        modifier = Modifier.size(10.dp)
                                    ) {}
                                    Text(
                                        text = tier.label,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (tierReached) tierTextColor else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val statusLabel = if (tierReached) {
                                        "Hedef: $targetValue • Açık"
                                    } else {
                                        val remaining = (targetValue - progress.count).coerceAtLeast(0)
                                        "Hedef: $targetValue • $remaining kaldı"
                                    }
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (tierReached) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (tierReached) tierTextColor else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (tierReached) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Açık",
                                            tint = tierTextColor,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onToggleShowcase != null && activeTier != null && isSelectedInShowcase != null) {
                Button(onClick = onToggleShowcase) {
                    Text(
                        text = if (isSelectedInShowcase) "Vitrinden Çıkar" else "Vitrine Ekle",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Kapat", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (onToggleShowcase != null && activeTier != null && isSelectedInShowcase != null) {
                TextButton(onClick = onDismiss) {
                    Text("Kapat")
                }
            }
        }
    )
}

/**
 * Modal bottom sheet allowing the user to filter and search across all tiered badges
 * (BRONZE -> SILVER -> GOLD -> MEGALODON), inspect tier requirements, and pick up to
 * [SharkBadgeCatalog.MAX_SHOWCASE_BADGES] earned badges for their Profile and Shark League showcase.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BadgeShowcaseSelectorSheet(
    unlockedBadgeIds: Set<String>,
    initialSelectedIds: List<String>,
    badgeProgress: Map<String, Int> = emptyMap(),
    mentorPoints: Int = 0,
    onDismiss: () -> Unit,
    onSaveShowcase: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allBadges = SharkBadgeCatalog.ALL_BADGES

    val effectiveUnlockedIds = remember(unlockedBadgeIds, badgeProgress, allBadges) {
        allBadges.filter { def ->
            val rawCount = badgeProgress[def.id] ?: 0
            def.progressFor(
                rawCount = rawCount,
                isLegacyUnlocked = rawCount <= 0 && unlockedBadgeIds.contains(def.id)
            ).unlockedTier != null
        }.map { it.id }.toSet()
    }

    val selectedIds = remember(initialSelectedIds, effectiveUnlockedIds) {
        mutableStateListOf<String>().apply {
            addAll(
                initialSelectedIds
                    .map { it.trim().substringBefore(":") }
                    .filter { effectiveUnlockedIds.contains(it) && SharkBadgeCatalog.findById(it) != null }
                    .distinct()
                    .take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
            )
        }
    }

    var selectedCategory by rememberSaveable { mutableStateOf(BadgeFilterCategory.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var inspectedBadge by remember { mutableStateOf<SharkBadgeDef?>(null) }

    val categoryCounts = remember(allBadges) {
        val counts = mutableMapOf<BadgeFilterCategory, Int>()
        allBadges.forEach { badge ->
            val cat = resolveBadgeCategory(badge)
            counts[cat] = (counts[cat] ?: 0) + 1
        }
        counts
    }

    val filteredBadges = remember(allBadges, selectedCategory, searchQuery) {
        val normalizedQuery = normalizeBadgeText(searchQuery)
        allBadges.filter { badge ->
            val category = resolveBadgeCategory(badge)
            val matchesCategory = selectedCategory == BadgeFilterCategory.ALL || category == selectedCategory
            if (!matchesCategory) return@filter false
            if (normalizedQuery.isBlank()) return@filter true

            val searchable = normalizeBadgeText(
                "${badge.title} ${badge.description} ${badge.conditionLabel} ${badge.id} ${badge.symbolText} ${badge.category.title} ${category.label}"
            )
            searchable.contains(normalizedQuery)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Vitrini Düzenle (Maks. ${SharkBadgeCatalog.MAX_SHOWCASE_BADGES})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${allBadges.size} kademeli rozetten en fazla ${SharkBadgeCatalog.MAX_SHOWCASE_BADGES} vitrin rozeti seç • $mentorPoints Mentor Puanı",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "${selectedIds.size}/${SharkBadgeCatalog.MAX_SHOWCASE_BADGES}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Live 5-slot showcase preview (wraps neatly via FlowRow)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (slotIdx in 0 until SharkBadgeCatalog.MAX_SHOWCASE_BADGES) {
                        val badgeId = selectedIds.getOrNull(slotIdx)
                        val badgeDef = SharkBadgeCatalog.findById(badgeId)
                        if (badgeDef != null) {
                            val tierInfo = badgeDef.progressFor(
                                rawCount = badgeProgress[badgeDef.id] ?: 0,
                                isLegacyUnlocked = effectiveUnlockedIds.contains(badgeDef.id)
                            )
                            SharkBadgeChip(
                                badge = badgeDef,
                                tier = tierInfo.unlockedTier ?: BadgeTier.BRONZE,
                                compact = true,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { selectedIds.remove(badgeDef.id) }
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "${slotIdx + 1}. Boş Slot",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Search box for 50+ badges
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it.take(48) },
                placeholder = {
                    Text(
                        text = "Rozet veya konu ara (örn. Türev)...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Ara",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Aramayı Temizle",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Category FilterChips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(BadgeFilterCategory.entries, key = { it.name }) { category ->
                    val selected = selectedCategory == category
                    val count = if (category == BadgeFilterCategory.ALL) {
                        allBadges.size
                    } else {
                        categoryCounts[category] ?: 0
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { selectedCategory = category },
                        label = {
                            Text(
                                text = "${category.label} ($count)",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            if (filteredBadges.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Bu filtreye uygun rozet bulunamadı.",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                selectedCategory = BadgeFilterCategory.ALL
                                searchQuery = ""
                            }
                        ) {
                            Text("Filtreleri Sıfırla", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredBadges, key = { it.id }) { badge ->
                        val isUnlocked = effectiveUnlockedIds.contains(badge.id)
                        val isSelected = selectedIds.contains(badge.id)
                        val progress = badge.progressFor(
                            rawCount = badgeProgress[badge.id] ?: 0,
                            isLegacyUnlocked = isUnlocked
                        )
                        BadgeCatalogSelectionRow(
                            badge = badge,
                            progress = progress,
                            isUnlocked = isUnlocked,
                            isSelected = isSelected,
                            onClick = {
                                if (!isUnlocked) {
                                    Toast.makeText(
                                        context,
                                        "Kilitli rozet (${badge.title}): ${badge.conditionLabel}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else if (isSelected) {
                                    selectedIds.remove(badge.id)
                                } else if (selectedIds.size >= SharkBadgeCatalog.MAX_SHOWCASE_BADGES) {
                                    Toast.makeText(
                                        context,
                                        "Vitrine en fazla ${SharkBadgeCatalog.MAX_SHOWCASE_BADGES} rozet seçebilirsin.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    selectedIds.add(badge.id)
                                }
                            },
                            onInspectDetail = { inspectedBadge = badge }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Vazgeç")
                }
                Button(
                    onClick = {
                        onSaveShowcase(selectedIds.toList())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.3f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Vitrini Kaydet (${selectedIds.size}/${SharkBadgeCatalog.MAX_SHOWCASE_BADGES})",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    inspectedBadge?.let { badge ->
        val isUnlocked = effectiveUnlockedIds.contains(badge.id)
        val isSelected = selectedIds.contains(badge.id)
        BadgeTierDetailDialog(
            badge = badge,
            rawCount = badgeProgress[badge.id] ?: 0,
            isLegacyUnlocked = isUnlocked,
            isSelectedInShowcase = isSelected,
            onToggleShowcase = {
                if (isSelected) {
                    selectedIds.remove(badge.id)
                    inspectedBadge = null
                } else if (selectedIds.size >= SharkBadgeCatalog.MAX_SHOWCASE_BADGES) {
                    Toast.makeText(
                        context,
                        "Vitrine en fazla ${SharkBadgeCatalog.MAX_SHOWCASE_BADGES} rozet seçebilirsin.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    selectedIds.add(badge.id)
                    inspectedBadge = null
                }
            },
            onDismiss = { inspectedBadge = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BadgeCatalogSelectionRow(
    badge: SharkBadgeDef,
    progress: BadgeProgressInfo,
    isUnlocked: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onInspectDetail: () -> Unit
) {
    val activeTier = progress.unlockedTier
    val surfaceColor = MaterialTheme.colorScheme.surface
    val tierPrimary = activeTier?.let { Color(it.primaryColorHex) } ?: Color(badge.primaryColorHex)
    val startColor = Color(badge.primaryColorHex)
    val activeAccentText = readableTierTextColor(activeTier, startColor, surfaceColor)
    val thresholds = remember(badge.id, badge.thresholds) { safeThresholdsFor(badge) }
    val tiers = listOf(BadgeTier.BRONZE, BadgeTier.SILVER, BadgeTier.GOLD, BadgeTier.MEGALODON)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isUnlocked) 1f else 0.78f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onInspectDetail
            ),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            startColor.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) tierPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BadgeSymbolMedallion(
                badge = badge,
                unlockedTier = activeTier,
                isUnlocked = isUnlocked,
                size = 46.dp
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = badge.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (activeTier != null) {
                            tierPrimary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = activeTier?.shortLabel ?: "Kilitli",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = if (activeTier != null) activeAccentText else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Tier Progress Bar: BRONZE -> SILVER -> GOLD -> MEGALODON
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = activeAccentText,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (progress.nextTier != null) {
                            "${progress.count}/${progress.nextTarget} • Hedef: ${progress.nextTier.shortLabel}"
                        } else {
                            "${progress.count}/${progress.nextTarget} • Megalodon Zirvesi"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = activeAccentText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Clear 4-tier threshold chips right on the row
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tiers.forEachIndexed { idx, tier ->
                        val tVal = thresholds[idx]
                        val reached = progress.count >= tVal || ((activeTier?.level ?: 0) >= tier.level)
                        val tColor = Color(tier.primaryColorHex)
                        val tTextColor = readableTierTextColor(tier, tColor, surfaceColor)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (reached) {
                                tColor.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                            },
                            border = BorderStroke(
                                0.75.dp,
                                if (reached) tTextColor.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            )
                        ) {
                            Text(
                                text = "${tier.shortLabel} $tVal",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = if (reached) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (reached) tTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isUnlocked) {
                    Surface(
                        shape = CircleShape,
                        color = if (isSelected) tierPrimary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Seçili",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Kilitli",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onInspectDetail() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Detay",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Detay",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
