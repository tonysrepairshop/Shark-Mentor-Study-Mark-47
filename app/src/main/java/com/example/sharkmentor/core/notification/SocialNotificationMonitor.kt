package com.example.sharkmentor.core.notification

import android.content.Context
import android.util.Log
import com.example.sharkmentor.core.badges.SharkBadgeCatalog
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.preferences.UserPreferences
import com.example.sharkmentor.service.sync.SyncWorker
import com.example.sharkmentor.services.SharkMessagingService
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors Firestore social streams 24/7 (without quiet-hour suppression) and dispatches
 * separated notifications for:
 * 1) Shared questions (`CHANNEL_LEAGUE_QUESTIONS`)
 * 2) Chat & DM messages (`CHANNEL_CHAT_MESSAGES`)
 * 3) Study nudges (`CHANNEL_STUDY_NUDGE`)
 */
object SocialNotificationMonitor {

    private const val TAG = "SocialNotifMonitor"
    private const val PREFS_GROUP_CACHE = "social_notif_group_cache"
    private const val GROUP_NAME_TTL_MS = 24L * 60L * 60L * 1000L
    private const val PREFS_PENDING_REQUESTS = "social_notif_pending_requests"
    
    @Volatile
    private var activeUid: String? = null

    @Volatile
    private var monitorStartMillis: Long = 0L

    private var studentListener: ListenerRegistration? = null
    private var dmThreadsListener: ListenerRegistration? = null
    private var nudgesListener: ListenerRegistration? = null
    private val groupMessageListeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val groupNamesCache = ConcurrentHashMap<String, String>()
    private val seenDmMessageIds = ConcurrentHashMap<String, String>()
    private val seenPendingRequestIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    
    private fun readCachedGroupName(context: Context, groupCode: String): String? {
        groupNamesCache[groupCode]?.takeIf { it.isNotBlank() }?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_GROUP_CACHE, Context.MODE_PRIVATE)
        val cachedName = prefs.getString("name_$groupCode", null)?.takeIf { it.isNotBlank() } ?: return null
        val cachedAt = prefs.getLong("ts_$groupCode", 0L)
        if (System.currentTimeMillis() - cachedAt <= GROUP_NAME_TTL_MS) {
            groupNamesCache[groupCode] = cachedName
            return cachedName
        }
        return null
    }

    private fun saveCachedGroupName(context: Context, groupCode: String, name: String) {
        groupNamesCache[groupCode] = name
        context.getSharedPreferences(PREFS_GROUP_CACHE, Context.MODE_PRIVATE)
            .edit()
            .putString("name_$groupCode", name)
            .putLong("ts_$groupCode", System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun start(context: Context) {
        val appCtx = context.applicationContext
        SharkNotificationEngine.init(appCtx)

        val auth = GoogleServicesHelper.getFirebaseAuth(appCtx)
        val uid = GoogleServicesHelper.getCurrentUser(auth, appCtx)?.uid
        if (uid.isNullOrBlank() || uid == "local_student" || uid == "student_default") {
            return
        }
        val db = GoogleServicesHelper.getFirebaseFirestore(appCtx) ?: return
        if (activeUid == uid && studentListener != null && dmThreadsListener != null) {
            return
        }

        stop()
        activeUid = uid
        monitorStartMillis = System.currentTimeMillis()
        val userPrefs = UserPreferences.getInstance(appCtx)

        syncGroupListeners(appCtx, db, uid, userPrefs.joinedGroupCodes)

        studentListener = runCatching {
            db.collection("students").document(uid)
                .addSnapshotListener { snap, error ->
                    if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
                    val remoteGroups = (snap.get("joinedGroupCodes") as? List<*>)
                        ?.mapNotNull { it?.toString()?.trim()?.uppercase() }
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        .orEmpty()
                    val allCodes = (userPrefs.joinedGroupCodes + remoteGroups)
                        .map { it.trim().uppercase() }
                        .filter { it.isNotBlank() }
                        .toSet()
                    syncGroupListeners(appCtx, db, uid, allCodes)
                }
        }.onFailure {
            Log.w(TAG, "studentListener error: ${it.message}")
        }.getOrNull()

        dmThreadsListener = runCatching {
            db.collection("dm_threads")
                .whereArrayContains("participantIds", uid)
                .limit(30)
                .addSnapshotListener { snap, error ->
                    if (error != null || snap == null) return@addSnapshotListener
                    val blockedIds = SharkBadgeCatalog.getBlockedUserIds(appCtx)
                    snap.documentChanges.forEach { change ->
                        val doc = change.document
                        val status = doc.getString("status").orEmpty()
                       if (status == "PENDING") {
                           val recipientId = doc.getString("recipientId").orEmpty().trim()
                           val requesterId = doc.getString("requesterId").orEmpty().trim()
                           if (recipientId == uid && requesterId.isNotBlank() && requesterId != uid && requesterId !in blockedIds && !seenPendingRequestIds.contains(doc.id)) {
                               val prefs = appCtx.getSharedPreferences(PREFS_PENDING_REQUESTS, Context.MODE_PRIVATE)
                               val prefKey = "pending_${uid}_${doc.id}"; val alreadyNotified = prefs.getBoolean(prefKey, false)
                               val createdOrUpdatedAt = maxOf(doc.getLong("updatedAtMillis") ?: 0L, doc.getLong("createdAtMillis") ?: 0L); if (createdOrUpdatedAt >= monitorStartMillis - 60_000L || !alreadyNotified) {
                          seenPendingRequestIds.add(doc.id); if (!alreadyNotified) prefs.edit().putBoolean(prefKey, true).apply(); val requesterName = SyncWorker.sanitizeTurkishText(doc.getString("requesterName").orEmpty()).trim().ifBlank { "Bir kullanıcı" }; SharkMessagingService.showChatMessageNotification(context = appCtx, senderName = "Yeni Sohbet İsteği", messageText = "$requesterName sana sohbet isteği gönderdi.", messageId = "dm_req_${doc.id}", isDm = true); }; }; return@forEach; }
                        if (status != "ACCEPTED") return@forEach

                        val updatedAtMillis = doc.getLong("updatedAtMillis") ?: 0L
                        if (updatedAtMillis < monitorStartMillis - 60_000L) return@forEach

                        val lastSenderId = doc.getString("lastSenderId").orEmpty().trim()
                        val lastMessageId = doc.getString("lastMessageId").orEmpty().trim()
                        val lastMessageType = doc.getString("lastMessageType").orEmpty().trim()
                        val lastMessageText = SyncWorker.sanitizeTurkishText(
                            doc.getString("lastMessageText").orEmpty()
                        ).trim()

                        if (lastSenderId.isBlank() ||
                            lastSenderId == uid ||
                            blockedIds.contains(lastSenderId) ||
                            lastMessageId.isBlank()
                        ) {
                            return@forEach
                        }

                        val previousSeen = seenDmMessageIds[doc.id]
                        if (previousSeen == lastMessageId) return@forEach
                        seenDmMessageIds[doc.id] = lastMessageId

                        @Suppress("UNCHECKED_CAST")
                        val participantNames = doc.get("participantNames") as? Map<String, Any?>
                        val senderName = SyncWorker.sanitizeTurkishText(
                            participantNames?.get(lastSenderId)?.toString().orEmpty()
                        ).ifBlank { "Arkadaşın" }

                        if (lastMessageType == "QUESTION") {
                            val cleanDetail = lastMessageText.removePrefix("Soru Paylaşımı:").trim()
                            val subj = cleanDetail.substringBefore("(").trim().ifBlank { "Ders" }
                            val topic = cleanDetail.substringAfter("(", "")
                                .substringBefore(")", "")
                                .trim()
                            SharkMessagingService.showQuestionSharedNotification(
                                context = appCtx,
                                senderName = senderName,
                                subject = subj,
                                topic = topic,
                                messageId = lastMessageId,
                                isDm = true
                            )
                        } else {
                            SharkMessagingService.showChatMessageNotification(
                                context = appCtx,
                                senderName = senderName,
                                messageText = lastMessageText.ifBlank { "Yeni mesaj gönderdi." },
                                messageId = lastMessageId,
                                isDm = true
                            )
                        }
                    }
                }
        }.onFailure {
            Log.w(TAG, "dmThreadsListener error: ${it.message}")
        }.getOrNull()

        nudgesListener = runCatching {
            db.collection("nudges")
                .whereEqualTo("toUid", uid)
                .whereEqualTo("seen", false)
                .limit(20)
                .addSnapshotListener { snap, error ->
                    if (error != null || snap == null) return@addSnapshotListener
                    val blockedIds = SharkBadgeCatalog.getBlockedUserIds(appCtx)
                    snap.documentChanges
                        .filter { it.type == DocumentChange.Type.ADDED }
                        .forEach { change ->
                            val doc = change.document
                            val createdMs = doc.getLong("createdAtMillis")
                                ?: doc.getTimestamp("createdAt")?.toDate()?.time
                                ?: 0L
                            if (createdMs > 0L && createdMs < monitorStartMillis - 60_000L) {
                                return@forEach
                            }
                            val fromUid = (doc.getString("fromUid") ?: doc.getString("senderId")).orEmpty()
                            if (fromUid == uid || blockedIds.contains(fromUid)) return@forEach
                            val fromName = SyncWorker.sanitizeTurkishText(
                                doc.getString("fromName") ?: doc.getString("senderName") ?: "Arkadaşın"
                            ).ifBlank { "Arkadaşın" }
                            val message = SyncWorker.sanitizeTurkishText(
                                doc.getString("message").orEmpty()
                            ).ifBlank {
                                "$fromName seni çalışmaya dürttü. Odak oturumunu başlatabilirsin."
                            }
                            val groupId = doc.getString("groupId")?.takeIf { it.isNotBlank() }

                            SharkMessagingService.showStudyInviteNotification(
                                context = appCtx,
                                fromName = fromName,
                                message = message,
                                groupId = groupId,
                                nudgeId = doc.id
                            )
                        }
                }
        }.onFailure {
            Log.w(TAG, "nudgesListener error: ${it.message}")
        }.getOrNull()
    }

    private fun syncGroupListeners(
        appCtx: Context,
        db: FirebaseFirestore,
        uid: String,
        groupCodes: Set<String>
    ) {
        val staleCodes = groupMessageListeners.keys.filter { !groupCodes.contains(it) }
        staleCodes.forEach { code ->
            groupMessageListeners.remove(code)?.remove()
        }

        groupCodes.forEach { rawCode ->
            val groupCode = rawCode.trim().uppercase()
            if (groupCode.isBlank() || groupMessageListeners.containsKey(groupCode)) return@forEach

            if (readCachedGroupName(appCtx, groupCode) == null) {
                runCatching {
                    db.collection("study_groups").document(groupCode).get()
                        .addOnSuccessListener { doc ->
                            val name = SyncWorker.sanitizeTurkishText(doc.getString("name").orEmpty()).trim()
                            if (name.isNotBlank()) {
                                saveCachedGroupName(appCtx, groupCode, name)
                            }
                        }
                }
            }

            val registration = runCatching {
                db.collection("study_groups").document(groupCode).collection("messages")
                    .whereGreaterThanOrEqualTo("createdAtMillis", monitorStartMillis - 60_000L)
                    .limit(30)
                    .addSnapshotListener { snap, error ->
                        if (error != null || snap == null) return@addSnapshotListener
                        val blockedIds = SharkBadgeCatalog.getBlockedUserIds(appCtx)
                        val resolvedGroupName = groupNamesCache[groupCode].orEmpty()

                        snap.documentChanges
                            .filter { it.type == DocumentChange.Type.ADDED }
                            .forEach { change ->
                                val doc = change.document
                                if (doc.getBoolean("isDeleted") == true) return@forEach
                                val senderId = doc.getString("senderId").orEmpty().trim()
                                if (senderId.isBlank() || senderId == uid || blockedIds.contains(senderId)) {
                                    return@forEach
                                }
                                val createdMs = doc.getLong("createdAtMillis")
                                    ?: doc.getTimestamp("createdAt")?.toDate()?.time
                                    ?: 0L
                                if (createdMs > 0L && createdMs < monitorStartMillis - 60_000L) {
                                    return@forEach
                                }

                                val senderName = SyncWorker.sanitizeTurkishText(
                                    doc.getString("senderName").orEmpty()
                                ).ifBlank { "Grup Üyesi" }
                                val type = doc.getString("type").orEmpty().ifBlank { "TEXT" }

                                if (type == "QUESTION") {
                                    val subj = SyncWorker.sanitizeTurkishText(
                                        doc.getString("questionSubject").orEmpty()
                                    ).ifBlank { "Ders" }
                                    val topic = SyncWorker.sanitizeTurkishText(
                                        doc.getString("questionTopic").orEmpty()
                                    )
                                    SharkMessagingService.showQuestionSharedNotification(
                                        context = appCtx,
                                        senderName = senderName,
                                        subject = subj,
                                        topic = topic,
                                        groupName = resolvedGroupName,
                                        messageId = doc.id,
                                        groupCode = groupCode,
                                        isDm = false
                                    )
                                } else {
                                    val rawText = SyncWorker.sanitizeTurkishText(
                                        doc.getString("text").orEmpty()
                                    ).trim()
                                    val preview = if (type == "SOLUTION") {
                                        if (rawText.isNotBlank()) "Soru için çözüm paylaştı: $rawText"
                                        else "Soru için çözüm paylaştı."
                                    } else {
                                        rawText.ifBlank { "Yeni mesaj gönderdi." }
                                    }
                                    SharkMessagingService.showChatMessageNotification(
                                        context = appCtx,
                                        senderName = senderName,
                                        messageText = preview,
                                        groupName = resolvedGroupName,
                                        messageId = doc.id,
                                        groupCode = groupCode,
                                        isDm = false
                                    )
                                }
                            }
                    }
            }.onFailure {
                Log.w(TAG, "Group listener warning for $groupCode: ${it.message}")
            }.getOrNull()

            if (registration != null) {
                groupMessageListeners[groupCode] = registration
            }
        }
    }

    @Synchronized
    fun stop() {
        studentListener?.remove()
        studentListener = null
        dmThreadsListener?.remove()
        dmThreadsListener = null
        nudgesListener?.remove()
        nudgesListener = null
        groupMessageListeners.values.forEach { it.remove() }
        groupMessageListeners.clear()
       seenPendingRequestIds.clear()
        seenDmMessageIds.clear()
        activeUid = null
    }
}
