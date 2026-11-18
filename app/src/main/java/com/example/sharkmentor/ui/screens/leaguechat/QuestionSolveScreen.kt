package com.example.sharkmentor.ui.screens.leaguechat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingTool
import com.example.sharkmentor.ui.viewmodel.QuestionSolveViewModel
import com.example.sharkmentor.ui.viewmodel.SolveRewardTier
import com.example.sharkmentor.ui.viewmodel.SolveWorkspaceTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lig Sohbeti Soru Çözüm Ekranı:
 * - FLAG_SECURE ile ekran görüntüsü ve ekran kaydı engeli (DisposableEffect ile girişte aktif, çıkışta temizlenir)
 * - 105 dakikalık canlı geri sayım sayacı ve Kademeli Ödül Banner'ı (Altın %100 / Gümüş %70 / Bronz %40)
 * - 4 Tam Araç Desteği:
 *   1. Kalem (basınç hassasiyetli çizim)
 *   2. Metin Kutusu (en az 25 karakter kuralı)
 *   3. Kamera / Galeri (kağıda çözülen fotoğrafı seçme)
 *   4. Shark AI ("AI çözümünü görürsen bu soruya çözüm gönderemez ve puan alamazsın" onay diyaloğu + onAiLockConfirmed + gönder butonu kilidi)
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
    onAiLockConfirmed: () -> Unit = {},
    initialAiLocked: Boolean = false,
    questionNote: String = "",
    viewModel: QuestionSolveViewModel = viewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val textBoxFocusRequester = remember { FocusRequester() }

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

    LaunchedEffect(questionId, subject, topic, imageUrl, sharedAtMillis, expiresAtMillis, initialAiLocked) {
        viewModel.initializeQuestion(
            questionId = questionId,
            subject = subject,
            topic = topic,
            imageUrl = imageUrl,
            sharedAtMillis = sharedAtMillis,
            expiresAtMillis = expiresAtMillis,
            initialAiLocked = initialAiLocked
        )
    }

    val remainingSeconds by viewModel.remainingSeconds.collectAsState()
    val currentTier by viewModel.currentTier.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val explanationText by viewModel.explanationText.collectAsState()
    val paperSolutionBitmap by viewModel.paperSolutionBitmap.collectAsState()
    val isAiLocked by viewModel.isAiLocked.collectAsState()
    val aiSolutionText by viewModel.aiSolutionText.collectAsState()
    val isAiLoading by viewModel.isAiLoading.collectAsState()
    val aiError by viewModel.aiError.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()
    val submitError by viewModel.submitError.collectAsState()

    val annotatorState = remember(questionId, imageUrl) { QuestionAnnotatorState() }
    var isBottomPanelExpanded by remember { mutableStateOf(true) }
    var showSharkAiConfirmDialog by remember { mutableStateOf(false) }

    // 3. Araç: Kamera (TakePicturePreview) ve Galeri (GetContent) Launcher'ları
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { capturedBitmap: Bitmap? ->
        if (capturedBitmap != null) {
            val safeCopy = capturedBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: capturedBitmap
            viewModel.setPaperSolutionBitmap(safeCopy)
            viewModel.selectTool(SolveWorkspaceTool.CAMERA_GALLERY)
            isBottomPanelExpanded = true
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { selectedUri: Uri? ->
        if (selectedUri != null) {
            coroutineScope.launch {
                val decoded = decodeSampledBitmapFromUri(context, selectedUri, maxDimension = 1920)
                if (decoded != null) {
                    viewModel.setPaperSolutionBitmap(decoded)
                    viewModel.selectTool(SolveWorkspaceTool.CAMERA_GALLERY)
                    isBottomPanelExpanded = true
                } else {
                    viewModel.setError("Seçilen fotoğraf yüklenemedi. Lütfen başka bir görsel deneyin.")
                }
            }
        }
    }

    val tierAccentColor = Color(currentTier.colorHex)
    val totalWindowSeconds = QuestionSolveViewModel.TOTAL_SOLVE_WINDOW_MINUTES * 60f
    val progressFraction = (remainingSeconds.toFloat() / totalWindowSeconds).coerceIn(0f, 1f)
    val trimmedExplanationLength = explanationText.trim().length
    val meetsMinCharRule = trimmedExplanationLength >= QuestionSolveViewModel.MIN_EXPLANATION_LENGTH

    // 4. Araç: Shark AI Onay Diyaloğu
    if (showSharkAiConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSharkAiConfirmDialog = false },
            containerColor = Color(0xFF111A2E),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF59E0B).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Shark AI Uyarısı",
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(26.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Shark AI Çözüm Kilidi",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "AI çözümünü görürsen bu soruya çözüm gönderemez ve puan alamazsın",
                        color = Color(0xFFFDE68A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Onayladığında Shark AI adım adım çözümü açılır, ancak bu soru için gönderim butonun kalıcı olarak kilitlenir.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.5.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSharkAiConfirmDialog = false
                        viewModel.confirmAiLock()
                        viewModel.selectTool(SolveWorkspaceTool.SHARK_AI)
                        isBottomPanelExpanded = true
                        onAiLockConfirmed()
                        viewModel.requestSharkAiSolution(context, questionNote)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Onayla ve AI Çözümünü Gör",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSharkAiConfirmDialog = false }
                ) {
                    Text(
                        text = "Vazgeç (Kendim Çözeceğim)",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )
    }

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
        // 1. Üst Bilgi Barı + 105 Dakikalık Canlı Geri Sayım Sayacı + Ödül Banner'ı
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

        // 3. Orta Alan: Soru Fotoğrafı Üzerine Basınç Hassasiyetli Kalemle Çizim Tuvali
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

        // 4. Alt Çalışma Alanı: 4 Tam Araç Seçici + Aktif Araç Paneli + Çözümü Gönder Butonu
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
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 4 Tam Araç Çubuğu: 1. Kalem | 2. Metin Kutusu | 3. Kamera/Galeri | 4. Shark AI
                SolveToolsBar(
                    activeTool = activeTool,
                    hasPenStrokes = annotatorState.hasAnnotations,
                    meetsMinCharRule = meetsMinCharRule,
                    hasPaperPhoto = paperSolutionBitmap != null,
                    isAiLocked = isAiLocked,
                    onSelectTool = { selected ->
                        when (selected) {
                            SolveWorkspaceTool.PEN -> {
                                viewModel.selectTool(SolveWorkspaceTool.PEN)
                                if (annotatorState.drawingController.activeTool == DrawingTool.NONE ||
                                    annotatorState.drawingController.activeTool.isEraserTool
                                ) {
                                    annotatorState.drawingController.selectTool(DrawingTool.FOUNTAIN_PEN)
                                }
                                annotatorState.drawingController.updatePressureSensitivity(true)
                                focusManager.clearFocus()
                            }
                            SolveWorkspaceTool.TEXT_BOX -> {
                                viewModel.selectTool(SolveWorkspaceTool.TEXT_BOX)
                                isBottomPanelExpanded = true
                            }
                            SolveWorkspaceTool.CAMERA_GALLERY -> {
                                viewModel.selectTool(SolveWorkspaceTool.CAMERA_GALLERY)
                                isBottomPanelExpanded = true
                                focusManager.clearFocus()
                            }
                            SolveWorkspaceTool.SHARK_AI -> {
                                focusManager.clearFocus()
                                if (isAiLocked) {
                                    viewModel.selectTool(SolveWorkspaceTool.SHARK_AI)
                                    isBottomPanelExpanded = true
                                    onAiLockConfirmed()
                                    viewModel.requestSharkAiSolution(context, questionNote)
                                } else {
                                    showSharkAiConfirmDialog = true
                                }
                            }
                        }
                    }
                )

                // Durum / Küçült-Genişlet Satırı
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isBottomPanelExpanded = !isBottomPanelExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = when (activeTool) {
                                SolveWorkspaceTool.PEN -> "Basınç Hassasiyetli Kalem Modu"
                                SolveWorkspaceTool.TEXT_BOX -> "Metin Kutusu (En Az 25 Karakter)"
                                SolveWorkspaceTool.CAMERA_GALLERY -> "Kağıda Çözülen Fotoğrafı Ekle"
                                SolveWorkspaceTool.SHARK_AI -> "Shark AI Çözüm Desteği"
                            },
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (annotatorState.hasAnnotations) {
                            StatusMiniBadge(
                                text = "${annotatorState.drawingController.strokes.size} çizim",
                                color = Color(0xFF38BDF8)
                            )
                        }
                        if (paperSolutionBitmap != null) {
                            StatusMiniBadge(
                                text = "Fotoğraf eklendi",
                                color = Color(0xFF34D399)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = if (isBottomPanelExpanded) "Gizle" else "Göster",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        Icon(
                            imageVector = if (isBottomPanelExpanded) {
                                Icons.Default.ExpandMore
                            } else {
                                Icons.Default.ExpandLess
                            },
                            contentDescription = if (isBottomPanelExpanded) "Paneli Küçült" else "Paneli Genişlet",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Seçili Araca Göre Detay Paneli
                AnimatedVisibility(
                    visible = isBottomPanelExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (activeTool) {
                            SolveWorkspaceTool.PEN -> {
                                // Kalem modundayken bilgi + hızlı metin kutusu özeti
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF131E35))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Brush,
                                            contentDescription = null,
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Soru görseli üzerindeki yüzen kalem kutusuyla basınç hassasiyetli çizim yapabilirsiniz.",
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 11.5.sp
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.selectTool(SolveWorkspaceTool.TEXT_BOX) }
                                    ) {
                                        Text(
                                            text = "Açıklama Yaz",
                                            color = Color(0xFF38BDF8),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            SolveWorkspaceTool.TEXT_BOX -> {
                                // 2. Araç: Metin Kutusu (En az 25 karakter kuralı)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Adım adım çözümünüzü açıklayın (min. 25 karakter):",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.5.sp
                                        )
                                        Text(
                                            text = if (meetsMinCharRule) {
                                                "$trimmedExplanationLength / ${QuestionSolveViewModel.MIN_EXPLANATION_LENGTH} karakter (Tamam)"
                                            } else {
                                                "$trimmedExplanationLength / ${QuestionSolveViewModel.MIN_EXPLANATION_LENGTH} min. karakter"
                                            },
                                            color = if (meetsMinCharRule) {
                                                Color(0xFF34D399)
                                            } else {
                                                Color(0xFFFBBF24)
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    OutlinedTextField(
                                        value = explanationText,
                                        onValueChange = { viewModel.updateExplanation(it) },
                                        enabled = !isAiLocked && !isSubmitting,
                                        placeholder = {
                                            Text(
                                                text = "Sorunun çözüm adımlarını, kullandığın formülü ve sonucu en az 25 karakterle açıkla...",
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
                                            focusedBorderColor = if (meetsMinCharRule) Color(0xFF10B981) else tierAccentColor,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                                            cursorColor = tierAccentColor
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(textBoxFocusRequester)
                                    )
                                }
                            }

                            SolveWorkspaceTool.CAMERA_GALLERY -> {
                                // 3. Araç: Kamera / Galeri (Kağıda çözülen fotoğrafı seçme)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF131E35))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { cameraLauncher.launch(null) },
                                            enabled = !isAiLocked && !isSubmitting,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                Color(0xFF38BDF8).copy(alpha = 0.5f)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CameraAlt,
                                                contentDescription = "Kameradan Çek",
                                                tint = Color(0xFF38BDF8),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Kameradan Çek",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = { galleryLauncher.launch("image/*") },
                                            enabled = !isAiLocked && !isSubmitting,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                Color(0xFFA855F7).copy(alpha = 0.5f)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PhotoLibrary,
                                                contentDescription = "Galeriden Seç",
                                                tint = Color(0xFFC084FC),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Galeriden Seç",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    val currentPaperBmp = paperSolutionBitmap
                                    if (currentPaperBmp != null) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF0B1324))
                                                .border(
                                                    1.dp,
                                                    Color(0xFF10B981).copy(alpha = 0.45f),
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Image(
                                                bitmap = currentPaperBmp.asImageBitmap(),
                                                contentDescription = "Seçilen Kağıt Çözümü",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(54.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Kağıt Çözüm Fotoğrafı Hazır",
                                                    color = Color(0xFF34D399),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "İstersen tuvale yükleyip üzerine kalemle işaret koyabilirsin.",
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 10.5.sp
                                                )
                                            }

                                            TextButton(
                                                onClick = {
                                                    annotatorState.loadBitmap(currentPaperBmp)
                                                    viewModel.selectTool(SolveWorkspaceTool.PEN)
                                                }
                                            ) {
                                                Text(
                                                    text = "Tuvale Yükle",
                                                    color = Color(0xFF38BDF8),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            IconButton(
                                                onClick = { viewModel.setPaperSolutionBitmap(null) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Fotoğrafı Kaldır",
                                                    tint = Color(0xFFF87171),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "Soruyu defterde/kağıtta çözdüysen fotoğrafını çekerek veya galeriden seçerek ekleyebilirsin.",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            SolveWorkspaceTool.SHARK_AI -> {
                                // 4. Araç: Shark AI Durum & Çözüm Paneli
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFFEF4444).copy(alpha = 0.14f))
                                            .border(
                                                1.dp,
                                                Color(0xFFEF4444).copy(alpha = 0.45f),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = Color(0xFFF87171),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Shark AI çözümü görüntülendiği için bu soruya çözüm gönderimi ve puan kazanımı kilitlendi.",
                                            color = Color(0xFFFECACA),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 72.dp, max = 180.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF131E35))
                                            .border(
                                                1.dp,
                                                Color(0xFFF59E0B).copy(alpha = 0.35f),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .padding(10.dp)
                                    ) {
                                        when {
                                            isAiLoading && aiSolutionText.isBlank() -> {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp,
                                                        color = Color(0xFFFBBF24)
                                                    )
                                                    Text(
                                                        text = "Shark AI soruyu çözüyor...",
                                                        color = Color(0xFFFDE68A),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                            aiError != null && aiSolutionText.isBlank() -> {
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(
                                                        text = aiError ?: "Shark AI çözümü alınamadı.",
                                                        color = Color(0xFFFCA5A5),
                                                        fontSize = 11.5.sp
                                                    )
                                                    TextButton(
                                                        onClick = {
                                                            viewModel.requestSharkAiSolution(
                                                                context = context,
                                                                questionNote = questionNote,
                                                                forceReload = true
                                                            )
                                                        }
                                                    ) {
                                                        Text(
                                                            text = "Tekrar Dene",
                                                            color = Color(0xFFFBBF24),
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                            aiSolutionText.isNotBlank() -> {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .verticalScroll(rememberScrollState()),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = aiSolutionText,
                                                        color = Color(0xFFE2E8F0),
                                                        fontSize = 12.sp,
                                                        lineHeight = 17.sp
                                                    )
                                                }
                                            }
                                            else -> {
                                                TextButton(
                                                    onClick = {
                                                        viewModel.requestSharkAiSolution(
                                                            context = context,
                                                            questionNote = questionNote,
                                                            forceReload = true
                                                        )
                                                    }
                                                ) {
                                                    Text(
                                                        text = "Shark AI Çözümünü Getir",
                                                        color = Color(0xFFFBBF24),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Hata / Doğrulama Uyarısı
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

                // Çözümü Gönder Butonu (Shark AI onaylandıysa kilitlenir)
                Button(
                    onClick = {
                        if (isAiLocked) {
                            viewModel.setError("Shark AI çözümünü gördüğün için bu soruya çözüm gönderemezsin.")
                            return@Button
                        }

                        val trimmedExplanation = explanationText.trim()
                        val hasDrawing = annotatorState.hasAnnotations
                        val hasPaperPhoto = paperSolutionBitmap != null

                        // En az 25 karakter kuralı kontrolü:
                        // 1) Kullanıcı Metin Kutusu aracındaysa veya metin yazmaya başladıysa en az 25 karakter zorunlu.
                        // 2) Hiç çizim veya fotoğraf eklenmemişse de en az 25 karakterlik metin açıklaması zorunlu.
                        if (trimmedExplanation.isNotEmpty() &&
                            trimmedExplanation.length < QuestionSolveViewModel.MIN_EXPLANATION_LENGTH
                        ) {
                            viewModel.selectTool(SolveWorkspaceTool.TEXT_BOX)
                            isBottomPanelExpanded = true
                            viewModel.setError(
                                "Metin kutusu açıklaması en az ${QuestionSolveViewModel.MIN_EXPLANATION_LENGTH} karakter olmalıdır (şu an: ${trimmedExplanation.length} karakter)."
                            )
                            return@Button
                        }

                        if (!hasDrawing && !hasPaperPhoto &&
                            trimmedExplanation.length < QuestionSolveViewModel.MIN_EXPLANATION_LENGTH
                        ) {
                            viewModel.selectTool(SolveWorkspaceTool.TEXT_BOX)
                            isBottomPanelExpanded = true
                            viewModel.setError(
                                "Lütfen soru üzerine çizim yapın, çözüm fotoğrafı ekleyin veya en az ${QuestionSolveViewModel.MIN_EXPLANATION_LENGTH} karakter açıklama yazın."
                            )
                            return@Button
                        }

                        focusManager.clearFocus()
                        viewModel.markSubmitting(true)
                        val exportedCanvasBitmap = if (hasDrawing) {
                            annotatorState.exportAnnotatedBitmap()
                        } else {
                            null
                        }
                        val finalSolutionBitmap = exportedCanvasBitmap ?: paperSolutionBitmap
                        onSubmitSolution(trimmedExplanation, finalSolutionBitmap)
                        viewModel.markSubmitted()
                    },
                    enabled = !isSubmitting && !isAiLocked && currentTier != SolveRewardTier.EXPIRED_0,
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
                    } else if (isAiLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI Çözümü Görüldü • Çözüm Gönderilemez",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.5.sp
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

/**
 * 4 Tam Araç Seçici Bar:
 * 1. Kalem
 * 2. Metin Kutusu
 * 3. Kamera / Galeri
 * 4. Shark AI
 */
@Composable
private fun SolveToolsBar(
    activeTool: SolveWorkspaceTool,
    hasPenStrokes: Boolean,
    meetsMinCharRule: Boolean,
    hasPaperPhoto: Boolean,
    isAiLocked: Boolean,
    onSelectTool: (SolveWorkspaceTool) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SolveToolChip(
            tool = SolveWorkspaceTool.PEN,
            isSelected = activeTool == SolveWorkspaceTool.PEN,
            hasBadge = hasPenStrokes,
            accentColor = Color(0xFF38BDF8),
            onClick = { onSelectTool(SolveWorkspaceTool.PEN) },
            modifier = Modifier.weight(1f)
        )
        SolveToolChip(
            tool = SolveWorkspaceTool.TEXT_BOX,
            isSelected = activeTool == SolveWorkspaceTool.TEXT_BOX,
            hasBadge = meetsMinCharRule,
            accentColor = Color(0xFF10B981),
            onClick = { onSelectTool(SolveWorkspaceTool.TEXT_BOX) },
            modifier = Modifier.weight(1f)
        )
        SolveToolChip(
            tool = SolveWorkspaceTool.CAMERA_GALLERY,
            isSelected = activeTool == SolveWorkspaceTool.CAMERA_GALLERY,
            hasBadge = hasPaperPhoto,
            accentColor = Color(0xFFA855F7),
            onClick = { onSelectTool(SolveWorkspaceTool.CAMERA_GALLERY) },
            modifier = Modifier.weight(1f)
        )
        SolveToolChip(
            tool = SolveWorkspaceTool.SHARK_AI,
            isSelected = activeTool == SolveWorkspaceTool.SHARK_AI || isAiLocked,
            hasBadge = isAiLocked,
            accentColor = if (isAiLocked) Color(0xFFEF4444) else Color(0xFFF59E0B),
            onClick = { onSelectTool(SolveWorkspaceTool.SHARK_AI) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SolveToolChip(
    tool: SolveWorkspaceTool,
    isSelected: Boolean,
    hasBadge: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) accentColor.copy(alpha = 0.20f) else Color(0xFF152036),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.2.dp else 0.8.dp,
            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.10f)
        ),
        modifier = modifier.height(38.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = when (tool) {
                    SolveWorkspaceTool.PEN -> Icons.Default.Brush
                    SolveWorkspaceTool.TEXT_BOX -> Icons.Default.EditNote
                    SolveWorkspaceTool.CAMERA_GALLERY -> Icons.Default.CameraAlt
                    SolveWorkspaceTool.SHARK_AI -> Icons.Default.AutoAwesome
                },
                contentDescription = null,
                tint = if (isSelected) accentColor else Color(0xFF94A3B8),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = tool.label,
                color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                fontSize = 10.5.sp,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hasBadge) {
                Spacer(modifier = Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        }
    }
}

@Composable
private fun StatusMiniBadge(
    text: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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

/**
 * OOM güvenli galeri fotoğrafı çözücü (ARGB_8888).
 */
private suspend fun decodeSampledBitmapFromUri(
    context: Context,
    uri: Uri,
    maxDimension: Int = 1920
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) return@withContext null

        var sampleSize = 1
        while ((rawW / sampleSize) > maxDimension || (rawH / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    } catch (_: OutOfMemoryError) {
        System.gc()
        null
    } catch (_: Throwable) {
        null
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
