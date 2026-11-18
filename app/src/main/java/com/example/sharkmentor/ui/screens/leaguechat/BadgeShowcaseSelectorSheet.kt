package com.example.sharkmentor.ui.screens.leaguechat

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharkmentor.core.badges.BadgeProgressInfo
import com.example.sharkmentor.core.badges.BadgeTier
import com.example.sharkmentor.core.badges.SharkBadgeCatalog
import com.example.sharkmentor.core.badges.SharkBadgeChip
import com.example.sharkmentor.core.badges.SharkBadgeDef

/**
 * Modal bottom sheet allowing the user to track all 12 tiered badges
 * (BRONZE -> SILVER -> GOLD -> MEGALODON) and pick up to 5 earned badges
 * for their Profile and Shark League showcase.
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

    val effectiveUnlockedIds = remember(unlockedBadgeIds, badgeProgress) {
        val fromCounts = badgeProgress.filter { (id, count) ->
            val def = SharkBadgeCatalog.findById(id)
            def != null && count >= (def.thresholds.firstOrNull() ?: 1)
        }.keys
        unlockedBadgeIds + fromCounts
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Vitrini Düzenle (Maks. 5)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "12 kademeli rozetten en fazla 5 vitrin rozeti seç • $mentorPoints Mentor Puanı",
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
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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
                                modifier = Modifier.clickable { selectedIds.remove(badgeDef.id) }
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SharkBadgeCatalog.ALL_BADGES, key = { it.id }) { badge ->
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
                        }
                    )
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
                    Text("Vitrini Kaydet (${selectedIds.size}/5)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BadgeCatalogSelectionRow(
    badge: SharkBadgeDef,
    progress: BadgeProgressInfo,
    isUnlocked: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val activeTier = progress.unlockedTier
    val tierPrimary = activeTier?.let { Color(it.primaryColorHex) } ?: Color(badge.primaryColorHex)
    val startColor = Color(badge.primaryColorHex)
    val endColor = Color(badge.secondaryColorHex)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isUnlocked) 1f else 0.7f)
            .clickable { onClick() },
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(startColor, endColor))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = badge.icon,
                    contentDescription = badge.title,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${badge.emoji} ${badge.title}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (activeTier != null) {
                            tierPrimary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = if (activeTier != null) "${activeTier.emoji} ${activeTier.shortLabel}" else "Kilitli",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = if (activeTier != null) tierPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (activeTier != null) tierPrimary else startColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (progress.nextTier != null) {
                            "${progress.count}/${progress.nextTarget} • Hedef: ${progress.nextTier.emoji} ${progress.nextTier.shortLabel}"
                        } else {
                            "${progress.count}/${progress.nextTarget} • 🦈 Megalodon Maksimum!"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = if (activeTier != null) tierPrimary else startColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = badge.conditionLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

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
        }
    }
}
