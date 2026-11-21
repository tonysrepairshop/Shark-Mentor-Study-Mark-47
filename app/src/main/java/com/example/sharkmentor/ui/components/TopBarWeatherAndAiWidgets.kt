package com.example.sharkmentor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import com.example.sharkmentor.data.topbar.TopBarAnimationType
import com.example.sharkmentor.data.topbar.TopBarFontOption
import com.example.sharkmentor.data.topbar.TopBarSettings
import com.example.sharkmentor.data.topbar.TopBarTextSource
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
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
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

object PendingAiPromptBus {
    val pendingPrompt = MutableStateFlow<String?>(null)
}

@Composable
fun TopBarAiShortcutBox(
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
topBarSettings: TopBarSettings = TopBarSettings()
) {
    val prompts = remember {
        listOf(
            "Bugün ne planlayalım?",
            "Deneme analizini yapalım mı?",
            "Zayıf konuları çıkaralım",
            "25 dk odak seansı kuralım",
            "Haftalık programını güncelleyelim",
            "Bugünkü hedeflerini belirle",
            "Yanlış sorularını özetleyelim",
            "Tekrar listeni gözden geçirelim",
            "Kısa bir mola sonrası planı?",
            "Hangi dersten başlayalım?",
            "Net artış stratejini kuralım",
            "Sınav öncesi hızlı tekrar?",
            "Günlük çalışma ritmini ölçelim",
            "Öncelikli konuları sıralayalım",
            "Akşam değerlendirmesi yapalım",
            "Shark AI ile yol haritanı çiz"
        )
    }

val activeTexts = remember(
topBarSettings.textSource,
topBarSettings.customTexts,
topBarSettings.aiQuotes,
prompts
) {
when (topBarSettings.textSource) {
TopBarTextSource.AI_SUGGESTIONS -> prompts
TopBarTextSource.CUSTOM_TEXTS ->
topBarSettings.customTexts.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { prompts }
TopBarTextSource.AI_QUOTES ->
topBarSettings.aiQuotes.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { prompts }
}
}

val fontFamily = when (topBarSettings.fontOption) {
TopBarFontOption.DEFAULT -> null
TopBarFontOption.SANS_SERIF -> FontFamily.SansSerif
TopBarFontOption.SERIF -> FontFamily.Serif
TopBarFontOption.MONOSPACE -> FontFamily.Monospace
}

val textColor = topBarSettings.customTextColorArgb?.let { Color(it.toInt()) }
?: MaterialTheme.colorScheme.onSurfaceVariant

var currentIndex by remember(activeTexts, topBarSettings.textSource) { mutableIntStateOf(0) }
var visibleText by remember { mutableStateOf("") }
var showCursor by remember { mutableStateOf(true) }
val flipRotation = remember { Animatable(0f) }

LaunchedEffect(topBarSettings.animationType, activeTexts) {
if (activeTexts.isEmpty()) return@LaunchedEffect
currentIndex = currentIndex.coerceIn(0, activeTexts.lastIndex)
when (topBarSettings.animationType) {
TopBarAnimationType.NONE -> {
visibleText = activeTexts[currentIndex]
showCursor = false
}
TopBarAnimationType.TYPEWRITER -> {
while (true) {
val fullText = activeTexts[currentIndex]
showCursor = true
for (i in 1..fullText.length) {
visibleText = fullText.substring(0, i)
delay(48L)
}
repeat(9) {
showCursor = !showCursor
delay(500L)
}
showCursor = true
for (i in fullText.length - 1 downTo 0) {
visibleText = fullText.substring(0, i)
delay(24L)
}
delay(350L)
currentIndex = (currentIndex + 1) % activeTexts.size
}
}
TopBarAnimationType.FLIP_BOX -> {
flipRotation.snapTo(0f)
while (true) {
delay(4500L)
flipRotation.animateTo(90f, animationSpec = tween(220))
currentIndex = (currentIndex + 1) % activeTexts.size
flipRotation.snapTo(-90f)
flipRotation.animateTo(0f, animationSpec = tween(220))
}
}
TopBarAnimationType.FADE,
TopBarAnimationType.SLIDE_VERTICAL,
TopBarAnimationType.SCALE_FADE -> {
while (true) {
delay(4500L)
currentIndex = (currentIndex + 1) % activeTexts.size
}
}
}
}

val safeIndex = currentIndex.coerceIn(0, activeTexts.lastIndex)
val currentText = activeTexts[safeIndex]
val cursor = if (showCursor) "|" else ""
val textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = fontFamily)
val displayText = if (topBarSettings.textSource == TopBarTextSource.AI_SUGGESTIONS) "\"$currentText\"" else currentText

Surface(
modifier = modifier
.let { baseModifier ->
if (topBarSettings.animationType == TopBarAnimationType.FLIP_BOX) {
baseModifier.graphicsLayer {
rotationX = flipRotation.value
cameraDistance = 12f * density
}
} else {
baseModifier
}
}
.clickable {
if (topBarSettings.textSource == TopBarTextSource.AI_SUGGESTIONS) {
onClick(currentText)
} else if (activeTexts.isNotEmpty()) {
currentIndex = (safeIndex + 1) % activeTexts.size
}
},
shape = RoundedCornerShape(12.dp),
color = MaterialTheme.colorScheme.surfaceVariant,
contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
) {
when (topBarSettings.animationType) {
TopBarAnimationType.NONE -> {
Text(
text = displayText,
style = textStyle,
color = textColor,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
)
}
TopBarAnimationType.TYPEWRITER -> {
val typewriterText = if (topBarSettings.textSource == TopBarTextSource.AI_SUGGESTIONS) {
"\"$visibleText\"$cursor"
} else {
"$visibleText$cursor"
}
Text(
text = typewriterText,
style = textStyle,
color = textColor,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
)
}
TopBarAnimationType.FLIP_BOX -> {
Text(
text = displayText,
style = textStyle,
color = textColor,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
)
}
TopBarAnimationType.FADE -> {
AnimatedContent(
targetState = displayText,
transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
label = "top_bar_fade"
) { target ->
Text(
text = target,
style = textStyle,
color = textColor,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
)
}
}
TopBarAnimationType.SLIDE_VERTICAL -> {
AnimatedContent(
targetState = displayText,
transitionSpec = {
(slideInVertically(tween(400)) { it } + fadeIn(tween(400))) togetherWith
(slideOutVertically(tween(400)) { -it } + fadeOut(tween(400)))
},
label = "top_bar_slide_vertical"
) { target ->
Text(
text = target,
style = textStyle,
color = textColor,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
)
}
}
TopBarAnimationType.SCALE_FADE -> {
AnimatedContent(
targetState = displayText,
transitionSpec = {
(scaleIn(initialScale = 0.85f, animationSpec = tween(400)) + fadeIn(tween(400))) togetherWith
(scaleOut(targetScale = 1.1f, animationSpec = tween(400)) + fadeOut(tween(400)))
},
label = "top_bar_scale_fade"
) { target ->
Text(
text = target,
style = textStyle,
color = textColor,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
)
}
}
}
}
}

