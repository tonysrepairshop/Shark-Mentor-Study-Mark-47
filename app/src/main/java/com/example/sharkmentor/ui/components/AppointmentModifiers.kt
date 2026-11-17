package com.example.sharkmentor.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.diagonalStrikeThrough(
    isStriked: Boolean,
    strikeColor: Color = Color(0xFFEF4444).copy(alpha = 0.7f),
    strokeWidth: Dp = 2.dp,
    disabledAlpha: Float = 0.32f
): Modifier = this.then(
    if (isStriked) {
        Modifier
            .alpha(disabledAlpha)
            .drawWithContent {
                drawContent()
                drawLine(
                    color = strikeColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                    strokeWidth = strokeWidth.toPx()
                )
            }
    } else {
        Modifier
    }
)
