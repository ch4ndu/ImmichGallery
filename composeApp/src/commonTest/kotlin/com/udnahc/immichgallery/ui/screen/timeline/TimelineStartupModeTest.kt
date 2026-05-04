package com.udnahc.immichgallery.ui.screen.timeline

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineStartupModeTest {
    @Test
    fun noBucketMetadataStartsCold() {
        assertEquals(
            TimelineStartupMode.Cold,
            timelineStartupMode(
                hasCachedBuckets = false,
                hasCompletedColdSync = false,
                isFullRefresh = false
            )
        )
    }

    @Test
    fun bucketMetadataWithoutColdCompleteMarkerStillStartsCold() {
        assertEquals(
            TimelineStartupMode.Cold,
            timelineStartupMode(
                hasCachedBuckets = true,
                hasCompletedColdSync = false,
                isFullRefresh = true
            )
        )
    }

    @Test
    fun completedColdSyncAllowsCachedLaunch() {
        assertEquals(
            TimelineStartupMode.CachedLaunch,
            timelineStartupMode(
                hasCachedBuckets = true,
                hasCompletedColdSync = true,
                isFullRefresh = false
            )
        )
    }

    @Test
    fun completedColdSyncAllowsManualRefresh() {
        assertEquals(
            TimelineStartupMode.ManualRefresh,
            timelineStartupMode(
                hasCachedBuckets = true,
                hasCompletedColdSync = true,
                isFullRefresh = true
            )
        )
    }
}
