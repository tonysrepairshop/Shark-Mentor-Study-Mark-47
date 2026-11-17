package com.example.sharkmentor.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.core.util.SharkVisualKit
import com.example.sharkmentor.ui.viewmodel.LeagueEntry
import com.example.sharkmentor.ui.viewmodel.SharkLeagueUiState
import com.example.sharkmentor.ui.viewmodel.SharkLeagueViewModel
import com.example.sharkmentor.ui.viewmodel.SharkTier
import com.example.sharkmentor.ui.viewmodel.nextThreshold

private fun SharkTier.accentColor(): Color = Color(color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharkLeagueScreen(
    onBackClick: () -> Unit,
    viewModel: SharkLeagueViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadLeague() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Shark Arena",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (uiState.groupName != null) "Grup: ${uiState.groupName} • Seviye Odak Ligi" else "Lig Sıralaması",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingIndicator(Modifier.padding(innerPadding))
            uiState.error != null -> ErrorCard(uiState.error!!, Modifier.padding(innerPadding))
            else -> LeagueContent(uiState = uiState, modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun LeagueContent(uiState: SharkLeagueUiState, modifier: Modifier) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Left Column (40% width): Overview & My Tier Card
            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MotivationalBanner()
                uiState.myEntry?.let { me ->
                    MyTierCard(entry = me)
                }
            }

            // Right Column (58% width): Leaderboard list
            LazyColumn(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = if (uiState.groupName != null) "GRUP SIRALAMASI" else "LİG SIRALAMASI",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (uiState.leaderboard.isEmpty()) {
                    item {
                        Text(
                            "Henüz lig verisi yok. Çalışmaya başla!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                } else {
                    itemsIndexed(uiState.leaderboard, key = { _, e -> e.studentId }) { index, entry ->
                        LeaderboardRow(rank = index + 1, entry = entry)
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                MotivationalBanner()
            }

            uiState.myEntry?.let { me ->
                item {
                    MyTierCard(entry = me)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        text = if (uiState.groupName != null) "GRUP SIRALAMASI" else "LİG SIRALAMASI",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
            }

            if (uiState.leaderboard.isEmpty()) {
                item {
                    Text(
                        "Henüz lig verisi yok. Çalışmaya başla!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    )
                }
            } else {
                itemsIndexed(uiState.leaderboard, key = { _, e -> e.studentId }) { index, entry ->
                    LeaderboardRow(rank = index + 1, entry = entry)
                }
            }

            item {
                Text(
                    text = "⚠️ Sıralama yalnızca doğrulanmış masa odaklanma sürelerine dayanır.\nTest netleri veya sınav puanları kesinlikle kullanılmaz.",
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun MotivationalBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Shark Arena Felsefesi",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "\"Netler değişken, odaklanma alışkanlığı kalıcıdır.\"",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Bu sıralamada yalnızca masa başında geçirilen doğrulanmış odaklanma süresi sayılır.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun MyTierCard(entry: LeagueEntry) {
    val tier = entry.tier
    val accentColor = tier.accentColor()
    val currentSecs = entry.weeklyFocusSeconds
    val nextThresh = tier.nextThreshold()
    val progressFrac = if (nextThresh != null) {
        val prevThresh = when (tier) {
            SharkTier.CAMGOZ -> 0L
            SharkTier.MAKO -> 10 * 3600L
            SharkTier.CEKICBAS -> 20 * 3600L
            SharkTier.KAPLAN -> 30 * 3600L
            SharkTier.BUYUK_BEYAZ -> 45 * 3600L
        }
        ((currentSecs - prevThresh).toFloat() / (nextThresh - prevThresh)).coerceIn(0f, 1f)
    } else 1f

    val hours = currentSecs / 3600
    val mins = (currentSecs % 3600) / 60

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = SharkVisualKit.getTierBadge((currentSecs / 60).toInt())),
                        contentDescription = tier.label,
                        modifier = Modifier.size(40.dp)
                    )
                    Column {
                        Text(
                            text = "SENİN SEVİYEN",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tier.label,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = accentColor
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${hours}s ${mins}dk",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Haftalık Puan",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Lig Yükselme Hattı",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (nextThresh != null) {
                            val remSecs = nextThresh - currentSecs
                            val remH = remSecs / 3600
                            val remM = (remSecs % 3600) / 60
                            "${remH}s ${remM}dk kaldı"
                        } else "Zirvedesin",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progressFrac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            if (entry.streakDays > 0) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "${entry.streakDays} günlük seri",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(rank: Int, entry: LeagueEntry) {
    val accentColor = entry.tier.accentColor()
    val hours = entry.weeklyFocusSeconds / 3600
    val mins = (entry.weeklyFocusSeconds % 3600) / 60
    val sharkBadgeRes = SharkVisualKit.getTierBadge((entry.weeklyFocusSeconds / 60).toInt())

    Surface(
        color = if (entry.isCurrentUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (rank <= 3) 2.dp else 1.dp,
            color = when (rank) {
                1 -> Color(0xFFFFD700)
                2 -> Color(0xFFC0C0C0)
                3 -> Color(0xFFCD7F32)
                else -> if (entry.isCurrentUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center
            )

            Image(
                painter = painterResource(id = sharkBadgeRes),
                contentDescription = entry.tier.label,
                modifier = Modifier.size(32.dp)
            )

            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (entry.isCurrentUser || rank <= 3) FontWeight.Bold else FontWeight.Normal
                ),
                color = if (entry.isCurrentUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${hours}s ${mins}dk",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = accentColor
                )
                if (entry.streakDays > 1) {
                    Text(
                        "${entry.streakDays}g seri",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚠️", fontSize = 32.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
