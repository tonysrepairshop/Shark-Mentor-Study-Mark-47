package com.example.sharkmentor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingBookingBottomSheet(
    studentName: String,
    coachName: String,
    onDismiss: () -> Unit,
    onConfirmBooking: (appointmentDate: Date, note: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var topic by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Koçla Görüşme Talebi", style = MaterialTheme.typography.titleLarge)
            Text(text = "Eğitim Koçunuz: $coachName")
            
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("Görüşme Konusu (Örn: Haftalık Plan)") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Button(
                onClick = {
                    isSubmitting = true
                    onConfirmBooking(Date(), topic)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text("Talebi Gönder (Otomatik Onay)")
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
