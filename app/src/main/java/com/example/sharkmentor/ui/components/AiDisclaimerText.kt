package com.example.sharkmentor.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AiDisclaimerText(
    modifier: Modifier = Modifier,
    text: String = "Yapay zekâ yanıtları hata içerebilir; nihai kontrol ve akademik doğrulama öğrenciye aittir."
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 13.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        modifier = modifier.padding(top = 4.dp)
    )
}
