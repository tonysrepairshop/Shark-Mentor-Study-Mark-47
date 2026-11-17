package com.example.sharkmentor.ui.screens.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharkmentor.data.model.TimeSlotDefinition
import com.example.sharkmentor.data.model.generateDynamicSlots
import com.example.sharkmentor.ui.components.diagonalStrikeThrough
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachAppointmentBookingSheet(
    studentName: String,
    coachName: String,
    coachOffDayName: String?, // e.g., "Pazar" or null
    workStartHour: Int,
    workEndHour: Int,
    bookedTimestamps: List<Date>, // Booked meeting dates from Firestore
    onDismiss: () -> Unit,
    onConfirmBooking: (appointmentDate: Date, reason: String) -> Unit
) {
    var reasonText by remember { mutableStateOf("") }
    val calendar = remember { Calendar.getInstance() }
    val now = remember { Date() }
    
    val dynamicSlots = remember(workStartHour, workEndHour) {
        generateDynamicSlots(workStartHour, workEndHour)
    }

    // Generate next 7 days window (Day 0 to Day +6)
    val rollingDays = remember {
        (0..6).map { offset ->
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
            val dayName = SimpleDateFormat("EEEE", Locale("tr")).format(cal.time)
            val formatted = SimpleDateFormat("dd-MM-yyyy", Locale("tr")).format(cal.time)
            val isOff = coachOffDayName?.equals(dayName, ignoreCase = true) == true
            Triple(cal.time, dayName, isOff)
        }
    }

    var selectedDayTriple by remember { mutableStateOf(rollingDays.first()) }
    var selectedSlot by remember { mutableStateOf<TimeSlotDefinition?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Randevu Tarih ve Saat Seçimi",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Danışan: $studentName • Koç: $coachName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat")
                }
            }

            // Reason Input
            OutlinedTextField(
                value = reasonText,
                onValueChange = { if (it.length <= 150) reasonText = it },
                label = { Text("Randevu Talep Sebebi (İsteğe Bağlı)") },
                placeholder = { Text("Örn: TYT Matematik Net Analizi & Çalışma Takvimi") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Section 1: Date Grid (Medicana / Hospital Style)
            Text(
                text = "TARİH SEÇİMİ (ÖNÜMÜZDEKİ 7 GÜN)",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 2-Column Hospital-Style Day Cards
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rollingDays.chunked(2).forEach { rowDays ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowDays.forEach { (date, dayName, isOff) ->
                            val isSelected = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(selectedDayTriple.first) ==
                                    SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date)

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    isOff -> MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .diagonalStrikeThrough(isStriked = isOff)
                                    .clickable(enabled = !isOff) {
                                        selectedDayTriple = Triple(date, dayName, isOff)
                                        selectedSlot = null
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = dayName,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isOff) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = SimpleDateFormat("dd-MM-yyyy", Locale("tr")).format(date),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (rowDays.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // Section 2: Time Slots
            Text(
                text = "SAAT DİLİMİ (45 DK SEANS + 15 DK MOLA)",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (selectedDayTriple.third) {
                // Coach Off-day warning banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventBusy, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Seçilen gün koçunuzun haftalık izin günüdür. Randevu oluşturulamaz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                // Slots Grid
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dynamicSlots.chunked(2).forEach { slotRow ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            slotRow.forEach { slot ->
                                // Calculate slot Date
                                val slotCal = Calendar.getInstance().apply {
                                    time = selectedDayTriple.first
                                    set(Calendar.HOUR_OF_DAY, slot.startHour)
                                    set(Calendar.MINUTE, slot.startMinute)
                                    set(Calendar.SECOND, 0)
                                }

                                // Check if in past or already booked
                                val isPast = slotCal.time.before(now)
                                val isBooked = bookedTimestamps.any { bookedDate ->
                                    val bookedCal = Calendar.getInstance().apply { time = bookedDate }
                                    slotCal.get(Calendar.YEAR) == bookedCal.get(Calendar.YEAR) &&
                                    slotCal.get(Calendar.DAY_OF_YEAR) == bookedCal.get(Calendar.DAY_OF_YEAR) &&
                                    slotCal.get(Calendar.HOUR_OF_DAY) == bookedCal.get(Calendar.HOUR_OF_DAY) &&
                                    slotCal.get(Calendar.MINUTE) == bookedCal.get(Calendar.MINUTE)
                                }

                                val isUnavailable = isPast || isBooked
                                val isSelected = selectedSlot == slot

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isUnavailable -> MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .diagonalStrikeThrough(isStriked = isUnavailable)
                                        .clickable(enabled = !isUnavailable) { selectedSlot = slot }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = slot.displayLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = when {
                                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                                isUnavailable -> MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                            if (slotRow.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Confirm Action
            Button(
                onClick = {
                    selectedSlot?.let { slot ->
                        val finalCal = Calendar.getInstance().apply {
                            time = selectedDayTriple.first
                            set(Calendar.HOUR_OF_DAY, slot.startHour)
                            set(Calendar.MINUTE, slot.startMinute)
                            set(Calendar.SECOND, 0)
                        }
                        onConfirmBooking(finalCal.time, reasonText.trim())
                    }
                },
                enabled = selectedSlot != null && !selectedDayTriple.third,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    text = "Randevuyu Onayla & Talebi Gönder",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}
