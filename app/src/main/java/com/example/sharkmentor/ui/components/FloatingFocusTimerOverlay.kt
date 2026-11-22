package com.example.sharkmentor.ui.components

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.service.FocusTimerService
import com.example.sharkmentor.core.timer.TimerMode
import com.example.sharkmentor.core.timer.TimerPhase
import com.example.sharkmentor.ui.screens.notes.drawing.NoteDrawingPreferences
import kotlin.math.roundToInt

/**
 * Live-synchronized, draggable, shape-switchable, resizable floating Focus Timer overlay
 * shared identically by Notes and PDF Reader.
 * Controlled from Settings -> "Not Özellikleri" or directly via the quick toggle.
 */
@Composable
fun FloatingFocusTimerOverlay(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        NoteDrawingPreferences.ensureLoaded(context)
    }

    val visible by NoteDrawingPreferences.showFloatingFocusTimer.collectAsStateWithLifecycle()
    if (!visible) return

    val isCompact by NoteDrawingPreferences.floatingFocusTimerCompact.collectAsStateWithLifecycle()
    val scale by NoteDrawingPreferences.floatingFocusTimerScale.collectAsStateWithLifecycle()
    val savedXRatio by NoteDrawingPreferences.floatingFocusTimerXRatio.collectAsStateWithLifecycle()
    val savedYRatio by NoteDrawingPreferences.floatingFocusTimerYRatio.collectAsStateWithLifecycle()
    val timerState by FocusTimerService.timerStateFlow.collectAsStateWithLifecycle()

    val colorScheme = MaterialTheme.colorScheme
    val primaryColor = colorScheme.primary
    val onSurfaceColor = colorScheme.onSurface

    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var dragX by remember { mutableFloatStateOf(savedXRatio * viewportWidthPx.coerceAtLeast(1f)) }
    var dragY by remember { mutableFloatStateOf(savedYRatio * viewportHeightPx.coerceAtLeast(1f)) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(savedXRatio, savedYRatio, viewportWidthPx, viewportHeightPx) {
        if (!isDragging && viewportWidthPx > 1f && viewportHeightPx > 1f) {
            dragX = savedXRatio * viewportWidthPx
            dragY = savedYRatio * viewportHeightPx
        }
    }

    val maxX = (viewportWidthPx - overlaySize.width.toFloat() - 8f).coerceAtLeast(8f)
    val maxY = (viewportHeightPx - overlaySize.height.toFloat() - 8f).coerceAtLeast(8f)
    val clampedX = dragX.coerceIn(8f, maxX)
    val clampedY = dragY.coerceIn(8f, maxY)

    val statusColor = when (timerState.timerPhase) {
        TimerPhase.FOCUSING -> primaryColor
        TimerPhase.PAUSED -> Color(0xFFF59E0B)
        TimerPhase.MANDATORY_BREAK -> colorScheme.tertiary
        TimerPhase.IDLE -> onSurfaceColor.copy(alpha = 0.4f)
    }

    val modeLabel = when {
        timerState.timerPhase == TimerPhase.IDLE -> "Sayaç"
        timerState.timerPhase == TimerPhase.MANDATORY_BREAK -> "Mola"
        timerState.timerPhase == TimerPhase.PAUSED -> "Duraklatıldı"
        timerState.currentMode == TimerMode.STOPWATCH -> "Kronometre"
        timerState.currentMode == TimerMode.POMODORO -> "Pomodoro"
        timerState.currentMode == TimerMode.BLOCK_90 -> "90 Dk"
        else -> "Odak"
    }

    val formattedTime = FocusTimerService.formatMillis(timerState.displayMillis)
    val clampedScale = scale.coerceIn(0.8f, 1.4f)

    Surface(
        modifier = modifier
            .zIndex(25f)
            .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
            .onSizeChanged { overlaySize = it }
            .pointerInput(viewportWidthPx, viewportHeightPx, overlaySize) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        if (viewportWidthPx > 1f && viewportHeightPx > 1f) {
                            val nx = (dragX.coerceIn(8f, maxX) / viewportWidthPx).coerceIn(0.02f, 0.95f)
                            val ny = (dragY.coerceIn(8f, maxY) / viewportHeightPx).coerceIn(0.02f, 0.90f)
                            NoteDrawingPreferences.setFloatingFocusTimerPosition(context, nx, ny)
                        }
                    },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragX = (dragX + dragAmount.x).coerceIn(8f, maxX)
                        dragY = (dragY + dragAmount.y).coerceIn(8f, maxY)
                    }
                )
            }
            .animateContentSize(),
        shape = if (isCompact) RoundedCornerShape(50) else RoundedCornerShape(16.dp),
        color = colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 1.2.dp,
            color = if (timerState.timerPhase == TimerPhase.FOCUSING) {
                primaryColor.copy(alpha = 0.55f)
            } else {
                colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
        )
    ) {
        if (isCompact) {
            Row(
                modifier = Modifier
                    .clickable {
                        NoteDrawingPreferences.setFloatingFocusTimerCompact(context, false)
                    }
                    .padding(
                        horizontal = (10f * clampedScale).dp,
                        vertical = (6f * clampedScale).dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((6f * clampedScale).dp)
            ) {
                Box(
                    modifier = Modifier
                        .size((8f * clampedScale).dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = formattedTime,
                    fontSize = (13.5f * clampedScale).sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                )
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Genişlet",
                    tint = onSurfaceColor.copy(alpha = 0.55f),
                    modifier = Modifier.size((13f * clampedScale).dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(
                    horizontal = (11f * clampedScale).dp,
                    vertical = (8f * clampedScale).dp
                ),
                verticalArrangement = Arrangement.spacedBy((6f * clampedScale).dp)
            ) {
                // Header: Mode + Collapse + Close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size((8f * clampedScale).dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = modeLabel,
                        fontSize = (11f * clampedScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { NoteDrawingPreferences.setFloatingFocusTimerCompact(context, true) },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.UnfoldLess,
                            contentDescription = "Küçült",
                            tint = onSurfaceColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = { NoteDrawingPreferences.setShowFloatingFocusTimer(context, false) },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Gizle",
                            tint = onSurfaceColor.copy(alpha = 0.65f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                // Live synced clock readout
                Text(
                    text = formattedTime,
                    fontSize = (20f * clampedScale).sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = onSurfaceColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                // Size controls + Quick Pause/Resume when active
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Surface(
                        onClick = {
                            NoteDrawingPreferences.setFloatingFocusTimerScale(context, clampedScale - 0.15f)
                        },
                        enabled = clampedScale > 0.82f,
                        shape = CircleShape,
                        color = onSurfaceColor.copy(alpha = 0.08f),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Boyutu küçült",
                                tint = onSurfaceColor.copy(alpha = if (clampedScale > 0.82f) 0.85f else 0.3f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            NoteDrawingPreferences.setFloatingFocusTimerScale(context, clampedScale + 0.15f)
                        },
                        enabled = clampedScale < 1.38f,
                        shape = CircleShape,
                        color = onSurfaceColor.copy(alpha = 0.08f),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Boyutu büyüt",
                                tint = onSurfaceColor.copy(alpha = if (clampedScale < 1.38f) 0.85f else 0.3f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    if (timerState.timerPhase == TimerPhase.FOCUSING || timerState.timerPhase == TimerPhase.PAUSED) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val isPaused = timerState.timerPhase == TimerPhase.PAUSED
                        Surface(
                            onClick = {
                                val action = if (isPaused) FocusTimerService.ACTION_RESUME else FocusTimerService.ACTION_PAUSE
                                runCatching {
                                    context.startService(Intent(context, FocusTimerService::class.java).apply {
                                        this.action = action
                                    })
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = primaryColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Devam Et" else "Duraklat",
                                    tint = primaryColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isPaused) "Devam" else "Duraklat",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
