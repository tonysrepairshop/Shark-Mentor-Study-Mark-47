package com.example.sharkmentor.ui.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.example.sharkmentor.ui.components.AiDisclaimerText
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity

@Composable
fun QuickQuestionPreviewDialog(
    question: ErrorQuestionEntity,
    onDismiss: () -> Unit,
    onSolveWithSharkAi: (ErrorQuestionEntity) -> Unit = {},
    onTwinWithSharkAi: (ErrorQuestionEntity) -> Unit = {}
) {
    var showSolution by remember { mutableStateOf(false) }

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
                        fontWeight = FontWeight.Bold
                    )
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

                    // Topic Text
                    Text(
                        text = "Konu: ${question.topic}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
