package com.example.sharkmentor.core.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.R

class SharkMentorWidget : GlanceAppWidget() {

    companion object {
        private val SIZE_COMPACT = DpSize(180.dp, 80.dp)
        private val SIZE_MEDIUM = DpSize(250.dp, 120.dp)
        private val SIZE_EXPANDED = DpSize(300.dp, 170.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_COMPACT, SIZE_MEDIUM, SIZE_EXPANDED)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetUpdateManager.loadSnapshot(context)

        provideContent {
            GlanceTheme {
                WidgetLayout(snapshot = snapshot)
            }
        }
    }

    @Composable
    private fun WidgetLayout(snapshot: WidgetSnapshot) {
        val context = LocalContext.current
        val size = LocalSize.current
        val isCompact = size.height < 110.dp
        val isExpanded = size.height >= 155.dp

        val openHomeIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_TARGET_ROUTE", "home")
        }
        val openAnalyticsIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_TARGET_ROUTE", "analytics")
        }
        val openErrorBankIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_TARGET_ROUTE", "error_bank")
        }
        val startFocusIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_START_FOCUS", true)
            putExtra("EXTRA_TARGET_ROUTE", "home")
        }

        val hasPending = snapshot.pendingReviews > 0
        val goalProgress = (snapshot.todayFocusMinutes.toFloat() / snapshot.dailyGoalMinutes.toFloat())
            .coerceIn(0f, 1f)

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetThemeTokens.DeepOceanBg)
                .cornerRadius(24.dp)
                .padding(if (isCompact) 10.dp else 12.dp)
                .clickable(actionStartActivity(openHomeIntent))
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                // Header Row
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SharkMentor",
                        style = TextStyle(
                            color = WidgetThemeTokens.OffWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    // Pending Errors / Queue Status Badge (Direct jump to Error Bank)
                    Box(
                        modifier = GlanceModifier
                            .background(if (hasPending) WidgetThemeTokens.AlertAmberBg else WidgetThemeTokens.SuccessGreenBg)
                            .cornerRadius(10.dp)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .clickable(actionStartActivity(openErrorBankIntent))
                    ) {
                        Text(
                            text = if (hasPending) "${snapshot.pendingReviews} Tekrar" else "Kuyruk Temiz",
                            style = TextStyle(
                                color = if (hasPending) WidgetThemeTokens.AlertAmber else WidgetThemeTokens.SuccessGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(6.dp))

                    // Refresh Action Button
                    Box(
                        modifier = GlanceModifier
                            .size(26.dp)
                            .background(WidgetThemeTokens.ElevatedCardBg)
                            .cornerRadius(13.dp)
                            .clickable(actionRunCallback<RefreshWidgetAction>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_refresh),
                            contentDescription = "Yenile",
                            modifier = GlanceModifier.size(14.dp),
                            colorFilter = ColorFilter.tint(WidgetThemeTokens.IceBlue)
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.height(8.dp))

                // Metric Cards Row
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Card 1: Mastery Score -> Analytics
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .background(WidgetThemeTokens.ElevatedCardBg)
                            .cornerRadius(16.dp)
                            .padding(6.dp)
                            .clickable(actionStartActivity(openAnalyticsIntent)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!isCompact) {
                                Image(
                                    provider = ImageProvider(R.drawable.ic_widget_trophy),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(16.dp),
                                    colorFilter = ColorFilter.tint(WidgetThemeTokens.IceBlue)
                                )
                                Spacer(modifier = GlanceModifier.height(2.dp))
                            }
                            Text(
                                text = "%${snapshot.masteryScore}",
                                style = TextStyle(
                                    color = WidgetThemeTokens.OffWhite,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                            Text(
                                text = "Ustalık",
                                style = TextStyle(
                                    color = WidgetThemeTokens.MutedBlue,
                                    fontSize = 10.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Card 2: Today Focus -> Home
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .background(WidgetThemeTokens.ElevatedCardBg)
                            .cornerRadius(16.dp)
                            .padding(6.dp)
                            .clickable(actionStartActivity(openHomeIntent)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!isCompact) {
                                Image(
                                    provider = ImageProvider(R.drawable.ic_widget_desk),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(16.dp),
                                    colorFilter = ColorFilter.tint(WidgetThemeTokens.OceanTeal)
                                )
                                Spacer(modifier = GlanceModifier.height(2.dp))
                            }
                            Text(
                                text = WidgetUpdateManager.formatMinutesCompact(snapshot.todayFocusMinutes),
                                style = TextStyle(
                                    color = WidgetThemeTokens.OffWhite,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                            Text(
                                text = "Bugün Odak",
                                style = TextStyle(
                                    color = WidgetThemeTokens.MutedBlue,
                                    fontSize = 10.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Card 3: Quick Focus CTA
                    val isTimerRunning = snapshot.timerState.isRunning
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .background(
                                if (isTimerRunning) WidgetThemeTokens.OceanTeal else WidgetThemeTokens.SignaturePurple
                            )
                            .cornerRadius(16.dp)
                            .padding(6.dp)
                            .clickable(actionStartActivity(startFocusIntent)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_widget_bolt),
                                contentDescription = "Odaklan",
                                modifier = GlanceModifier.size(16.dp),
                                colorFilter = ColorFilter.tint(
                                    if (isTimerRunning) WidgetThemeTokens.DarkOnAccent else WidgetThemeTokens.OffWhite
                                )
                            )
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            Text(
                                text = if (isTimerRunning) "Aktif" else "Odaklan",
                                style = TextStyle(
                                    color = if (isTimerRunning) WidgetThemeTokens.DarkOnAccent else WidgetThemeTokens.OffWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }

                // Expanded Footer: Daily Goal Progress + Streak Pill
                if (isExpanded) {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .background(WidgetThemeTokens.ElevatedCardBg)
                            .cornerRadius(12.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Row(
                                modifier = GlanceModifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Günlük Hedef",
                                    style = TextStyle(
                                        color = WidgetThemeTokens.MutedBlue,
                                        fontSize = 10.sp
                                    ),
                                    maxLines = 1
                                )
                                Spacer(modifier = GlanceModifier.defaultWeight())
                                Text(
                                    text = "${snapshot.todayFocusMinutes}/${snapshot.dailyGoalMinutes} dk",
                                    style = TextStyle(
                                        color = WidgetThemeTokens.OffWhite,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = goalProgress,
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .cornerRadius(3.dp),
                                color = WidgetThemeTokens.IceBlue,
                                backgroundColor = WidgetThemeTokens.TrackBg
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(10.dp))

                        Row(
                            modifier = GlanceModifier
                                .background(WidgetThemeTokens.SoftCardBg)
                                .cornerRadius(10.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_widget_water_drop),
                                contentDescription = "Su Serisi",
                                modifier = GlanceModifier.size(12.dp),
                                colorFilter = ColorFilter.tint(WidgetThemeTokens.WaterDropAqua)
                            )
                            Spacer(modifier = GlanceModifier.width(4.dp))
                            Text(
                                text = "${snapshot.streakCount}g",
                                style = TextStyle(
                                    color = WidgetThemeTokens.OffWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

class SharkMentorWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SharkMentorWidget()
}
