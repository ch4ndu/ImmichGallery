package com.udnahc.immichgallery.ui.util

class NoOpSyncActivityNotifier : PlatformSyncActivityNotifier {
    override fun onActiveSyncCountChanged(activeCount: Int) = Unit
}
