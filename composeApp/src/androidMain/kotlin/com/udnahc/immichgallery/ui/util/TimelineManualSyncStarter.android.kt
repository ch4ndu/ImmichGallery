package com.udnahc.immichgallery.ui.util

import android.content.Context
import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncRequest

class PlatformTimelineManualSyncStarter(
    private val context: Context
) : TimelineManualSyncStarter {
    override fun start(request: TimelineManualSyncRequest): Boolean =
        SyncForegroundService.startManualTimelineSync(context, request)
}
