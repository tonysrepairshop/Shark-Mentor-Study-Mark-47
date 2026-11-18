package com.example.sharkmentor.ui.components

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.UniversalExamScanner
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.MonthlyExamNetEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class SubjectScoreInput(
    val subjectName: String,
    initialCorrect: String = "",
    initialIncorrect: String = "",
    initialBlank: String = ""
) {
    var correct by mutableStateOf(initialCorrect)
    var incorrect by mutableStateOf(initialIncorrect)
    var blank by mutableStateOf(initialBlank)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrialExamEntryDialog(
    onDismiss: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var examType by remember { mutableStateOf("TYT") }
    var publisherName by remember { mutableStateOf("") }
    
    var isScanning by remember { mutableStateOf(false) }
    
    val subjects = remember(examType) {
        when (examType) {
            "LGS" -> listOf(
                SubjectScoreInput("Türkçe"),
                SubjectScoreInput("Matematik"),
                SubjectScoreInput("Fen Bilimleri"),
                SubjectScoreInput("T.C. İnkılap"),
                SubjectScoreInput("Din Kültürü"),
                SubjectScoreInput("İngilizce")
            )
            "AYT" -> listOf(
                SubjectScoreInput("Matematik"),
                SubjectScoreInput("Fizik"),
                SubjectScoreInput("Kimya"),
                SubjectScoreInput("Biyoloji"),
                SubjectScoreInput("Edebiyat"),
                SubjectScoreInput("Tarih-1")
            )
            else -> listOf( // TYT
                SubjectScoreInput("Türkçe"),
                SubjectScoreInput("Matematik"),
                SubjectScoreInput("Fen Bilimleri"),
                SubjectScoreInput("Sosyal Bilimler")
            )
        }
    }
    
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            isScanning = true
            coroutineScope.launch {
                val result = UniversalExamScanner.parseExamFile(context, uri, examType)
                result.onSuccess { parsedScores ->
                    subjects.forEach { subject ->
                        parsedScores[subject.subjectName]?.let { (c, i) ->
                            subject.correct = c.toInt().toString()
                            subject.incorrect = i.toInt().toString()
                        }
                    }
                    Toast.makeText(context, "Sonuçlar tarandı ve aktarıldı.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Tarama hatası: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
                isScanning = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Deneme Sınavı Net Gir", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Exam type selector
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("TYT", "AYT", "LGS").forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = examType == type,
                            onClick = { examType = type },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                        ) {
                            Text(type)
                        }
                    }
                }
                
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Taranıyor...")
                    } else {
                        Text("Excel/PDF ile Oku")
                    }
                }

                OutlinedTextField(
                    value = publisherName,
                    onValueChange = { publisherName = it },
                    label = { Text("Yayın / Deneme Adı") },
                    placeholder = { Text("Örn: 3D Türkiye Geneli Deneme-1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                Text("Ders Başarı Girdileri", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                subjects.forEach { subject ->
                    val cVal = subject.correct.toIntOrNull() ?: 0
                    val iVal = subject.incorrect.toIntOrNull() ?: 0
                    val divisor = if (examType == "LGS") 3.0 else 4.0
                    val calcNet = Math.max(0.0, cVal - (iVal / divisor))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(subject.subjectName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text("Net: ${String.format(Locale.US, "%.2f", calcNet)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = subject.correct,
                                    onValueChange = { subject.correct = it },
                                    label = { Text("Doğru", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = subject.incorrect,
                                    onValueChange = { subject.incorrect = it },
                                    label = { Text("Yanlış", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        saveTrialExam(context, examType, publisherName, subjects)
                        Toast.makeText(context, "Deneme başarıyla kaydedildi.", Toast.LENGTH_SHORT).show()
                        onSaveSuccess()
                        onDismiss()
                    }
                }
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

private suspend fun saveTrialExam(
    context: Context,
    examType: String,
    publisher: String,
    subjects: List<SubjectScoreInput>
) {
    val divisor = if (examType == "LGS") 3.0 else 4.0
    var totalNet = 0.0
    val detailsMap = mutableMapOf<String, Any>()

    subjects.forEach { s ->
        val c = s.correct.toIntOrNull() ?: 0
        val i = s.incorrect.toIntOrNull() ?: 0
        val net = Math.max(0.0, c - (i / divisor))
        totalNet += net
        detailsMap[s.subjectName] = mapOf(
            "correct" to c,
            "incorrect" to i,
            "net" to net
        )
    }

    val cal = Calendar.getInstance()
    val yearMonthStr = String.format("%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    val detailsJson = "$publisher ($examType) - Toplam Net: ${String.format("%.2f", totalNet)}"

    withContext(Dispatchers.IO) {
        val db = SharkMentorDatabase.getDatabase(context)
        db.monthlyExamNetDao().insertNet(
            MonthlyExamNetEntity(
                yearMonth = yearMonthStr,
                examType = examType,
                netDetailsJson = detailsJson,
                notes = publisher
            )
        )
    }

    val uid = GoogleServicesHelper.getCurrentUser()?.uid
    if (uid != null) {
        val examData = hashMapOf(
            "examType" to examType,
            "publisher" to publisher,
            "totalNet" to totalNet,
            "subjectDetails" to detailsMap,
            "timestamp" to FieldValue.serverTimestamp()
        )

        GoogleServicesHelper.getFirebaseFirestore()
            ?.collection("students")?.document(uid)
            ?.collection("exams")?.add(examData)
    }
}
