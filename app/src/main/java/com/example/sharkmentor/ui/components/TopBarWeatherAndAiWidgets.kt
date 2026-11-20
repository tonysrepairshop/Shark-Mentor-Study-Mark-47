package com.example.sharkmentor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.sharkmentor.data.weather.WeatherSettings
import com.example.sharkmentor.data.weather.WeatherUiState
import com.example.sharkmentor.data.weather.formatTemp
import com.example.sharkmentor.data.weather.weatherConditionLabel
import kotlinx.coroutines.delay

fun weatherIconForCode(code: Int): ImageVector {
    return when (code) {
        0, 1 -> Icons.Default.WbSunny
        2, 3 -> Icons.Default.Cloud
        45, 48 -> Icons.Default.Grain
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Default.WaterDrop
        71, 73, 75, 77, 85, 86 -> Icons.Default.AcUnit
        95, 96, 99 -> Icons.Default.Thunderstorm
        else -> Icons.Default.Cloud
    }
}

@Composable
fun TopBarWeatherPill(
    weatherState: WeatherUiState,
    settings: WeatherSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tempLabel = weatherState.currentTempCelsius?.let {
        formatTemp(it, settings.useFahrenheit)
    } ?: if (weatherState.isLoading) "..." else "--"

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = weatherIconForCode(weatherState.currentWeatherCode),
                contentDescription = weatherConditionLabel(weatherState.currentWeatherCode),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = tempLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
fun WeatherHourlyDialog(
    weatherState: WeatherUiState,
    settings: WeatherSettings,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentTempText = weatherState.currentTempCelsius?.let {
        formatTemp(it, settings.useFahrenheit)
    } ?: "--"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${weatherState.city}, ${weatherState.country}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${weatherConditionLabel(weatherState.currentWeatherCode)} • $currentTempText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !weatherState.isLoading
                ) {
                    if (weatherState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Yenile",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!weatherState.errorMessage.isNullOrBlank()) {
                    Text(
                        text = weatherState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (weatherState.hourly.isEmpty()) {
                    Text(
                        text = "Saatlik tahmin verisi henüz yüklenmedi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Saatlik Tahmin",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(weatherState.hourly) { item ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .width(72.dp)
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.hourLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        imageVector = weatherIconForCode(item.weatherCode),
                                        contentDescription = weatherConditionLabel(item.weatherCode),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = formatTemp(item.tempCelsius, settings.useFahrenheit),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Kapat",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}

@Composable
fun TopBarAiShortcutBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prompts = remember {
        listOf(
            "\"Bugün ne planlayalım?\"",
            "\"Deneme analizini yapalım mı?\"",
            "\"Zayıf konuları çıkaralım\"",
            "\"25 dk odak seansı kuralım\"",
            "\"Haftalık programını güncelleyelim\"",
            "\"Bugünkü hedeflerini belirle\"",
            "\"Yanlış sorularını özetleyelim\"",
            "\"Tekrar listeni gözden geçirelim\"",
            "\"Kısa bir mola sonrası planı?\"",
            "\"Hangi dersten başlayalım?\"",
            "\"Net artış stratejini kuralım\"",
            "\"Sınav öncesi hızlı tekrar?\"",
            "\"Günlük çalışma ritmini ölçelim\"",
            "\"Öncelikli konuları sıralayalım\"",
            "\"Akşam değerlendirmesi yapalım\"",
            "\"Shark AI ile yol haritanı çiz\""
        )
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            rotation.animateTo(
                targetValue = 90f,
                animationSpec = tween(durationMillis = 220)
            )
            currentIndex = (currentIndex + 1) % prompts.size
            rotation.snapTo(-90f)
            rotation.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 220)
            )
        }
    }

    Surface(
        modifier = modifier
            .graphicsLayer {
                rotationX = rotation.value
                cameraDistance = 12f * density
            }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = prompts[currentIndex],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}
