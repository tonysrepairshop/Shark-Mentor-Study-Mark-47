package com.example.sharkmentor.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownSettingsScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sharkmentor_prefs", Context.MODE_PRIVATE) }
    var selectedMillis by remember { mutableLongStateOf(prefs.getLong("target_date_millis", 0L)) }
    var targetTitle by remember { mutableStateOf(prefs.getString("target_exam_title", "") ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = if (selectedMillis > 0) selectedMillis else System.currentTimeMillis()
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis
                        if (millis != null) {
                            selectedMillis = millis
                            val cleanTitle = targetTitle.ifBlank { "Hedef Sınav" }
                            saveExamTarget(context, cleanTitle, millis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Vazgeç")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 50.dp,
                title = { Text("Hedef Sınav Tarihi Belirle") },
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Özel Hedef & Sınav Tarihi",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = targetTitle,
                onValueChange = { targetTitle = it },
                label = { Text("Hedef / Sınav Adı") },
                placeholder = { Text("Örn: YKS 2027, Final Sınavı, LGS") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { showDatePicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sınav Tarihini Takvimden Seç", fontWeight = FontWeight.Bold)
            }

            if (selectedMillis > 0) {
                val formattedDate = SimpleDateFormat("dd MMMM yyyy", Locale("tr")).format(Date(selectedMillis))
                val diffDays = (selectedMillis - System.currentTimeMillis()) / (24 * 3600 * 1000)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Mevcut Hedef: ${targetTitle.ifBlank { "Hedef Sınav" }}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tarih: $formattedDate (${if (diffDays >= 0) "$diffDays Gün Kaldı" else "Geçmiş Tarih"})",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

private fun saveExamTarget(context: Context, title: String, millis: Long) {
    val prefs = context.getSharedPreferences("sharkmentor_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putLong("target_date_millis", millis)
        .putString("target_exam_title", title)
        .apply()

    val uid = GoogleServicesHelper.getCurrentUser(context = context)?.uid
    if (uid != null) {
        GoogleServicesHelper.getFirebaseFirestore(context)?.collection("students")?.document(uid)
            ?.set(
                hashMapOf(
                    "targetExamTitle" to title,
                    "targetExamDate" to millis
                ),
                SetOptions.merge()
            )
    }

    Toast.makeText(context, "Hedef sınav tarihi kaydedildi.", Toast.LENGTH_SHORT).show()
}
