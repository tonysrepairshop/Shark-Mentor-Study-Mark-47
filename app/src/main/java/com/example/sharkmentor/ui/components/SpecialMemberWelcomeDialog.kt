package com.example.sharkmentor.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import com.google.firebase.auth.FirebaseAuth

private const val SPECIAL_MEMBER_UID = "dcLyQsJBBaQvL5ItGPOkl8WPwuw1"
private const val SPECIAL_MEMBER_EMAIL = "emrwator@gmail.com"

@Composable
fun SpecialMemberWelcomeGate() {
    var dismissedInSession by rememberSaveable { mutableStateOf(false) }

    if (dismissedInSession) return

    val currentUser = FirebaseAuth.getInstance().currentUser
    val isSpecialMember = currentUser != null &&
        currentUser.uid == SPECIAL_MEMBER_UID &&
        currentUser.email.equals(SPECIAL_MEMBER_EMAIL, ignoreCase = true)

    if (!isSpecialMember) return

    AlertDialog(
        onDismissRequest = { dismissedInSession = true },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(
                text = "SharkMentor Company",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Text(
                text = "Desteğiniz için SharkMentor Company size teşekkür ediyor. Hoş geldiniz Emre Bey.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = { dismissedInSession = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = "Devam Et")
            }
        }
    )
}
