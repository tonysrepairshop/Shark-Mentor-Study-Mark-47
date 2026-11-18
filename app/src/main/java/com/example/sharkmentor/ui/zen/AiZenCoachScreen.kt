package com.example.sharkmentor.ui.zen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@Composable
fun AiZenCoachScreen(
    viewModel: AiZenCoachViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val remainingSeconds by viewModel.remainingCooldownSeconds.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1724))
    ) {
        // Header
        Surface(
            color = Color(0xFF112233),
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "AI Zen Koçu",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2EC4B6),
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Saatte 1 iç dökme & destek hakkı",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp
                    )
                }

                if (remainingSeconds > 0) {
                    val minutes = remainingSeconds / 60
                    val seconds = remainingSeconds % 60
                    Surface(
                        color = Color(0x22FF5A5F),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF5A5F))
                    ) {
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                            color = Color(0xFFFF5A5F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { msg ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Surface(
                        color = if (msg.isUser) Color(0xFF2EC4B6) else Color(0xFF1B2B3B),
                        shape = RoundedCornerShape(
                            topStart = 14.dp,
                            topEnd = 14.dp,
                            bottomStart = if (msg.isUser) 14.dp else 2.dp,
                            bottomEnd = if (msg.isUser) 2.dp else 14.dp
                        ),
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = msg.text,
                            color = if (msg.isUser) Color.Black else Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            if (isLoading) {
                item {
                    Text(
                        text = "Koç seni dinliyor...",
                        color = Color(0xFF2EC4B6),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        // Bottom Input Area
        Surface(
            color = Color(0xFF112233),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (remainingSeconds > 0) {
                    val m = remainingSeconds / 60
                    val s = remainingSeconds % 60
                    Text(
                        text = "Yeni seans için bekleme süresi: ${m} dk ${s} sn (Zihinsel dinlenme)",
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Neler hissediyorsun, zihnini ne yoruyor?...", color = Color.Gray, fontSize = 13.sp) },
                        enabled = remainingSeconds == 0L && !isLoading,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF2EC4B6),
                            unfocusedBorderColor = Color(0xFF334455),
                            disabledBorderColor = Color(0xFF223344)
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            viewModel.sendQuestion(inputText)
                            inputText = ""
                        },
                        enabled = remainingSeconds == 0L && inputText.isNotBlank() && !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2EC4B6),
                            disabledContainerColor = Color(0xFF223344)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (remainingSeconds > 0) "Kilitli" else "Paylaş",
                            color = if (remainingSeconds > 0) Color.Gray else Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
