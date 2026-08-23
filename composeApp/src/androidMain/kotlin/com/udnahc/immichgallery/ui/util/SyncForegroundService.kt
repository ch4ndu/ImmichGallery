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
import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncCoordinator
import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncRequest
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import org.lighthousegames.logging.logging

class SyncForegroundService : Service() {
    private val log = logging("SyncForegroundService")
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MANUAL_TIMELINE_SYNC) {
            // Satisfy the foreground-service startup deadline while the
            // coordinator registers the manual sync with the shared tracker.
            startForegroundNow(activeCount = maxOf(currentTrackedCount(), 1))
            val request = intent.toManualSyncRequest() ?: run {
                reconcileForegroundState(startId)
                return START_NOT_STICKY
            }
            serviceScope.launch {
                runCatching {
                    KoinPlatform.getKoin()
                        .get<TimelineManualSyncCoordinator>()
                        .run(request)
                }.onFailure { e ->
                    log.e { "Manual Timeline sync failed in foreground service" }
                }
                reconcileForegroundState(startId)
            }
            return START_NOT_STICKY
        }

        reconcileForegroundState(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceStarted = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNow(activeCount: Int) {
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
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        log.d { "Sync foreground service timed out type=<redacted>" }
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

    private fun reconcileForegroundState(startId: Int) {
        val activeCount = currentTrackedCount()
        if (activeCount > 0) {
            startForegroundNow(activeCount)
        } else {
            stopSyncForegroundService(startId)
        }
    }

    private fun currentTrackedCount(): Int =
        runCatching {
            KoinPlatform.getKoin().get<SyncActivityTracker>().activeCount.value
        }.getOrDefault(0)

    companion object {
        private const val ACTION_UPDATE = "com.udnahc.immichgallery.sync.UPDATE"
        private const val ACTION_STOP = "com.udnahc.immichgallery.sync.STOP"
        private const val ACTION_MANUAL_TIMELINE_SYNC =
            "com.udnahc.immichgallery.sync.MANUAL_TIMELINE"
        private const val EXTRA_ACTIVE_COUNT = "active_count"
        private const val EXTRA_MANUAL_SYNC_REQUEST_ID = "manual_sync_request_id"
        private const val EXTRA_SESSION_GENERATION = "session_generation"
        private const val EXTRA_SESSION_OWNER_EPOCH = "session_owner_epoch"
        private const val EXTRA_GROUP_SIZE = "group_size"
        private const val EXTRA_COLUMN_COUNT = "column_count"
        private const val EXTRA_FAMILIES = "families"
        private const val EXTRA_MOSAIC_ENABLED = "mosaic_enabled"
        private const val EXTRA_CACHE_MOSAIC_RESULTS = "cache_mosaic_results"
        private const val EXTRA_HAS_GEOMETRY = "has_geometry"
        private const val EXTRA_AVAILABLE_WIDTH = "available_width"
        private const val EXTRA_MAX_ROW_HEIGHT = "max_row_height"
        private const val EXTRA_SPACING = "spacing"
        private const val EXTRA_HAD_BANNER_ERROR = "had_banner_error"
        private const val CHANNEL_ID = "sync_status"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var serviceStarted = false

        fun startManualTimelineSync(
            context: Context,
            request: TimelineManualSyncRequest
        ): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SyncForegroundService::class.java).apply {
                action = ACTION_MANUAL_TIMELINE_SYNC
                putExtra(EXTRA_MANUAL_SYNC_REQUEST_ID, request.requestId)
                putExtra(EXTRA_SESSION_GENERATION, request.sessionStamp.generation)
                putExtra(EXTRA_SESSION_OWNER_EPOCH, request.sessionStamp.ownerEpoch)
                putExtra(EXTRA_GROUP_SIZE, request.groupSize.apiValue)
                putExtra(EXTRA_COLUMN_COUNT, request.columnCount)
                putExtra(
                    EXTRA_FAMILIES,
                    request.families.map { it.persistedId }.toTypedArray()
                )
                putExtra(EXTRA_MOSAIC_ENABLED, request.mosaicEnabled)
                putExtra(EXTRA_CACHE_MOSAIC_RESULTS, request.cacheMosaicResults)
                putExtra(EXTRA_HAD_BANNER_ERROR, request.hadBannerError)
                val geometry = request.geometryRequest
                putExtra(EXTRA_HAS_GEOMETRY, geometry != null)
                if (geometry != null) {
                    putExtra(EXTRA_AVAILABLE_WIDTH, geometry.availableWidth)
                    putExtra(EXTRA_MAX_ROW_HEIGHT, geometry.maxRowHeight)
                    putExtra(EXTRA_SPACING, geometry.spacing)
                }
            }
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }.isSuccess
        }

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

private fun Intent.toManualSyncRequest(): TimelineManualSyncRequest? {
    val groupSizeValue = getStringExtra("group_size") ?: return null
    val groupSize = TimelineGroupSize.entries.firstOrNull { it.apiValue == groupSizeValue }
        ?: return null
    val families = getStringArrayExtra("families")
        ?.mapNotNull { MosaicTemplateFamily.fromPersistedId(it) }
        ?.toSet()
        ?.ifEmpty { MosaicTemplateFamily.defaultSet() }
        ?: MosaicTemplateFamily.defaultSet()
    val geometryRequest =
        if (getBooleanExtra("has_geometry", false)) {
            TimelineMosaicGeometryRequest(
                availableWidth = getFloatExtra("available_width", 0f),
                maxRowHeight = getFloatExtra("max_row_height", 0f),
                spacing = getFloatExtra("spacing", 0f)
            )
        } else {
            null
        }
    return TimelineManualSyncRequest(
        requestId = getLongExtra("manual_sync_request_id", Long.MIN_VALUE),
        sessionStamp = SessionOperationStamp(
            generation = getLongExtra("session_generation", Long.MIN_VALUE),
            ownerEpoch = getLongExtra("session_owner_epoch", Long.MIN_VALUE)
        ),
        groupSize = groupSize,
        columnCount = getIntExtra("column_count", 0),
        families = families,
        mosaicEnabled = getBooleanExtra("mosaic_enabled", false),
        cacheMosaicResults = getBooleanExtra("cache_mosaic_results", false),
        geometryRequest = geometryRequest,
        hadBannerError = getBooleanExtra("had_banner_error", false)
    )
}
