package com.example.sharkmentor.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PlanScreen(viewModel: StudyPlanViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val activePlan = uiState.plan
    val tasksByDay = uiState.tasksByDay

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF06B6D4))
        }
    } else if (activePlan == null || tasksByDay.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Koçunuz henüz bir çalışma planı atamadı.", color = Color.Gray)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = activePlan.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                if (activePlan.strategyNote.isNotBlank()) {
                    Text(
                        text = activePlan.strategyNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF06B6D4),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            tasksByDay.forEach { (dayName, tasks) ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B121C)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = dayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF06B6D4),
                                fontWeight = FontWeight.Bold
                            )

                            tasks.forEach { task ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF060A10), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = task.subject, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(text = task.time, color = Color(0xFF06B6D4), fontFamily = FontFamily.Monospace)
                                    }
                                    Text(text = task.topic, color = Color.LightGray, fontSize = 12.sp)
                                    Text(text = task.goal, color = Color(0xFF10B981), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
