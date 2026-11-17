package com.example.sharkmentor.ui.aichat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.example.sharkmentor.ui.viewmodel.SharkAiViewModel
import java.util.UUID

@Immutable
data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String = "user", // "user" | "ai" | "system"
    val text: String = "",
    val personaTag: String = "shark_coach",
    val timestampMillis: Long = System.currentTimeMillis(),
    val planSummaryCard: String? = null,
    val planFolderId: String? = null,
    val isPlanCard: Boolean = false,
    val attachmentUri: Uri? = null,
    val attachmentName: String? = null
)

@Immutable
data class AiChatHubUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val currentPersona: AiPersona = AiPersona.SHARK_COACH,
    val isLoading: Boolean = false,
    val isDopamineActive: Boolean = false,
    val isInstitutional: Boolean = false,
    val error: String? = null
)

class AiChatHubViewModel(application: Application) : SharkAiViewModel(application)
