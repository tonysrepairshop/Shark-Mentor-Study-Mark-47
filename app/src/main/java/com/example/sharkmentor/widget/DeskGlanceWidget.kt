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
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
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
import com.example.sharkmentor.core.widget.RefreshWidgetAction
import com.example.sharkmentor.core.widget.WidgetSnapshot
import com.example.sharkmentor.core.widget.WidgetThemeTokens
import com.example.sharkmentor.core.widget.WidgetUpdateManager
import com.example.sharkmentor.service.blocker.StrictAppBlockerService

class DeskGlanceWidget : GlanceAppWidget() {

    companion object {
        private val SIZE_COMPACT = DpSize(180.dp, 64.dp)
        private val SIZE_MEDIUM = DpSize(240.dp, 100.dp)
        private val SIZE_EXPANDED = DpSize(280.dp, 140.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_COMPACT, SIZE_MEDIUM, SIZE_EXPANDED)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetUpdateManager.loadSnapshot(context)

        provideContent {
            GlanceTheme {
                DeskWidgetContent(snapshot = snapshot)
            }
        }
    }

    @Composable
    private fun DeskWidgetContent(snapshot: WidgetSnapshot) {
        val context = LocalContext.current
        val size = LocalSize.current
        val isCompact = size.height < 90.dp

        val isDeskActive = snapshot.isDeskActive
        val todayMinutes = snapshot.todayFocusMinutes
        val goalMinutes = snapshot.dailyGoalMinutes
        val goalProgress = (todayMinutes.toFloat() / goalMinutes.toFloat()).coerceIn(0f, 1f)
        val goalPercent = (goalProgress * 100).toInt().coerceIn(0, 100)

        val openDeskIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_TARGET_ROUTE", "home")
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetThemeTokens.DeepOceanBg)
                .cornerRadius(24.dp)
                .padding(if (isCompact) 10.dp else 12.dp)
                .clickable(actionStartActivity(openDeskIntent))
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "GÜNLÜK ODAK",
                                style = TextStyle(
                                    color = WidgetThemeTokens.MutedBlue,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            Box(
                                modifier = GlanceModifier
                                    .background(
                                        if (isDeskActive) WidgetThemeTokens.SuccessGreenBg else WidgetThemeTokens.ElevatedCardBg
                                    )
                                    .cornerRadius(8.dp)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isDeskActive) "Masada" else "Boşta",
                                    style = TextStyle(
                                        color = if (isDeskActive) WidgetThemeTokens.OceanTeal else WidgetThemeTokens.MutedBlue,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height(2.dp))

                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = WidgetUpdateManager.formatMinutesCompact(todayMinutes),
                                style = TextStyle(
                                    color = WidgetThemeTokens.OffWhite,
                                    fontSize = if (isCompact) 18.sp else 20.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            Text(
                                text = "/ ${WidgetUpdateManager.formatMinutesCompact(goalMinutes)}",
                                style = TextStyle(
                                    color = WidgetThemeTokens.MutedBlue,
                                    fontSize = 11.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    if (!isCompact) {
                        Box(
                            modifier = GlanceModifier
                                .size(30.dp)
                                .background(WidgetThemeTokens.ElevatedCardBg)
                                .cornerRadius(12.dp)
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
                        Spacer(modifier = GlanceModifier.width(6.dp))
                    }

                    // 1-Tap Desk Toggle Button
                    Box(
                        modifier = GlanceModifier
                            .background(
                                if (isDeskActive) WidgetThemeTokens.PauseRedBg else WidgetThemeTokens.OceanTeal
                            )
                            .cornerRadius(16.dp)
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                            .clickable(actionRunCallback<ToggleDeskActionCallback>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                provider = ImageProvider(
                                    if (isDeskActive) R.drawable.ic_widget_stop else R.drawable.ic_widget_desk
                                ),
                                contentDescription = null,
                                modifier = GlanceModifier.size(15.dp),
                                colorFilter = ColorFilter.tint(
                                    if (isDeskActive) WidgetThemeTokens.PauseRed else WidgetThemeTokens.DarkOnAccent
                                )
                            )
                            Spacer(modifier = GlanceModifier.width(5.dp))
                            Text(
                                text = if (isDeskActive) "Bitir" else "Masaya Geç",
                                style = TextStyle(
                                    color = if (isDeskActive) WidgetThemeTokens.OffWhite else WidgetThemeTokens.DarkOnAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Goal Progress Row
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = goalProgress,
                        modifier = GlanceModifier
                            .defaultWeight()
                            .height(6.dp)
                            .cornerRadius(3.dp),
                        color = if (isDeskActive) WidgetThemeTokens.OceanTeal else WidgetThemeTokens.IceBlue,
                        backgroundColor = WidgetThemeTokens.TrackBg
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = "%$goalPercent",
                        style = TextStyle(
                            color = WidgetThemeTokens.IceBlue,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

class ToggleDeskActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = context.getSharedPreferences("shark_desk_prefs", Context.MODE_PRIVATE)
        val isDeskActive = prefs.getBoolean("is_desk_active", false)

        val serviceIntent = Intent(context, StrictAppBlockerService::class.java).apply {
            action = if (isDeskActive) StrictAppBlockerService.ACTION_STOP_SERVICE else null
        }

        runCatching {
            if (isDeskActive) {
                context.startService(serviceIntent)
            } else {
                context.startForegroundService(serviceIntent)
            }
        }

        prefs.edit().putBoolean("is_desk_active", !isDeskActive).apply()
        WidgetUpdateManager.updateAllWidgets(context)
    }
}

class DeskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DeskGlanceWidget()
}
