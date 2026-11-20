package com.example.sharkmentor.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.core.notification.ActiveChatTracker
import com.example.sharkmentor.R
import com.example.sharkmentor.core.notification.SharkNotificationEngine
import com.example.sharkmentor.core.util.AppIconManager
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.repository.FirebaseToRoomSyncManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Collections
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SharkMessagingService : FirebaseMessagingService() {

    private val syncManager: FirebaseToRoomSyncManager by lazy {
        FirebaseToRoomSyncManager(applicationContext)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        try {
            if (!GoogleServicesHelper.isGooglePlayServicesAvailable(this)) return
            val currentUserId = GoogleServicesHelper.getCurrentUser(context = this)?.uid ?: return
            val payload = mapOf(
                "fcmToken" to token,
                "fcmTokens" to com.google.firebase.firestore.FieldValue.arrayUnion(token)
            )
            GoogleServicesHelper.getFirebaseFirestore(this)?.collection("students")?.document(currentUserId)
                ?.set(payload, com.google.firebase.firestore.SetOptions.merge())
                ?.addOnSuccessListener {
                    Log.d("FCM", "Token updated in Firestore: $token")
                }
        } catch (e: Throwable) {
            Log.w("FCM", "Error updating token in Firestore: ${e.message}")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        SharkNotificationEngine.init(this)

        val syncType = remoteMessage.data["type"] ?: remoteMessage.data["triggerType"]
        if (syncType == "SYNC_STUDY_PLAN" || syncType == "SCHEDULE") {
            serviceScope.launch {
                syncManager.syncRemotePlanToRoom(force = true)
            }
        }

        if (syncType == "NUDGE" || syncType == "STUDY_INVITE" || syncType == "STUDY_INVITE_NUDGE") {
            val fromName = remoteMessage.data["fromName"]
                ?: remoteMessage.data["senderName"]
                ?: "Arkadaşın"
            val body = remoteMessage.notification?.body
                ?: remoteMessage.data["body"]
                ?: remoteMessage.data["message"]
                ?: "$fromName seni birlikte çalışmaya dürttü. Odak oturumunu başlatabilirsin."
            val groupId = remoteMessage.data["groupId"]
            val nudgeId = remoteMessage.data["nudgeId"] ?: remoteMessage.data["id"]

            showStudyInviteNotification(
                context = this,
                fromName = fromName,
                message = body,
                groupId = groupId,
                nudgeId = nudgeId
            )
            return
        }

        if (syncType == "QUESTION" || syncType == "LEAGUE_QUESTION" || syncType == "LEAGUE_QUESTION_SHARED") {
            val senderName = remoteMessage.data["senderName"]
                ?: remoteMessage.data["fromName"]
                ?: "Grup Üyesi"
            val subject = remoteMessage.data["questionSubject"]
                ?: remoteMessage.data["subject"]
                ?: "Ders"
            val topic = remoteMessage.data["questionTopic"]
                ?: remoteMessage.data["topic"]
                ?: ""
            val groupName = remoteMessage.data["groupName"] ?: ""
            val messageId = remoteMessage.data["messageId"] ?: remoteMessage.data["id"]
            val groupCode = remoteMessage.data["groupCode"] ?: remoteMessage.data["groupId"]
            val isDm = remoteMessage.data["isDm"] == "true" || remoteMessage.data["chatMode"] == "DIRECT"

            showQuestionSharedNotification(
                context = this,
                senderName = senderName,
                subject = subject,
                topic = topic,
                groupName = groupName,
                messageId = messageId,
                groupCode = groupCode,
                isDm = isDm
            )
            return
        }

        if (syncType == "CHAT_MESSAGE" || syncType == "GROUP_MESSAGE" || syncType == "DM_MESSAGE") {
            val senderName = remoteMessage.data["senderName"]
                ?: remoteMessage.data["fromName"]
                ?: "Grup Üyesi"
            val messageText = remoteMessage.notification?.body
                ?: remoteMessage.data["text"]
                ?: remoteMessage.data["body"]
                ?: remoteMessage.data["message"]
                ?: "Yeni mesaj gönderdi."
            val groupName = remoteMessage.data["groupName"] ?: ""
            val messageId = remoteMessage.data["messageId"] ?: remoteMessage.data["id"]
            val groupCode = remoteMessage.data["groupCode"] ?: remoteMessage.data["groupId"]
            val isDm = syncType == "DM_MESSAGE" || remoteMessage.data["isDm"] == "true"

            showChatMessageNotification(
                context = this,
                senderName = senderName,
                messageText = messageText,
                groupName = groupName,
                messageId = messageId,
                groupCode = groupCode,
                isDm = isDm
            )
            return
        }

        val defaultTitle = "SharkMentor"
        val defaultBody = "Koçunuz yeni bir görev atadı."
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: defaultTitle
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: defaultBody

        showHeadsUpNotification(title, body)
    }

    private fun showHeadsUpNotification(title: String, body: String) {
        val channelId = "shark_coach_tasks"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Koç Görevleri & Hedefler",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Koçtan gelen anlık plan ve rota güncellemeleri"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activeShark = AppIconManager.getActiveIcon(this)
        val largeIconBitmap = runCatching { BitmapFactory.decodeResource(resources, activeShark.iconRes) }.getOrNull()
        val smallIconRes = if (activeShark.notificationSmallIconRes != 0) activeShark.notificationSmallIconRes else R.drawable.ic_notification_original

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(smallIconRes)
            .apply { if (largeIconBitmap != null) setLargeIcon(largeIconBitmap) }
            .setColor(getColor(R.color.shark_blue_primary))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        runCatching {
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }

    companion object {
        const val ACTION_STUDY_INVITE_NUDGE = "STUDY_INVITE_NUDGE"
        const val ACTION_OPEN_LEAGUE_CHAT = "OPEN_LEAGUE_CHAT"
        const val CHANNEL_STUDY_INVITE_ID = SharkNotificationEngine.CHANNEL_STUDY_NUDGE
        const val CHANNEL_CHAT_MESSAGES_ID = SharkNotificationEngine.CHANNEL_CHAT_MESSAGES
        const val CHANNEL_LEAGUE_QUESTIONS_ID = SharkNotificationEngine.CHANNEL_LEAGUE_QUESTIONS

        private val postedNotificationKeys = Collections.synchronizedSet(LinkedHashSet<String>())

        private val QUESTION_PROMPTS = listOf(
            "Gruba yeni bir %SUBJECT% sorusu düştü. Sen çözebilecek misin?",
            "%SENDER% yeni bir %SUBJECT% sorusu paylaştı. Çözümünü göndermek için sohbete göz at.",
            "%SUBJECT% adına yeni soru paylaşıldı. İlk doğru çözümü sen üretmeye hazır mısın?",
            "Grupta %SUBJECT% sorusu seni bekliyor. Adımını at, çözüm önerini paylaş.",
            "%SENDER% az önce %SUBJECT% sorusu bıraktı. İpucu ya da tam çözüm ile destek ol."
        )

        private fun markAndCheckShouldNotify(dedupKey: String?): Boolean {
            if (dedupKey.isNullOrBlank()) return true
            synchronized(postedNotificationKeys) {
                if (postedNotificationKeys.contains(dedupKey)) return false
                if (postedNotificationKeys.size > 400) {
                    val iter = postedNotificationKeys.iterator()
                    repeat(100) {
                        if (iter.hasNext()) {
                            iter.next()
                            iter.remove()
                        }
                    }
                }
                postedNotificationKeys.add(dedupKey)
                return true
            }
        }

        private fun hasNotificationPermission(appCtx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun resolveSharkIcons(appCtx: Context): Pair<Int, android.graphics.Bitmap?> {
            val activeShark = AppIconManager.getActiveIcon(appCtx)
            val largeIconBitmap = runCatching {
                BitmapFactory.decodeResource(appCtx.resources, activeShark.iconRes)
            }.getOrNull()
            val smallIconRes = if (activeShark.notificationSmallIconRes != 0) {
                activeShark.notificationSmallIconRes
            } else {
                R.drawable.ic_notification_original
            }
            return smallIconRes to largeIconBitmap
        }

        fun showStudyInviteNotification(
            context: Context,
            fromName: String,
            message: String,
            groupId: String? = null,
            nudgeId: String? = null
        ) {
            val appCtx = context.applicationContext
            SharkNotificationEngine.init(appCtx)
            if (!markAndCheckShouldNotify(nudgeId?.takeIf { it.isNotBlank() }?.let { "nudge:$it" })) return
            if (!hasNotificationPermission(appCtx)) return

            val notificationManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val cleanSender = fromName.trim().ifBlank { "Arkadaşın" }
            val finalTitle = "$cleanSender seni dürttü"
            val finalBody = message.ifBlank {
                "$cleanSender seni birlikte çalışmaya dürttü. Odak oturumunu başlatabilirsin."
            }

            val reqSeed = 1_000_000 + (abs((nudgeId ?: cleanSender).hashCode()) % 899_990)
            val openLeagueIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = ACTION_STUDY_INVITE_NUDGE
                putExtra("EXTRA_TARGET_ROUTE", "shark_league")
                if (!groupId.isNullOrBlank()) putExtra("EXTRA_GROUP_ID", groupId)
                if (!nudgeId.isNullOrBlank()) putExtra("EXTRA_NUDGE_ID", nudgeId)
            }
            val openLeaguePendingIntent = PendingIntent.getActivity(
                appCtx,
                reqSeed,
                openLeagueIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val startFocusIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = ACTION_STUDY_INVITE_NUDGE
                putExtra("EXTRA_START_FOCUS", true)
                putExtra("EXTRA_TARGET_ROUTE", "home")
                if (!groupId.isNullOrBlank()) putExtra("EXTRA_GROUP_ID", groupId)
                if (!nudgeId.isNullOrBlank()) putExtra("EXTRA_NUDGE_ID", nudgeId)
            }
            val startFocusPendingIntent = PendingIntent.getActivity(
                appCtx,
                reqSeed + 1,
                startFocusIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val (smallIconRes, largeIconBitmap) = resolveSharkIcons(appCtx)
            val builder = NotificationCompat.Builder(appCtx, CHANNEL_STUDY_INVITE_ID)
                .setSmallIcon(smallIconRes)
                .apply { if (largeIconBitmap != null) setLargeIcon(largeIconBitmap) }
                .setColor(ContextCompat.getColor(appCtx, R.color.shark_blue_primary))
                .setContentTitle(finalTitle)
                .setContentText(finalBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(finalBody))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setAutoCancel(true)
                .setContentIntent(openLeaguePendingIntent)
                .addAction(0, "Odak Başlat", startFocusPendingIntent)

            val notificationId = if (!nudgeId.isNullOrBlank()) {
                1_000_000 + (abs(nudgeId.hashCode()) % 899_999)
            } else {
                1_000_000 + (abs(System.currentTimeMillis().toInt()) % 899_999)
            }

            runCatching {
                notificationManager.notify(notificationId, builder.build())
            }.onFailure {
                Log.w("SharkMessaging", "Unable to post study nudge notification: ${it.message}")
            }
        }

        fun showQuestionSharedNotification(
            context: Context,
            senderName: String,
            subject: String,
            topic: String = "",
            groupName: String = "",
            messageId: String? = null,
            groupCode: String? = null,
            isDm: Boolean = false
        ) {
            if (ActiveChatTracker.isViewingChannel(groupCode)) return
            val appCtx = context.applicationContext
            SharkNotificationEngine.init(appCtx)
            if (!markAndCheckShouldNotify(messageId?.takeIf { it.isNotBlank() }?.let { "question:$it" })) return
            if (!hasNotificationPermission(appCtx)) return

            val notificationManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val cleanSender = senderName.trim().ifBlank { "Grup Üyesi" }
            val cleanSubject = subject.trim().ifBlank { "Ders" }
            val cleanTopic = topic.trim()
            val subjectLabel = if (cleanTopic.isNotBlank()) "$cleanSubject ($cleanTopic)" else cleanSubject
            val cleanGroup = groupName.trim()

            val title = when {
                isDm -> "$cleanSender sana yeni bir soru gönderdi"
                cleanGroup.isNotBlank() -> "$cleanGroup • Yeni Soru Paylaşımı"
                else -> "Grupta Yeni Soru Var"
            }

            val seedStr = messageId ?: "$cleanSender:$cleanSubject:$cleanTopic"
            val template = QUESTION_PROMPTS[abs(seedStr.hashCode()) % QUESTION_PROMPTS.size]
            val body = if (isDm) {
                "$cleanSender senden $subjectLabel konusunda destek bekliyor. Sen çözebilecek misin?"
            } else {
                template
                    .replace("%SENDER%", cleanSender)
                    .replace("%SUBJECT%", subjectLabel)
            }

            val reqSeed = 2_000_000 + (abs(seedStr.hashCode()) % 899_990)
            val openChatIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = ACTION_OPEN_LEAGUE_CHAT
                putExtra("EXTRA_TARGET_ROUTE", "shark_league")
                if (!groupCode.isNullOrBlank()) putExtra("EXTRA_GROUP_ID", groupCode)
                if (!messageId.isNullOrBlank()) putExtra("EXTRA_MESSAGE_ID", messageId)
            }
            val openChatPendingIntent = PendingIntent.getActivity(
                appCtx,
                reqSeed,
                openChatIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val (smallIconRes, largeIconBitmap) = resolveSharkIcons(appCtx)
            val builder = NotificationCompat.Builder(appCtx, CHANNEL_LEAGUE_QUESTIONS_ID)
                .setSmallIcon(smallIconRes)
                .apply { if (largeIconBitmap != null) setLargeIcon(largeIconBitmap) }
                .setColor(ContextCompat.getColor(appCtx, R.color.shark_blue_primary))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openChatPendingIntent)

            val notificationId = if (!messageId.isNullOrBlank()) {
                2_000_000 + (abs(messageId.hashCode()) % 899_999)
            } else {
                2_000_000 + (abs(System.currentTimeMillis().toInt()) % 899_999)
            }

            runCatching {
                notificationManager.notify(notificationId, builder.build())
            }.onFailure {
                Log.w("SharkMessaging", "Unable to post question notification: ${it.message}")
            }
        }

        fun showChatMessageNotification(
            context: Context,
            senderName: String,
            messageText: String,
            groupName: String = "",
            messageId: String? = null,
            groupCode: String? = null,
            isDm: Boolean = false
        ) {
            if (ActiveChatTracker.isViewingChannel(groupCode)) return
            val appCtx = context.applicationContext
            SharkNotificationEngine.init(appCtx)
            if (!markAndCheckShouldNotify(messageId?.takeIf { it.isNotBlank() }?.let { "chat:$it" })) return
            if (!hasNotificationPermission(appCtx)) return

            val notificationManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val cleanSender = senderName.trim().ifBlank { "Grup Üyesi" }
            val cleanGroup = groupName.trim()
            val cleanText = messageText.trim().ifBlank { "Yeni mesaj gönderdi." }

            val title = when {
                isDm -> cleanSender
                cleanGroup.isNotBlank() -> "$cleanGroup • $cleanSender"
                else -> "$cleanSender yeni mesaj gönderdi"
            }

            val seedStr = messageId ?: "$cleanSender:$cleanText"
            val reqSeed = 3_000_000 + (abs(seedStr.hashCode()) % 899_990)
            val openChatIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = ACTION_OPEN_LEAGUE_CHAT
                putExtra("EXTRA_TARGET_ROUTE", "shark_league")
                if (!groupCode.isNullOrBlank()) putExtra("EXTRA_GROUP_ID", groupCode)
                if (!messageId.isNullOrBlank()) putExtra("EXTRA_MESSAGE_ID", messageId)
            }
            val openChatPendingIntent = PendingIntent.getActivity(
                appCtx,
                reqSeed,
                openChatIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val (smallIconRes, largeIconBitmap) = resolveSharkIcons(appCtx)
            val builder = NotificationCompat.Builder(appCtx, CHANNEL_CHAT_MESSAGES_ID)
                .setSmallIcon(smallIconRes)
                .apply { if (largeIconBitmap != null) setLargeIcon(largeIconBitmap) }
                .setColor(ContextCompat.getColor(appCtx, R.color.shark_blue_primary))
                .setContentTitle(title)
                .setContentText(cleanText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(cleanText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openChatPendingIntent)

            val notificationId = if (!messageId.isNullOrBlank()) {
                3_000_000 + (abs(messageId.hashCode()) % 899_999)
            } else {
                3_000_000 + (abs(System.currentTimeMillis().toInt()) % 899_999)
            }

            runCatching {
                notificationManager.notify(notificationId, builder.build())
            }.onFailure {
                Log.w("SharkMessaging", "Unable to post chat notification: ${it.message}")
            }
        }
        fun cancelChatNotifications(context: Context, channelId: String?) {
            runCatching {
               if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching
               val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return@runCatching
                nm.activeNotifications.forEach { sbn ->
                    val notifChannel = NotificationCompat.getChannelId(sbn.notification)
                    if (notifChannel == CHANNEL_CHAT_MESSAGES_ID || notifChannel == CHANNEL_LEAGUE_QUESTIONS_ID) {
                        if (sbn.tag != null) nm.cancel(sbn.tag, sbn.id) else nm.cancel(sbn.id)
                    }
                }
                
            }
        }
    }
}
