package com.example.sharkmentor.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.data.local.entity.TargetPeriod
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.local.entity.TargetWithTasks
import com.example.sharkmentor.ui.viewmodel.PlansUiState
import com.example.sharkmentor.ui.viewmodel.PlansViewModel
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.example.sharkmentor.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TaskEntity
import com.example.sharkmentor.ui.plans.viewmodel.AiStudyPlannerViewModel
import com.example.sharkmentor.ui.plans.viewmodel.PlannerUiState
import com.example.sharkmentor.ui.screens.plan.PlanScreen
import com.example.sharkmentor.ui.screens.notes.NotesScreen
import com.example.sharkmentor.ui.viewmodel.PlanImportState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(
    modifier: Modifier = Modifier,
    viewModel: PlansViewModel = viewModel(),
    onNavigateToSchedule: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.triggerManualSync()
    }

    PlansScreenContent(
        modifier = modifier,
        uiState = uiState,
        onImportSchedule = { uri, isLongTerm -> viewModel.importScheduleFromUri(uri, isLongTerm) },
        onConfirmPlan = { plan, isLongTerm -> viewModel.confirmAndSavePlan(plan, isLongTerm) },
        onClearUiState = { viewModel.clearUiState() },
        onToggleTask = { task -> viewModel.toggleTask(task) },
        onToggleTarget = { target -> viewModel.toggleTarget(target) },
        onDeleteTarget = { id -> viewModel.deleteTarget(id) },
        onAddTarget = { title, desc, type, period, deadline, tasks, priority, targetValue ->
            viewModel.addTarget(title, desc, type, period, deadline, tasks, priority, targetValue)
        },
        onManualSync = { viewModel.triggerManualSync() },
        onNavigateToSchedule = onNavigateToSchedule
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreenContent(
    modifier: Modifier = Modifier,
    uiState: PlansUiState,
    onImportSchedule: (Uri, Boolean) -> Unit = { _, _ -> },
    onConfirmPlan: (com.example.sharkmentor.core.ai.ParsedPlanResult, Boolean) -> Unit = { _, _ -> },
    onClearUiState: () -> Unit = {},
    onToggleTask: (com.example.sharkmentor.data.local.entity.TaskEntity) -> Unit = {},
    onToggleTarget: (com.example.sharkmentor.data.local.entity.TargetEntity) -> Unit = {},
    onDeleteTarget: (Long) -> Unit = {},
    onAddTarget: (String, String, TargetType, TargetPeriod, Long, List<String>, String, String?) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onManualSync: () -> Unit = {},
    onNavigateToSchedule: () -> Unit = {}
) {
    var currentModule by rememberSaveable { mutableStateOf(NavModule.PLANS) }


    // Part 3: UI Period Filtering & Tab Alignment
    // Ensure the filter condition accommodates both string forms as requested by user
    val currentTargets = remember(uiState.shortTermTargets) {
        uiState.shortTermTargets.filter { 
            it.target.type == TargetType.SHORT_TERM ||
            it.target.type.name.contains("KISA", ignoreCase = true) 
        }
    }

    val groupedPlansByDay = remember(currentTargets) {
        currentTargets.groupBy { getDayInfo(it.target) }.toSortedMap(compareBy { it.first })
    }
    val initialDayIdx = remember {
        val cal = java.util.Calendar.getInstance()
        when(cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> 1
            java.util.Calendar.TUESDAY -> 2
            java.util.Calendar.WEDNESDAY -> 3
            java.util.Calendar.THURSDAY -> 4
            java.util.Calendar.FRIDAY -> 5
            java.util.Calendar.SATURDAY -> 6
            java.util.Calendar.SUNDAY -> 7
            else -> 1
        }
    }
    var expandedDays by rememberSaveable { mutableStateOf(setOf(initialDayIdx)) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showAddGoalSheet by rememberSaveable { mutableStateOf(false) }
    var isAddTopicDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isAddResourceDialogOpen by rememberSaveable { mutableStateOf(false) }

    val hostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onImportSchedule(it, false) }
    }

    var pendingPlanToConfirm by remember { mutableStateOf<com.example.sharkmentor.ui.viewmodel.PlanImportState.ImportSuccess?>(null) }
    
    LaunchedEffect(uiState.importState) {
        when (val state = uiState.importState) {
            is com.example.sharkmentor.ui.viewmodel.PlanImportState.ImportSuccess -> {
                pendingPlanToConfirm = state
            }
            else -> {}
        }
    }
    
    if (pendingPlanToConfirm != null) {
        val planState = pendingPlanToConfirm!!
        PlanImportSuccessDialog(
            planResult = planState.parsedPlan,
            isLongTerm = planState.isLongTerm,
            onConfirm = {
                onConfirmPlan(planState.parsedPlan, planState.isLongTerm)
                pendingPlanToConfirm = null
            },
            onDismiss = {
                pendingPlanToConfirm = null
                onClearUiState()
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState) },
        floatingActionButton = {
            if (currentModule != NavModule.PLANS) {
                FloatingActionButton(
                    onClick = { 
                        when (currentModule) {
                            NavModule.GOALS_ROUTE -> showAddGoalSheet = true
                            NavModule.TOPIC_MASTERY -> isAddTopicDialogOpen = true
                            NavModule.LIBRARY -> isAddResourceDialogOpen = true
                            else -> {}
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.plans_add_new))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // --- SUB-HEADER: Sleek Horizontal Module Pill Chips ---
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(NavModule.values()) { module ->
                            val isSelected = currentModule == module
                            Surface(
                                onClick = { currentModule = module },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = module.iconVector,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = module.title,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = onManualSync,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Eşitle",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                when (currentModule) {
                    NavModule.PLANS -> {
                        PlanScreen(
                            modifier = Modifier.fillMaxSize(),
                            onNavigateToSchedule = onNavigateToSchedule
                        )
                    }
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp)
                                .padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            GoalsScreen(
                                currentModule = currentModule,
                                isAddTopicDialogOpen = isAddTopicDialogOpen,
                                onCloseTopicDialog = { isAddTopicDialogOpen = false },
                                isAddResourceDialogOpen = isAddResourceDialogOpen,
                                onCloseResourceDialog = { isAddResourceDialogOpen = false },
                                longTermTargetsListContent = {
                                    val longTermTargets = remember(uiState.longTermTargets) {
                                        uiState.longTermTargets.filter { 
                                            it.target.type == TargetType.LONG_TERM ||
                                            it.target.type.name.contains("UZUN", ignoreCase = true) 
                                        }
                                    }
                                    if (longTermTargets.isEmpty()) {
                                        PlanListPlaceholder(
                                            title = "Henüz Belirlenmiş Bir Rota Yok",
                                            subtitle = "Seni motive edecek üniversite, bölüm veya net hedeflerini ekleyerek rotanı çizmeye başla."
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            longTermTargets.forEach { targetWithTasks ->
                                                TargetTodoItem(
                                                    targetEntity = targetWithTasks.target,
                                                    onCheckedChange = { onToggleTarget(targetWithTasks.target) },
                                                    onDelete = { onDeleteTarget(targetWithTasks.target.id) }
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddTargetBottomSheet(
            onDismiss = { showAddSheet = false },
            onSave = { title, desc, type, period, deadline, tasks ->
                onAddTarget(title, desc, type, period, deadline, tasks, "NORMAL", null)
                showAddSheet = false
            }
        )
    }

    if (showAddGoalSheet) {
        AddGoalBottomSheet(
            onDismiss = { showAddGoalSheet = false },
            onSave = { title, priority, targetValue, deadlineMillis ->
                onAddTarget(title, "", TargetType.LONG_TERM, TargetPeriod.SIX_MONTH, deadlineMillis, emptyList(), priority, targetValue)
                showAddGoalSheet = false
            }
        )
    }
}


@Composable
private fun PlanToggleRow(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
    val tabs = listOf(stringResource(R.string.plans_long_term), stringResource(R.string.plans_short_term))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            if (index == selectedIndex) {
                Button(
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Text(label, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PlanListPlaceholder(title: String, subtitle: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun AiImportBanner(
    isLoading: Boolean,
    onPickFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading) { onPickFile() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plans_ai_import_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Hedefler: Haftalık kazanım dağılımı ve ders bazlı soru adetleri belirlendi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

private fun getDayInfo(target: TargetEntity): Pair<Int, String> {
    val title = target.title
    val daysMap = mapOf(
        "Pazartesi" to 1,
        "Salı" to 2,
        "Çarşamba" to 3,
        "Perşembe" to 4,
        "Cumartesi" to 6, // Check Cumartesi before Cuma
        "Cuma" to 5,
        "Pazar" to 7
    )
    for ((day, idx) in daysMap) {
        if (title.contains(day, ignoreCase = true)) {
            val displayDay = if (day == "Cumartesi") "Cumartesi" else if (day == "Cuma") "Cuma" else day
            return idx to displayDay
        }
    }
    val cal = Calendar.getInstance()
    cal.timeInMillis = if (target.deadlineMillis > 0) target.deadlineMillis else target.createdAt
    val dayIdx = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1 to "Pazartesi"
        Calendar.TUESDAY -> 2 to "Salı"
        Calendar.WEDNESDAY -> 3 to "Çarşamba"
        Calendar.THURSDAY -> 4 to "Perşembe"
        Calendar.FRIDAY -> 5 to "Cuma"
        Calendar.SATURDAY -> 6 to "Cumartesi"
        Calendar.SUNDAY -> 7 to "Pazar"
        else -> 1 to "Pazartesi"
    }
    return dayIdx
}

@Composable
private fun FlatChecklistItem(
    item: Any,
    parentTarget: TargetEntity,
    onToggleTask: (TaskEntity) -> Unit,
    onToggleTarget: (TargetEntity) -> Unit,
    onDeleteTarget: (Long) -> Unit
) {
    val isCompleted = when (item) {
        is TargetEntity -> item.isCompleted
        is TaskEntity -> item.isCompleted
        else -> false
    }
    val title = when (item) {
        is TargetEntity -> item.title
        is TaskEntity -> item.title
        else -> ""
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (item is TargetEntity) onToggleTarget(item) else if (item is TaskEntity) onToggleTask(item)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Checkbox(
                checked = isCompleted,
                onCheckedChange = { 
                    if (item is TargetEntity) onToggleTarget(item) else if (item is TaskEntity) onToggleTask(item)
                },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else null
                ),
                color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
            )
        }

        IconButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Sil",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Görevi Sil") },
            text = { Text("Görevi silmek istediğinize emin misiniz?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteTarget(parentTarget.id)
                    }
                ) { Text("Sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Vazgeç") }
            }
        )
    }
}

@Composable
fun TargetTodoItem(
    targetEntity: com.example.sharkmentor.data.local.entity.TargetEntity,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = targetEntity.isCompleted,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = targetEntity.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (targetEntity.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onBackground,
                    textDecoration = if (targetEntity.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )
                if (targetEntity.description.isNotBlank()) {
                    Text(
                        text = targetEntity.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Sil",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hedefi Sil") },
            text = { Text("Bu hedefi silmek istediğinize emin misiniz?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) { Text("Sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Vazgeç") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetCard(
    targetWithTasks: TargetWithTasks,
    onToggleTask: (com.example.sharkmentor.data.local.entity.TaskEntity) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val target = targetWithTasks.target
    val tasks = targetWithTasks.tasks
    
    val completedCount = tasks.count { it.isCompleted }
    val progress = if (tasks.isEmpty()) 0f else completedCount.toFloat() / tasks.size

    val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val deadlineStr = sdf.format(Date(target.deadlineMillis))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (target.priority == "HIGH") {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE11D48))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = target.title,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // ── Coach badge ─────────────────────────────────────
                if (target.isAssignedByCoach) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFFFFCA28), // Amber 400
                                        Color(0xFFFF9800)  // Orange 500
                                    )
                                ),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Koç Görevi",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = target.period.name,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Sil",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                    )
                }
            }
            // ── Coach Note box (collapsible) ───────────────────────────
            if (target.isAssignedByCoach && !target.coachNote.isNullOrBlank()) {
                AnimatedVisibility(visible = expanded || true) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0ea5e9).copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                tint = Color(0xFF38bdf8),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = target.coachNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF38bdf8),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (target.description.isNotBlank()) {
                Text(
                    text = target.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Bitiş: $deadlineStr",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$completedCount/${tasks.size} Görev",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.background,
            )

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tasks.forEach { task ->
                        PlanTaskItemCard(
                            task = task,
                            onToggleCompletion = { onToggleTask(task) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hedefi Sil") },
            text = { Text("Bu planı silmek istediğinize emin misiniz?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("Sil", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Vazgeç")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTargetBottomSheet(
    onDismiss: () -> Unit,
    onSave: (String, String, TargetType, TargetPeriod, Long, List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(TargetType.SHORT_TERM) }
    var period by rememberSaveable { mutableStateOf(TargetPeriod.WEEKLY) }
    
    var newTaskTitle by rememberSaveable { mutableStateOf("") }
    val tasks = remember { mutableStateListOf<String>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Yeni Plan Ekle", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Plan Başlığı", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(12.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                singleLine = true
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Removed Uzun/Kisa Toggle
                
                // Period selector
                Box(Modifier.fillMaxWidth()) {
                    var periodExpanded by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { periodExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)
                    ) {
                        Text(period.name)
                    }
                    DropdownMenu(
                        expanded = periodExpanded,
                        onDismissRequest = { periodExpanded = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.background)
                    ) {
                        listOf(TargetPeriod.DAILY, TargetPeriod.WEEKLY).forEach { p ->
                            DropdownMenuItem(
                                text = { Text(if (p == TargetPeriod.DAILY) "Günlük" else "Haftalık", color = MaterialTheme.colorScheme.onBackground) },
                                onClick = { period = p; periodExpanded = false }
                            )
                        }
                    }
                }
            }

            Text("Görevler", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEachIndexed { index, t ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}. Görev: $t", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                        IconButton(onClick = { tasks.removeAt(index) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Sil", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTaskTitle,
                    onValueChange = { newTaskTitle = it },
                    placeholder = { Text("Alt Görev Başlığı...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newTaskTitle.isNotBlank()) {
                            tasks.add(newTaskTitle.trim())
                            newTaskTitle = ""
                        }
                    },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .size(48.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Ekle", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }

            Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val cal = Calendar.getInstance()
                            when(period) {
                                TargetPeriod.DAILY -> cal.add(Calendar.DAY_OF_YEAR, 1)
                                TargetPeriod.WEEKLY -> cal.add(Calendar.DAY_OF_YEAR, 7)
                                else -> cal.add(Calendar.DAY_OF_YEAR, 1)
                            }
                            onSave(title, description, TargetType.SHORT_TERM, period, cal.timeInMillis, tasks)
                        },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = title.isNotBlank() && tasks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Text("Kaydet", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGoalBottomSheet(
    onDismiss: () -> Unit,
    onSave: (String, String, String?, Long) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    var title by rememberSaveable { mutableStateOf("") }
    var targetValue by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf("NORMAL") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Yeni Hedef Ekle", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Hedef Başlığı (Örn: TYT Matematik Bitiş)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = targetValue,
                onValueChange = { targetValue = it },
                label = { Text("Hedef Değeri (İsteğe bağlı, Örn: 30 Net)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text("Öncelik", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val priorities = listOf("HIGH" to "Yüksek", "NORMAL" to "Normal", "LOW" to "Düşük")
                priorities.forEach { (key, label) ->
                    val isSelected = priority == key
                    Button(
                        onClick = { priority = key },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(label, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.MONTH, 3) // Default 3 months
                    onSave(title, priority, targetValue.takeIf { it.isNotBlank() }, cal.timeInMillis)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Kaydet", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun PlanImportSuccessDialog(
    planResult: com.example.sharkmentor.core.ai.ParsedPlanResult,
    isLongTerm: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.plan_import_success_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.plan_import_success_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Strateji Özeti Kartı
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.plan_summary_header),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = planResult.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.plan_tasks_header) + " (${planResult.tasks.size} Adet):",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Görev Listesi Önizleme
                planResult.tasks.forEachIndexed { index, task ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${task.targetDayOrWeek} • ${task.estimatedMinutes} dakika",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.plan_confirm_save_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.plan_cancel_btn))
            }
        }
    )
}

@Composable
fun PlanTaskItemCard(
    task: com.example.sharkmentor.data.local.entity.TaskEntity,
    onToggleCompletion: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isCompleted) 
                MaterialTheme.colorScheme.surfaceContainerLow 
            else 
                MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = onToggleCompletion,
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }

            // Durum Rozeti
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (task.isCompleted) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = if (task.isCompleted) stringResource(R.string.plan_task_completed) else stringResource(R.string.plan_task_pending),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (task.isCompleted) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlansScreenPreview() {
    val sampleTasks = listOf(
        com.example.sharkmentor.data.local.entity.TaskEntity(id = 1, targetId = 1, title = "Konu Tekrarı: Limit ve Süreklilik", isCompleted = true, orderIndex = 0),
        com.example.sharkmentor.data.local.entity.TaskEntity(id = 2, targetId = 1, title = "Soru Çözümü: 100 Soru", isCompleted = false, orderIndex = 1),
        com.example.sharkmentor.data.local.entity.TaskEntity(id = 3, targetId = 1, title = "Deneme Sınavı Analizi", isCompleted = false, orderIndex = 2)
    )
    
    val sampleTargets = listOf(
        com.example.sharkmentor.data.local.entity.TargetWithTasks(
            target = com.example.sharkmentor.data.local.entity.TargetEntity(
                id = 1,
                title = "Matematik AYT Kampı",
                description = "Limit, Türev, İntegral konularını bitir.",
                type = com.example.sharkmentor.data.local.entity.TargetType.LONG_TERM,
                period = com.example.sharkmentor.data.local.entity.TargetPeriod.MONTHLY,
                deadlineMillis = System.currentTimeMillis() + 1000 * 60 * 60 * 24 * 30L
            ),
            tasks = sampleTasks
        ),
        com.example.sharkmentor.data.local.entity.TargetWithTasks(
            target = com.example.sharkmentor.data.local.entity.TargetEntity(
                id = 2,
                title = "Haftalık Kitap Okuma",
                description = "Sefiller - 200 sayfa",
                type = com.example.sharkmentor.data.local.entity.TargetType.LONG_TERM,
                period = com.example.sharkmentor.data.local.entity.TargetPeriod.WEEKLY,
                deadlineMillis = System.currentTimeMillis() + 1000 * 60 * 60 * 24 * 7L
            ),
            tasks = emptyList()
        )
    )

    com.example.sharkmentor.ui.theme.SharkMentorTheme {
        PlansScreenContent(
            uiState = PlansUiState(
                longTermTargets = sampleTargets,
                shortTermTargets = emptyList()
            )
        )
    }
}
