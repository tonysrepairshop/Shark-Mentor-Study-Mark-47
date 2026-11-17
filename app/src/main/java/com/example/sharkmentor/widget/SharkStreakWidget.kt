package com.example.sharkmentor.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.FontWeight
import androidx.glance.Button
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider
import com.example.sharkmentor.MainActivity

class SharkStreakWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("streak_prefs", Context.MODE_PRIVATE)
        val streakCount = prefs.getInt("streakCount", 0)

        provideContent {
            // Because custom dynamic themes are used, we fallback to GlanceTheme which 
            // uses system Material You (monet) on A12+ or default styles, or we can use 
            // a custom ColorProvider. 
            // To abide strictly by user requirements: "wrap the Glance composable with GlanceTheme"
            androidx.glance.GlanceTheme {
                WidgetContent(streakCount = streakCount)
            }
        }
    }
}

@Composable
fun WidgetContent(streakCount: Int) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(androidx.glance.GlanceTheme.colors.surface)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🔥 SharkMentor Seri",
            style = TextStyle(
                color = androidx.glance.GlanceTheme.colors.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            ),
            modifier = GlanceModifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = "$streakCount Gün",
            style = TextStyle(
                color = androidx.glance.GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            ),
            modifier = GlanceModifier.padding(bottom = 16.dp)
        )

        Button(
            text = "Hemen Odaklan",
            onClick = actionStartActivity<MainActivity>(),
            colors = androidx.glance.ButtonDefaults.buttonColors(
                backgroundColor = androidx.glance.GlanceTheme.colors.primary,
                contentColor = androidx.glance.GlanceTheme.colors.onPrimary
            )
        )
    }
}

class SharkStreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SharkStreakWidget()
}
