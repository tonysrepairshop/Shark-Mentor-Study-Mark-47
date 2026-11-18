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
import com.example.sharkmentor.R
import com.example.sharkmentor.core.util.AppIconManager
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.repository.FirebaseToRoomSyncManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
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

        val syncType = remoteMessage.data["type"] ?: remoteMessage.data["triggerType"]
        if (syncType == "SYNC_STUDY_PLAN" || syncType == "SCHEDULE") {
            serviceScope.launch {
                syncManager.syncRemotePlanToRoom(force = true)
            }
        }

        if (syncType == "NUDGE" || syncType == "STUDY_INVITE") {
            val fromName = remoteMessage.data["fromName"]
                ?: remoteMessage.data["senderName"]
                ?: "Arkadaşın"
            val body = remoteMessage.notification?.body
                ?: remoteMessage.data["body"]
                ?: remoteMessage.data["message"]
                ?: "$fromName sizi birlikte çalışmaya davet ediyor. Odak oturumunu başlatabilirsiniz."
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
        const val CHANNEL_STUDY_INVITE_ID = "shark_study_invites"

        fun showStudyInviteNotification(
            context: Context,
            fromName: String,
            message: String,
            groupId: String? = null,
            nudgeId: String? = null
        ) {
            val appCtx = context.applicationContext
            val notificationManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_STUDY_INVITE_ID,
                    "Çalışma Davetleri",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Arkadaşlarından ve çalışma gruplarından gelen canlı çalışma davetleri"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val cleanSender = fromName.trim().ifBlank { "Arkadaşın" }
            val finalTitle = "$cleanSender çalışma daveti gönderdi"
            val finalBody = message.ifBlank {
                "$cleanSender sizi birlikte çalışmaya davet ediyor. Odak oturumunu başlatabilirsiniz."
            }

            val reqSeed = ((nudgeId ?: cleanSender).hashCode() and 0x0FFFFFF0)
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

            val activeShark = AppIconManager.getActiveIcon(appCtx)
            val largeIconBitmap = runCatching {
                BitmapFactory.decodeResource(appCtx.resources, activeShark.iconRes)
            }.getOrNull()
            val smallIconRes = if (activeShark.notificationSmallIconRes != 0) {
                activeShark.notificationSmallIconRes
            } else {
                R.drawable.ic_notification_original
            }

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
                (nudgeId.hashCode() and 0x7FFFFFFF).coerceAtLeast(2000)
            } else {
                (System.currentTimeMillis().toInt() and 0x7FFFFFFF).coerceAtLeast(2000)
            }

            runCatching {
                notificationManager.notify(notificationId, builder.build())
            }.onFailure {
                Log.w("SharkMessaging", "Unable to post study invite notification: ${it.message}")
            }
        }
    }
}
