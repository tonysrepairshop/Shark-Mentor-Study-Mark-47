package com.example.sharkmentor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConcreteTargetDialog(
    onDismiss: () -> Unit,
    onSave: (examType: String, targetTitle: String) -> Unit
) {
    var examType by remember { mutableStateOf("YKS") }
    var targetTitle by remember { mutableStateOf("") }
    
    val examTypes = listOf("YKS", "LGS", "KPSS", "DGS", "SAT", "AP", "IB", "IMAT", "ABITUR")
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Somut Hedef Belirle", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = examType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sınav Türü") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        examTypes.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    examType = selectionOption
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                OutlinedTextField(
                    value = targetTitle,
                    onValueChange = { targetTitle = it },
                    label = { Text("Hedef Üniversite / Bölüm / Derece") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(examType, targetTitle) },
                enabled = targetTitle.isNotBlank()
            ) {
                Text("Yol Haritasını AI ile Çıkar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}
