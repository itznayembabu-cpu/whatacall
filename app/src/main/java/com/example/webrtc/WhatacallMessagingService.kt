package com.example.webrtc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.UserRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WhatacallMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        serviceScope.launch {
            try {
                UserRepository(applicationContext).updateFcmToken(token)
            } catch (e: Exception) {
                Log.w(TAG, "Failed saving refreshed FCM token: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"] ?: "message"
        when (type) {
            "incoming_call" -> {
                val callId = data["callId"] ?: return
                val callerName = data["callerName"] ?: "Incoming Whatacall"
                val callType = data["callType"] ?: "audio"
                showIncomingCallNotification(callId, callerName, callType)
            }
            "missed_call" -> {
                val callerName = data["callerName"] ?: "Unknown"
                val callType = data["callType"] ?: "audio"
                showSimpleNotification(
                    channelId = CHANNEL_CALLS,
                    title = "Missed $callType call",
                    body = "You missed a call from $callerName",
                    notificationId = (System.currentTimeMillis() % 10000).toInt()
                )
            }
            else -> {
                val title = message.notification?.title ?: data["senderName"] ?: "Whatacall"
                val body = message.notification?.body ?: data["preview"] ?: "New message"
                val chatId = data["chatId"]
                showSimpleNotification(
                    channelId = CHANNEL_MESSAGES,
                    title = title,
                    body = body,
                    notificationId = (System.currentTimeMillis() % 10000).toInt(),
                    chatId = chatId
                )
            }
        }
    }

    private fun showIncomingCallNotification(callId: String, callerName: String, callType: String) {
        ensureChannels()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INCOMING_CALL_ID, callId)
        }
        val pending = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = if (callType == "video") "Incoming Whatacall video call" else "Incoming Whatacall voice call"
        val notification = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.whatacall_logo)
            .setContentTitle(callerName)
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(callId.hashCode(), notification)
    }

    private fun showSimpleNotification(
        channelId: String,
        title: String,
        body: String,
        notificationId: Int,
        chatId: String? = null
    ) {
        ensureChannels()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (chatId != null) putExtra(EXTRA_CHAT_ID, chatId)
        }
        val pending = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.whatacall_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_CALLS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_CALLS,
                        "Incoming Calls",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            if (nm.getNotificationChannel(CHANNEL_MESSAGES) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_MESSAGES,
                        "Messages",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }

    companion object {
        const val TAG = "WhatacallFCM"
        const val CHANNEL_CALLS = "whatacall_incoming_calls"
        const val CHANNEL_MESSAGES = "whatacall_messages"
        const val EXTRA_INCOMING_CALL_ID = "extra_incoming_call_id"
        const val EXTRA_CHAT_ID = "extra_chat_id"
    }
}
