package com.example.sharkmentor.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.R
import com.example.sharkmentor.core.analytics.SubjectMastery
import com.example.sharkmentor.data.local.entity.FocusSessionEntity
import com.example.sharkmentor.ui.components.AiDisclaimerText
import com.example.sharkmentor.ui.viewmodel.AiAdviceState
import com.example.sharkmentor.ui.viewmodel.AnalyticsViewModel
import com.example.sharkmentor.ui.viewmodel.HistoryViewModel
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.ui.viewmodel.SubjectTimeStat
import java.util.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailedAnalyticsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    analyticsViewModel: AnalyticsViewModel = viewModel()
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Detaylı Analiz & Raporlar") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            AnalyticsDashboardContent(viewModel = analyticsViewModel)
        }
    }
}

@Composable
fun AnalyticsDashboardContent(viewModel: AnalyticsViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    val weeklyFocusMinutes = uiState.weeklyFocusMinutes
    val modeBreakdown = uiState.modeBreakdown
    val todayPhoneLifts = uiState.todayPhoneLifts
    val todayScreenOnMinutes = uiState.todayScreenOnMinutes

    val totalWeeklyFocus = remember(weeklyFocusMinutes) { weeklyFocusMinutes.values.sum() }
    val avgDailyFocus = remember(weeklyFocusMinutes, totalWeeklyFocus) { if (weeklyFocusMinutes.isNotEmpty()) totalWeeklyFocus / 7 else 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Timer,
                title = stringResource(R.string.analytics_weekly_total),
                value = "${totalWeeklyFocus / 60}s ${totalWeeklyFocus % 60}d",
                unit = stringResource(R.string.analytics_this_week),
                trendColor = MaterialTheme.colorScheme.primary,
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Timer,
                title = stringResource(R.string.analytics_daily_avg),
                value = "${avgDailyFocus / 60}s ${avgDailyFocus % 60}d",
                unit = stringResource(R.string.analytics_daily_avg_unit),
                trendColor = MaterialTheme.colorScheme.secondary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.PhoneAndroid,
                title = stringResource(R.string.analytics_session_screen_time),
                value = "$todayScreenOnMinutes dk",
                unit = stringResource(R.string.analytics_during_focus),
                trendColor = MaterialTheme.colorScheme.secondary,
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.PhoneAndroid,
                title = stringResource(R.string.analytics_phone_lifts),
                value = "$todayPhoneLifts kez",
                unit = stringResource(R.string.analytics_total_violations),
                trendColor = MaterialTheme.colorScheme.error,
            )
        }

        WeeklyChartCard(weeklyFocusMinutes)
        ModeBreakdownCard(modeBreakdown)
        ActivityHeatmapCard()
        SubjectBreakdownCard(subjects = uiState.subjectDistribution)
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun ActivityHeatmapCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("30 Günlük Çalışma Isı Haritası (Heatmap)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            val cellCount = 35
            val simulatedMinutes = remember {
                val rnd = Random(42)
                List(cellCount) { rnd.nextInt(240) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in 0 until 5) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (col in 0 until 7) {
                            val index = row * 7 + col
                            val mins = simulatedMinutes.getOrElse(index) { 0 }
                            val cellColor = when {
                                mins >= 180 -> MaterialTheme.colorScheme.primary
                                mins >= 61 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                mins >= 1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(cellColor, RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("0d", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(4.dp))
                Box(modifier = Modifier.size(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Box(modifier = Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Box(modifier = Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Box(modifier = Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("180d+", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SubjectBreakdownCard(subjects: List<SubjectTimeStat>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.PieChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Ders Bazlı Süre Dağılımı", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (subjects.isEmpty()) {
                Text(
                    text = "Henüz ders bazlı çalışma kaydı bulunmuyor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                subjects.forEach { stat ->
                    val progressVal = (stat.percentage / 100f).coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stat.subjectName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("${stat.percentage}% (${stat.formattedDuration})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(
                            progress = { progressVal },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OverallMasteryCard(
    report: com.example.sharkmentor.core.analytics.OverallMasteryReport,
    isRefreshing: Boolean,
    onRefreshClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // CARD HEADER ROW (Title + Top-Right Refresh Button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Genel Ustalık Puanı",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Sağ Üst Yenileme Butonu
                IconButton(
                    onClick = onRefreshClick,
                    enabled = !isRefreshing,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Puanı Yenile",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Score Display / Circular Progress / Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                // Skoru gösteren ana dairesel gösterge veya yüzde metni
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${report.overallScore}%",
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = when {
                            report.overallScore >= 80 -> "Usta Seviye"
                            report.overallScore >= 50 -> "İlerleme İyi"
                            report.overallScore > 0 -> "Başlangıç Aşaması"
                            else -> "Veri Bekleniyor"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AiProjectionCard(state: AiAdviceState, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb, 
                        contentDescription = "AI",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Shark Hedef Projeksiyonu",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Yenile",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            when (state) {
                is AiAdviceState.Idle -> {
                    Text(
                        text = "Mevcut verilerine göre stratejik tavsiye almak için yenile ikonuna tıkla.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is AiAdviceState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                is AiAdviceState.Success -> {
                    Text(
                        text = state.advice,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is AiAdviceState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (state is AiAdviceState.Success) {
                Spacer(Modifier.height(8.dp))
                AiDisclaimerText()
            }
        }
    }
}

@Composable
fun SubjectMasteryCard(subject: SubjectMastery) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subject.subjectName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${subject.score}% Yetkinlik",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { subject.score / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Ort. Seviye: ${String.format("%.1f", subject.topicAverage)}/5 • Kaynak: ${(subject.resourceProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    unit: String,
    trendColor: androidx.compose.ui.graphics.Color,
) {
    Card(
        modifier = modifier.height(130.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = trendColor,
                modifier = Modifier.size(22.dp),
            )
            Column {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = unit,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun WeeklyChartCard(weeklyData: Map<Int, Int>) {
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.analytics_weekly_dist),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            
            val maxMinutes = (weeklyData.values.maxOrNull() ?: 60).coerceAtLeast(60).toFloat()
            val dfs = DateFormatSymbols.getInstance()
            val days = listOf(dfs.shortWeekdays[Calendar.MONDAY], dfs.shortWeekdays[Calendar.TUESDAY], dfs.shortWeekdays[Calendar.WEDNESDAY], dfs.shortWeekdays[Calendar.THURSDAY], dfs.shortWeekdays[Calendar.FRIDAY], dfs.shortWeekdays[Calendar.SATURDAY], dfs.shortWeekdays[Calendar.SUNDAY])
            val dayMapping = listOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
            )

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                dayMapping.forEachIndexed { index, calDay ->
                    val minutes = weeklyData[calDay] ?: 0
                    val heightRatio = (minutes / maxMinutes).coerceIn(0.05f, 1f)
                    
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .weight(1f),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            val barColor = MaterialTheme.colorScheme.primary
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val barHeight = size.height * heightRatio
                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset(0f, size.height - barHeight),
                                    size = Size(size.width, barHeight),
                                    cornerRadius = CornerRadius(4.dp.toPx())
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = days[index],
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeBreakdownCard(modeBreakdown: Map<String, Int>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PieChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.analytics_mode_dist),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
            
            if (modeBreakdown.isEmpty()) {
                Text(
                    text = stringResource(R.string.analytics_no_data),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                modeBreakdown.entries.sortedByDescending { it.value }.forEach { (mode, minutes) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = mode, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)
                        Text(text = "${minutes} dk", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
