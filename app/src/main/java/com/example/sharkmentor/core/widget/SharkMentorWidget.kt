package com.example.sharkmentor.core.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.core.analytics.MasteryScoreEngine
import com.example.sharkmentor.data.local.SharkMentorDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

class SharkMentorWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (masteryScore, todayFocusMins, pendingReviews) = withContext(Dispatchers.IO) {
            try {
                val db = SharkMentorDatabase.getDatabase(context)

                val errors = db.errorQuestionDao().getAllQuestionsRaw()
                val now = System.currentTimeMillis()
                val dueCount = errors.count { it.state == 0 || it.dueDateMillis <= now }

                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis
                val focusSessions = db.sessionDao().getSessionsSince(startOfDay)
                val todayMins = focusSessions.sumOf { it.durationSeconds / 60 }

                val topics = db.goalsDao().getAllTopicsRaw()
                val resources = db.goalsDao().getAllResourcesRaw()
                val allFocus = db.sessionDao().getAllSessionsRaw()
                val totalFocusMinutes = allFocus.sumOf { it.durationSeconds / 60 }

                val reflections = db.dailyReflectionDao().getRecentReflectionsRaw()
                val avgScore = if (reflections.isNotEmpty()) reflections.map { it.productivityScore }.average().toFloat() else null

                val report = MasteryScoreEngine.calculate(
                    topics = topics,
                    resources = resources,
                    errorQuestions = errors,
                    totalFocusMinutes = totalFocusMinutes,
                    recentReflectionAvg = avgScore
                )

                Triple(report.overallScore, todayMins, dueCount)
            } catch (_: Exception) {
                Triple(0, 0L, 0)
            }
        }

        provideContent {
            GlanceTheme {
                WidgetLayout(
                    masteryScore = masteryScore,
                    todayFocusMins = todayFocusMins,
                    pendingReviews = pendingReviews
                )
            }
        }
    }

    @Composable
    private fun WidgetLayout(
        masteryScore: Int,
        todayFocusMins: Long,
        pendingReviews: Int
    ) {
        // Color Palette
        val darkBg = Color(0xFF0B132B)           // Deep Ocean Navy
        val cardBg = Color(0xFF1E293B)           // Slate Container
        val offWhite = Color(0xFFF8FAFC)         // Crisp Broken White
        val textSecondary = Color(0xFF94A3B8)    // Muted Slate Grey
        val signaturePurple = Color(0xFF7C3AED)  // App Signature Vibrant Purple
        val alertAmber = Color(0xFFF59E0B)
        val successGreen = Color(0xFF10B981)

        val openAppIntent = Intent(androidx.glance.LocalContext.current, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val startFocusIntent = Intent(androidx.glance.LocalContext.current, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_START_FOCUS", true)
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(darkBg)
                .cornerRadius(18.dp)
                .padding(10.dp)
                .clickable(actionStartActivity(openAppIntent))
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {

                // 1. HEADER ROW: Title (Shark Study Mentor) + Status Badge + Refresh Button
                Row(
                    modifier = GlanceModifier.fillMaxWidth().height(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shark Study Mentor Title Hierarchy
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Shark ",
                            style = TextStyle(
                                color = ColorProvider(offWhite),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "Study ",
                            style = TextStyle(
                                color = ColorProvider(textSecondary),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Text(
                            text = "Mentor",
                            style = TextStyle(
                                color = ColorProvider(offWhite),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    // Status Badge (Shifted slightly left)
                    Box(
                        modifier = GlanceModifier
                            .background(if (pendingReviews > 0) alertAmber.copy(alpha = 0.25f) else successGreen.copy(alpha = 0.25f))
                            .cornerRadius(6.dp)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (pendingReviews > 0) "$pendingReviews Tekrar" else "Kuyruk Temiz",
                            style = TextStyle(
                                color = ColorProvider(if (pendingReviews > 0) alertAmber else successGreen),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(6.dp))

                    // Manual Refresh Button (🔄)
                    Box(
                        modifier = GlanceModifier
                            .background(cardBg)
                            .cornerRadius(6.dp)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clickable(actionRunCallback<RefreshWidgetAction>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔄",
                            style = TextStyle(
                                color = ColorProvider(offWhite),
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // 2. METRIC CARDS ROW (Ustalık, Bugün Odak, Mor Odaklan Butonu)
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // KART 1: Genel Ustalık
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .background(cardBg)
                            .cornerRadius(10.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "%$masteryScore",
                                style = TextStyle(
                                    color = ColorProvider(offWhite),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Genel Ustalık",
                                style = TextStyle(
                                    color = ColorProvider(textSecondary),
                                    fontSize = 9.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.width(6.dp))

                    // KART 2: Bugün Odak
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .background(cardBg)
                            .cornerRadius(10.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$todayFocusMins dk",
                                style = TextStyle(
                                    color = ColorProvider(offWhite),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Bugün Odak",
                                style = TextStyle(
                                    color = ColorProvider(textSecondary),
                                    fontSize = 9.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.width(6.dp))

                    // KART 3: ⚡ Odaklan Butonu (İmza Mor Renk #7C3AED)
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .background(signaturePurple)
                            .cornerRadius(10.dp)
                            .padding(4.dp)
                            .clickable(actionStartActivity(startFocusIntent)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "⚡",
                                style = TextStyle(
                                    color = ColorProvider(offWhite),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Odaklan",
                                style = TextStyle(
                                    color = ColorProvider(offWhite),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
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
