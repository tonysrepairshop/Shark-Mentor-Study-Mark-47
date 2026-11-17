package com.example.sharkmentor.ui.focus.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CircularTimerDisplay(
    progress: Float, // Normalized value from 0.0f to 1.0f
    timeText: String,
    modeLabel: String,
    modifier: Modifier = Modifier
) {
    // 1. Theme Color Extraction
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    // 2. High-Contrast Track Colors (Ensures 100% visibility on all dark themes)
    val solidTrackColor = surfaceContainerHigh

    // 3. Smooth Progress Animation
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "CircularTimerProgressAnimation"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(280.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            val strokeWidthPx = 14.dp.toPx()
            val diameter = size.minDimension - strokeWidthPx
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(diameter, diameter)

            // 1. Permanent 360-degree background orbit track
            drawCircle(
                color = solidTrackColor,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidthPx)
            )

            // 2. Active animated progress arc overlay
            drawArc(
                brush = Brush.sweepGradient(
                    0.0f to secondaryColor,
                    1.0f to primaryColor,
                    center = center
                ),
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            )
        }

        // Central Countdown Display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    letterSpacing = (-1).sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = modeLabel.uppercase(java.util.Locale("tr")),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
