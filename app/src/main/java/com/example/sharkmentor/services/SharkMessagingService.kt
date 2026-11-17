package com.example.sharkmentor.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.graphics.BitmapFactory
import com.example.sharkmentor.MainActivity
import com.example.sharkmentor.R
import com.example.sharkmentor.core.util.AppIconManager
import com.example.sharkmentor.core.util.GoogleServicesHelper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.example.sharkmentor.data.repository.FirebaseToRoomSyncManager

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
            GoogleServicesHelper.getFirebaseFirestore(this)?.collection("students")?.document(currentUserId)
                ?.update("fcmToken", token)
                ?.addOnSuccessListener {
                    Log.d("FCM", "Token updated in Firestore: $token")
                }
        } catch (e: Throwable) {
            Log.w("FCM", "Error updating token in Firestore: ${e.message}")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val syncType = remoteMessage.data["type"]
        if (syncType == "SYNC_STUDY_PLAN" || syncType == "SCHEDULE") {
            serviceScope.launch {
                syncManager.syncRemotePlanToRoom(force = true)
            }
        }

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "SharkMentor"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: "Koçun yeni bir görev atadı!"

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

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activeShark = AppIconManager.getActiveIcon(this)
        val largeIconBitmap = BitmapFactory.decodeResource(resources, activeShark.iconRes)
        val smallIconRes = if (activeShark.notificationSmallIconRes != 0) activeShark.notificationSmallIconRes else R.drawable.ic_notification_original

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(smallIconRes)
            .setLargeIcon(largeIconBitmap)
            .setColor(getColor(R.color.shark_blue_primary))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
