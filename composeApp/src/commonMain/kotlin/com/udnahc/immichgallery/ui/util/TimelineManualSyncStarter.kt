package com.udnahc.immichgallery.ui.util

import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncRequest

interface TimelineManualSyncStarter {
    fun start(request: TimelineManualSyncRequest): Boolean
}
