package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.core.badges.SharkBadgeCatalog
import com.example.sharkmentor.core.util.AvatarHelper
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.data.local.preferences.UserPreferences
import com.example.sharkmentor.service.sync.SyncWorker
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

const val QUESTION_REWARD_WINDOW_MS: Long = 105L * 60L * 1_000L // 105 minutes
private const val GOLD_ELAPSED_MS: Long = 15L * 60L * 1_000L     // 0..15 min  -> 100%
private const val SILVER_ELAPSED_MS: Long = 60L * 60L * 1_000L   // 15..60 min -> 70%

enum class ChatFilterTab(val label: String) {
    ALL("Tümü"),
    OPEN_QUESTIONS("🔥 Açık Sorular"),
    PENDING_APPROVAL("⏳ Onay Bekleyenler"),
    RESOLVED("✅ Çözülenler")
}

enum class LeagueChatMode {
    GROUP_CHAT,
    DM_INBOX,
    DM_THREAD
}

enum class QuestionRewardTier(
    val title: String,
    val emoji: String,
    val percent: Int,
    val points: Int,
    val bonusMinutes: Int
) {
    GOLD("Altın %100", "⚡", 100, 100, 15),
    SILVER("Gümüş %70", "🥈", 70, 70, 10),
    BRONZE("Bronz %40", "🥉", 40, 40, 6);

    fun summaryBadge(): String = "$emoji $title • +$points Puan & +$bonusMinutes dk"
}

@Immutable
data class QuestionCardUi(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val subject: String,
    val topic: String,
    val noteText: String,
    val imageBase64: String,
    val localImageUri: String,
    val status: String, // "OPEN", "PENDING_APPROVAL", "RESOLVED"
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val approvedSolutionId: String? = null,
    val solverName: String? = null,
    val aiAccessedByUids: List<String> = emptyList()
) {
    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (expiresAtMillis - nowMillis).coerceAtLeast(0L)

    fun elapsedMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (nowMillis - createdAtMillis).coerceAtLeast(0L)

    fun currentRewardTier(nowMillis: Long = System.currentTimeMillis()): QuestionRewardTier {
        val elapsed = elapsedMillis(nowMillis)
        return when {
            elapsed <= GOLD_ELAPSED_MS -> QuestionRewardTier.GOLD
            elapsed <= SILVER_ELAPSED_MS -> QuestionRewardTier.SILVER
            else -> QuestionRewardTier.BRONZE
        }
    }
}

@Immutable
data class SolutionReplyUi(
    val messageId: String,
    val replyToQuestionId: String,
    val senderId: String,
    val senderName: String,
    val solutionText: String,
    val solutionImageBase64: String,
    val approvalStatus: String, // "PENDING", "APPROVED", "REJECTED"
    val escrowPoints: Int,
    val escrowBonusMinutes: Int,
    val createdAtMillis: Long
)

@Immutable
data class LeagueChatMessageUi(
    val id: String,
    val senderId: String,
    val senderName: String,
    val senderAvatarBase64: String = "",
    val senderAvatarPresetId: String = "",
    val senderShowcaseBadges: List<String> = emptyList(),
    val senderBadgeProgress: Map<String, Int> = emptyMap(),
    val type: String, // "TEXT", "QUESTION", "SOLUTION"
    val text: String,
    val createdAtMillis: Long,
    val isMine: Boolean,
    val questionCard: QuestionCardUi? = null,
    val solutionReply: SolutionReplyUi? = null
)

@Immutable
data class DmThreadUi(
    val threadId: String,
    val peerUid: String,
    val peerName: String,
    val peerAvatarBase64: String = "",
    val peerAvatarPresetId: String = "",
    val requesterId: String,
    val requesterName: String,
    val recipientId: String,
    val status: String, // "PENDING", "ACCEPTED", "REJECTED"
    val requestNote: String,
    val lastMessageText: String,
    val updatedAtMillis: Long,
    val isIncomingRequest: Boolean
)

@Immutable
data class LeagueChatUiState(
    val mode: LeagueChatMode = LeagueChatMode.GROUP_CHAT,
    val currentUid: String = "",
    val currentName: String = "Öğrenci",
    val activeGroupCode: String = "",
    val activeGroupName: String = "",
    val activeFilter: ChatFilterTab = ChatFilterTab.ALL,
    val allMessages: List<LeagueChatMessageUi> = emptyList(),
    val dmThreads: List<DmThreadUi> = emptyList(),
    val dmStatusByPeerId: Map<String, String> = emptyMap(),
    val activeDmThread: DmThreadUi? = null,
    val savedQuestionIds: Set<String> = emptySet(),
    val blockedUserIds: Set<String> = emptySet(),
    val selectedMemberProfile: LeagueEntry? = null,
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

object ChatImageCache {
    private val bitmapCache = object : LruCache<String, ImageBitmap>(36) {}

    fun decodeBase64Bitmap(base64: String): ImageBitmap? {
        if (base64.isBlank()) return null
        val key = "${base64.length}_${base64.hashCode()}"
        bitmapCache.get(key)?.let { return it }
        return runCatching {
            val clean = base64.substringAfter("base64,", base64).trim()
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val imageBmp = bmp.asImageBitmap()
            bitmapCache.put(key, imageBmp)
            imageBmp
        }.getOrNull()
    }

    suspend fun compressPathOrUriToBase64(context: Context, pathOrUri: String): String =
        withContext(Dispatchers.IO) {
            if (pathOrUri.isBlank()) return@withContext ""
            runCatching {
                val appCtx = context.applicationContext
                val rawBytes = readBytes(appCtx, pathOrUri) ?: return@runCatching ""
                if (rawBytes.isEmpty()) return@runCatching ""

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching ""

                val maxDim = 920
                var sampleSize = 1
                while ((bounds.outWidth / (sampleSize * 2)) >= maxDim ||
                    (bounds.outHeight / (sampleSize * 2)) >= maxDim
                ) {
                    sampleSize *= 2
                }
                val decoded = BitmapFactory.decodeByteArray(
                    rawBytes,
                    0,
                    rawBytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                ) ?: return@runCatching ""

                val maxSide = maxOf(decoded.width, decoded.height)
                val scaled = if (maxSide > maxDim) {
                    val ratio = maxDim.toFloat() / maxSide.toFloat()
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * ratio).toInt().coerceAtLeast(1),
                        (decoded.height * ratio).toInt().coerceAtLeast(1),
                        true
                    ).also { if (it !== decoded) decoded.recycle() }
                } else {
                    decoded
                }

                val maxBytes = 115 * 1024 // Keep chat doc compact & fast
                val baos = ByteArrayOutputStream()
                var quality = 74
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                while (baos.size() > maxBytes && quality > 38) {
                    baos.reset()
                    quality -= 10
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                }
                val finalBytes = baos.toByteArray()
                scaled.recycle()
                Base64.encodeToString(finalBytes, Base64.NO_WRAP)
            }.getOrDefault("")
        }

    fun readBytes(context: Context, pathOrUri: String): ByteArray? {
        val f = File(pathOrUri)
        if (f.exists()) {
            return f.inputStream().use { it.readBytes() }
        }
        val uri = Uri.parse(pathOrUri)
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }?.inputStream()?.use { it.readBytes() }
            else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }
}

class LeagueChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val userPrefs = UserPreferences.getInstance(appContext)
    private val db get() = GoogleServicesHelper.getFirebaseFirestore(appContext)
    private val auth get() = GoogleServicesHelper.getFirebaseAuth(appContext)

    private val _uiState = MutableStateFlow(
        LeagueChatUiState(
            savedQuestionIds = loadPersistedSavedQuestionIds(),
            blockedUserIds = SharkBadgeCatalog.getBlockedUserIds(appContext)
        )
    )
    val uiState: StateFlow<LeagueChatUiState> = _uiState.asStateFlow()

    private var messagesListener: ListenerRegistration? = null
    private var dmThreadsListener: ListenerRegistration? = null
    private var rawMessages: List<LeagueChatMessageUi> = emptyList()
    private var rawDmThreads: List<DmThreadUi> = emptyList()

    fun selectFilter(tab: ChatFilterTab) {
        _uiState.value = _uiState.value.copy(activeFilter = tab)
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    private fun refreshBlockedUsers(uid: String) {
        val localBlocked = SharkBadgeCatalog.getBlockedUserIds(appContext)
        if (_uiState.value.blockedUserIds != localBlocked) {
            _uiState.value = _uiState.value.copy(blockedUserIds = localBlocked)
            applyBlockedFilters()
        }
        val database = db ?: return
        if (uid.isBlank() || uid == "local_student" || uid == "student_default") return
        viewModelScope.launch {
            runCatching {
                val doc = database.collection("students").document(uid).get().await()
                val remoteBlocked = (doc.get("blockedUserIds") as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                val merged = SharkBadgeCatalog.mergeBlockedUserIds(appContext, remoteBlocked)
                if (merged != _uiState.value.blockedUserIds) {
                    _uiState.value = _uiState.value.copy(blockedUserIds = merged)
                    applyBlockedFilters()
                }
            }
        }
    }

    private fun applyBlockedFilters() {
        val current = _uiState.value
        val blocked = current.blockedUserIds
        val filteredMessages = rawMessages.filter { it.isMine || !blocked.contains(it.senderId) }
        val filteredThreads = rawDmThreads.filter { !blocked.contains(it.peerUid) }
        val statusMap = rawDmThreads.associate { it.peerUid to it.status }
        val activeId = current.activeDmThread?.threadId
        val updatedActive = if (activeId != null) {
            rawDmThreads.firstOrNull { it.threadId == activeId } ?: current.activeDmThread
        } else {
            current.activeDmThread
        }
        _uiState.value = current.copy(
            allMessages = filteredMessages,
            dmThreads = filteredThreads,
            dmStatusByPeerId = statusMap,
            activeDmThread = updatedActive
        )
    }

    fun openGroupChat(groupCode: String, groupName: String, myDisplayNameHint: String = "") {
        val cleanCode = groupCode.trim().uppercase(Locale.ROOT)
        if (cleanCode.isBlank()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Geçerli bir çalışma grubu seçilmedi."
            )
            return
        }

        val uid = resolveCurrentUid()
        val myName = resolveDisplayName(myDisplayNameHint)
        rawMessages = emptyList()
        _uiState.value = _uiState.value.copy(
            mode = LeagueChatMode.GROUP_CHAT,
            currentUid = uid,
            currentName = myName,
            activeGroupCode = cleanCode,
            activeGroupName = groupName.ifBlank { "Grup $cleanCode" },
            activeFilter = ChatFilterTab.ALL,
            activeDmThread = null,
            allMessages = emptyList(),
            blockedUserIds = SharkBadgeCatalog.getBlockedUserIds(appContext),
            isLoading = true,
            errorMessage = null
        )

        refreshBlockedUsers(uid)
        observeDmThreads(uid)
        val database = db
        if (database == null || uid == "local_student" || uid == "student_default") {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Sohbete bağlanmak için oturum ve internet bağlantısı gerekir."
            )
            return
        }

        subscribeToMessages(
            collection = database.collection("study_groups").document(cleanCode).collection("messages"),
            currentUid = uid
        )
    }

    fun openDmInbox(myDisplayNameHint: String = "") {
        val uid = resolveCurrentUid()
        val myName = resolveDisplayName(myDisplayNameHint)
        messagesListener?.remove()
        messagesListener = null
        rawMessages = emptyList()
        _uiState.value = _uiState.value.copy(
            mode = LeagueChatMode.DM_INBOX,
            currentUid = uid,
            currentName = myName,
            activeDmThread = null,
            allMessages = emptyList(),
            blockedUserIds = SharkBadgeCatalog.getBlockedUserIds(appContext),
            isLoading = false,
            errorMessage = null
        )
        refreshBlockedUsers(uid)
        observeDmThreads(uid)
    }

    fun openDmThread(thread: DmThreadUi, myDisplayNameHint: String = "") {
        val uid = resolveCurrentUid()
        val myName = resolveDisplayName(myDisplayNameHint)
        rawMessages = emptyList()
        _uiState.value = _uiState.value.copy(
            mode = LeagueChatMode.DM_THREAD,
            currentUid = uid,
            currentName = myName,
            activeFilter = ChatFilterTab.ALL,
            activeDmThread = thread,
            allMessages = emptyList(),
            blockedUserIds = SharkBadgeCatalog.getBlockedUserIds(appContext),
            isLoading = true,
            errorMessage = null
        )
        refreshBlockedUsers(uid)
        observeDmThreads(uid)

        val database = db ?: run {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Direkt sohbet için internet bağlantısı gerekir."
            )
            return
        }

        subscribeToMessages(
            collection = database.collection("dm_threads").document(thread.threadId).collection("messages"),
            currentUid = uid
        )
    }

    fun openOrStartDmWithPeer(
        peerUid: String,
        peerName: String,
        peerAvatarBase64: String = "",
        peerAvatarPresetId: String = "",
        myDisplayNameHint: String = ""
    ) {
        val uid = resolveCurrentUid()
        val cleanName = resolveDisplayName(myDisplayNameHint)
        val threadId = deterministicThreadId(uid, peerUid)
        val existing = _uiState.value.dmThreads.firstOrNull { it.threadId == threadId }
        if (existing != null) {
            openDmThread(existing, cleanName)
            return
        }

        val placeholder = DmThreadUi(
            threadId = threadId,
            peerUid = peerUid,
            peerName = peerName,
            peerAvatarBase64 = peerAvatarBase64,
            peerAvatarPresetId = peerAvatarPresetId,
            requesterId = uid,
            requesterName = cleanName,
            recipientId = peerUid,
            status = "NONE",
            requestNote = "",
            lastMessageText = "",
            updatedAtMillis = System.currentTimeMillis(),
            isIncomingRequest = false
        )
        openDmThread(placeholder, cleanName)

        val database = db ?: return
        viewModelScope.launch {
            runCatching {
                val doc = database.collection("dm_threads").document(threadId).get().await()
                if (doc.exists()) {
                    val parsed = parseDmThreadDoc(doc, uid)
                    if (parsed != null) {
                        _uiState.value = _uiState.value.copy(activeDmThread = parsed)
                    }
                }
            }
        }
    }

    fun sendDmRequestForActiveThread(note: String) {
        val thread = _uiState.value.activeDmThread ?: return
        val uid = resolveCurrentUid()
        val myName = _uiState.value.currentName.ifBlank { resolveDisplayName("") }
        val database = db ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            runCatching {
                val now = System.currentTimeMillis()
                val myAvatarBase64 = AvatarHelper.getSavedAvatarBase64(appContext)
                val myAvatarPreset = AvatarHelper.getSavedAvatarPresetId(appContext)
                val cleanNote = note.trim().ifBlank { "Soru & çalışma hakkında mesaj isteği" }
                val payload = hashMapOf<String, Any>(
                    "threadId" to thread.threadId,
                    "participantIds" to listOf(uid, thread.peerUid).sorted(),
                    "participantNames" to mapOf(uid to myName, thread.peerUid to thread.peerName),
                    "participantAvatarsBase64" to mapOf(uid to myAvatarBase64, thread.peerUid to thread.peerAvatarBase64),
                    "participantAvatarPresets" to mapOf(uid to myAvatarPreset, thread.peerUid to thread.peerAvatarPresetId),
                    "requesterId" to uid,
                    "requesterName" to myName,
                    "recipientId" to thread.peerUid,
                    "recipientName" to thread.peerName,
                    "status" to "PENDING",
                    "requestNote" to cleanNote,
                    "lastMessageText" to cleanNote,
                    "updatedAtMillis" to now,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                database.collection("dm_threads").document(thread.threadId)
                    .set(payload, SetOptions.merge())
                    .await()
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    activeDmThread = thread.copy(status = "PENDING", requestNote = cleanNote),
                    statusMessage = "📩 Mesaj isteği gönderildi! Onaylandığında sohbet açılacak."
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    statusMessage = "Mesaj isteği gönderilemedi: ${e.localizedMessage ?: "Bağlantı hatası"}"
                )
            }
        }
    }

    fun respondToDmRequest(thread: DmThreadUi, accept: Boolean) {
        val database = db ?: return
        val nextStatus = if (accept) "ACCEPTED" else "REJECTED"
        viewModelScope.launch {
            runCatching {
                database.collection("dm_threads").document(thread.threadId)
                    .set(
                        mapOf(
                            "status" to nextStatus,
                            "updatedAtMillis" to System.currentTimeMillis(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                    .await()
                val updated = thread.copy(status = nextStatus)
                _uiState.value = _uiState.value.copy(
                    activeDmThread = if (_uiState.value.activeDmThread?.threadId == thread.threadId) updated else _uiState.value.activeDmThread,
                    statusMessage = if (accept) {
                        "💬 Mesaj isteği kabul edildi! Artık mesajlaşabilirsiniz."
                    } else {
                        "Mesaj isteği reddedildi."
                    }
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "İstek yanıtlanamadı, lütfen tekrar deneyin."
                )
            }
        }
    }

    fun sendTextMessage(rawText: String) {
        val text = SyncWorker.sanitizeTurkishText(rawText.trim())
        if (text.isBlank()) return
        val state = _uiState.value
        val database = db ?: return
        val uid = resolveCurrentUid()
        if (uid == "local_student" || uid == "student_default") return

        if (state.mode == LeagueChatMode.DM_THREAD && state.activeDmThread?.status != "ACCEPTED") {
            _uiState.value = state.copy(
                statusMessage = "Karşı taraf mesaj isteğini onaylamadan mesaj gönderemezsin."
            )
            return
        }

        val targetCollection = activeMessagesCollection() ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            runCatching {
                val now = System.currentTimeMillis()
                val msgDoc = targetCollection.document()
                val payload = buildBaseSenderPayload(uid, state.currentName, now).apply {
                    put("id", msgDoc.id)
                    put("groupId", state.activeGroupCode)
                    put("type", "TEXT")
                    put("text", text)
                }
                msgDoc.set(payload).await()

                if (state.mode == LeagueChatMode.DM_THREAD && state.activeDmThread != null) {
                    database.collection("dm_threads").document(state.activeDmThread.threadId)
                        .set(
                            mapOf(
                                "lastMessageText" to text,
                                "updatedAtMillis" to now,
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                }
                _uiState.value = _uiState.value.copy(isSending = false)
            }.onFailure { e ->
                Log.w("LeagueChatVM", "sendTextMessage error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    statusMessage = "Mesaj gönderilemedi."
                )
            }
        }
    }

    fun shareErrorQuestion(question: ErrorQuestionEntity, optionalNote: String = "") {
        val state = _uiState.value
        val database = db ?: return
        val uid = resolveCurrentUid()
        if (uid == "local_student" || uid == "student_default") return

        if (state.mode == LeagueChatMode.DM_THREAD && state.activeDmThread?.status != "ACCEPTED") {
            _uiState.value = state.copy(
                statusMessage = "Karşı taraf mesaj isteğini onaylamadan soru paylaşamazsın."
            )
            return
        }

        val targetCollection = activeMessagesCollection() ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            runCatching {
                val now = System.currentTimeMillis()
                val imageBase64 = ChatImageCache.compressPathOrUriToBase64(appContext, question.questionImageUri)
                val cleanSubject = SyncWorker.sanitizeTurkishText(question.subject.ifBlank { "Ders" })
                val cleanTopic = SyncWorker.sanitizeTurkishText(
                    question.aiTopic?.takeIf { it.isNotBlank() } ?: question.topic.ifBlank { "Genel" }
                )
                val cleanNote = SyncWorker.sanitizeTurkishText(
                    optionalNote.trim().ifBlank { "$cleanSubject • $cleanTopic sorusunu paylaştı" }
                )

                val msgDoc = targetCollection.document()
                val payload = buildBaseSenderPayload(uid, state.currentName, now).apply {
                    put("id", msgDoc.id)
                    put("groupId", state.activeGroupCode)
                    put("type", "QUESTION")
                    put("text", cleanNote)
                    put("questionSubject", cleanSubject)
                    put("questionTopic", cleanTopic)
                    put("questionImageBase64", imageBase64)
                    put("questionLocalUri", question.questionImageUri)
                    put("questionStatus", "OPEN")
                    put("expiresAtMillis", now + QUESTION_REWARD_WINDOW_MS)
                }
                msgDoc.set(payload).await()

                if (state.mode == LeagueChatMode.DM_THREAD && state.activeDmThread != null) {
                    database.collection("dm_threads").document(state.activeDmThread.threadId)
                        .set(
                            mapOf(
                                "lastMessageText" to "📸 Soru Paylaşımı: $cleanSubject ($cleanTopic)",
                                "updatedAtMillis" to now,
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                }

                SharkBadgeCatalog.recordQuestionSharedLocally(appContext, now)
                SharkBadgeCatalog.syncShowcaseAndBadgesToCloud(appContext, uid)

                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    statusMessage = "🔥 Soru sohbete eklendi! 105 dakikalık ödül sayacı başladı."
                )
            }.onFailure { e ->
                Log.w("LeagueChatVM", "shareErrorQuestion error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    statusMessage = "Soru paylaşılamadı: ${e.localizedMessage ?: "Bağlantı hatası"}"
                )
            }
        }
    }

    fun submitSolution(
        question: QuestionCardUi,
        solutionText: String,
        solutionImageUri: Uri? = null
    ) {
        val cleanText = SyncWorker.sanitizeTurkishText(solutionText.trim())
        if (cleanText.isBlank() && solutionImageUri == null) return

        val state = _uiState.value
        val database = db ?: return
        val uid = resolveCurrentUid()
        if (uid == "local_student" || uid == "student_default") return
        val targetCollection = activeMessagesCollection() ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            runCatching {
                val now = System.currentTimeMillis()
                val tier = question.currentRewardTier(now)
                val imageBase64 = if (solutionImageUri != null) {
                    ChatImageCache.compressPathOrUriToBase64(appContext, solutionImageUri.toString())
                } else {
                    ""
                }

                val msgDoc = targetCollection.document()
                val payload = buildBaseSenderPayload(uid, state.currentName, now).apply {
                    put("id", msgDoc.id)
                    put("groupId", state.activeGroupCode)
                    put("type", "SOLUTION")
                    put("text", cleanText.ifBlank { "Çözüm görseli eklendi." })
                    put("replyToQuestionId", question.messageId)
                    put("questionCreatedAtMillis", question.createdAtMillis)
                    put("solutionText", cleanText)
                    put("solutionImageBase64", imageBase64)
                    put("approvalStatus", "PENDING")
                    put("escrowPoints", tier.points)
                    put("escrowBonusMinutes", tier.bonusMinutes)
                }
                msgDoc.set(payload).await()

                if (question.status != "RESOLVED") {
                    runCatching {
                        targetCollection.document(question.messageId)
                            .set(mapOf("questionStatus" to "PENDING_APPROVAL"), SetOptions.merge())
                            .await()
                    }
                }

                if (state.mode == LeagueChatMode.DM_THREAD && state.activeDmThread != null) {
                    database.collection("dm_threads").document(state.activeDmThread.threadId)
                        .set(
                            mapOf(
                                "lastMessageText" to "✏️ Çözüm gönderildi (+${tier.points} P bekliyor)",
                                "updatedAtMillis" to now,
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                }

                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    statusMessage = "⏳ Çözüm gönderildi! +${tier.points} Puan soru sahibinin onayını bekliyor."
                )
            }.onFailure { e ->
                Log.w("LeagueChatVM", "submitSolution error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    statusMessage = "Çözüm gönderilemedi: ${e.localizedMessage ?: "Bağlantı hatası"}"
                )
            }
        }
    }

    fun approveSolution(question: QuestionCardUi, solution: SolutionReplyUi) {
        val database = db ?: return
        val uid = resolveCurrentUid()
        if (question.senderId != uid) return
        val targetCollection = activeMessagesCollection() ?: return

        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val solveElapsedMs = (solution.createdAtMillis - question.createdAtMillis).coerceAtLeast(0L)
                val bonusSecs = solution.escrowBonusMinutes.coerceAtLeast(0) * 60L
                val weekKey = SharkBadgeCatalog.currentIsoWeekKey(now)

                targetCollection.document(solution.messageId)
                    .set(
                        mapOf(
                            "approvalStatus" to "APPROVED",
                            "reviewedAtMillis" to now
                        ),
                        SetOptions.merge()
                    )
                    .await()

                targetCollection.document(question.messageId)
                    .set(
                        mapOf(
                            "questionStatus" to "RESOLVED",
                            "approvedSolutionId" to solution.messageId,
                            "solverId" to solution.senderId,
                            "solverName" to solution.senderName
                        ),
                        SetOptions.merge()
                    )
                    .await()

                // Credit solver mentor points, capped weekly bonus seconds & 12 tiered badges
                val solverId = solution.senderId
                if (solverId.isNotBlank()) {
                    if (solverId == uid) {
                        SharkBadgeCatalog.addMentorPoints(appContext, solution.escrowPoints)
                        if (bonusSecs > 0L) {
                            SharkBadgeCatalog.addWeeklyBonusSeconds(appContext, bonusSecs, weekKey)
                        }
                        SharkBadgeCatalog.recordApprovedSolutionLocally(
                            context = appContext,
                            subject = question.subject,
                            solveElapsedMs = solveElapsedMs,
                            createdAtMillis = solution.createdAtMillis
                        )
                        SharkBadgeCatalog.syncShowcaseAndBadgesToCloud(appContext, uid)
                    } else {
                        val earnedFirstTierBadges = mutableListOf(SharkBadgeCatalog.BADGE_ILK_COZUM)
                        val progressIncrements = hashMapOf<String, Any>(
                            SharkBadgeCatalog.BADGE_ILK_COZUM to FieldValue.increment(1L),
                            SharkBadgeCatalog.BADGE_SORU_AVCISI to FieldValue.increment(1L)
                        )
                        if (solveElapsedMs in 0L..(15L * 60L * 1_000L)) {
                            earnedFirstTierBadges.add(SharkBadgeCatalog.BADGE_HIZLI_KOPEKBALIGI)
                            progressIncrements[SharkBadgeCatalog.BADGE_HIZLI_KOPEKBALIGI] = FieldValue.increment(1L)
                        }
                        if (solveElapsedMs in (60L * 60L * 1_000L)..(105L * 60L * 1_000L)) {
                            earnedFirstTierBadges.add(SharkBadgeCatalog.BADGE_CAN_SIMIDI)
                            progressIncrements[SharkBadgeCatalog.BADGE_CAN_SIMIDI] = FieldValue.increment(1L)
                        }
                        SharkBadgeCatalog.subjectBadgeIdFor(question.subject)?.let { subjBadgeId ->
                            earnedFirstTierBadges.add(subjBadgeId)
                            progressIncrements[subjBadgeId] = FieldValue.increment(1L)
                        }

                        runCatching {
                            val studentRewardPayload = hashMapOf<String, Any>(
                                "mentorPoints" to FieldValue.increment(solution.escrowPoints.toLong()),
                                "approvedSolutionsCount" to FieldValue.increment(1L),
                                "unlockedBadges" to FieldValue.arrayUnion(*earnedFirstTierBadges.toTypedArray()),
                                "badgeProgress" to progressIncrements
                            )
                            if (bonusSecs > 0L) {
                                studentRewardPayload["weeklyBonusByWeek"] = mapOf(
                                    weekKey to FieldValue.increment(bonusSecs)
                                )
                            }
                            database.collection("students").document(solverId)
                                .set(studentRewardPayload, SetOptions.merge())
                                .await()
                        }
                        runCatching {
                            val leagueRewardPayload = hashMapOf<String, Any>(
                                "mentorPoints" to FieldValue.increment(solution.escrowPoints.toLong()),
                                "badgeProgress" to progressIncrements
                            )
                            if (bonusSecs > 0L) {
                                leagueRewardPayload["weeklyBonusByWeek"] = mapOf(
                                    weekKey to FieldValue.increment(bonusSecs)
                                )
                            }
                            database.collection("institutions").document(GLOBAL_LEAGUE_BUCKET)
                                .collection("weeklyLeague").document(solverId)
                                .set(leagueRewardPayload, SetOptions.merge())
                                .await()
                        }
                    }
                }

                _uiState.value = _uiState.value.copy(
                    statusMessage = "✅ Çözüm onaylandı! ${solution.senderName} +${solution.escrowPoints} Puan & +${solution.escrowBonusMinutes} dk kazandı."
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Çözüm onaylanamadı, bağlantıyı kontrol edin."
                )
            }
        }
    }

    fun rejectSolution(question: QuestionCardUi, solution: SolutionReplyUi) {
        val state = _uiState.value
        val uid = resolveCurrentUid()
        if (question.senderId != uid) return
        val targetCollection = activeMessagesCollection() ?: return

        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                targetCollection.document(solution.messageId)
                    .set(
                        mapOf(
                            "approvalStatus" to "REJECTED",
                            "reviewedAtMillis" to now
                        ),
                        SetOptions.merge()
                    )
                    .await()

                val hasOtherPending = state.allMessages.any { msg ->
                    val reply = msg.solutionReply
                    reply != null &&
                        reply.replyToQuestionId == question.messageId &&
                        reply.messageId != solution.messageId &&
                        reply.approvalStatus == "PENDING"
                }

                if (!hasOtherPending && question.status != "RESOLVED") {
                    targetCollection.document(question.messageId)
                        .set(mapOf("questionStatus" to "OPEN"), SetOptions.merge())
                        .await()
                }

                _uiState.value = _uiState.value.copy(
                    statusMessage = "Çözüm reddedildi; soru diğer üyeler için açık kalmaya devam ediyor."
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "İşlem tamamlanamadı, bağlantıyı kontrol edin."
                )
            }
        }
    }

    fun markQuestionAiLocked(questionId: String) {
        val cleanId = questionId.trim()
        if (cleanId.isBlank()) return
        val uid = resolveCurrentUid()
        if (uid == "local_student" || uid == "student_default") return
        val targetCollection = activeMessagesCollection() ?: return

        viewModelScope.launch {
            runCatching {
                targetCollection.document(cleanId)
                    .set(
                        mapOf("aiAccessedByUids" to FieldValue.arrayUnion(uid)),
                        SetOptions.merge()
                    )
                    .await()
            }.onFailure { e ->
                Log.w("LeagueChatVM", "markQuestionAiLocked error: ${e.message}")
            }
        }
    }

    fun saveQuestionToMyErrorBank(question: QuestionCardUi) {
        if (_uiState.value.savedQuestionIds.contains(question.messageId)) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Bu soru zaten Hata Bankana kaydedildi."
            )
            return
        }

        viewModelScope.launch {
            val savedOk = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val dir = File(appContext.filesDir, "error_bank_saved").apply { mkdirs() }
                    val safeMsgId = question.messageId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "q" }
                    val questionFile = File(dir, "q_${safeMsgId}_${now}.jpg")

                    val wroteQuestionImg = writeBase64OrUriToJpegFile(
                        base64 = question.imageBase64,
                        fallbackUri = question.localImageUri,
                        destFile = questionFile
                    )
                    if (!wroteQuestionImg) {
                        renderQuestionTextFallbackFile(questionFile, question)
                    }

                    val approvedSol = rawMessages
                        .mapNotNull { it.solutionReply }
                        .firstOrNull { reply ->
                            reply.replyToQuestionId == question.messageId &&
                                (reply.approvalStatus == "APPROVED" || reply.messageId == question.approvedSolutionId) &&
                                reply.solutionImageBase64.isNotBlank()
                        }
                    var solutionPath: String? = null
                    if (approvedSol != null) {
                        val solFile = File(dir, "s_${safeMsgId}_${now}.jpg")
                        if (writeBase64OrUriToJpegFile(approvedSol.solutionImageBase64, "", solFile)) {
                            solutionPath = solFile.absolutePath
                        }
                    }

                    val entity = ErrorQuestionEntity(
                        subject = question.subject.ifBlank { "Ders" },
                        questionImageUri = questionFile.absolutePath,
                        solutionImageUri = solutionPath,
                        topic = question.topic.ifBlank { "Genel" },
                        aiTopic = question.topic.takeIf { it.isNotBlank() && !it.equals("Genel", ignoreCase = true) },
                        dueDateMillis = now,
                        createdAtMillis = now,
                        institutionId = userPrefs.institutionId,
                        isSynced = false
                    )
                    SharkMentorDatabase.getDatabase(appContext).errorQuestionDao().insertQuestion(entity)
                    true
                }.getOrElse { e ->
                    Log.w("LeagueChatVM", "saveQuestionToMyErrorBank error: ${e.message}")
                    false
                }
            }

            if (savedOk) {
                val updatedSaved = persistSavedQuestionId(question.messageId)
                _uiState.value = _uiState.value.copy(
                    savedQuestionIds = updatedSaved,
                    statusMessage = "📥 Soru Hata Bankana kaydedildi!"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Soru Hata Bankasına kaydedilemedi."
                )
            }
        }
    }

    fun openMemberProfile(
        studentId: String,
        fallbackName: String,
        fallbackAvatarBase64: String = "",
        fallbackAvatarPresetId: String = "",
        fallbackBadges: List<String> = emptyList()
    ) {
        val cleanId = studentId.trim()
        if (cleanId.isBlank()) return
        val uid = resolveCurrentUid()
        val isMe = (cleanId == uid)
        val cleanName = SyncWorker.sanitizeTurkishText(fallbackName.ifBlank { "Öğrenci" })
        val optimistic = LeagueEntry(
            studentId = cleanId,
            displayName = if (isMe) "Sen ($cleanName)" else cleanName,
            rawName = cleanName,
            isCurrentUser = isMe,
            avatarBase64 = fallbackAvatarBase64,
            avatarPresetId = fallbackAvatarPresetId,
            showcaseBadges = fallbackBadges.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES),
            badgeProgress = if (isMe) SharkBadgeCatalog.getBadgeProgressCounts(appContext) else emptyMap()
        )
        _uiState.value = _uiState.value.copy(selectedMemberProfile = optimistic)

        val database = db ?: return
        if (cleanId == "local_student" || cleanId == "student_default") return
        viewModelScope.launch {
            runCatching {
                val weekKey = SharkBadgeCatalog.currentIsoWeekKey()
                val studentSnap = database.collection("students").document(cleanId).get().await()
                val leagueSnap = runCatching {
                    database.collection("institutions").document(GLOBAL_LEAGUE_BUCKET)
                        .collection("weeklyLeague").document(cleanId).get().await()
                }.getOrNull()

                val resolvedName = SyncWorker.sanitizeTurkishText(
                    studentSnap.getString("name")?.takeIf { it.isNotBlank() }
                        ?: studentSnap.getString("displayName")?.takeIf { it.isNotBlank() }
                        ?: leagueSnap?.getString("displayName")?.takeIf { it.isNotBlank() }
                        ?: cleanName
                )
                val weeklySecs = maxOf(
                    studentSnap.getLong("weeklyFocusSeconds") ?: 0L,
                    leagueSnap?.getLong("weeklyFocusSeconds") ?: 0L
                )
                val bonusSecs = maxOf(
                    SharkBadgeCatalog.parseWeeklyBonusForWeek(studentSnap.get("weeklyBonusByWeek"), weekKey),
                    SharkBadgeCatalog.parseWeeklyBonusForWeek(leagueSnap?.get("weeklyBonusByWeek"), weekKey),
                    if (studentSnap.getString("weeklyBonusWeekKey") == weekKey) (studentSnap.getLong("weeklyBonusSeconds") ?: 0L) else 0L,
                    if (leagueSnap?.getString("weeklyBonusWeekKey") == weekKey) (leagueSnap.getLong("weeklyBonusSeconds") ?: 0L) else 0L
                ).coerceAtLeast(0L)
                val effectiveSecs = weeklySecs.coerceAtLeast(0L) + minOf(bonusSecs, MAX_WEEKLY_BONUS_SECONDS)
                val streak = maxOf(
                    studentSnap.getLong("streakDays")?.toInt() ?: 0,
                    leagueSnap?.getLong("streakDays")?.toInt() ?: 0
                )
                val points = maxOf(
                    studentSnap.getLong("mentorPoints")?.toInt() ?: 0,
                    leagueSnap?.getLong("mentorPoints")?.toInt() ?: 0
                )
                val avatarB64 = studentSnap.getString("avatarBase64")?.takeIf { it.isNotBlank() }
                    ?: leagueSnap?.getString("avatarBase64")?.takeIf { it.isNotBlank() }
                    ?: fallbackAvatarBase64
                val avatarPreset = if (avatarB64.isNotBlank()) "" else {
                    studentSnap.getString("avatarPresetId")?.takeIf { it.isNotBlank() }
                        ?: leagueSnap?.getString("avatarPresetId")?.takeIf { it.isNotBlank() }
                        ?: fallbackAvatarPresetId
                }
                val remoteBadges = (studentSnap.get("showcaseBadges") as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
                    .orEmpty()
                    .ifEmpty {
                        (leagueSnap?.get("showcaseBadges") as? List<*>)
                            ?.mapNotNull { it?.toString()?.trim() }
                            ?.filter { it.isNotBlank() }
                            ?.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
                            .orEmpty()
                    }
                    .ifEmpty { fallbackBadges.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES) }
                val badgeProgress = SharkBadgeCatalog.parseRemoteProgressMap(studentSnap.get("badgeProgress"))
                    .ifEmpty { SharkBadgeCatalog.parseRemoteProgressMap(leagueSnap?.get("badgeProgress")) }
                    .ifEmpty { if (isMe) SharkBadgeCatalog.getBadgeProgressCounts(appContext) else emptyMap() }

                val enriched = LeagueEntry(
                    studentId = cleanId,
                    displayName = if (isMe) "Sen ($resolvedName)" else resolvedName,
                    rawName = resolvedName,
                    friendCode = studentSnap.getString("friendCode") ?: leagueSnap?.getString("friendCode").orEmpty(),
                    weeklyFocusSeconds = weeklySecs,
                    weeklyBonusSeconds = bonusSecs,
                    dailyAverageMinutes = ((weeklySecs / 60L) / 7L).toInt(),
                    streakDays = streak,
                    tier = effectiveSecs.toTier(),
                    isCurrentUser = isMe,
                    isStudyingNow = studentSnap.getBoolean("isStudying") ?: false,
                    activeSubject = studentSnap.getString("activeSubject"),
                    avatarBase64 = avatarB64,
                    avatarPresetId = avatarPreset,
                    mentorPoints = points,
                    showcaseBadges = remoteBadges,
                    badgeProgress = badgeProgress
                )
                if (_uiState.value.selectedMemberProfile?.studentId == cleanId) {
                    _uiState.value = _uiState.value.copy(selectedMemberProfile = enriched)
                }
            }
        }
    }

    fun dismissMemberProfile() {
        _uiState.value = _uiState.value.copy(selectedMemberProfile = null)
    }

    fun blockUser(target: LeagueEntry, block: Boolean) {
        if (target.isCurrentUser || target.studentId.isBlank()) return
        val uid = resolveCurrentUid()
        viewModelScope.launch {
            val updated = SharkBadgeCatalog.setUserBlocked(
                context = appContext,
                myUid = uid,
                targetUid = target.studentId,
                blocked = block
            )
            val targetName = target.rawName.ifBlank { target.displayName }
            _uiState.value = _uiState.value.copy(
                blockedUserIds = updated,
                statusMessage = if (block) {
                    "🚫 $targetName engellendi. Mesajları ve soruları gizlendi."
                } else {
                    "$targetName engeli kaldırıldı."
                }
            )
            applyBlockedFilters()
        }
    }

    fun reportUser(target: LeagueEntry, reason: String, details: String) {
        if (target.isCurrentUser || target.studentId.isBlank()) return
        val uid = resolveCurrentUid()
        val myName = _uiState.value.currentName.ifBlank { resolveDisplayName("") }
        val targetName = target.rawName.ifBlank { target.displayName }
        viewModelScope.launch {
            val ok = SharkBadgeCatalog.submitUserReport(
                context = appContext,
                reporterUid = uid,
                reporterName = myName,
                targetUid = target.studentId,
                targetName = targetName,
                reason = reason,
                details = details,
                source = "league_chat"
            )
            _uiState.value = _uiState.value.copy(
                statusMessage = if (ok) {
                    "🚩 $targetName hakkında şikayet incelenmek üzere iletildi."
                } else {
                    "Şikayet gönderilemedi, internet bağlantını kontrol et."
                }
            )
        }
    }

    fun sendDmRequest(target: LeagueEntry, note: String) {
        if (target.isCurrentUser || target.studentId.isBlank()) return
        if (_uiState.value.blockedUserIds.contains(target.studentId)) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Engellediğin bir kullanıcıya mesaj isteği gönderemezsin."
            )
            return
        }
        val uid = resolveCurrentUid()
        val myName = _uiState.value.currentName.ifBlank { resolveDisplayName("") }
        val database = db ?: return
        if (uid == "local_student" || uid == "student_default") return

        viewModelScope.launch {
            runCatching {
                val threadId = deterministicThreadId(uid, target.studentId)
                val peerName = SyncWorker.sanitizeTurkishText(target.rawName.ifBlank { target.displayName })
                val cleanNote = SyncWorker.sanitizeTurkishText(note.trim()).ifBlank {
                    "Soru & çalışma hakkında direkt mesaj isteği"
                }
                val now = System.currentTimeMillis()
                val myAvatarBase64 = AvatarHelper.getSavedAvatarBase64(appContext)
                val myAvatarPreset = AvatarHelper.getSavedAvatarPresetId(appContext)
                val payload = hashMapOf<String, Any>(
                    "threadId" to threadId,
                    "participantIds" to listOf(uid, target.studentId).sorted(),
                    "participantNames" to mapOf(uid to myName, target.studentId to peerName),
                    "participantAvatarsBase64" to mapOf(uid to myAvatarBase64, target.studentId to target.avatarBase64),
                    "participantAvatarPresets" to mapOf(uid to myAvatarPreset, target.studentId to target.avatarPresetId),
                    "requesterId" to uid,
                    "requesterName" to myName,
                    "recipientId" to target.studentId,
                    "recipientName" to peerName,
                    "status" to "PENDING",
                    "requestNote" to cleanNote,
                    "lastMessageText" to cleanNote,
                    "updatedAtMillis" to now,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                database.collection("dm_threads").document(threadId)
                    .set(payload, SetOptions.merge())
                    .await()
                _uiState.value = _uiState.value.copy(
                    dmStatusByPeerId = _uiState.value.dmStatusByPeerId + (target.studentId to "PENDING"),
                    statusMessage = "📩 $peerName için mesaj isteği gönderildi!"
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Mesaj isteği gönderilemedi, lütfen tekrar dene."
                )
            }
        }
    }

    private fun writeBase64OrUriToJpegFile(
        base64: String,
        fallbackUri: String,
        destFile: File
    ): Boolean {
        if (base64.isNotBlank()) {
            val clean = base64.substringAfter("base64,", base64).trim()
            val decodedBytes = runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()
            if (decodedBytes != null && decodedBytes.isNotEmpty()) {
                val bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bmp != null) {
                    return runCatching {
                        FileOutputStream(destFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        bmp.recycle()
                        destFile.exists() && destFile.length() > 0L
                    }.getOrDefault(false)
                }
            }
        }
        if (fallbackUri.isNotBlank()) {
            val rawBytes = runCatching { ChatImageCache.readBytes(appContext, fallbackUri) }.getOrNull()
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (bmp != null) {
                    return runCatching {
                        FileOutputStream(destFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        bmp.recycle()
                        destFile.exists() && destFile.length() > 0L
                    }.getOrDefault(false)
                }
            }
        }
        return false
    }

    private fun renderQuestionTextFallbackFile(destFile: File, question: QuestionCardUi) {
        val width = 900
        val height = 560
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(AndroidColor.rgb(248, 250, 252))

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(15, 118, 110)
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(71, 85, 105)
            textSize = 26f
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(15, 23, 42)
            textSize = 30f
        }

        canvas.drawText("${question.subject} • ${question.topic}", 48f, 76f, headerPaint)
        canvas.drawText("Paylaşan: ${question.senderName}", 48f, 120f, subPaint)

        val bodyText = question.noteText.ifBlank { "${question.subject} (${question.topic}) sorusu" }
        val layout = StaticLayout.Builder.obtain(bodyText, 0, bodyText.length, bodyPaint, width - 96)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1.15f)
            .setMaxLines(10)
            .build()
        canvas.save()
        canvas.translate(48f, 168f)
        layout.draw(canvas)
        canvas.restore()

        FileOutputStream(destFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bmp.recycle()
    }

    private fun subscribeToMessages(collection: CollectionReference, currentUid: String) {
        messagesListener?.remove()
        messagesListener = collection
            .orderBy("createdAtMillis", Query.Direction.ASCENDING)
            .limitToLast(150)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w("LeagueChatVM", "messages listener warning: ${error.message}")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Mesajlar alınırken hata oluştu."
                    )
                    return@addSnapshotListener
                }
                rawMessages = snap?.documents?.mapNotNull { doc ->
                    parseMessageDoc(doc, currentUid)
                }.orEmpty()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = null
                )
                applyBlockedFilters()
            }
    }

    private fun observeDmThreads(uid: String) {
        val database = db ?: return
        if (uid == "local_student" || uid == "student_default") return
        if (dmThreadsListener != null) return

        dmThreadsListener = database.collection("dm_threads")
            .whereArrayContains("participantIds", uid)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w("LeagueChatVM", "dm_threads listener warning: ${error.message}")
                    return@addSnapshotListener
                }
                rawDmThreads = snap?.documents
                    ?.mapNotNull { doc -> parseDmThreadDoc(doc, uid) }
                    ?.sortedByDescending { it.updatedAtMillis }
                    .orEmpty()
                applyBlockedFilters()
            }
    }

    private fun parseMessageDoc(doc: DocumentSnapshot, currentUid: String): LeagueChatMessageUi? {
        val senderId = doc.getString("senderId").orEmpty()
        val rawSenderName = SyncWorker.sanitizeTurkishText(
            doc.getString("senderName").orEmpty().ifBlank { "Öğrenci" }
        )
        val type = doc.getString("type").orEmpty().ifBlank { "TEXT" }
        val text = SyncWorker.sanitizeTurkishText(doc.getString("text").orEmpty())
        val createdAtMs = doc.getLong("createdAtMillis")
            ?: doc.getTimestamp("createdAt")?.toDate()?.time
            ?: System.currentTimeMillis()
        val badges = (doc.get("senderBadges") as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            ?.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
            ?: emptyList()
        val badgeProgress = SharkBadgeCatalog.parseRemoteProgressMap(doc.get("senderBadgeProgress"))

        val questionCard = if (type == "QUESTION") {
            val subject = SyncWorker.sanitizeTurkishText(doc.getString("questionSubject").orEmpty().ifBlank { "Ders" })
            val topic = SyncWorker.sanitizeTurkishText(doc.getString("questionTopic").orEmpty().ifBlank { "Genel" })
            val expiresAt = doc.getLong("expiresAtMillis") ?: (createdAtMs + QUESTION_REWARD_WINDOW_MS)
            val aiLockedUids = (doc.get("aiAccessedByUids") as? List<*>)
                ?.mapNotNull { it?.toString()?.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            QuestionCardUi(
                messageId = doc.id,
                senderId = senderId,
                senderName = rawSenderName,
                subject = subject,
                topic = topic,
                noteText = text,
                imageBase64 = doc.getString("questionImageBase64").orEmpty(),
                localImageUri = doc.getString("questionLocalUri").orEmpty(),
                status = doc.getString("questionStatus").orEmpty().ifBlank { "OPEN" },
                createdAtMillis = createdAtMs,
                expiresAtMillis = expiresAt,
                approvedSolutionId = doc.getString("approvedSolutionId"),
                solverName = doc.getString("solverName")?.let { SyncWorker.sanitizeTurkishText(it) },
                aiAccessedByUids = aiLockedUids
            )
        } else {
            null
        }

        val solutionReply = if (type == "SOLUTION") {
            SolutionReplyUi(
                messageId = doc.id,
                replyToQuestionId = doc.getString("replyToQuestionId").orEmpty(),
                senderId = senderId,
                senderName = rawSenderName,
                solutionText = SyncWorker.sanitizeTurkishText(
                    doc.getString("solutionText").orEmpty().ifBlank { text }
                ),
                solutionImageBase64 = doc.getString("solutionImageBase64").orEmpty(),
                approvalStatus = doc.getString("approvalStatus").orEmpty().ifBlank { "PENDING" },
                escrowPoints = (doc.getLong("escrowPoints") ?: 40L).toInt(),
                escrowBonusMinutes = (doc.getLong("escrowBonusMinutes") ?: 6L).toInt(),
                createdAtMillis = createdAtMs
            )
        } else {
            null
        }

        return LeagueChatMessageUi(
            id = doc.id,
            senderId = senderId,
            senderName = rawSenderName,
            senderAvatarBase64 = doc.getString("senderAvatarBase64").orEmpty(),
            senderAvatarPresetId = doc.getString("senderAvatarPresetId").orEmpty(),
            senderShowcaseBadges = badges,
            senderBadgeProgress = badgeProgress,
            type = type,
            text = text,
            createdAtMillis = createdAtMs,
            isMine = (senderId == currentUid),
            questionCard = questionCard,
            solutionReply = solutionReply
        )
    }

    private fun parseDmThreadDoc(doc: DocumentSnapshot, myUid: String): DmThreadUi? {
        val participants = (doc.get("participantIds") as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?: emptyList()
        val requesterId = doc.getString("requesterId").orEmpty()
        val recipientId = doc.getString("recipientId").orEmpty()
        val peerUid = participants.firstOrNull { it != myUid }
            ?: if (requesterId == myUid) recipientId else requesterId
        if (peerUid.isBlank()) return null

        val nameMap = doc.get("participantNames") as? Map<*, *>
        val avatarMap = doc.get("participantAvatarsBase64") as? Map<*, *>
        val presetMap = doc.get("participantAvatarPresets") as? Map<*, *>

        val fallbackPeerName = if (requesterId == peerUid) {
            doc.getString("requesterName")
        } else {
            doc.getString("recipientName")
        }
        val peerName = SyncWorker.sanitizeTurkishText(
            nameMap?.get(peerUid)?.toString()?.takeIf { it.isNotBlank() }
                ?: fallbackPeerName
                ?: "Öğrenci"
        )
        val status = doc.getString("status").orEmpty().ifBlank { "PENDING" }

        return DmThreadUi(
            threadId = doc.id,
            peerUid = peerUid,
            peerName = peerName,
            peerAvatarBase64 = avatarMap?.get(peerUid)?.toString().orEmpty(),
            peerAvatarPresetId = presetMap?.get(peerUid)?.toString().orEmpty(),
            requesterId = requesterId,
            requesterName = SyncWorker.sanitizeTurkishText(doc.getString("requesterName").orEmpty()),
            recipientId = recipientId,
            status = status,
            requestNote = SyncWorker.sanitizeTurkishText(doc.getString("requestNote").orEmpty()),
            lastMessageText = SyncWorker.sanitizeTurkishText(doc.getString("lastMessageText").orEmpty()),
            updatedAtMillis = doc.getLong("updatedAtMillis")
                ?: doc.getTimestamp("updatedAt")?.toDate()?.time
                ?: 0L,
            isIncomingRequest = (status == "PENDING" && requesterId != myUid)
        )
    }

    private fun buildBaseSenderPayload(
        uid: String,
        myName: String,
        timestampMillis: Long
    ): HashMap<String, Any> {
        return hashMapOf(
            "senderId" to uid,
            "senderName" to myName.ifBlank { "Öğrenci" },
            "senderAvatarBase64" to AvatarHelper.getSavedAvatarBase64(appContext),
            "senderAvatarPresetId" to AvatarHelper.getSavedAvatarPresetId(appContext),
            "senderBadges" to SharkBadgeCatalog.getShowcaseBadgeIds(appContext),
            "senderBadgeProgress" to SharkBadgeCatalog.getBadgeProgressCounts(appContext),
            "createdAtMillis" to timestampMillis,
            "createdAt" to FieldValue.serverTimestamp()
        )
    }

    private fun activeMessagesCollection(): CollectionReference? {
        val database = db ?: return null
        val state = _uiState.value
        return when (state.mode) {
            LeagueChatMode.GROUP_CHAT -> {
                if (state.activeGroupCode.isBlank()) null
                else database.collection("study_groups").document(state.activeGroupCode).collection("messages")
            }
            LeagueChatMode.DM_THREAD -> {
                val threadId = state.activeDmThread?.threadId ?: return null
                database.collection("dm_threads").document(threadId).collection("messages")
            }
            LeagueChatMode.DM_INBOX -> null
        }
    }

    private fun resolveCurrentUid(): String =
        GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid ?: "local_student"

    private fun resolveDisplayName(hint: String): String {
        val cleanHint = SyncWorker.sanitizeTurkishText(hint.trim())
        if (cleanHint.isNotBlank() && !cleanHint.equals("Öğrenci", ignoreCase = true)) {
            return cleanHint
        }
        val pref = SyncWorker.sanitizeTurkishText(userPrefs.studentName.orEmpty().trim())
        if (pref.isNotBlank() && !pref.equals("Öğrenci", ignoreCase = true)) {
            return pref
        }
        val authUser = GoogleServicesHelper.getCurrentUser(auth, appContext)
        val authName = SyncWorker.sanitizeTurkishText(authUser?.displayName.orEmpty().trim())
        if (authName.isNotBlank()) return authName
        val emailPrefix = authUser?.email?.substringBefore("@")?.trim().orEmpty()
        if (emailPrefix.isNotBlank()) {
            return emailPrefix.replaceFirstChar { it.titlecase(Locale("tr")) }
        }
        return "Öğrenci"
    }

    private fun deterministicThreadId(uid1: String, uid2: String): String =
        listOf(uid1.trim(), uid2.trim()).sorted().joinToString("_")

    private fun loadPersistedSavedQuestionIds(): Set<String> =
        appContext.getSharedPreferences("shark_chat_saved_questions", Context.MODE_PRIVATE)
            .getStringSet("saved_msg_ids", emptySet())
            .orEmpty()

    private fun persistSavedQuestionId(messageId: String): Set<String> {
        val next = loadPersistedSavedQuestionIds() + messageId
        appContext.getSharedPreferences("shark_chat_saved_questions", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("saved_msg_ids", next)
            .apply()
        return next
    }

    override fun onCleared() {
        messagesListener?.remove()
        messagesListener = null
        dmThreadsListener?.remove()
        dmThreadsListener = null
        super.onCleared()
    }
}
