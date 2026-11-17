package com.example.sharkmentor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RedeemCodeDialog(
    onDismiss: () -> Unit,
    onSubmit: (studentName: String, branchCode: String) -> Unit
) {
    var studentName by remember { mutableStateOf("") }
    var branchCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kurum & Koç Bağlantısı", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Koçunuzun sizi tanıyabilmesi için bilgilerinizi eksiksiz girin:",
                    style = MaterialTheme.typography.bodySmall
                )
                
                OutlinedTextField(
                    value = studentName,
                    onValueChange = { studentName = it },
                    label = { Text("Adınız Soyadınız *") },
                    placeholder = { Text("Örn: Ahmet Yılmaz") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = branchCode,
                    onValueChange = { branchCode = it.uppercase() },
                    label = { Text("Kurum / Koç Kodu *") },
                    placeholder = { Text("Örn: SHK-102") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (studentName.isNotBlank() && branchCode.isNotBlank()) {
                        onSubmit(studentName, branchCode)
                    }
                },
                enabled = studentName.isNotBlank() && branchCode.isNotBlank()
            ) {
                Text("Bağlan ve Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Vazgeç") }
        }
    )
}
