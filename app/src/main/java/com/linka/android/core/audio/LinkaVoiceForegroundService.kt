package com.linka.android.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.linka.android.LinkaApplication
import com.linka.android.MainActivity
import com.linka.android.R

/**
 * Keeps an accepted LINKA microphone call eligible to continue while the
 * activity is backgrounded. The process-wide call/audio runtime is owned by
 * LinkaApplication; this service supplies the Android foreground lifecycle
 * and a persistent, user-visible stop action.
 */
class LinkaVoiceForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as? LinkaApplication)?.callController?.endCall()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                serviceType
            )
        } catch (error: SecurityException) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LINKA calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing nearby voice calls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, LinkaVoiceForegroundService::class.java)
            .setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP_CALL,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("LINKA voice call")
            .setContentText("Direct nearby call in progress")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "End call",
                stopPendingIntent
            )
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "linka_active_calls"
        private const val NOTIFICATION_ID = 7041
        private const val REQUEST_OPEN_APP = 7042
        private const val REQUEST_STOP_CALL = 7043
        private const val ACTION_START = "com.linka.android.action.START_VOICE_CALL"
        private const val ACTION_STOP = "com.linka.android.action.STOP_VOICE_CALL"

        fun start(context: Context) {
            val intent = Intent(context, LinkaVoiceForegroundService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkaVoiceForegroundService::class.java))
        }
    }
}
