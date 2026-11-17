package com.example.sharkmentor.ui.aichat

import androidx.compose.ui.graphics.vector.ImageVector

data class QuickActionCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val promptPayload: String,
    val icon: ImageVector,
    val targetMode: AiPersona
)
