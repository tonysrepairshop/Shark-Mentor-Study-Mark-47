package com.example.sharkmentor.ui.widget

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
import com.example.sharkmentor.core.service.FocusTimerService
import com.example.sharkmentor.core.timer.TimerPhase
import com.example.sharkmentor.core.widget.RefreshWidgetAction
import com.example.sharkmentor.core.widget.WidgetSnapshot
import com.example.sharkmentor.core.widget.WidgetThemeTokens
import com.example.sharkmentor.core.widget.WidgetUpdateManager

class FocusTimerGlanceWidget : GlanceAppWidget() {

    companion object {
        private val SIZE_COMPACT = DpSize(180.dp, 72.dp)
        private val SIZE_WIDE = DpSize(240.dp, 88.dp)
        private val SIZE_EXPANDED = DpSize(260.dp, 130.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_COMPACT, SIZE_WIDE, SIZE_EXPANDED)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetUpdateManager.loadSnapshot(context)
        provideContent {
            GlanceTheme {
                WidgetContent(snapshot = snapshot)
            }
        }
    }

    @Composable
    private fun WidgetContent(snapshot: WidgetSnapshot) {
        val context = LocalContext.current
        val size = LocalSize.current
        val isExpanded = size.height >= 115.dp
        val isWide = size.width >= 220.dp

        val timerState = snapshot.timerState
        val phase = timerState.timerPhase
        val isFocusing = phase == TimerPhase.FOCUSING
        val isPaused = phase == TimerPhase.PAUSED
        val isBreak = phase == TimerPhase.MANDATORY_BREAK

        val statusLabel = when {
            isBreak -> "Mola"
            isFocusing -> "Odaklanıyor"
            isPaused -> "Duraklatıldı"
            else -> "Hazır"
        }
        val statusColor = when {
            isBreak -> WidgetThemeTokens.IceBlue
            isFocusing -> WidgetThemeTokens.OceanTeal
            isPaused -> WidgetThemeTokens.AlertAmber
            else -> WidgetThemeTokens.MutedBlue
        }
        val statusBg = when {
            isBreak -> WidgetThemeTokens.ActiveBlueBg
            isFocusing -> WidgetThemeTokens.SuccessGreenBg
            isPaused -> WidgetThemeTokens.AlertAmberBg
            else -> WidgetThemeTokens.ElevatedCardBg
        }

        val modeTitle = if (phase == TimerPhase.IDLE) {
            "SharkMentor Odak"
        } else {
            "${timerState.currentMode.displayName} • ${FocusTimerService.formatMillis(timerState.displayMillis)}"
        }

        val todayFocusText = "Bugün: ${WidgetUpdateManager.formatMinutesCompact(snapshot.todayFocusMinutes)}"
        val progress = if (phase != TimerPhase.IDLE) {
            timerState.progress
        } else {
            (snapshot.todayFocusMinutes.toFloat() / snapshot.dailyGoalMinutes.toFloat()).coerceIn(0f, 1f)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_TARGET_ROUTE", "home")
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetThemeTokens.DeepOceanBg)
                .cornerRadius(24.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(openAppIntent))
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = GlanceModifier
                                    .background(statusBg)
                                    .cornerRadius(8.dp)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = statusLabel,
                                    style = TextStyle(
                                        color = statusColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1
                                )
                            }
                            if (isWide) {
                                Spacer(modifier = GlanceModifier.width(6.dp))
                                Text(
                                    text = todayFocusText,
                                    style = TextStyle(
                                        color = WidgetThemeTokens.MutedBlue,
                                        fontSize = 10.sp
                                    ),
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height(4.dp))

                        Text(
                            text = modeTitle,
                            style = TextStyle(
                                color = WidgetThemeTokens.OffWhite,
                                fontSize = if (phase == TimerPhase.IDLE) 14.sp else 15.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )

                        if (!isWide) {
                            Text(
                                text = todayFocusText,
                                style = TextStyle(
                                    color = WidgetThemeTokens.MutedBlue,
                                    fontSize = 10.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Refresh Button
                    Box(
                        modifier = GlanceModifier
                            .size(34.dp)
                            .background(WidgetThemeTokens.ElevatedCardBg)
                            .cornerRadius(12.dp)
                            .clickable(actionRunCallback<RefreshWidgetAction>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_refresh),
                            contentDescription = "Yenile",
                            modifier = GlanceModifier.size(16.dp),
                            colorFilter = ColorFilter.tint(WidgetThemeTokens.IceBlue)
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(6.dp))

                    // 1-Click Play / Pause Button -> FocusTimerService
                    Box(
                        modifier = GlanceModifier
                            .size(44.dp)
                            .background(
                                when {
                                    isFocusing -> WidgetThemeTokens.AlertAmber
                                    isBreak -> WidgetThemeTokens.ElevatedCardBg
                                    else -> WidgetThemeTokens.OceanTeal
                                }
                            )
                            .cornerRadius(16.dp)
                            .clickable(actionRunCallback<ToggleTimerActionCallback>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(
                                if (isFocusing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                            ),
                            contentDescription = if (isFocusing) "Duraklat" else "Başlat",
                            modifier = GlanceModifier.size(20.dp),
                            colorFilter = ColorFilter.tint(
                                if (isBreak) WidgetThemeTokens.IceBlue else WidgetThemeTokens.DarkOnAccent
                            )
                        )
                    }
                }

                if (isExpanded) {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .cornerRadius(3.dp),
                        color = if (isFocusing) WidgetThemeTokens.OceanTeal else WidgetThemeTokens.IceBlue,
                        backgroundColor = WidgetThemeTokens.TrackBg
                    )
                }
            }
        }
    }
}

class FocusTimerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusTimerGlanceWidget()
}
