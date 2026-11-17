package com.example.sharkmentor.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.data.focus.FocusSessionManager

class FocusTimerGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val sessionManager = FocusSessionManager(context)
            val isRunning = sessionManager.isSessionActivelyRunning()

            WidgetContent(context = context, isRunning = isRunning)
        }
    }

    @Composable
    private fun WidgetContent(context: Context, isRunning: Boolean) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(18.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = GlanceModifier.defaultWeight()
                ) {
                    Text(
                        text = "SharkMentor Odak",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = if (isRunning) "Seans Devam Ediyor..." else "Oturum Kapalı",
                        style = TextStyle(
                            color = GlanceTheme.colors.secondary,
                            fontSize = 10.sp
                        )
                    )
                }

                // 1-Click Interactive Play / Pause Button
                Box(
                    modifier = GlanceModifier
                        .size(44.dp)
                        .background(if (isRunning) GlanceTheme.colors.error else GlanceTheme.colors.primary)
                        .cornerRadius(22.dp)
                        .clickable(actionRunCallback<ToggleTimerActionCallback>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(
                            if (isRunning) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                        ),
                        contentDescription = "Toggle Timer",
                        modifier = GlanceModifier.size(22.dp)
                    )
                }
            }
        }
    }
}

class FocusTimerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusTimerGlanceWidget()
}
