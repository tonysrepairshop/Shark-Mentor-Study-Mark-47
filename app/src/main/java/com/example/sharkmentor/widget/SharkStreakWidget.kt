package com.example.sharkmentor.widget

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
import com.example.sharkmentor.core.widget.DayFocusStatus
import com.example.sharkmentor.core.widget.RefreshWidgetAction
import com.example.sharkmentor.core.widget.WidgetSnapshot
import com.example.sharkmentor.core.widget.WidgetThemeTokens
import com.example.sharkmentor.core.widget.WidgetUpdateManager

class SharkStreakWidget : GlanceAppWidget() {

    companion object {
        private val SIZE_COMPACT = DpSize(140.dp, 110.dp)
        private val SIZE_MEDIUM = DpSize(210.dp, 130.dp)
        private val SIZE_EXPANDED = DpSize(260.dp, 170.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_COMPACT, SIZE_MEDIUM, SIZE_EXPANDED)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetUpdateManager.loadSnapshot(context)

        provideContent {
            GlanceTheme {
                StreakWidgetContent(snapshot = snapshot)
            }
        }
    }
}

@Composable
private fun StreakWidgetContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    val isCompact = size.width < 185.dp

    val startFocusIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("EXTRA_START_FOCUS", true)
        putExtra("EXTRA_TARGET_ROUTE", "home")
    }

    val doneToday = snapshot.isStreakDoneToday

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetThemeTokens.DeepOceanBg)
            .cornerRadius(24.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(startFocusIntent))
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Header: Flame Icon + Title + Status + Refresh
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_flame),
                    contentDescription = "Seri",
                    modifier = GlanceModifier.size(16.dp),
                    colorFilter = ColorFilter.tint(WidgetThemeTokens.AlertAmber)
                )
                Spacer(modifier = GlanceModifier.width(5.dp))
                Text(
                    text = "Seri",
                    style = TextStyle(
                        color = WidgetThemeTokens.OffWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    maxLines = 1
                )

                Spacer(modifier = GlanceModifier.defaultWeight())

                if (!isCompact) {
                    Box(
                        modifier = GlanceModifier
                            .background(
                                if (doneToday) WidgetThemeTokens.SuccessGreenBg else WidgetThemeTokens.AlertAmberBg
                            )
                            .cornerRadius(8.dp)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (doneToday) "Bugün Tamam" else "Odak Bekliyor",
                            style = TextStyle(
                                color = if (doneToday) WidgetThemeTokens.SuccessGreen else WidgetThemeTokens.AlertAmber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = GlanceModifier.width(6.dp))
                }

                Box(
                    modifier = GlanceModifier
                        .size(24.dp)
                        .background(WidgetThemeTokens.ElevatedCardBg)
                        .cornerRadius(12.dp)
                        .clickable(actionRunCallback<RefreshWidgetAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_refresh),
                        contentDescription = "Yenile",
                        modifier = GlanceModifier.size(12.dp),
                        colorFilter = ColorFilter.tint(WidgetThemeTokens.IceBlue)
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            // Streak Count Row
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${snapshot.streakCount} Gün",
                    style = TextStyle(
                        color = WidgetThemeTokens.OffWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompact) 20.sp else 24.sp
                    ),
                    maxLines = 1
                )

                if (!isCompact && snapshot.streakFreezeAvailable > 0) {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Box(
                        modifier = GlanceModifier
                            .background(WidgetThemeTokens.ActiveBlueBg)
                            .cornerRadius(8.dp)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${snapshot.streakFreezeAvailable} Dondurma",
                            style = TextStyle(
                                color = WidgetThemeTokens.IceBlue,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            // 7-Day Mini Status Indicator
            val days = snapshot.last7Days
            if (days.isNotEmpty()) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(WidgetThemeTokens.ElevatedCardBg)
                        .cornerRadius(12.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    days.forEachIndexed { index, day ->
                        DayMiniIndicator(
                            day = day,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        if (index < days.lastIndex) {
                            Spacer(modifier = GlanceModifier.width(4.dp))
                        }
                    }
                }
            }

            Spacer(modifier = GlanceModifier.defaultWeight())
            Spacer(modifier = GlanceModifier.height(6.dp))

            // CTA: Hemen Odaklan
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(WidgetThemeTokens.SignaturePurple)
                    .cornerRadius(14.dp)
                    .padding(vertical = 8.dp)
                    .clickable(actionStartActivity(startFocusIntent)),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_bolt),
                        contentDescription = null,
                        modifier = GlanceModifier.size(14.dp),
                        colorFilter = ColorFilter.tint(WidgetThemeTokens.OffWhite)
                    )
                    Spacer(modifier = GlanceModifier.width(5.dp))
                    Text(
                        text = "Hemen Odaklan",
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

@Composable
private fun DayMiniIndicator(day: DayFocusStatus, modifier: GlanceModifier = GlanceModifier) {
    val pillColor = when {
        day.isCompleted -> WidgetThemeTokens.AlertAmber
        day.minutes > 0L -> WidgetThemeTokens.IceBlue
        else -> WidgetThemeTokens.TrackBg
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(8.dp)
                .background(pillColor)
                .cornerRadius(4.dp)
        ) {}
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = day.dayLabel,
            style = TextStyle(
                color = if (day.isToday) WidgetThemeTokens.OffWhite else WidgetThemeTokens.MutedBlue,
                fontSize = 9.sp,
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
            ),
            maxLines = 1
        )
    }
}

class SharkStreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SharkStreakWidget()
}
