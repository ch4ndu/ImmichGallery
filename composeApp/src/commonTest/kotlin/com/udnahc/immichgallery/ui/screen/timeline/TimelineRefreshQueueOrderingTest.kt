package com.udnahc.immichgallery.ui.screen.timeline

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineRefreshQueueOrderingTest {
    @Test
    fun newerDragTargetDemotesOlderDragBuckets() {
        val ordering = reorderTimelineRefreshQueue(
            pendingBuckets = listOf("old_drag", "normal"),
            pendingDragBuckets = setOf("old_drag"),
            bucketsToQueue = listOf("new_drag", "new_neighbor"),
            priority = TimelineRefreshQueuePriority.ScrollbarDrag
        )

        assertEquals(listOf("new_drag", "new_neighbor", "normal", "old_drag"), ordering.pendingBuckets)
        assertEquals(setOf("old_drag", "new_drag", "new_neighbor"), ordering.pendingDragBuckets)
    }

    @Test
    fun stoppedBucketAndNeighborsPrecedeOlderDragAndRemainingWork() {
        val ordering = reorderTimelineRefreshQueue(
            pendingBuckets = listOf("old_drag", "normal_a", "normal_b"),
            pendingDragBuckets = setOf("old_drag"),
            bucketsToQueue = listOf("stopped", "near_prev", "near_next"),
            priority = TimelineRefreshQueuePriority.ScrollbarStop
        )

        assertEquals(
            listOf("stopped", "near_prev", "near_next", "old_drag", "normal_a", "normal_b"),
            ordering.pendingBuckets
        )
        assertEquals(setOf("old_drag"), ordering.pendingDragBuckets)
    }

    @Test
    fun visibleWorkRemovesQueuedBucketFromDragSet() {
        val ordering = reorderTimelineRefreshQueue(
            pendingBuckets = listOf("drag_bucket", "normal"),
            pendingDragBuckets = setOf("drag_bucket"),
            bucketsToQueue = listOf("drag_bucket"),
            priority = TimelineRefreshQueuePriority.Visible
        )

        assertEquals(listOf("drag_bucket", "normal"), ordering.pendingBuckets)
        assertEquals(emptySet(), ordering.pendingDragBuckets)
    }

    @Test
    fun runtimeMosaicIsDeferredDuringScrollWhenCacheResultsAreDisabled() {
        val selection = selectTimelineMosaicBucketsForScroll(
            requestedBuckets = listOf("visible", "neighbor"),
            visibleOrTargetBuckets = listOf("visible"),
            cacheMosaicResults = false,
            isScrollActive = true
        )

        assertEquals(emptyList(), selection.runnableBuckets)
        assertEquals(listOf("visible", "neighbor"), selection.deferredBuckets)
    }

    @Test
    fun cachedMosaicReadAllowsOnlyVisibleOrTargetBucketsDuringScroll() {
        val selection = selectTimelineMosaicBucketsForScroll(
            requestedBuckets = listOf("neighbor", "visible", "target"),
            visibleOrTargetBuckets = listOf("visible", "target"),
            cacheMosaicResults = true,
            isScrollActive = true
        )

        assertEquals(listOf("visible", "target"), selection.runnableBuckets)
        assertEquals(listOf("neighbor"), selection.deferredBuckets)
    }

    @Test
    fun mosaicWorkRunsAllRequestedBucketsAfterScrollSettles() {
        val selection = selectTimelineMosaicBucketsForScroll(
            requestedBuckets = listOf("visible", "neighbor"),
            visibleOrTargetBuckets = listOf("visible"),
            cacheMosaicResults = false,
            isScrollActive = false
        )

        assertEquals(listOf("visible", "neighbor"), selection.runnableBuckets)
        assertEquals(emptyList(), selection.deferredBuckets)
    }

    @Test
    fun cacheOffRuntimeMosaicIsRenderDemandOnly() {
        assertEquals(
            true,
            shouldComputeRuntimeTimelineMosaic(
                cacheMosaicResults = false,
                source = TimelineMosaicWorkSource.RenderDemand
            )
        )
        assertEquals(
            false,
            shouldComputeRuntimeTimelineMosaic(
                cacheMosaicResults = false,
                source = TimelineMosaicWorkSource.SyncPrecompute
            )
        )
        assertEquals(
            false,
            shouldComputeRuntimeTimelineMosaic(
                cacheMosaicResults = true,
                source = TimelineMosaicWorkSource.RenderDemand
            )
        )
    }

    @Test
    fun visibleBucketsPrecedeNeighborsInMosaicRuntimeOrder() {
        val orderedIndexes = orderedTimelineBucketIndexesForMosaic(
            visibleBucketIndexes = listOf(10, 11),
            targetBucketIndex = 12,
            fallbackVisibleBucketIndexes = emptyList(),
            bucketCount = 20,
            radius = 2
        )

        assertEquals(listOf(10, 11, 12, 9, 8, 13, 14), orderedIndexes)
    }

    @Test
    fun scrollSettleMergesVisibleBucketsBeforeDeferredMosaicWork() {
        val requestOrder = mergeTimelineMosaicRequestOrder(
            priorityBuckets = listOf("visible", "target", "neighbor"),
            deferredBuckets = listOf("old_deferred", "visible", "offscreen")
        )

        assertEquals(listOf("visible", "target", "neighbor", "old_deferred", "offscreen"), requestOrder)
    }
}
