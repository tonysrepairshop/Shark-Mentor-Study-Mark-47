package com.example.sharkmentor.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ScanningOverlay(isScanning: Boolean) {
    if (!isScanning) return

    val infiniteTransition = rememberInfiniteTransition(label = "scanHud")
    val sweepOffsetY by infiniteTransition.animateFloat(
        initialValue = -40f,
        targetValue = 140f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweepOffset"
    )

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        val stages = listOf(
            "Belge taranıyor ve metin blokları ayrıştırılıyor...",
            "Dersler, saatler ve öğretmen isimleri tespit ediliyor...",
            "Haftalık dershane matrisine yerleştiriliyor...",
            "Son kontroller yapılıyor..."
        )
        DynamicThinkingCard(
            stages = stages,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
