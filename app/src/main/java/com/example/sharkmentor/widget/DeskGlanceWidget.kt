package com.example.sharkmentor.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.sharkmentor.service.blocker.StrictAppBlockerService
import java.util.Locale

class DeskGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read daily focus time (minutes) from SharedPreferences
        val prefs = context.getSharedPreferences("shark_desk_prefs", Context.MODE_PRIVATE)
        val dailyMinutes = prefs.getInt("daily_total_minutes", 225) // Example: 3h 45m
        val isDeskActive = prefs.getBoolean("is_desk_active", false)

        val hours = dailyMinutes / 60
        val mins = dailyMinutes % 60
        val timeDisplay = String.format(Locale.US, "%02d:%02d sa", hours, mins)

        provideContent {
            GlanceTheme {
                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(Color(0xFF070D14))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Left Column: Total Study Time and Status
                    Column(
                        modifier = GlanceModifier.defaultWeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "GÜNLÜK ODAK",
                            style = TextStyle(
                                color = ColorProvider(day = Color(0xFF64748B), night = Color(0xFF64748B)),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = timeDisplay,
                            style = TextStyle(
                                color = ColorProvider(day = Color.White, night = Color.White),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        Text(
                            text = if (isDeskActive) "Masada • Aktif" else "Boşta",
                            style = TextStyle(
                                color = ColorProvider(
                                    day = if (isDeskActive) Color(0xFF2EC4B6) else Color(0xFF94A3B8),
                                    night = if (isDeskActive) Color(0xFF2EC4B6) else Color(0xFF94A3B8)
                                ),
                                fontSize = 11.sp
                            )
                        )
                    }

                    // Right Button: 1-Tap Trigger
                    Box(
                        modifier = GlanceModifier
                            .background(if (isDeskActive) Color(0xFF1E293B) else Color(0xFF2EC4B6))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .clickable(actionRunCallback<ToggleDeskActionCallback>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isDeskActive) "Bitir" else "Masaya Geç",
                            style = TextStyle(
                                color = ColorProvider(
                                    day = if (isDeskActive) Color.White else Color.Black,
                                    night = if (isDeskActive) Color.White else Color.Black
                                ),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                    }
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
        if (isDeskActive) {
            context.startService(serviceIntent)
        } else {
            context.startForegroundService(serviceIntent)
        }

        prefs.edit().putBoolean("is_desk_active", !isDeskActive).apply()
        DeskGlanceWidget().update(context, glanceId)
    }
}

class DeskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DeskGlanceWidget()
}
