package com.udnahc.immichgallery.ui.util

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
import com.udnahc.immichgallery.MainActivity
import com.udnahc.immichgallery.R
import org.lighthousegames.logging.logging

class SyncForegroundService : Service() {
    private val log = logging("SyncForegroundService")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 0) ?: 0
        if (intent?.action == ACTION_STOP || activeCount <= 0) {
            stopSyncForegroundService(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification(activeCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        serviceStarted = true
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        log.d { "Sync foreground service timed out type=$fgsType" }
        stopSyncForegroundService(startId)
    }

    private fun buildNotification(activeCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = resources.getQuantityString(
            R.plurals.sync_notification_text,
            activeCount,
            activeCount
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sync_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun stopSyncForegroundService(startId: Int) {
        serviceStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    companion object {
        private const val ACTION_UPDATE = "com.udnahc.immichgallery.sync.UPDATE"
        private const val ACTION_STOP = "com.udnahc.immichgallery.sync.STOP"
        private const val EXTRA_ACTIVE_COUNT = "active_count"
        private const val CHANNEL_ID = "sync_status"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var serviceStarted = false

        fun update(context: Context, activeCount: Int) {
            val appContext = context.applicationContext
            if (activeCount > 0) {
                val intent = Intent(appContext, SyncForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_ACTIVE_COUNT, activeCount)
                }
                val result = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !serviceStarted) {
                        appContext.startForegroundService(intent)
                    } else {
                        appContext.startService(intent)
                    }
                }
                if (result.isSuccess) {
                    serviceStarted = true
                }
                return
            }

            if (!serviceStarted) return
            val intent = Intent(appContext, SyncForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                appContext.startService(intent)
            }
        }
    }
}
