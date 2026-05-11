package com.udnahc.immichgallery.ui.screen.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun cacheOnlyExperimentAllowsOnlyColdAndManualServerRefresh() {
        TimelineServerRefreshSource.entries.forEach { source ->
            val expected = source == TimelineServerRefreshSource.ColdSync ||
                source == TimelineServerRefreshSource.ManualRefresh
            assertEquals(
                expected,
                allowTimelineServerRefresh(
                    disableNonManualServerSync = true,
                    source = source
                ),
                "source=$source"
            )
        }
    }

    @Test
    fun disabledCacheOnlyExperimentAllowsEveryServerRefreshSource() {
        TimelineServerRefreshSource.entries.forEach { source ->
            assertEquals(
                true,
                allowTimelineServerRefresh(
                    disableNonManualServerSync = false,
                    source = source
                ),
                "source=$source"
            )
        }
    }

    @Test
    fun unchangedBucketEmissionIsNotChanged() {
        val buckets = listOf(bucket("2026-05", count = 10))

        assertFalse(timelineBucketsChanged(buckets, buckets.toList()))
    }

    @Test
    fun changedBucketEmissionIsChanged() {
        val current = listOf(bucket("2026-05", count = 10))
        val next = listOf(bucket("2026-05", count = 11))

        assertTrue(timelineBucketsChanged(current, next))
    }

    private fun bucket(
        timeBucket: String,
        count: Int
    ) = com.udnahc.immichgallery.domain.model.TimelineBucket(
        displayLabel = timeBucket,
        timeBucket = timeBucket,
        count = count
    )
}
