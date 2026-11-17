package com.example.sharkmentor.ui.aichat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ActionFlashCardsRow(
    cards: List<QuickActionCard>,
    onCardClick: (QuickActionCard) -> Unit
) {
    AnimatedVisibility(
        visible = cards.isNotEmpty(),
        enter = fadeIn() + slideInHorizontally(),
        exit = fadeOut()
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(cards, key = { it.id }) { card ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B), // Slate 800
                    border = BorderStroke(1.dp, Color(0xFF334155)), // Slate 700
                    modifier = Modifier
                        .width(240.dp)
                        .height(100.dp)
                        .clickable { onCardClick(card) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color(0xFF0F172A), CircleShape), // Slate 900
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = card.icon,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8), // Sky 400
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = card.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF1F5F9), // Slate 100
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = card.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8), // Slate 400
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}
