package com.example.sharkmentor.ui.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.ui.components.AiDisclaimerText

val PRESET_ERROR_BANK_SUBJECTS = listOf(
    "Matematik",
    "Fizik",
    "Kimya",
    "Biyoloji",
    "Türkçe / Edebiyat",
    "Tarih",
    "Coğrafya",
    "Felsefe / Din",
    "İngilizce",
    "Fransızca",
    "Almanca",
    "İtalyanca",
    "Genel Yetenek / Diğer"
)

private val SUBJECT_TOPIC_SUGGESTIONS: Map<String, List<String>> = mapOf(
    "Biyoloji" to listOf(
        "Hücre ve Organeller",
        "Enzimler ve ATP",
        "Hücresel Solunum",
        "Fotosentez ve Kemosentez",
        "DNA, RNA ve Protein Sentezi",
        "Hücre Bölünmeleri",
        "Kalıtım",
        "Sinir Sistemi",
        "Endokrin Sistem",
        "Sindirim Sistemi",
        "Dolaşım Sistemi",
        "Solunum Sistemi",
        "Üriner Sistem",
        "Bitki Biyolojisi",
        "Ekoloji"
    ),
    "Kimya" to listOf(
        "Atom ve Periyodik Sistem",
        "Kimyasal Türler Arası Etkileşimler",
        "Mol ve Kimyasal Hesaplamalar",
        "Gazlar",
        "Sıvı Çözeltiler ve Çözünürlük",
        "Kimyasal Tepkimelerde Enerji",
        "Tepkime Hızı",
        "Kimyasal Denge",
        "Asit-Baz Dengesi",
        "Çözünürlük Dengesi (Kçç)",
        "Kimya ve Elektrik",
        "Organik Kimya"
    ),
    "Fizik" to listOf(
        "Madde ve Özellikleri",
        "Kuvvet ve Hareket",
        "İş, Güç ve Enerji",
        "Isı, Sıcaklık ve Genleşme",
        "Basınç ve Kaldırma Kuvveti",
        "Elektrostatik",
        "Elektrik Devreleri",
        "Manyetizma",
        "Dalgalar",
        "Optik",
        "Çembersel Hareket",
        "Modern Fizik"
    ),
    "Matematik" to listOf(
        "Temel Kavramlar",
        "Fonksiyonlar",
        "Polinomlar",
        "2. Dereceden Denklemler",
        "Trigonometri",
        "Logaritma ve Diziler",
        "Limit ve Süreklilik",
        "Türev",
        "İntegral",
        "Olasılık",
        "Geometri"
    )
)

@Composable
fun QuickQuestionPreviewDialog(
    question: ErrorQuestionEntity,
    onDismiss: () -> Unit,
    onSolveWithSharkAi: (ErrorQuestionEntity) -> Unit = {},
    onTwinWithSharkAi: (ErrorQuestionEntity) -> Unit = {},
    onEditClassification: ((ErrorQuestionEntity) -> Unit)? = null
) {
    var showSolution by remember { mutableStateOf(false) }
    val effectiveTopic = remember(question.aiTopic, question.topic) {
        question.aiTopic?.takeIf { it.isNotBlank() } ?: question.topic.ifBlank { "Genel" }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = question.subject.uppercase(java.util.Locale("tr")),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (onEditClassification != null) {
                        TextButton(
                            onClick = { onEditClassification(question) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Dersi / Konuyu Düzenle",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Dersi / Konuyu Düzenle",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Question Image
                    if (question.questionImageUri.isNotEmpty()) {
                        AsyncImage(
                            model = question.questionImageUri,
                            contentDescription = "Question Image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Subject & Topic Row with Edit Chip
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { mod ->
                                if (onEditClassification != null) {
                                    mod.clickable { onEditClassification(question) }
                                } else mod
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ders: ${question.subject}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Konu: $effectiveTopic",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (onEditClassification != null) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Dersi / Konuyu Düzenle",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Solution Section (Toggleable)
                    if (showSolution) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "KAYITLI ÇÖZÜM",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (!question.solutionImageUri.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = question.solutionImageUri,
                                        contentDescription = "Solution Image",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                } else {
                                    Text(
                                        text = "Bu soru için eklenmiş çözüm notu/görseli bulunmuyor.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (showSolution) {
                        AiDisclaimerText()
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Shark AI Direct Specialist Engine Handoff Row ("Soru Çözümü" & "Benzer Soru")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onSolveWithSharkAi(question)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Soru Çözümü", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onTwinWithSharkAi(question)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Benzer Soru", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Bar: Toggle Solution
                Button(
                    onClick = { showSolution = !showSolution },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showSolution) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (showSolution) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (showSolution) "Çözümü Gizle" else "Yüklü Çözümü Göster")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditQuestionClassificationDialog(
    question: ErrorQuestionEntity,
    availableTopics: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (newSubject: String, newTopic: String) -> Unit,
    onAiReclassify: ((onComplete: (String, String) -> Unit, onError: (String) -> Unit) -> Unit)? = null
) {
    var selectedSubject by remember(question.id) {
        mutableStateOf(question.subject.ifBlank { "Matematik" })
    }
    var customTopic by remember(question.id) {
        val current = question.aiTopic?.takeIf { it.isNotBlank() } ?: question.topic
        mutableStateOf(if (current.equals("Genel", ignoreCase = true)) "" else current)
    }
    var isAiLoading by remember { mutableStateOf(false) }
    var aiStatusText by remember { mutableStateOf<String?>(null) }

    val subjectPresets = remember(question.subject) {
        if (PRESET_ERROR_BANK_SUBJECTS.any { it.equals(question.subject, ignoreCase = true) }) {
            PRESET_ERROR_BANK_SUBJECTS
        } else {
            listOf(question.subject) + PRESET_ERROR_BANK_SUBJECTS
        }
    }

    val suggestedTopics = remember(selectedSubject, availableTopics) {
        val builtIn = SUBJECT_TOPIC_SUGGESTIONS[selectedSubject].orEmpty()
        (availableTopics + builtIn).distinct()
    }

    AlertDialog(
        onDismissRequest = { if (!isAiLoading) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Dersi / Konuyu Düzenle",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Sorunun ait olduğu dersi ve alt konuyu seçin. Kaydettiğinizde soru otomatik olarak seçilen ders/konu klasörüne taşınır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 1. Hazır Ders Çipleri
                Text(
                    text = "Ders Klasörü",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    subjectPresets.forEach { subj ->
                        FilterChip(
                            selected = subj.equals(selectedSubject, ignoreCase = true),
                            onClick = { selectedSubject = subj },
                            label = { Text(subj) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Özel Konu Girişi
                Text(
                    text = "Alt Konu Adı",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = customTopic,
                    onValueChange = { customTopic = it },
                    placeholder = { Text("Örn: Hücresel Solunum, Sıvı Çözeltiler...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (suggestedTopics.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Hızlı Konu Seçimi ($selectedSubject):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestedTopics.forEach { topicSuggestion ->
                            AssistChip(
                                onClick = { customTopic = topicSuggestion },
                                label = { Text(topicSuggestion, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // 3. İsteğe bağlı gemini-3.6-flash ile Yeniden Sınıflandır
                if (onAiReclassify != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            isAiLoading = true
                            aiStatusText = null
                            onAiReclassify(
                                { aiSubject, aiTopic ->
                                    isAiLoading = false
                                    selectedSubject = aiSubject
                                    customTopic = aiTopic
                                    aiStatusText = "AI önerisi uygulandı: $aiSubject • $aiTopic"
                                },
                                { errMsg ->
                                    isAiLoading = false
                                    aiStatusText = errMsg
                                }
                            )
                        },
                        enabled = !isAiLoading,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isAiLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("AI Sınıflandırıyor (gemini-3.6-flash)...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("AI ile Yeniden Sınıflandır")
                        }
                    }

                    if (!aiStatusText.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = aiStatusText!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isAiLoading,
                onClick = {
                    val finalSubject = selectedSubject.trim().ifBlank { "Matematik" }
                    val finalTopic = customTopic.trim().ifBlank { "Genel" }
                    onSave(finalSubject, finalTopic)
                }
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isAiLoading
            ) {
                Text("İptal")
            }
        }
    )
}

