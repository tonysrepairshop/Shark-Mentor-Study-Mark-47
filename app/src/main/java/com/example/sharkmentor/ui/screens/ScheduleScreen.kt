package com.example.sharkmentor.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.ui.viewmodel.ScheduleUiState
import com.example.sharkmentor.ui.viewmodel.ScheduleViewModel
import com.example.sharkmentor.data.local.entity.InstitutionScheduleEntity
import java.util.Calendar

enum class ScheduleViewMode { TABLE, LIST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onBackClick: () -> Unit,
    viewModel: ScheduleViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    val initialDayIdx = remember {
        val cal = Calendar.getInstance()
        when(cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }
    var selectedDay by remember { mutableIntStateOf(initialDayIdx) }
    var viewMode by remember { mutableStateOf(ScheduleViewMode.TABLE) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.scanAndSaveSchedule(it) }
    }
    
    var showMenu by remember { mutableStateOf(false) }
    var showClearDayConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    val isReadOnly = (uiState as? ScheduleUiState.Success)?.isReadOnlyMode == true

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 52.dp,
                title = {
                    Column {
                        Text("Haftalık Programım", fontWeight = FontWeight.Bold)
                        if (isReadOnly) {
                            Text("Kurumsal Program (Salt Okunur)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    if (uiState is ScheduleUiState.Success) {
                        IconButton(onClick = { 
                            viewMode = if (viewMode == ScheduleViewMode.TABLE) ScheduleViewMode.LIST else ScheduleViewMode.TABLE 
                        }) {
                            Icon(
                                if (viewMode == ScheduleViewMode.TABLE) Icons.Default.ViewList else Icons.Default.ViewModule,
                                contentDescription = "Görünümü Değiştir"
                            )
                        }
                    }
                    if (!isReadOnly && uiState is ScheduleUiState.Success) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Seçenekler")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Bu Günü Temizle") },
                                onClick = {
                                    showMenu = false
                                    showClearDayConfirm = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Tüm Programı Sıfırla", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showClearAllConfirm = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is ScheduleUiState.Loading -> {
                    val stages = listOf(
                        "Belge taranıyor ve metin blokları ayrıştırılıyor...",
                        "Dersler, saatler ve öğretmen isimleri tespit ediliyor...",
                        "Haftalık dershane matrisine yerleştiriliyor...",
                        "Son kontroller yapılıyor..."
                    )
                    com.example.sharkmentor.ui.components.DynamicThinkingCard(
                        stages = stages,
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.9f)
                    )
                }
                is ScheduleUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { filePickerLauncher.launch("*/*") }) {
                            Text("Tekrar Yükle")
                        }
                    }
                }
                is ScheduleUiState.Success -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        if (state.isReadOnlyMode) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        "Kurumsal Öğrenci",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Programınız kurumunuz tarafından yönetilmektedir.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        if (!state.isReadOnlyMode) {
                            if (state.isCoachSchedule) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                ) {
                                    Text(
                                        "Koçunuz bu programı sizin için oluşturdu.",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { filePickerLauncher.launch("*/*") },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.DocumentScanner, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Belge / Fotoğraf Tara", textAlign = TextAlign.Center)
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }

                        if (state.isReadOnlyMode && state.schedules.isEmpty()) {
                            if (state.webScheduleText.isNullOrBlank()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "Kurumunuz henüz güncel bir ders programı yüklemedi.",
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Text(
                                        text = state.webScheduleText,
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        } else {
                            if (state.isReadOnlyMode && !state.webScheduleText.isNullOrBlank()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Text(
                                        text = state.webScheduleText,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }

                            if (viewMode == ScheduleViewMode.TABLE) {
                                TimetableMatrixView(
                                    schedules = state.schedules,
                                    isReadOnly = state.isReadOnlyMode,
                                    onDelete = { scheduleId -> viewModel.deleteSchedule(scheduleId) }
                                )
                            } else {
                                // Day Tabs
                                val dayNames = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
                                LazyRow(
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(dayNames.indices.toList()) { index ->
                                        val dayNum = index + 1
                                        val isSelected = selectedDay == dayNum
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedDay = dayNum },
                                            label = { Text(dayNames[index], fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }
                                
                                val daySchedules = state.schedules.filter { it.dayOfWeek == selectedDay }

                                if (daySchedules.isEmpty()) {
                                    Text(
                                        "Bu güne ait program bulunmuyor.",
                                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                        items(daySchedules, key = { it.id }) { schedule ->
                                            ScheduleItemCard(
                                                schedule = schedule,
                                                isReadOnly = state.isReadOnlyMode,
                                                onDelete = { viewModel.deleteSchedule(schedule.id) }
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
    }
    
    // Bulk Delete Confirmation Dialogs
    val dayNames = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
    val selectedDayName = dayNames.getOrElse(selectedDay - 1) { "Bilinmeyen" }

    if (showClearDayConfirm) {
        AlertDialog(
            onDismissRequest = { showClearDayConfirm = false },
            title = { Text("Günü Temizle") },
            text = { Text("$selectedDayName gününe ait tüm program silinsin mi?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSchedulesByDay(selectedDay)
                        showClearDayConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Sil") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDayConfirm = false }) { Text("Vazgeç") }
            }
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Tüm Programı Sıfırla") },
            text = { Text("Tüm program kayıtları silinecek. Bu işlem geri alınamaz. Emin misiniz?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllIndividualSchedules()
                        showClearAllConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Sıfırla") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("Vazgeç") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableMatrixView(
    schedules: List<InstitutionScheduleEntity>,
    isReadOnly: Boolean,
    onDelete: (String) -> Unit
) {
    val dayNames = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
    
    // Extract unique time slots across all days, sorted chronologically
    val timeSlots = schedules.map { "${it.startTime}-${it.endTime}" }
        .distinct()
        .sortedBy { it.split("-")[0].trim() }

    if (timeSlots.isEmpty()) {
        Text(
            "Program bulunmuyor.",
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    // Scrollable grid
    val scrollStateHorizontal = rememberScrollState()
    val scrollStateVertical = rememberScrollState()
    
    var selectedSchedule by remember { mutableStateOf<InstitutionScheduleEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(scrollStateHorizontal)
        ) {
            Column(
                modifier = Modifier.verticalScroll(scrollStateVertical)
            ) {
                // Header row
                Row {
                    // Empty top-left cell
                    Box(modifier = Modifier.width(50.dp).height(40.dp))
                    
                    timeSlots.forEach { slot ->
                        Box(
                            modifier = Modifier
                                .width(90.dp)
                                .height(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = slot,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // Rows for each day
                dayNames.forEachIndexed { index, dayName ->
                    val dayNum = index + 1
                    Row {
                        // Day column
                        Box(
                            modifier = Modifier
                                .width(50.dp)
                                .height(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        // Cells
                        timeSlots.forEach { slot ->
                            val scheduleForCell = schedules.find { 
                                it.dayOfWeek == dayNum && "${it.startTime}-${it.endTime}" == slot 
                            }
                            
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(60.dp)
                                    .padding(2.dp)
                                    .background(Color(0xFF1E293B), shape = MaterialTheme.shapes.extraSmall)
                                    .border(1.dp, Color(0xFF334155), shape = MaterialTheme.shapes.extraSmall)
                                    .clickable(enabled = scheduleForCell != null) {
                                        selectedSchedule = scheduleForCell
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (scheduleForCell != null) {
                                    val subjAbbrev = scheduleForCell.subject.take(4).uppercase(java.util.Locale("tr"))
                                    val accentColor = getSubjectColor(scheduleForCell.subject)
                                    
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(accentColor))
                                        Column(
                                            modifier = Modifier.padding(4.dp).fillMaxSize(),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = subjAbbrev,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (scheduleForCell.teacherName.isNotBlank()) {
                                                Text(
                                                    text = scheduleForCell.teacherName.take(7),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF94A3B8),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "—",
                                        color = Color(0xFF475569)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Bottom Sheet for selected cell
    if (selectedSchedule != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedSchedule = null }
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(
                    text = selectedSchedule!!.subject,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Saat: ${selectedSchedule!!.startTime} - ${selectedSchedule!!.endTime}")
                if (selectedSchedule!!.teacherName.isNotBlank()) {
                    Text(text = "Öğretmen: ${selectedSchedule!!.teacherName}")
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (!isReadOnly) {
                    Button(
                        onClick = {
                            onDelete(selectedSchedule!!.id)
                            selectedSchedule = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sil")
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

fun getSubjectColor(subject: String): Color {
    val s = subject.lowercase()
    return when {
        s.contains("mat") || s.contains("geo") -> Color(0xFF38BDF8) // Math
        s.contains("fiz") || s.contains("kim") || s.contains("biy") || s.contains("fen") -> Color(0xFF34D399) // Science
        s.contains("tür") || s.contains("edeb") -> Color(0xFFF472B6) // Turkish
        else -> Color(0xFF94A3B8) // Slate 400
    }
}

@Composable
private fun ScheduleItemCard(
    schedule: InstitutionScheduleEntity,
    isReadOnly: Boolean,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(schedule.subject, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${schedule.startTime} - ${schedule.endTime}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!isReadOnly) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Sil",
                        tint = Color(0xFF94A3B8) // Slate 400
                    )
                }
            }
        }
    }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Dersi Sil") },
            text = { Text("${schedule.subject} (${schedule.startTime} - ${schedule.endTime}) programdan silinsin mi?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)) // Red 500
                ) { Text("Sil") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Vazgeç") }
            }
        )
    }
}
