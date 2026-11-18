package com.example.sharkmentor.ui.screens.leaguechat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.ui.viewmodel.QuestionSolveViewModel
import com.example.sharkmentor.ui.viewmodel.SolveRewardTier

/**
 * Lig Sohbeti Soru Çözüm Ekranı:
 * - FLAG_SECURE ile ekran görüntüsü ve ekran kaydı engeli (DisposableEffect ile girişte aktif, çıkışta temizlenir)
 * - 105 dakikalık canlı geri sayım sayacı ve Kademeli Ödül Banner'ı (Altın %100 / Gümüş %70 / Bronz %40)
 * - Orta alanda QuestionImageAnnotator ile soru fotoğrafı üzerine basınç hassasiyetli kalem/fosforlu kalemle çizim
 * - Alt kısımda adım adım çözüm açıklaması ve "Çözümü Gönder" butonu
 */
@Composable
fun QuestionSolveScreen(
    questionId: String,
    subject: String,
    topic: String,
    imageUrl: String,
    sharedAtMillis: Long,
    expiresAtMillis: Long = sharedAtMillis + 105 * 60_000L,
    onSubmitSolution: (explanation: String, annotatedBitmap: Bitmap?) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuestionSolveViewModel = viewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // FLAG_SECURE: Bu ekrandayken ekran görüntüsü alınmasını ve ekran kaydını engelle
    DisposableEffect(context) {
        val activity = context.findHostActivity()
        val window = activity?.window
        val hadSecureFlag = window?.attributes?.flags?.let { flags ->
            (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        } ?: false

        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        onDispose {
            if (!hadSecureFlag) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    LaunchedEffect(questionId, subject, topic, imageUrl, sharedAtMillis, expiresAtMillis) {
        viewModel.initializeQuestion(
            questionId = questionId,
            subject = subject,
            topic = topic,
            imageUrl = imageUrl,
            sharedAtMillis = sharedAtMillis,
            expiresAtMillis = expiresAtMillis
        )
    }

    val remainingSeconds by viewModel.remainingSeconds.collectAsState()
    val currentTier by viewModel.currentTier.collectAsState()
    val explanationText by viewModel.explanationText.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()
    val submitError by viewModel.submitError.collectAsState()

    val annotatorState = remember(questionId, imageUrl) { QuestionAnnotatorState() }
    var isComposerExpanded by remember { mutableStateOf(true) }

    val tierAccentColor = Color(currentTier.colorHex)
    val totalWindowSeconds = QuestionSolveViewModel.TOTAL_SOLVE_WINDOW_MINUTES * 60f
    val progressFraction = (remainingSeconds.toFloat() / totalWindowSeconds).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060A14),
                        Color(0xFF0B1222),
                        Color(0xFF090E1B)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 1. Üst Bilgi Barı + 105 Dakikalık Canlı Geri Sayım Sayacı
        Surface(
            color = Color(0xFF0E1729).copy(alpha = 0.96f),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Geri Dön",
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = subject.ifBlank { "Soru Çözümü" },
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF10B981).copy(alpha = 0.16f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        0.8.dp,
                                        Color(0xFF10B981).copy(alpha = 0.45f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "FLAG_SECURE Aktif",
                                            tint = Color(0xFF34D399),
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Text(
                                            text = "KORUMALI",
                                            color = Color(0xFF34D399),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }

                            if (topic.isNotBlank()) {
                                Text(
                                    text = topic,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Canlı 105:00 Geri Sayım Rozeti
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tierAccentColor.copy(alpha = 0.16f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            tierAccentColor.copy(alpha = 0.55f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Kalan Süre",
                                tint = tierAccentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = QuestionSolveViewModel.formatRemainingTime(remainingSeconds),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // 2. Kademeli Ödül Banner'ı (Altın %100 / Gümüş %70 / Bronz %40)
                RewardTierBanner(
                    currentTier = currentTier,
                    progressFraction = progressFraction
                )
            }
        }

        // 3. Orta Alan: Soru Fotoğrafı Üzerine Kalemle Çizim Tuvali (QuestionImageAnnotator)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            QuestionImageAnnotator(
                imageUrl = imageUrl,
                annotatorState = annotatorState,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 4. Alt Panel: Adım Adım Çözüm Açıklaması ve "Çözümü Gönder" Butonu
        Surface(
            color = Color(0xFF0E1729).copy(alpha = 0.98f),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Panel Başlığı + Çizim Durumu + Daralt/Genişlet
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isComposerExpanded = !isComposerExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Adım Adım Çözüm & Açıklama",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (annotatorState.hasAnnotations) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF38BDF8).copy(alpha = 0.16f),
                                border = androidx.compose.foundation.BorderStroke(
                                    0.8.dp,
                                    Color(0xFF38BDF8).copy(alpha = 0.45f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Brush,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "${annotatorState.drawingController.strokes.size} çizim eklendi",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (isComposerExpanded) "Küçült" else "Açıklama Yaz",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        Icon(
                            imageVector = if (isComposerExpanded) {
                                Icons.Default.ExpandMore
                            } else {
                                Icons.Default.ExpandLess
                            },
                            contentDescription = if (isComposerExpanded) "Paneli Küçült" else "Paneli Genişlet",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isComposerExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = explanationText,
                        onValueChange = { viewModel.updateExplanation(it) },
                        placeholder = {
                            Text(
                                text = "Sorunun çözüm adımlarını, kullandığın formülü ve doğru cevabı buraya yaz...",
                                color = Color(0xFF64748B),
                                fontSize = 13.sp
                            )
                        },
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF131E35),
                            unfocusedContainerColor = Color(0xFF131E35),
                            focusedBorderColor = tierAccentColor,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                            cursorColor = tierAccentColor
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (submitError != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = submitError ?: "",
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Çözümü Gönder Butonu
                Button(
                    onClick = {
                        val trimmedExplanation = explanationText.trim()
                        if (trimmedExplanation.isBlank() && !annotatorState.hasAnnotations) {
                            viewModel.setError(
                                "Lütfen soru görseli üzerine çizim yapın veya adım adım çözüm açıklaması yazın."
                            )
                            return@Button
                        }
                        focusManager.clearFocus()
                        viewModel.markSubmitting(true)
                        val annotatedBitmap = annotatorState.exportAnnotatedBitmap()
                        onSubmitSolution(trimmedExplanation, annotatedBitmap)
                        viewModel.markSubmitted()
                    },
                    enabled = !isSubmitting && currentTier != SolveRewardTier.EXPIRED_0,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tierAccentColor,
                        contentColor = Color(0xFF090D16),
                        disabledContainerColor = Color(0xFF334155),
                        disabledContentColor = Color(0xFF94A3B8)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            color = Color(0xFF090D16),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Çözüm Gönderiliyor...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (currentTier == SolveRewardTier.EXPIRED_0) {
                                "Çözüm Süresi Doldu (105 dk)"
                            } else {
                                "Çözümü Gönder (${currentTier.shortLabel} • +${currentTier.points} Puan)"
                            },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RewardTierBanner(
    currentTier: SolveRewardTier,
    progressFraction: Float
) {
    val activeColor = Color(currentTier.colorHex)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF152036))
            .border(
                width = 1.dp,
                color = activeColor.copy(alpha = 0.42f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = currentTier.badgeText,
                color = activeColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = "105 dk Penceresi",
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        LinearProgressIndicator(
            progress = { progressFraction },
            color = activeColor,
            trackColor = Color.White.copy(alpha = 0.08f),
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
        )

        // 3 Kademe Özeti (Altın 0-15dk | Gümüş 15-60dk | Bronz 60-105dk)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TierStagePill(
                tier = SolveRewardTier.GOLD_100,
                isActive = currentTier == SolveRewardTier.GOLD_100,
                modifier = Modifier.weight(1f)
            )
            TierStagePill(
                tier = SolveRewardTier.SILVER_70,
                isActive = currentTier == SolveRewardTier.SILVER_70,
                modifier = Modifier.weight(1f)
            )
            TierStagePill(
                tier = SolveRewardTier.BRONZE_40,
                isActive = currentTier == SolveRewardTier.BRONZE_40,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TierStagePill(
    tier: SolveRewardTier,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val tierColor = Color(tier.colorHex)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (isActive) tierColor.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                width = if (isActive) 1.dp else 0.5.dp,
                color = if (isActive) tierColor.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(7.dp)
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (isActive) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = tierColor,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = "${tier.shortLabel} (${tier.timeRangeLabel})",
            color = if (isActive) Color.White else Color(0xFF94A3B8),
            fontSize = 9.5.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
