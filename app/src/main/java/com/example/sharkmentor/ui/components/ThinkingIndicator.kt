package com.example.sharkmentor.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PulsingDotsIndicator(tint: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "PulsingDots")
    val dot1Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 0, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Dot1"
    )
    val dot2Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Dot2"
    )
    val dot3Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Dot3"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).scale(dot1Scale).background(tint, CircleShape))
        Spacer(modifier = Modifier.width(4.dp))
        Box(modifier = Modifier.size(6.dp).scale(dot2Scale).background(tint, CircleShape))
        Spacer(modifier = Modifier.width(4.dp))
        Box(modifier = Modifier.size(6.dp).scale(dot3Scale).background(tint, CircleShape))
    }
}

@Composable
fun DynamicThinkingCard(
    stages: List<String>,
    modifier: Modifier = Modifier
) {
    var currentStageIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(stages) {
        while (isActive) {
            delay(2800)
            currentStageIndex = (currentStageIndex + 1) % stages.size
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "BorderPulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BorderAlpha"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E293B), // Slate 800
        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = borderAlpha)), // Sky 400 with pulsing alpha
        modifier = modifier.padding(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            PulsingDotsIndicator(tint = Color(0xFF38BDF8))
            Spacer(modifier = Modifier.width(12.dp))
            AnimatedContent(
                targetState = stages[currentStageIndex],
                transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
                label = "ThoughtTextTransition"
            ) { stageText ->
                Text(
                    text = stageText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color(0xFFF1F5F9), // Slate 100
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}
