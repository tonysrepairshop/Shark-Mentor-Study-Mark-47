package com.example.sharkmentor.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.compose.runtime.Immutable
import com.example.sharkmentor.core.util.GoogleServicesHelper

data class ChatMessage(
    val id: String              = "",
    val senderId: String        = "",
    val senderRole: String      = "student",   // "student" | "coach"
    val text: String            = "",
    val timestampMillis: Long   = 0L,
    val isRead: Boolean         = false,
    val isEdited: Boolean       = false,
    val isDeleted: Boolean      = false
)

@Immutable
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSending: Boolean          = false,
    val error: String?              = null
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class ChatViewModel : ViewModel() {

    private val db   get() = GoogleServicesHelper.getFirebaseFirestore()
    private val auth get() = GoogleServicesHelper.getFirebaseAuth()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val studentId: String? get() = GoogleServicesHelper.getCurrentUser(auth)?.uid

    // ─── Start real-time listener ─────────────────────────────────────────────

    fun startListening() {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val uid = studentId ?: return
        messagesFlow(uid)
            .onEach { msgs ->
                _uiState.value = _uiState.value.copy(messages = msgs, error = null)
                markCoachMessagesRead(uid, msgs)
            }
            .catch { e ->
                Log.w("ChatViewModel", "Snapshot error: ${e.message}")
                _uiState.value = _uiState.value.copy(error = null)
            }
            .launchIn(viewModelScope)
    }

    // ─── Firestore callbackFlow ───────────────────────────────────────────────

    private fun messagesFlow(uid: String) = callbackFlow<List<ChatMessage>> {
        if (FirebaseAuth.getInstance().currentUser == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val database = db ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = database.collection("students").document(uid)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)

        val listener = runCatching {
            ref.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("ChatViewModel", "Firestore messages listener error: ${error.message}")
                    trySend(emptyList())
                    close()
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.map { doc ->
                    val ts = doc.getTimestamp("timestamp")
                    ChatMessage(
                        id              = doc.id,
                        senderId        = doc.getString("senderId")   ?: "",
                        senderRole      = doc.getString("senderRole") ?: "student",
                        text            = doc.getString("text")       ?: "",
                        timestampMillis = ts?.toDate()?.time          ?: 0L,
                        isRead          = doc.getBoolean("isRead")    ?: false,
                        isEdited        = doc.getBoolean("isEdited")  ?: false,
                        isDeleted       = doc.getBoolean("isDeleted") ?: false
                    )
                } ?: emptyList()
                trySend(list)
            }
        }.onFailure { e ->
            Log.w("ChatViewModel", "Failed to attach messages listener: ${e.message}")
            trySend(emptyList())
            close()
        }.getOrNull()
        awaitClose { listener?.remove() }
    }

    // ─── Send ─────────────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        val uid = studentId ?: return
        if (text.isBlank()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSending = true)
            try {
                val database = db ?: throw IllegalStateException("Firestore unavailable")
                val payload = hashMapOf<String, Any>(
                    "senderId"   to uid,
                    "senderRole" to "student",
                    "text"       to text.trim(),
                    "timestamp"  to FieldValue.serverTimestamp(),
                    "isRead"     to false,
                    "isEdited"   to false,
                    "isDeleted"  to false
                )
                database.collection("students").document(uid)
                    .collection("messages")
                    .add(payload)
                    .await()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Send error", e)
                _uiState.value = _uiState.value.copy(error = "Mesaj gönderilemedi.")
            } finally {
                _uiState.value = _uiState.value.copy(isSending = false)
            }
        }
    }

    // ─── Edit ─────────────────────────────────────────────────────────────────

    fun editMessage(messageId: String, newText: String) {
        val uid = studentId ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val database = db ?: throw IllegalStateException("Firestore unavailable")
                database.collection("students").document(uid)
                    .collection("messages").document(messageId)
                    .update(
                        mapOf(
                            "text"     to newText.trim(),
                            "isEdited" to true,
                            "editedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .await()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Edit error", e)
                _uiState.value = _uiState.value.copy(error = "Mesaj düzenlenemedi.")
            }
        }
    }

    // ─── Delete (soft) ────────────────────────────────────────────────────────
    // Marks the document as deleted without removing it; UI shows tombstone.

    fun deleteMessage(messageId: String) {
        val uid = studentId ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val database = db ?: throw IllegalStateException("Firestore unavailable")
                database.collection("students").document(uid)
                    .collection("messages").document(messageId)
                    .update(
                        mapOf(
                            "isDeleted" to true,
                            "text" to "",
                            "deletedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .await()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Delete error", e)
                _uiState.value = _uiState.value.copy(error = "Mesaj silinemedi.")
            }
        }
    }

    // ─── Mark coach messages as read ──────────────────────────────────────────

    private fun markCoachMessagesRead(uid: String, messages: List<ChatMessage>) {
        val unread = messages.filter { it.senderRole == "coach" && !it.isRead && !it.isDeleted && it.id.isNotBlank() }
        if (unread.isEmpty()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val database = db ?: return@launch
            val messagesCol = database.collection("students").document(uid).collection("messages")
            val readPayload = mapOf("isRead" to true)
            for (chunk in unread.chunked(450)) {
                try {
                    val batch = database.batch()
                    chunk.forEach { msg ->
                        batch.set(messagesCol.document(msg.id), readPayload, SetOptions.merge())
                    }
                    batch.commit().await()
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "markRead batch failed: ${e.message}")
                }
            }
        }
    }
}

