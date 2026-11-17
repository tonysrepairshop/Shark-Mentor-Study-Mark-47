package com.example.sharkmentor.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import android.widget.Toast
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sharkmentor.core.util.ImageStorageManager
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.data.local.entity.TrainingFolderEntity
import com.example.sharkmentor.data.repository.SharkAiEngine
import com.example.sharkmentor.data.sync.VaultBackupDialog
import com.example.sharkmentor.ui.viewmodel.ErrorBankViewModel
import com.example.sharkmentor.ui.viewmodel.SharkAiHandoffManager
import com.example.sharkmentor.ui.viewmodel.SubjectFolderItem

import com.example.sharkmentor.ui.screens.components.EditQuestionClassificationDialog
import com.example.sharkmentor.ui.screens.components.QuickQuestionPreviewDialog
import com.example.sharkmentor.ui.errorbank.components.QuestionImagePickerWithCrop
import com.example.sharkmentor.core.service.FocusTimerService
import com.example.sharkmentor.core.timer.TimerPhase
import com.example.sharkmentor.core.util.TimeUtils.formatAdaptiveTime
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorBankScreen(
    viewModel: ErrorBankViewModel = viewModel(),
    onStartTraining: (String) -> Unit,
    onNavigateToSharkAi: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.bankUiState.collectAsStateWithLifecycle()
    val folders = uiState.folders
    val aiTrainingFolders = uiState.aiTrainingFolders
    val selectedSubject = uiState.selectedSubject
    val selectedTopic = uiState.selectedTopic
    val subjectQuestions = uiState.subjectQuestions
    val availableTopics = uiState.availableTopics
    val dueQuestionsCount = uiState.dueQuestionsCount
    val timerState by FocusTimerService.timerStateFlow.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var showVaultBackupDialog by remember { mutableStateOf(false) }
    var questionToReclassify by remember { mutableStateOf<ErrorQuestionEntity?>(null) }

    // System back press handling: Topic -> Subject -> Root
    BackHandler(enabled = selectedTopic != null || selectedSubject != null) {
        if (selectedTopic != null) {
            viewModel.selectTopic(null)
        } else if (selectedSubject != null) {
            viewModel.selectSubject(null)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Hata Ekle")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Persistent Timer Banner
            if (timerState.timerPhase != TimerPhase.IDLE) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
                            Text("Odak Seansı", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(formatAdaptiveTime(timerState.displayMillis), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Header Summary
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedTopic != null) {
                            IconButton(onClick = { viewModel.selectTopic(null) }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Konulara Dön")
                            }
                        } else if (selectedSubject != null) {
                            IconButton(onClick = { viewModel.selectSubject(null) }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Derslere Dön")
                            }
                        }

                        Text(
                            text = when {
                                selectedSubject != null && selectedTopic != null -> {
                                    val displayTopic = if (selectedTopic == "__ALL__") "Tüm Sorular" else selectedTopic
                                    "$selectedSubject • $displayTopic"
                                }
                                selectedSubject != null -> "$selectedSubject: ${folders.find { it.subjectName == selectedSubject }?.dueCount ?: 0} Tekrar"
                                else -> "Tekrar Bekleyen: $dueQuestionsCount"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = if (selectedSubject != null) 8.dp else 0.dp)
                        )
                        
                        var isRefreshing by remember { mutableStateOf(false) }
                        val rotation by animateFloatAsState(
                            targetValue = if (isRefreshing) 360f else 0f,
                            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                            finishedListener = { isRefreshing = false },
                            label = "refreshRotation"
                        )

                        IconButton(onClick = { showVaultBackupDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = "Yedekle / Geri Yükle",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(onClick = {
                            isRefreshing = true
                            viewModel.refreshDueQuestions()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Yenile",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.rotate(rotation)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            selectedSubject != null && selectedTopic != null -> "${subjectQuestions.size} soru"
                            selectedSubject != null -> "${subjectQuestions.size} soru"
                            else -> "Toplam ${uiState.allQuestions.size} soru"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val canLaunchTraining = if (selectedSubject != null) {
                        subjectQuestions.isNotEmpty()
                    } else {
                        uiState.allQuestions.isNotEmpty()
                    }
                    Button(
                        onClick = {
                            viewModel.loadQuestions(
                                subject = selectedSubject,
                                topic = if (selectedTopic == "__ALL__") null else selectedTopic
                            )
                            val route = buildString {
                                append(com.example.sharkmentor.ui.navigation.Screen.ErrorTraining.route)
                                val params = mutableListOf<String>()
                                if (!selectedSubject.isNullOrBlank()) {
                                    params.add("subject=${Uri.encode(selectedSubject)}")
                                }
                                if (!selectedTopic.isNullOrBlank() && selectedTopic != "__ALL__") {
                                    params.add("topic=${Uri.encode(selectedTopic)}")
                                }
                                if (params.isNotEmpty()) {
                                    append("?")
                                    append(params.joinToString("&"))
                                }
                            }
                            onStartTraining(route)
                        },
                        enabled = canLaunchTraining,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = when {
                                selectedSubject != null && selectedTopic != null -> {
                                    val displayTopic = if (selectedTopic == "__ALL__") "Tüm $selectedSubject" else selectedTopic
                                    "$displayTopic Başlat"
                                }
                                selectedSubject != null ->
                                    "$selectedSubject Başlat"
                                else ->
                                    "Testi Başlat"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── SEVİYE 1: DERSLER ANA EKRANI (Konu klasörleri burada gizli, sade ve ferah) ──
            if (selectedSubject == null) {
                Text(
                    text = "Dersler",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Grid of Ders Klasörleri
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = folders,
                        key = { it.subjectName },
                        contentType = { "folder_card" }
                    ) { folder ->
                        SubjectFolderCard(
                            folder = folder,
                            onClick = { viewModel.selectSubject(folder.subjectName) },
                            onStartFolderTraining = {
                                viewModel.loadQuestions(subject = folder.subjectName)
                                val route = "${com.example.sharkmentor.ui.navigation.Screen.ErrorTraining.route}?subject=${Uri.encode(folder.subjectName)}"
                                onStartTraining(route)
                            }
                        )
                    }
                }
            }
            // ── SEVİYE 2: DERS İÇİ KONU KLASÖRLERİ EKRANI (Fotoğraflar yerine konu klasörleri) ──
            else if (selectedTopic == null) {
                // Ders altındaki Özel/AI Çalışma klasörleri (varsa)
                val relevantAiFolders = remember(aiTrainingFolders, selectedSubject) {
                    aiTrainingFolders.filter { 
                        it.title.contains(selectedSubject, ignoreCase = true) 
                    }
                }
                if (relevantAiFolders.isNotEmpty()) {
                    Text(
                        text = "Özel Klasörler",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(relevantAiFolders, key = { it.id }) { aiFolder ->
                            AiTrainingFolderCard(
                                folder = aiFolder,
                                onStartFolderTraining = {
                                    viewModel.loadQuestions(folderId = aiFolder.id)
                                    val route = "${com.example.sharkmentor.ui.navigation.Screen.ErrorTraining.route}?folderId=${Uri.encode(aiFolder.id)}"
                                    onStartTraining(route)
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "$selectedSubject • Konular",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Build Topic Folders for this Subject
                val topicFolders = remember(subjectQuestions, availableTopics) {
                    val list = mutableListOf<TopicFolderItem>()
                    // 1. "Tüm Sorular" klasörü (İsterse hepsine tek tıkla ulaşsın)
                    val totalDue = subjectQuestions.count { it.state == 0 || it.dueDateMillis <= System.currentTimeMillis() }
                    list.add(TopicFolderItem(topicName = "Tüm Sorular", totalCount = subjectQuestions.size, dueCount = totalDue, isAllWildcard = true))

                    // 2. Her alt konu
                    availableTopics.forEach { topic ->
                        val count = subjectQuestions.count { q ->
                            q.aiTopic.equals(topic, ignoreCase = true) || q.topic.equals(topic, ignoreCase = true)
                        }
                        val due = subjectQuestions.count { q ->
                            (q.aiTopic.equals(topic, ignoreCase = true) || q.topic.equals(topic, ignoreCase = true)) &&
                            (q.state == 0 || q.dueDateMillis <= System.currentTimeMillis())
                        }
                        if (count > 0) {
                            list.add(TopicFolderItem(topicName = topic, totalCount = count, dueCount = due))
                        }
                    }

                    // 3. Genel veya henüz alt konu atanmamış sorular
                    val generalCount = subjectQuestions.count { q ->
                        val matches = availableTopics.any { t ->
                            q.aiTopic.equals(t, ignoreCase = true) || q.topic.equals(t, ignoreCase = true)
                        }
                        !matches
                    }
                    if (generalCount > 0 && !availableTopics.any { it.equals("Genel", ignoreCase = true) }) {
                        val generalDue = subjectQuestions.count { q ->
                            val matches = availableTopics.any { t ->
                                q.aiTopic.equals(t, ignoreCase = true) || q.topic.equals(t, ignoreCase = true)
                            }
                            !matches && (q.state == 0 || q.dueDateMillis <= System.currentTimeMillis())
                        }
                        list.add(TopicFolderItem(topicName = "Genel", totalCount = generalCount, dueCount = generalDue))
                    }
                    list
                }

                // Grid of Topic Folders
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = topicFolders,
                        key = { it.topicName }
                    ) { topicItem ->
                        TopicFolderCard(
                            folder = topicItem,
                            onClick = {
                                if (topicItem.isAllWildcard) {
                                    viewModel.selectTopic("__ALL__")
                                } else {
                                    viewModel.selectTopic(topicItem.topicName)
                                }
                            },
                            onStartPractice = {
                                val topicParam = if (topicItem.isAllWildcard) null else topicItem.topicName
                                viewModel.loadQuestions(subject = selectedSubject, topic = topicParam)
                                val route = buildString {
                                    append(com.example.sharkmentor.ui.navigation.Screen.ErrorTraining.route)
                                    append("?subject=${Uri.encode(selectedSubject)}")
                                    if (!topicParam.isNullOrBlank()) {
                                        append("&topic=${Uri.encode(topicParam)}")
                                    }
                                }
                                onStartTraining(route)
                            }
                        )
                    }
                }
            }
            // ── SEVİYE 3: SEÇİLEN KONUNUN SORU FOTOĞRAFLARI EKRANI ──
            else {
                val displayTopic = if (selectedTopic == "__ALL__") "Tüm Sorular" else selectedTopic

                // Konu Pratik Yap Çubuğu
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$selectedSubject → $displayTopic",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${subjectQuestions.size} Soru",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Button(
                            onClick = {
                                val topicParam = if (selectedTopic == "__ALL__") null else selectedTopic
                                viewModel.loadQuestions(subject = selectedSubject, topic = topicParam)
                                val route = buildString {
                                    append(com.example.sharkmentor.ui.navigation.Screen.ErrorTraining.route)
                                    append("?subject=${Uri.encode(selectedSubject)}")
                                    if (!topicParam.isNullOrBlank()) {
                                        append("&topic=${Uri.encode(topicParam)}")
                                    }
                                }
                                onStartTraining(route)
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Pratik Yap", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Soru Fotoğrafları Izgarası
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = subjectQuestions,
                        key = { it.id },
                        contentType = { "error_card" }
                    ) { question ->
                        ErrorQuestionCard(
                            question = question,
                            availableTopics = availableTopics,
                            onDelete = remember(question.id) { { viewModel.deleteQuestion(question) } },
                            onTopicChange = { newTopic -> viewModel.updateQuestionTopic(question.id, newTopic) },
                            onEditClassification = { questionToReclassify = question },
                            onClick = { viewModel.setPreviewQuestion(question) },
                            onSolveClick = {
                                SharkAiHandoffManager.enqueueFromErrorQuestion(
                                    imagePath = question.questionImageUri,
                                    subject = question.subject,
                                    topic = question.aiTopic ?: question.topic,
                                    targetEngine = SharkAiEngine.SOLVER_ENGINE
                                )
                                onNavigateToSharkAi()
                            },
                            onTwinClick = {
                                SharkAiHandoffManager.enqueueFromErrorQuestion(
                                    imagePath = question.questionImageUri,
                                    subject = question.subject,
                                    topic = question.aiTopic ?: question.topic,
                                    targetEngine = SharkAiEngine.TWIN_QUESTION_ENGINE
                                )
                                onNavigateToSharkAi()
                            }
                        )
                    }
                }
            }
        }
    }

    if (uiState.previewQuestion != null) {
        QuickQuestionPreviewDialog(
            question = uiState.previewQuestion!!,
            onDismiss = { viewModel.setPreviewQuestion(null) },
            onSolveWithSharkAi = { q ->
                SharkAiHandoffManager.enqueueFromErrorQuestion(
                    imagePath = q.questionImageUri,
                    subject = q.subject,
                    topic = q.aiTopic ?: q.topic,
                    targetEngine = SharkAiEngine.SOLVER_ENGINE
                )
                onNavigateToSharkAi()
            },
            onTwinWithSharkAi = { q ->
                SharkAiHandoffManager.enqueueFromErrorQuestion(
                    imagePath = q.questionImageUri,
                    subject = q.subject,
                    topic = q.aiTopic ?: q.topic,
                    targetEngine = SharkAiEngine.TWIN_QUESTION_ENGINE
                )
                onNavigateToSharkAi()
            },
            onEditClassification = { q ->
                questionToReclassify = q
            }
        )
    }

    questionToReclassify?.let { targetQuestion ->
        EditQuestionClassificationDialog(
            question = targetQuestion,
            availableTopics = availableTopics,
            onDismiss = { questionToReclassify = null },
            onSave = { newSubject, newTopic ->
                viewModel.reclassifyQuestion(
                    questionId = targetQuestion.id,
                    newSubject = newSubject,
                    newTopic = newTopic,
                    aiTopic = newTopic
                ) {
                    Toast.makeText(
                        context,
                        "Soru '$newSubject • $newTopic' klasörüne taşındı.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                questionToReclassify = null
            },
            onAiReclassify = { onComplete, onError ->
                viewModel.reclassifyQuestionWithAi(
                    question = targetQuestion,
                    onResult = { aiSubject, aiTopic ->
                        onComplete(aiSubject, aiTopic)
                    },
                    onError = { errMsg ->
                        onError(errMsg)
                    }
                )
            }
        )
    }

    if (showAddDialog) {
        AddErrorQuestionDialog(
            onDismiss = { showAddDialog = false },
            onSave = { qUri, sUri, subject ->
                viewModel.addQuestion(qUri, sUri, subject, "Genel")
                showAddDialog = false
            }
        )
    }

    if (showVaultBackupDialog) {
        VaultBackupDialog(
            onDismiss = { showVaultBackupDialog = false },
            onDataRestored = { viewModel.refreshDueQuestions() }
        )
    }
}

@Composable
fun ErrorQuestionCard(
    question: ErrorQuestionEntity,
    availableTopics: List<String> = emptyList(),
    onDelete: () -> Unit,
    onTopicChange: (String) -> Unit = {},
    onEditClassification: () -> Unit = {},
    onClick: () -> Unit = {},
    onSolveClick: () -> Unit = {},
    onTwinClick: () -> Unit = {}
) {
    val isDue = question.dueDateMillis <= System.currentTimeMillis()
    var showTopicMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().height(235.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(question.questionImageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Question Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Due badge
                if (isDue) {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Tekrar", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // AI Topic chip — clickable to reassign topic or edit subject/topic
                Box(modifier = Modifier.align(Alignment.BottomStart)) {
                    Row(
                        modifier = Modifier
                            .padding(8.dp)
                            .background(
                                if (question.aiTopic != null)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { showTopicMenu = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = question.aiTopic ?: question.topic,
                            color = if (question.aiTopic != null)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Dersi / Konuyu Düzenle",
                            tint = if (question.aiTopic != null)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(11.dp)
                        )
                    }

                    // Topic reassignment & Subject/Topic edit dropdown
                    DropdownMenu(
                        expanded = showTopicMenu,
                        onDismissRequest = { showTopicMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Dersi / Konuyu Düzenle",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            onClick = {
                                showTopicMenu = false
                                onEditClassification()
                            }
                        )
                        HorizontalDivider()
                        Text(
                            text = "Konu (${question.subject})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val allTopics = (availableTopics + listOfNotNull(question.aiTopic, question.topic)).distinct()
                        allTopics.forEach { topic ->
                            DropdownMenuItem(
                                text = { Text(topic) },
                                onClick = {
                                    onTopicChange(topic)
                                    showTopicMenu = false
                                },
                                trailingIcon = if (topic == (question.aiTopic ?: question.topic)) ({
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }) else null
                            )
                        }
                    }
                }

                // Delete button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Sil", tint = MaterialTheme.colorScheme.error)
                }
            }

            // Task 6.1: Direct Shark AI Specialist Engine Launch Buttons (Soru Çözümü & Benzer Soru)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onSolveClick,
                    modifier = Modifier.weight(1f).height(28.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Soru Çözümü",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                    }
                }
                Surface(
                    onClick = onTwinClick,
                    modifier = Modifier.weight(1f).height(28.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Benzer Soru",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddErrorQuestionDialog(
    onDismiss: () -> Unit,
    onSave: (questionUri: String, solutionUri: String?, topic: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var questionUri by remember { mutableStateOf<Uri?>(null) }
    var solutionUri by remember { mutableStateOf<Uri?>(null) }
    var selectedTopic by remember { mutableStateOf("Matematik") }
    var isSaving by remember { mutableStateOf(false) }

    val topics = listOf(
        "Matematik", "Fizik", "Kimya", "Biyoloji", "Türkçe / Edebiyat", 
        "Tarih", "Coğrafya", "Felsefe / Din", 
        "İngilizce", "Fransızca", "Almanca", "İtalyanca", 
        "Genel Yetenek / Diğer"
    )

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Yeni Hata Ekle") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Topic Selector
                Text("Ders", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                // Simple dropdown alternative using radio buttons or exposed dropdown
                // For simplicity, a horizontal scrollable row of chips could work, but a dropdown is better.
                // I'll use a simple compose layout for chips.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    topics.forEach { topic ->
                        FilterChip(
                            selected = topic == selectedTopic,
                            onClick = { selectedTopic = topic },
                            label = { Text(topic) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Question Image
                Text("Soru Fotoğrafı *", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                QuestionImagePickerWithCrop(
                    selectedImageUri = questionUri,
                    onImageSelected = { questionUri = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Solution Image
                Text("Çözüm Fotoğrafı", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                QuestionImagePickerWithCrop(
                    selectedImageUri = solutionUri,
                    onImageSelected = { solutionUri = it }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = questionUri != null && !isSaving,
                onClick = {
                    isSaving = true
                    coroutineScope.launch {
                        val savedQuestion = ImageStorageManager.processAndSaveImage(context, questionUri!!, isQuestion = true)
                        val savedSolution = solutionUri?.let { ImageStorageManager.processAndSaveImage(context, it, isQuestion = false) }
                        onSave(savedQuestion, savedSolution, selectedTopic)
                    }
                }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Kaydet")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("İptal")
            }
        }
    )
}

@Composable
fun FlowRow(
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    // Basic fallback for FlowRow using standard compose elements if FlowRow is not available in the foundation layer.
    // In actual Compose 1.4+ Contexts, FlowRow is ExperimentalLayoutApi. 
    // We will use a regular Row for this basic implementation if not many items, or LazyRow.
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = horizontalArrangement) {
        item { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTrainingFolderCard(
    folder: TrainingFolderEntity,
    onStartFolderTraining: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(230.dp)
            .height(106.dp),
        onClick = onStartFolderTraining,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = folder.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStartFolderTraining() }
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Pratik Yap",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectFolderCard(
    folder: SubjectFolderItem,
    onClick: () -> Unit,
    onStartFolderTraining: () -> Unit = onClick
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = folder.subjectName.uppercase(java.util.Locale("tr")),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${folder.totalCount} soru",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (folder.dueCount > 0) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${folder.dueCount} Tekrar",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStartFolderTraining() }
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Pratik Yap",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

data class TopicFolderItem(
    val topicName: String,
    val totalCount: Int,
    val dueCount: Int,
    val isAllWildcard: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicFolderCard(
    folder: TopicFolderItem,
    onClick: () -> Unit,
    onStartPractice: () -> Unit = onClick
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (folder.isAllWildcard)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.topicName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (folder.isAllWildcard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${folder.totalCount} Soru",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (folder.dueCount > 0) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${folder.dueCount} Tekrar",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStartPractice() }
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (folder.isAllWildcard) "Tümünü Çöz" else "Pratik Yap",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
