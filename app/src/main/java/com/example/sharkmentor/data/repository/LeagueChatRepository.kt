package com.example.sharkmentor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.data.model.ChatChannelType
import com.example.sharkmentor.data.model.DirectMessageThread
import com.example.sharkmentor.data.model.DmRequestStatus
import com.example.sharkmentor.data.model.LeagueChatMessage
import com.example.sharkmentor.data.model.MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER
import com.example.sharkmentor.data.model.MAX_SHOWCASE_BADGES
import com.example.sharkmentor.data.model.MAX_WEEKLY_QUESTION_BONUS_SECONDS
import com.example.sharkmentor.data.model.MentorStats
import com.example.sharkmentor.data.model.MessageType
import com.example.sharkmentor.data.model.QUESTION_REWARD_WINDOW_MILLIS
import com.example.sharkmentor.data.model.QUESTION_REWARD_WINDOW_MINUTES
import com.example.sharkmentor.data.model.QuestionAttachment
import com.example.sharkmentor.data.model.QuestionStatus
import com.example.sharkmentor.data.model.RewardTier
import com.example.sharkmentor.data.model.SolutionSubmission
import com.example.sharkmentor.data.model.SolutionVerifyStatus
import com.example.sharkmentor.data.model.calculateRewardTier
import com.example.sharkmentor.data.remote.CloudStorageManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Shark League Peer Learning & Question Chat:
 * - Study Group & Request-Approved Direct Message real-time streams (`limitToLast(40)`)
 * - Sharing Error Notebook questions to chat with 105-minute reward window
 * - Submitting annotated peer solutions with Escrow (`pendingMentorPoints`)
 * - Owner verification (`✓` approve / `✗` reject) with anti-abuse caps:
 *   - Weekly max +9000s (150 min) bonus focus seconds (`MAX_WEEKLY_QUESTION_BONUS_SECONDS`)
 *   - Daily max 2 rewarded solutions for the same question owner (`MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER`)
 * - Request-approved Direct Messages (`sendDmRequest`, `respondToDmRequest`, `observeDmThreads`)
 * - Showcase badge selection (`updateShowcaseBadges`, max 3 badges)
 */
@Singleton
class LeagueChatRepository @Inject constructor(
    private val cloudStorageManager: CloudStorageManager = CloudStorageManager()
) {
    companion object {
        private const val TAG = "LeagueChatRepository"
        const val CHAT_HISTORY_LIMIT = 40L
        private const val GLOBAL_LEAGUE_BUCKET = "GLOBAL_INDIVIDUAL"
    }

    private val firestore: FirebaseFirestore?
        get() = GoogleServicesHelper.getFirestore()

    private val auth: FirebaseAuth?
        get() = GoogleServicesHelper.getFirebaseAuth()

    private val currentUid: String?
        get() = GoogleServicesHelper.getCurrentUser(auth)?.uid

    private fun channelMessagesRef(db: FirebaseFirestore, channelId: String, isGroup: Boolean): CollectionReference {
        val parentCollection = if (isGroup) "study_groups" else "direct_messages"
        return db.collection(parentCollection).document(channelId).collection("messages")
    }

    private fun buildDeterministicDmThreadId(uidA: String, uidB: String): String {
        return if (uidA <= uidB) "${uidA}_${uidB}" else "${uidB}_${uidA}"
    }

    private fun currentDayKeyUtcPlus3(nowMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+03:00")
        }
        return sdf.format(Date(nowMillis))
    }

    // ── 1. Real-Time Group & DM Chat Listeners (limitToLast(40)) ─────────────

    fun observeMessages(channelId: String, isGroup: Boolean = true): Flow<List<LeagueChatMessage>> = callbackFlow {
        val db = firestore
        if (db == null || channelId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val registration: ListenerRegistration? = runCatching {
            channelMessagesRef(db, channelId, isGroup)
                .orderBy("createdAtMillis", Query.Direction.ASCENDING)
                .limitToLast(CHAT_HISTORY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "observeMessages($channelId, isGroup=$isGroup) error: ${error.message}")
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val messages = snapshot?.documents?.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        LeagueChatMessage.fromMap(doc.id, data)
                    }.orEmpty()
                    trySend(messages)
                }
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach observeMessages listener: ${e.message}")
            trySend(emptyList())
        }.getOrNull()

        awaitClose { registration?.remove() }
    }.catch { e ->
        Log.w(TAG, "observeMessages flow catch: ${e.message}")
        emit(emptyList())
    }

    fun observeGroupMessages(groupId: String): Flow<List<LeagueChatMessage>> =
        observeMessages(channelId = groupId, isGroup = true)

    fun observeDirectMessages(threadId: String): Flow<List<LeagueChatMessage>> =
        observeMessages(channelId = threadId, isGroup = false)

    // ── 2. Send Text Message ─────────────────────────────────────────────────

    suspend fun sendTextMessage(
        channelId: String,
        isGroup: Boolean,
        text: String,
        senderName: String? = null
    ): Result<LeagueChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val db = firestore ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")
            val uid = currentUid ?: throw IllegalStateException("Oturum açmış kullanıcı bulunamadı.")
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Mesaj metni boş olamaz." }
            require(channelId.isNotBlank()) { "Sohbet kanalı geçersiz." }

            if (!isGroup) {
                val threadSnap = db.collection("direct_messages").document(channelId).get().await()
                val statusStr = threadSnap.getString("status").orEmpty()
                if (threadSnap.exists() && statusStr == DmRequestStatus.REJECTED.name) {
                    throw IllegalStateException("Bu mesaj isteği reddedilmiş.")
                }
            }

            val profileSnap = runCatching { db.collection("students").document(uid).get().await() }.getOrNull()
            val resolvedName = senderName?.takeIf { it.isNotBlank() }
                ?: profileSnap?.getString("name")?.takeIf { it.isNotBlank() }
                ?: profileSnap?.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: "Öğrenci"
            val avatarPresetId = (profileSnap?.getLong("avatarPresetId") ?: 0L).toInt()
            val showcaseBadges = (profileSnap?.get("showcaseBadges") as? List<*>)
                ?.mapNotNull { it as? String }
                ?.take(MAX_SHOWCASE_BADGES)
                .orEmpty()

            val now = System.currentTimeMillis()
            val msgRef = channelMessagesRef(db, channelId, isGroup).document()
            val message = LeagueChatMessage(
                id = msgRef.id,
                channelId = channelId,
                channelType = if (isGroup) ChatChannelType.STUDY_GROUP else ChatChannelType.DIRECT_MESSAGE,
                senderUid = uid,
                senderName = resolvedName,
                senderAvatarPresetId = avatarPresetId,
                senderShowcaseBadges = showcaseBadges,
                messageType = MessageType.TEXT,
                text = trimmed,
                createdAtMillis = now
            )

            msgRef.set(message.toMap()).await()

            if (!isGroup) {
                runCatching {
                    db.collection("direct_messages").document(channelId).set(
                        mapOf(
                            "lastMessagePreview" to trimmed.take(120),
                            "lastMessageAtMillis" to now,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }

            message
        }
    }

    // ── 3. Share Error Question to Chat ──────────────────────────────────────

    suspend fun shareErrorQuestionToChat(
        context: Context,
        channelId: String,
        isGroup: Boolean,
        errorQuestion: ErrorQuestionEntity,
        noteText: String = "",
        senderName: String? = null
    ): Result<LeagueChatMessage> {
        val parsedUri = Uri.parse(errorQuestion.questionImageUri)
        return shareErrorQuestionToChat(
            context = context,
            channelId = channelId,
            isGroup = isGroup,
            questionImageUri = parsedUri,
            subject = errorQuestion.subject,
            topic = errorQuestion.aiTopic?.takeIf { it.isNotBlank() } ?: errorQuestion.topic,
            noteText = noteText,
            localErrorQuestionId = errorQuestion.id,
            senderName = senderName
        )
    }

    suspend fun shareErrorQuestionToChat(
        context: Context,
        channelId: String,
        isGroup: Boolean,
        questionImageUri: Uri,
        subject: String,
        topic: String,
        noteText: String = "",
        localErrorQuestionId: Long? = null,
        senderName: String? = null
    ): Result<LeagueChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val db = firestore ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")
            val uid = currentUid ?: throw IllegalStateException("Oturum açmış kullanıcı bulunamadı.")
            require(channelId.isNotBlank()) { "Sohbet kanalı geçersiz." }

            val now = System.currentTimeMillis()
            val questionId = "q_${now}_${UUID.randomUUID().toString().take(6)}"

            val uriStr = questionImageUri.toString()
            val uploadedUrl = if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                uriStr
            } else {
                cloudStorageManager.uploadLeagueQuestionImage(
                    context = context,
                    localUri = questionImageUri,
                    channelId = channelId,
                    questionId = questionId
                ) ?: throw IllegalStateException("Soru görseli buluta yüklenemedi.")
            }

            val profileSnap = runCatching { db.collection("students").document(uid).get().await() }.getOrNull()
            val resolvedName = senderName?.takeIf { it.isNotBlank() }
                ?: profileSnap?.getString("name")?.takeIf { it.isNotBlank() }
                ?: profileSnap?.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: "Öğrenci"
            val avatarPresetId = (profileSnap?.getLong("avatarPresetId") ?: 0L).toInt()
            val showcaseBadges = (profileSnap?.get("showcaseBadges") as? List<*>)
                ?.mapNotNull { it as? String }
                ?.take(MAX_SHOWCASE_BADGES)
                .orEmpty()

            val attachment = QuestionAttachment(
                questionId = questionId,
                localErrorQuestionId = localErrorQuestionId,
                ownerUid = uid,
                ownerName = resolvedName,
                subject = subject.ifBlank { "Genel" },
                topic = topic.ifBlank { "Genel" },
                questionImageUrl = uploadedUrl,
                noteText = noteText.trim(),
                sharedAtMillis = now,
                expiresAtMillis = now + QUESTION_REWARD_WINDOW_MILLIS,
                durationMinutes = QUESTION_REWARD_WINDOW_MINUTES.toInt(),
                status = QuestionStatus.OPEN,
                solutionsCount = 0
            )

            val msgRef = channelMessagesRef(db, channelId, isGroup).document()
            val displaySummary = buildString {
                append("❓ [${attachment.subject} • ${attachment.topic}] soru paylaşıldı")
                if (noteText.isNotBlank()) {
                    append(": ").append(noteText.trim())
                }
            }

            val message = LeagueChatMessage(
                id = msgRef.id,
                channelId = channelId,
                channelType = if (isGroup) ChatChannelType.STUDY_GROUP else ChatChannelType.DIRECT_MESSAGE,
                senderUid = uid,
                senderName = resolvedName,
                senderAvatarPresetId = avatarPresetId,
                senderShowcaseBadges = showcaseBadges,
                messageType = MessageType.QUESTION_CARD,
                text = displaySummary,
                createdAtMillis = now,
                questionAttachment = attachment
            )

            msgRef.set(message.toMap()).await()

            if (!isGroup) {
                runCatching {
                    db.collection("direct_messages").document(channelId).set(
                        mapOf(
                            "lastMessagePreview" to displaySummary.take(120),
                            "lastMessageAtMillis" to now,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }

            message
        }
    }

    // ── 4. Submit Question Solution (Escrow / Pending Points) ────────────────

    suspend fun submitQuestionSolution(
        context: Context? = null,
        channelId: String,
        isGroup: Boolean,
        questionMessageId: String,
        annotatedBitmap: Bitmap? = null,
        annotatedImageUri: Uri? = null,
        explanationText: String = "",
        solverName: String? = null
    ): Result<LeagueChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val db = firestore ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")
            val uid = currentUid ?: throw IllegalStateException("Oturum açmış kullanıcı bulunamadı.")
            require(channelId.isNotBlank() && questionMessageId.isNotBlank()) {
                "Kanal veya soru kimliği eksik."
            }

            val messagesCol = channelMessagesRef(db, channelId, isGroup)
            val questionDocRef = messagesCol.document(questionMessageId)
            val questionSnap = questionDocRef.get().await()
            if (!questionSnap.exists()) {
                throw IllegalStateException("Soru mesajı bulunamadı.")
            }

            val questionMsg = LeagueChatMessage.fromMap(
                questionSnap.id,
                questionSnap.data.orEmpty()
            )
            val qAttachment = questionMsg.questionAttachment
                ?: throw IllegalStateException("Bu mesaj geçerli bir soru kartı içermiyor.")

            if (qAttachment.ownerUid == uid) {
                throw IllegalStateException("Kendi paylaştığınız soruya ödüllü çözüm gönderemezsiniz.")
            }
            if (qAttachment.status == QuestionStatus.SOLVED_APPROVED ||
                qAttachment.status == QuestionStatus.CLOSED_BY_AI
            ) {
                throw IllegalStateException("Bu soru zaten çözülmüş ve kapatılmış.")
            }

            val now = System.currentTimeMillis()
            val solutionId = "sol_${now}_${UUID.randomUUID().toString().take(6)}"

            val annotatedUrl = when {
                annotatedBitmap != null && !annotatedBitmap.isRecycled -> {
                    cloudStorageManager.uploadLeagueSolutionBitmap(
                        bitmap = annotatedBitmap,
                        channelId = channelId,
                        questionMessageId = questionMessageId,
                        solutionId = solutionId
                    ) ?: throw IllegalStateException("Çizimli çözüm görseli yüklenemedi.")
                }
                annotatedImageUri != null -> {
                    val uriStr = annotatedImageUri.toString()
                    if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                        uriStr
                    } else {
                        val ctx = context ?: throw IllegalStateException("Görsel yüklemek için Context gerekli.")
                        cloudStorageManager.uploadLeagueSolutionImage(
                            context = ctx,
                            localUri = annotatedImageUri,
                            channelId = channelId,
                            questionMessageId = questionMessageId,
                            solutionId = solutionId
                        ) ?: throw IllegalStateException("Çözüm görseli yüklenemedi.")
                    }
                }
                else -> qAttachment.questionImageUrl
            }

            val profileSnap = runCatching { db.collection("students").document(uid).get().await() }.getOrNull()
            val resolvedSolverName = solverName?.takeIf { it.isNotBlank() }
                ?: profileSnap?.getString("name")?.takeIf { it.isNotBlank() }
                ?: profileSnap?.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: "Arkadaşın"
            val avatarPresetId = (profileSnap?.getLong("avatarPresetId") ?: 0L).toInt()
            val showcaseBadges = (profileSnap?.get("showcaseBadges") as? List<*>)
                ?.mapNotNull { it as? String }
                ?.take(MAX_SHOWCASE_BADGES)
                .orEmpty()

            val rewardTier = calculateRewardTier(
                sharedAtMillis = qAttachment.sharedAtMillis,
                submittedAtMillis = now
            )

            val submission = SolutionSubmission(
                solutionId = solutionId,
                questionMessageId = questionMessageId,
                questionOwnerUid = qAttachment.ownerUid,
                solverUid = uid,
                solverName = resolvedSolverName,
                annotatedImageUrl = annotatedUrl,
                explanationText = explanationText.trim(),
                submittedAtMillis = now,
                rewardTier = rewardTier,
                escrowPoints = rewardTier.mentorPoints,
                potentialBonusSeconds = rewardTier.bonusFocusSeconds,
                verifyStatus = SolutionVerifyStatus.PENDING_ESCROW
            )

            val solutionMsgRef = messagesCol.document(solutionId)
            val summaryText = buildString {
                append("✏️ $resolvedSolverName çözüm gönderdi (${rewardTier.windowLabel})")
                if (explanationText.isNotBlank()) {
                    append(": ").append(explanationText.trim())
                }
            }

            val solutionChatMessage = LeagueChatMessage(
                id = solutionId,
                channelId = channelId,
                channelType = if (isGroup) ChatChannelType.STUDY_GROUP else ChatChannelType.DIRECT_MESSAGE,
                senderUid = uid,
                senderName = resolvedSolverName,
                senderAvatarPresetId = avatarPresetId,
                senderShowcaseBadges = showcaseBadges,
                messageType = MessageType.SOLUTION_CARD,
                text = summaryText,
                createdAtMillis = now,
                solutionSubmission = submission,
                replyToQuestionMessageId = questionMessageId
            )

            val studentRef = db.collection("students").document(uid)
            db.runTransaction { tx ->
                val studentSnap = tx.get(studentRef)
                val currentPending = (studentSnap.getLong("pendingMentorPoints") ?: 0L).toInt()
                val updatedPending = (currentPending + submission.escrowPoints).coerceAtLeast(0)

                val latestQSnap = tx.get(questionDocRef)
                @Suppress("UNCHECKED_CAST")
                val latestQMap = latestQSnap.get("questionAttachment") as? Map<String, Any?>
                val latestQ = QuestionAttachment.fromMap(latestQMap) ?: qAttachment
                val updatedQ = latestQ.copy(solutionsCount = latestQ.solutionsCount + 1)

                tx.set(solutionMsgRef, solutionChatMessage.toMap())
                tx.set(
                    questionDocRef.collection("solutions").document(solutionId),
                    submission.toMap()
                )
                tx.update(questionDocRef, "questionAttachment", updatedQ.toMap())
                tx.set(
                    studentRef,
                    mapOf(
                        "pendingMentorPoints" to updatedPending,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }.await()

            solutionChatMessage
        }
    }

    // ── 5. Verify Question Solution (Approve ✓ / Reject ✗) ───────────────────

    suspend fun verifyQuestionSolution(
        channelId: String,
        isGroup: Boolean,
        questionMessageId: String,
        solutionId: String,
        approved: Boolean
    ): Result<SolutionSubmission> = withContext(Dispatchers.IO) {
        runCatching {
            val db = firestore ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")
            val callerUid = currentUid ?: throw IllegalStateException("Oturum açmış kullanıcı bulunamadı.")
            require(channelId.isNotBlank() && questionMessageId.isNotBlank() && solutionId.isNotBlank()) {
                "Eksik doğrulama parametresi."
            }

            val messagesCol = channelMessagesRef(db, channelId, isGroup)
            val questionDocRef = messagesCol.document(questionMessageId)
            val solutionMsgRef = messagesCol.document(solutionId)
            val subSolRef = questionDocRef.collection("solutions").document(solutionId)

            val now = System.currentTimeMillis()
            val todayKey = currentDayKeyUtcPlus3(now)

            val updatedSubmission = db.runTransaction { tx ->
                val qSnap = tx.get(questionDocRef)
                if (!qSnap.exists()) {
                    throw IllegalStateException("Soru mesajı bulunamadı.")
                }
                @Suppress("UNCHECKED_CAST")
                val qAttachment = QuestionAttachment.fromMap(
                    qSnap.get("questionAttachment") as? Map<String, Any?>
                ) ?: throw IllegalStateException("Soru verisi okunamadı.")

                if (qAttachment.ownerUid.isNotBlank() && qAttachment.ownerUid != callerUid) {
                    throw IllegalStateException("Sadece soruyu paylaşan öğrenci çözümü onaylayabilir veya reddedebilir.")
                }

                val solMsgSnap = tx.get(solutionMsgRef)
                val subSolSnap = tx.get(subSolRef)

                @Suppress("UNCHECKED_CAST")
                val currentSub = when {
                    solMsgSnap.exists() -> SolutionSubmission.fromMap(
                        solMsgSnap.get("solutionSubmission") as? Map<String, Any?>
                    )
                    subSolSnap.exists() -> SolutionSubmission.fromMap(subSolSnap.data)
                    else -> null
                } ?: throw IllegalStateException("Çözüm kaydı bulunamadı.")

                if (currentSub.verifyStatus != SolutionVerifyStatus.PENDING_ESCROW) {
                    return@runTransaction currentSub
                }

                val solverRef = db.collection("students").document(currentSub.solverUid)
                val solverSnap = tx.get(solverRef)
                val globalLeagueRef = db.collection("institutions")
                    .document(GLOBAL_LEAGUE_BUCKET)
                    .collection("weeklyLeague")
                    .document(currentSub.solverUid)
                val globalLeagueSnap = tx.get(globalLeagueRef)

                val currentPending = (solverSnap.getLong("pendingMentorPoints") ?: 0L).toInt()
                val nextPending = (currentPending - currentSub.escrowPoints).coerceAtLeast(0)

                if (approved) {
                    // Check daily cap: max 2 rewarded solutions per day to the same question owner
                    val pairCounterField = "dailyOwnerReward_${todayKey}_${qAttachment.ownerUid}"
                    val solvedTodayForOwner = (solverSnap.getLong(pairCounterField) ?: 0L).toInt()
                    val withinDailyOwnerLimit = solvedTodayForOwner < MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER

                    val currentMentorPoints = (solverSnap.getLong("mentorPoints") ?: 0L).toInt()
                    val currentWeeklyBonusSec = (solverSnap.getLong("weeklyQuestionBonusSeconds") ?: 0L)
                        .coerceIn(0L, MAX_WEEKLY_QUESTION_BONUS_SECONDS)
                    val currentSolvedTotal = (solverSnap.getLong("solvedTotalCount") ?: 0L).toInt()
                    val currentGoldenCount = (solverSnap.getLong("goldenWindowSolveCount") ?: 0L).toInt()
                    val currentWeeklyFocusSec = solverSnap.getLong("weeklyFocusSeconds") ?: 0L

                    val awardedPts = if (withinDailyOwnerLimit) currentSub.escrowPoints else 0
                    val remainingBonusCapacity = (MAX_WEEKLY_QUESTION_BONUS_SECONDS - currentWeeklyBonusSec)
                        .coerceAtLeast(0L)
                    val awardedBonusSec = if (withinDailyOwnerLimit) {
                        minOf(currentSub.potentialBonusSeconds, remainingBonusCapacity)
                    } else {
                        0L
                    }

                    val nextMentorPoints = currentMentorPoints + awardedPts
                    val nextWeeklyBonusSec = (currentWeeklyBonusSec + awardedBonusSec)
                        .coerceIn(0L, MAX_WEEKLY_QUESTION_BONUS_SECONDS)
                    val nextSolvedTotal = currentSolvedTotal + 1
                    val nextGoldenCount = if (currentSub.rewardTier == RewardTier.GOLD_100) {
                        currentGoldenCount + 1
                    } else {
                        currentGoldenCount
                    }

                    val existingBadges = (solverSnap.get("unlockedBadges") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.toMutableSet()
                        ?: mutableSetOf()
                    if (nextSolvedTotal >= 1) existingBadges.add("FIRST_SOLVE")
                    if (nextGoldenCount >= 1) existingBadges.add("GOLDEN_RESCUER")
                    if (nextGoldenCount >= 5) existingBadges.add("SPEED_SHARK")
                    if (nextSolvedTotal >= 10) existingBadges.add("PEER_MENTOR_10")
                    if (nextMentorPoints >= 500) existingBadges.add("SHARK_PROFESSOR")

                    val existingShowcase = (solverSnap.get("showcaseBadges") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.filter { it in existingBadges }
                        ?.distinct()
                        ?.take(MAX_SHOWCASE_BADGES)
                        .orEmpty()
                        .ifEmpty { existingBadges.take(MAX_SHOWCASE_BADGES) }

                    val finalizedSub = currentSub.copy(
                        verifyStatus = SolutionVerifyStatus.APPROVED,
                        verifiedAtMillis = now,
                        awardedPoints = awardedPts,
                        awardedBonusSeconds = awardedBonusSec
                    )

                    val finalizedQuestion = qAttachment.copy(
                        status = QuestionStatus.SOLVED_APPROVED,
                        approvedSolutionId = solutionId,
                        approvedSolverUid = currentSub.solverUid,
                        approvedSolverName = currentSub.solverName
                    )

                    tx.update(questionDocRef, "questionAttachment", finalizedQuestion.toMap())
                    if (solMsgSnap.exists()) {
                        tx.update(solutionMsgRef, "solutionSubmission", finalizedSub.toMap())
                    }
                    tx.set(subSolRef, finalizedSub.toMap(), SetOptions.merge())

                    val studentUpdates = mutableMapOf<String, Any>(
                        "mentorPoints" to nextMentorPoints,
                        "pendingMentorPoints" to nextPending,
                        "weeklyQuestionBonusSeconds" to nextWeeklyBonusSec,
                        "solvedTotalCount" to nextSolvedTotal,
                        "goldenWindowSolveCount" to nextGoldenCount,
                        "unlockedBadges" to existingBadges.toList(),
                        "showcaseBadges" to existingShowcase,
                        "weeklyFocusSeconds" to (currentWeeklyFocusSec + awardedBonusSec),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (withinDailyOwnerLimit && awardedPts > 0) {
                        studentUpdates[pairCounterField] = solvedTodayForOwner + 1
                    }
                    tx.set(solverRef, studentUpdates, SetOptions.merge())

                    if (awardedBonusSec > 0L && globalLeagueSnap.exists()) {
                        val leagueWeeklySec = globalLeagueSnap.getLong("weeklyFocusSeconds") ?: currentWeeklyFocusSec
                        tx.set(
                            globalLeagueRef,
                            mapOf(
                                "weeklyFocusSeconds" to (leagueWeeklySec + awardedBonusSec),
                                "mentorPoints" to nextMentorPoints,
                                "weeklyQuestionBonusSeconds" to nextWeeklyBonusSec
                            ),
                            SetOptions.merge()
                        )
                    }

                    finalizedSub
                } else {
                    // Rejected (✗): remove escrow points and reopen question
                    val finalizedSub = currentSub.copy(
                        verifyStatus = SolutionVerifyStatus.REJECTED,
                        verifiedAtMillis = now,
                        awardedPoints = 0,
                        awardedBonusSeconds = 0L
                    )

                    val reopenedQuestion = qAttachment.copy(
                        status = QuestionStatus.OPEN,
                        approvedSolutionId = null,
                        approvedSolverUid = null,
                        approvedSolverName = null
                    )

                    tx.update(questionDocRef, "questionAttachment", reopenedQuestion.toMap())
                    if (solMsgSnap.exists()) {
                        tx.update(solutionMsgRef, "solutionSubmission", finalizedSub.toMap())
                    }
                    tx.set(subSolRef, finalizedSub.toMap(), SetOptions.merge())

                    tx.set(
                        solverRef,
                        mapOf(
                            "pendingMentorPoints" to nextPending,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )

                    finalizedSub
                }
            }.await()

            updatedSubmission
        }
    }

    // ── 6. Request-Approved Direct Messages (DM) ─────────────────────────────

    suspend fun sendDmRequest(
        targetUid: String,
        targetName: String,
        initialMessage: String = "",
        senderName: String? = null
    ): Result<DirectMessageThread> = withContext(Dispatchers.IO) {
        runCatching {
            val db = firestore ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")
            val uid = currentUid ?: throw IllegalStateException("Oturum açmış kullanıcı bulunamadı.")
            require(targetUid.isNotBlank() && targetUid != uid) {
                "Geçerli bir alıcı seçilmelidir."
            }

            val profileSnap = runCatching { db.collection("students").document(uid).get().await() }.getOrNull()
            val resolvedSenderName = senderName?.takeIf { it.isNotBlank() }
                ?: profileSnap?.getString("name")?.takeIf { it.isNotBlank() }
                ?: profileSnap?.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: "Öğrenci"

            val threadId = buildDeterministicDmThreadId(uid, targetUid)
            val threadRef = db.collection("direct_messages").document(threadId)
            val existingSnap = threadRef.get().await()
            val now = System.currentTimeMillis()
            val preview = initialMessage.trim().ifBlank { "👋 Merhaba! Çalışma mesajı isteği gönderdi." }

            val existingStatus = existingSnap.getString("status")
                ?.let { runCatching { DmRequestStatus.valueOf(it) }.getOrNull() }

            val effectiveStatus = if (existingStatus == DmRequestStatus.ACCEPTED) {
                DmRequestStatus.ACCEPTED
            } else {
                DmRequestStatus.PENDING
            }

            val thread = DirectMessageThread(
                threadId = threadId,
                participantIds = listOf(uid, targetUid).sorted(),
                participantNames = mapOf(
                    uid to resolvedSenderName,
                    targetUid to targetName.ifBlank { "Öğrenci" }
                ),
                requesterUid = uid,
                requesterName = resolvedSenderName,
                receiverUid = targetUid,
                receiverName = targetName.ifBlank { "Öğrenci" },
                status = effectiveStatus,
                lastMessagePreview = preview.take(120),
                lastMessageAtMillis = now,
                createdAtMillis = existingSnap.getLong("createdAtMillis") ?: now
            )

            threadRef.set(thread.toMap(), SetOptions.merge()).await()

            if (initialMessage.isNotBlank()) {
                val msgRef = threadRef.collection("messages").document()
                val firstMsg = LeagueChatMessage(
                    id = msgRef.id,
                    channelId = threadId,
                    channelType = ChatChannelType.DIRECT_MESSAGE,
                    senderUid = uid,
                    senderName = resolvedSenderName,
                    messageType = MessageType.TEXT,
                    text = initialMessage.trim(),
                    createdAtMillis = now
                )
                msgRef.set(firstMsg.toMap()).await()
            }

            thread
        }
    }

    suspend fun respondToDmRequest(
        threadId: String,
        accept: Boolean
    ): Result<DirectMessageThread> = withContext(Dispatchers.IO) {
        runCatching {
            val db = firestore ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")
            val uid = currentUid ?: throw IllegalStateException("Oturum açmış kullanıcı bulunamadı.")
            require(threadId.isNotBlank()) { "DM kanal kimliği boş olamaz." }

            val threadRef = db.collection("direct_messages").document(threadId)
            val snap = threadRef.get().await()
            if (!snap.exists()) {
                throw IllegalStateException("DM isteği bulunamadı.")
            }

            val currentThread = DirectMessageThread.fromMap(snap.id, snap.data.orEmpty())
            if (currentThread.receiverUid.isNotBlank() &&
                currentThread.receiverUid != uid &&
                uid !in currentThread.participantIds
            ) {
                throw IllegalStateException("Bu DM isteğini yanıtlama yetkiniz yok.")
            }

            val nextStatus = if (accept) DmRequestStatus.ACCEPTED else DmRequestStatus.REJECTED
            val now = System.currentTimeMillis()
            threadRef.set(
                mapOf(
                    "status" to nextStatus.name,
                    "lastMessageAtMillis" to now,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            currentThread.copy(status = nextStatus, lastMessageAtMillis = now)
        }
    }

    fun observeDmThreads(): Flow<List<DirectMessageThread>> = callbackFlow {
        val db = firestore
        val uid = currentUid
        if (db == null || uid.isNullOrBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val registration: ListenerRegistration? = runCatching {
            db.collection("direct_messages")
                .whereArrayContains("participantIds", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "observeDmThreads error: ${error.message}")
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val threads = snapshot?.documents
                        ?.mapNotNull { doc ->
                            val data = doc.data ?: return@mapNotNull null
                            DirectMessageThread.fromMap(doc.id, data)
                        }
                        ?.sortedByDescending { it.lastMessageAtMillis }
                        .orEmpty()
                    trySend(threads)
                }
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach observeDmThreads listener: ${e.message}")
            trySend(emptyList())
        }.getOrNull()

        awaitClose { registration?.remove() }
    }.catch { e ->
        Log.w(TAG, "observeDmThreads flow catch: ${e.message}")
        emit(emptyList())
    }

    // ── 7. Mentor Stats & Showcase Badges (Max 3) ────────────────────────────

    suspend fun updateShowcaseBadges(
        uid: String? = currentUid,
        badgeIds: List<String>
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val db = firestore ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")
            val effectiveUid = uid?.takeIf { it.isNotBlank() }
                ?: currentUid
                ?: throw IllegalStateException("Oturum açmış kullanıcı bulunamadı.")

            val trimmed = badgeIds
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_SHOWCASE_BADGES)

            db.collection("students").document(effectiveUid).set(
                mapOf(
                    "showcaseBadges" to trimmed,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            runCatching {
                db.collection("institutions")
                    .document(GLOBAL_LEAGUE_BUCKET)
                    .collection("weeklyLeague")
                    .document(effectiveUid)
                    .set(
                        mapOf("showcaseBadges" to trimmed),
                        SetOptions.merge()
                    )
                    .await()
            }

            trimmed
        }
    }

    fun observeMentorStats(uid: String? = currentUid): Flow<MentorStats> = callbackFlow {
        val db = firestore
        val effectiveUid = uid?.takeIf { it.isNotBlank() } ?: currentUid
        if (db == null || effectiveUid.isNullOrBlank()) {
            trySend(MentorStats())
            close()
            return@callbackFlow
        }

        val registration: ListenerRegistration? = runCatching {
            db.collection("students").document(effectiveUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "observeMentorStats error: ${error.message}")
                        trySend(MentorStats())
                        return@addSnapshotListener
                    }
                    val stats = MentorStats.fromMap(snapshot?.data)
                    trySend(stats)
                }
        }.onFailure {
            trySend(MentorStats())
        }.getOrNull()

        awaitClose { registration?.remove() }
    }.catch {
        emit(MentorStats())
    }
}
