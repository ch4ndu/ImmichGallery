package com.udnahc.immichgallery.ui.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.lighthousegames.logging.logging

private const val POST_NOTIFICATIONS_REQUEST_CODE = 2401

class AndroidSyncActivityNotifier(
    private val context: Context
) : PlatformSyncActivityNotifier {
    private val log = logging("PlatformSyncActivityNotifier")
    private val mainHandler = Handler(Looper.getMainLooper())
    private var notificationPermissionRequested = false

    override fun onActiveSyncCountChanged(activeCount: Int) {
        mainHandler.post {
            if (activeCount > 0) {
                requestNotificationPermissionIfNeeded()
            }
            SyncForegroundService.update(context, activeCount)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionRequested) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        val activity = activityRef?.get() ?: return
        notificationPermissionRequested = true
        runCatching {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                POST_NOTIFICATIONS_REQUEST_CODE
            )
        }.onFailure { e ->
            log.e(e) { "Failed to request notification permission" }
        }
    }
}
