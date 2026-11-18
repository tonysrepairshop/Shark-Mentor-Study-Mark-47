package com.example.sharkmentor.ui.zen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.core.audio.ZenAudioManager
import com.example.sharkmentor.core.audio.ZenSound
import com.example.sharkmentor.ui.zen.dopamine.DopamineProtocolSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

enum class BreathingPattern(
    val ratioTitle: String,
    val subtitle: String,
    val steps: List<Pair<String, Int>>,
    val totalSecondsFor10Reps: Int
) {
    CALM_424(
        ratioTitle = "4-2-4",
        subtitle = "Sakinlik",
        steps = listOf("Nefes Al" to 4, "Tut" to 2, "Nefes Ver" to 4),
        totalSecondsFor10Reps = 100
    ),
    BOX_4444(
        ratioTitle = "4-4-4-4",
        subtitle = "Denge",
        steps = listOf("Nefes Al" to 4, "Nefesi Tut" to 4, "Nefes Ver" to 4, "Bekle" to 4),
        totalSecondsFor10Reps = 160
    ),
    RELAX_478(
        ratioTitle = "4-7-8",
        subtitle = "Rahatlama",
        steps = listOf("Derin Nefes Al" to 4, "İçinde Tut" to 7, "Ağır Ağır Ver" to 8),
        totalSecondsFor10Reps = 210
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenRoomScreen(
    onBackClick: () -> Unit
) {
    var selectedPattern by remember { mutableStateOf(BreathingPattern.BOX_4444) }
    var isBreathingActive by remember { mutableStateOf(false) }
    var isBreathingFinished by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()



    // 10-Rep Auto-Stop Engine
    LaunchedEffect(isBreathingActive, selectedPattern) {
        if (isBreathingActive) {
            isBreathingFinished = false
            var elapsed = 0
            val targetSeconds = selectedPattern.totalSecondsFor10Reps
            while (isBreathingActive && elapsed < targetSeconds) {
                delay(1000L)
                elapsed++
            }
            if (elapsed >= targetSeconds) {
                isBreathingActive = false
                isBreathingFinished = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zen Odası & Zihin Tazeleyici") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .imePadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. DERİN ODAKLANMA / EQUAL-HEIGHT BREATHING CARDS
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Derin Odaklanma",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Equal-Height Choice Cards Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BreathingPattern.entries.forEach { pattern ->
                            val isSelected = selectedPattern == pattern
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable {
                                        selectedPattern = pattern
                                        isBreathingActive = false
                                        isBreathingFinished = false
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                ),
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = pattern.ratioTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = pattern.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Breathing Visualizer
                    BreathingVisualizer(
                        phaseSteps = selectedPattern.steps,
                        isActive = isBreathingActive,
                        isFinished = isBreathingFinished
                    )

                    // Action Button (Clean text: "Nefes Egzersizi Başlat" or "Egzersizi Durdur")
                    Button(
                        onClick = {
                            if (isBreathingFinished) {
                                isBreathingFinished = false
                                isBreathingActive = true
                            } else {
                                isBreathingActive = !isBreathingActive
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBreathingActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isBreathingActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isBreathingActive) "Egzersizi Durdur" else "Nefes Egzersizi Başlat",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // 2. AMBİYANS SES KOLEKSİYONU (11 ATMOSPHERIC AMBIENT TRACKS)
            val currentSound by ZenAudioManager.currentSound.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val ambientTracks = remember { ZenSound.entries.filter { it != ZenSound.NONE } }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ambiyans Ses Koleksiyonu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (currentSound != ZenSound.NONE) {
                            Text(
                                text = "Çalıyor: ${currentSound.displayName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2EC4B6),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Adaptive 3-Column Matrix
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ambientTracks.chunked(3).forEach { rowSounds ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowSounds.forEach { sound ->
                                    val isSelected = currentSound == sound
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) Color(0xFF2EC4B6) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .background(
                                                color = if (isSelected) Color(0x1F2EC4B6) else MaterialTheme.colorScheme.surface,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable { ZenAudioManager.toggleSound(context, sound) }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = sound.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF2EC4B6) else MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2
                                        )
                                    }
                                }
                                if (rowSounds.size < 3) {
                                    repeat(3 - rowSounds.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Dopamine Fast section ───────
            Spacer(modifier = Modifier.height(20.dp))
            DopamineProtocolSection()
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
fun BreathingVisualizer(
    phaseSteps: List<Pair<String, Int>>,
    isActive: Boolean,
    isFinished: Boolean
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    val animatedScale = remember { Animatable(1.0f) }

    LaunchedEffect(isActive, stepIndex, phaseSteps) {
        if (!isActive) {
            animatedScale.snapTo(1.0f)
            stepIndex = 0
            return@LaunchedEffect
        }

        val safeIndex = stepIndex % phaseSteps.size
        val step = phaseSteps[safeIndex]
        val targetScale = when {
            step.first.contains("Al", ignoreCase = true) -> 1.45f
            step.first.contains("Ver", ignoreCase = true) -> 1.0f
            else -> animatedScale.value
        }

        animatedScale.animateTo(
            targetValue = targetScale,
            animationSpec = tween(durationMillis = step.second * 1000, easing = LinearEasing)
        )

        stepIndex = (stepIndex + 1) % phaseSteps.size
    }

    val safeIndex = stepIndex % phaseSteps.size
    val currentPhase = when {
        isFinished -> "Nefes Seansı Tamamlandı"
        isActive -> phaseSteps[safeIndex].first
        else -> "Hazır"
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(210.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = animatedScale.value, scaleY = animatedScale.value)
                .background(
                    if (isFinished) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    CircleShape
                )
                .border(2.dp, if (isFinished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary, CircleShape)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            if (isFinished) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = currentPhase,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            if (isActive) {
                Text(
                    text = "${phaseSteps[safeIndex].second}s",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
