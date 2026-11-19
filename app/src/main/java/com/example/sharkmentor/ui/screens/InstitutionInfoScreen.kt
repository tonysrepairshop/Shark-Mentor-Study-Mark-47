package com.example.sharkmentor.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Event
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import com.example.sharkmentor.ui.screens.coach.CoachAppointmentBookingSheet
import com.example.sharkmentor.ui.screens.profile.ProfileViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.core.util.SessionManager
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstitutionInfoScreen(
    onBackClick: () -> Unit,
    onNavigateToChat: () -> Unit = {},
    onNavigateToSchedule: () -> Unit = {}
) {
    val institutionViewModel: com.example.sharkmentor.ui.viewmodel.InstitutionViewModel = viewModel()
    val uiState by institutionViewModel.uiState.collectAsState()
    
    val isLoading = uiState.isLoading
    val coachName = uiState.coachName
    val coachBranch = uiState.coachBranch
    val coachPhone = uiState.coachPhone
    val institutionName = uiState.institutionName
    val instStartHour = uiState.instStartHour
    val instEndHour = uiState.instEndHour
    val hasCoach = uiState.hasCoach
    val activeCoachId = uiState.activeCoachId
    val coachOffDay = uiState.coachOffDay
    val studentName = uiState.studentName
    
    var showAppointmentSheet by remember { mutableStateOf(false) }
    var activeBookedDatesList by remember { mutableStateOf<List<Date>>(emptyList()) }
    val profileViewModel: ProfileViewModel = viewModel()
    val studentMeetings by profileViewModel.studentMeetings.collectAsState(initial = emptyList())
    val context = LocalContext.current

    LaunchedEffect(activeCoachId) {
        if (activeCoachId.isNotBlank() && FirebaseAuth.getInstance().currentUser != null) {
            val db = GoogleServicesHelper.getFirebaseFirestore()
            runCatching {
                db?.collection("meetings")
                    ?.whereEqualTo("coachId", activeCoachId)
                    ?.addSnapshotListener { snap, error ->
                        if (error != null) return@addSnapshotListener
                        val dates = snap?.documents?.mapNotNull { it.getTimestamp("appointmentTimestamp")?.toDate() } ?: emptyList()
                        activeBookedDatesList = dates
                    }
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Replaced LaunchedEffect(Unit) block with InstitutionViewModel}

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                expandedHeight = 50.dp,
                title = { Text("Kurum Bilgisi & Bağlantı") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else if (hasCoach) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Bağlı Kurum", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(institutionName, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Sorumlu Koç", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(coachName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                        if (coachBranch.isNotBlank()) {
                            Text(coachBranch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        // 1. Haftalık Randevu ve 24 Saat Kontrolü
                        val nowMillis = System.currentTimeMillis()
                        val activeWeekMeeting = studentMeetings.firstOrNull { meeting ->
                            val meetingTime = meeting.appointmentTimestamp.toDate().time
                            val now = System.currentTimeMillis()
                            // Görüşme süresi 45 dk olduğu için seans bitimine kadar aktif say, bitince arşive gönder:
                            val meetingEndTime = meetingTime + (45 * 60 * 1000)
                            meeting.status != "CANCELLED" && meetingEndTime > now
                        }

                        val canReschedule = activeWeekMeeting?.let {
                            val remainingHours = (it.appointmentTimestamp.toDate().time - nowMillis) / (1000 * 60 * 60)
                            remainingHours >= 24
                        } ?: true

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Header Row: Koç Bilgisi ve Canlı Durum
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(Color(0xFFFF6B00).copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.EventNote,
                                            contentDescription = null,
                                            tint = Color(0xFFFF6B00),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Koç Danışmanlık & Randevu Masası",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = if (coachName.isNotBlank()) "Rehber Koç: $coachName" else "Bağlı Kurum Koçu",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Aktif Randevu Bilgisi (Varsa)
                                if (activeWeekMeeting != null) {
                                    val dateStr = SimpleDateFormat("d MMMM yyyy, EEEE • HH:mm", Locale("tr"))
                                            .format(activeWeekMeeting.appointmentTimestamp.toDate())
                                    
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                            Text(
                                                text = "Planlanan Seans: $dateStr",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = Color(0xFF10B981)
                                            )
                                        }
                                    }
                                }

                                // Yeniden Konumlandırılmış Canlı Turuncu Buton (Bağımsız ve Dikkat Çekici)
                                Button(
                                    onClick = { showAppointmentSheet = true },
                                    enabled = activeWeekMeeting == null || canReschedule,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFF6B00), // Sabit Canlı Turuncu
                                        contentColor = Color.White,
                                        disabledContainerColor = Color(0xFF475569).copy(alpha = 0.3f),
                                        disabledContentColor = Color.White.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                ) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            activeWeekMeeting == null -> "Koçla Randevu Al (45 Dk Seans)"
                                            canReschedule -> "Randevu Saatini Değiştir"
                                            else -> "Randevu Değiştirilemez (Son 24 Saat)"
                                        },
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }

                        // In-app coach messaging CTA
                        Button(
                            onClick = onNavigateToChat,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2EC4B6)
                            )
                        ) {
                            Icon(
                                Icons.Default.Forum,
                                contentDescription = null,
                                tint = Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Koça Mesaj Gönder",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = onNavigateToSchedule,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Dershane Ders Programını Görüntüle",
                                color = MaterialTheme.colorScheme.onSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                    }
                }
            } else {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Study Modu (Bireysel)", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text("Lütfen Profil sayfasından bir koç veya eğitim kurumuna bağlanın.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showAppointmentSheet) {
        CoachAppointmentBookingSheet(
            studentName = studentName.ifBlank { "Öğrenci" },
            coachName = coachName.ifBlank { "Rehber Koç" },
            coachOffDayName = coachOffDay,
            workStartHour = instStartHour,
            workEndHour = instEndHour,
            bookedTimestamps = activeBookedDatesList,
            onDismiss = { showAppointmentSheet = false },
            onConfirmBooking = { appointmentDate, reason ->
                profileViewModel.bookMeeting(
                    coachId = activeCoachId,
                    institutionId = null,
                    studentName = studentName.ifBlank { "Öğrenci" },
                    appointmentDate = appointmentDate,
                    requestNote = reason
                )
                showAppointmentSheet = false
            }
        )
    }

    // Removed unlink confirm dialog
}
