package com.example.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class CallForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CALL) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "Whatacall Call"
        val callType = intent?.getStringExtra(EXTRA_CALL_TYPE) ?: "audio"
        val callId = intent?.getStringExtra(EXTRA_CALL_ID) ?: ""

        val notification = buildNotification(peerName, callType, callId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = if (callType == "video") {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(peerName: String, callType: String, callId: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_ID, callId)
        }
        val openPending = PendingIntent.getActivity(
            this,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_STOP_CALL
        }
        val endPending = PendingIntent.getService(
            this,
            102,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val label = if (callType == "video") "Active video call" else "Active voice call"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.whatacall_logo)
            .setContentTitle(peerName)
            .setContentText("$label • End-to-end encrypted")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endPending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Active Whatacall Calls",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Ongoing audio and video call controls"
                    }
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "whatacall_active_call"
        const val NOTIFICATION_ID = 4102
        const val ACTION_STOP_CALL = "com.example.webrtc.STOP_CALL"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_CALL_TYPE = "extra_call_type"
        const val EXTRA_CALL_ID = "extra_call_id"

        fun start(context: Context, callId: String, peerName: String, callType: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_CALL_TYPE, callType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
