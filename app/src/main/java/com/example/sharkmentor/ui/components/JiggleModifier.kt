package com.example.sharkmentor.ui.components

import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

fun Modifier.jiggle(enabled: Boolean): Modifier = composed {
    if (!enabled) return@composed Modifier

    val infiniteTransition = rememberInfiniteTransition(label = "gentleJiggle")
    
    // Slowed down from 120ms to 260ms, reduced angle to a subtle +/- 0.75 degrees
    val rotation by infiniteTransition.animateFloat(
        initialValue = -0.75f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gentleRotation"
    )

    this.graphicsLayer {
        rotationZ = rotation
    }
}
