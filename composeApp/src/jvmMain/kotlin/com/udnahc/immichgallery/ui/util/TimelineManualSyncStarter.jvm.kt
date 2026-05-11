package com.udnahc.immichgallery.ui.util

import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncRequest

class PlatformTimelineManualSyncStarter : TimelineManualSyncStarter {
    override fun start(request: TimelineManualSyncRequest): Boolean = false
}
