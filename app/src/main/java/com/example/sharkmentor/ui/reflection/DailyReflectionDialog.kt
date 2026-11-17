package com.example.sharkmentor.ui.reflection

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReflectionDialog(
    onDismiss: () -> Unit,
    onSubmit: (score: Int, note: String, monthlyNet: String?) -> Unit
) {
    var score by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var monthlyNet by remember { mutableStateOf("") }
    val maxChars = 400
    
    val calendar = Calendar.getInstance()
    val isLastDayOfMonth = calendar.get(Calendar.DAY_OF_MONTH) == calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    var isMonthlyNetExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Günün Bilişsel Değerlendirmesi",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "Bugünkü çalışma odak ve verimini nasıl değerlendiriyorsun?",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // 1-10 Score Selector
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items((1..10).toList()) { index ->
                    val isSelected = score == index
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { score = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = index.toString(),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary 
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= maxChars) note = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                label = { Text("Bugün nasıldı?") },
                placeholder = { Text("Zorlandığın konular, hislerin veya başarıların...") },
                supportingText = {
                    Text(
                        text = "${note.length} / $maxChars",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                },
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
            
            if (isLastDayOfMonth) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .clickable { isMonthlyNetExpanded = !isMonthlyNetExpanded },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Ay Sonu Deneme Netleri (İsteğe Bağlı)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (isMonthlyNetExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = monthlyNet,
                                onValueChange = { monthlyNet = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Net Özeti (Örn: TYT: 95 Net)") },
                                shape = RoundedCornerShape(8.dp)
                            )
                        } else {
                            Text("Aylık deneme netlerinizi girmek için dokunun", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠️ Bu puan sisteme 1 kez kaydedilir ve sonradan değiştirilemez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onSubmit(score, note, if (isLastDayOfMonth && monthlyNet.isNotBlank()) monthlyNet else null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = score > 0 && note.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Değerlendirmeyi Tamamla", fontWeight = FontWeight.Bold)
            }
        }
    }
}
