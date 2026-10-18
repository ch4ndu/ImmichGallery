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
    fun settledScrollbarTargetPrecedesFinalVisibleBucketsWithoutNeighbors() {
        val orderedIndexes = orderedSettledTimelineBucketIndexes(
            targetBucketIndex = 12,
            visibleBucketIndexes = listOf(11, 12, 13),
            fallbackVisibleBucketIndexes = emptyList(),
            bucketCount = 20
        )

        assertEquals(listOf(12, 11, 13), orderedIndexes)
    }

    @Test
    fun settledBucketsUseLastVisibleFallbackWhenCurrentVisibilityIsEmpty() {
        val orderedIndexes = orderedSettledTimelineBucketIndexes(
            targetBucketIndex = null,
            visibleBucketIndexes = emptyList(),
            fallbackVisibleBucketIndexes = listOf(4, 5),
            bucketCount = 10
        )

        assertEquals(listOf(4, 5), orderedIndexes)
    }

    @Test
    fun cachedMaterializationChunksVisibleBucketsBeforePrefetchBuckets() {
        val chunks = timelineCachedMaterializationChunks(
            requestedBuckets = listOf("neighbor_before", "visible", "neighbor_after", "target"),
            priorityBuckets = listOf("visible", "target"),
            prefetchChunkSize = 2
        )

        assertEquals(
            listOf(
                listOf("visible", "target"),
                listOf("neighbor_before", "neighbor_after")
            ),
            chunks
        )
    }

    @Test
    fun cachedMaterializationChunksDeduplicateAndBoundPrefetchWork() {
        val chunks = timelineCachedMaterializationChunks(
            requestedBuckets = listOf("visible", "near_1", "near_2", "near_1", "near_3", "near_4"),
            priorityBuckets = listOf("visible", "visible"),
            prefetchChunkSize = 2
        )

        assertEquals(
            listOf(
                listOf("visible"),
                listOf("near_1", "near_2"),
                listOf("near_3", "near_4")
            ),
            chunks
        )
    }

    @Test
    fun cachedMaterializationClaimsCachedBucketsMissingMemory() {
        val claims = timelineCachedMaterializationClaims(
            requestedBuckets = listOf("cached", "loaded_missing", "memory", "unknown"),
            cachedBuckets = setOf("cached"),
            loadedBuckets = setOf("loaded_missing", "memory"),
            materializingBuckets = emptySet(),
            inMemoryBuckets = setOf("memory")
        )

        assertEquals(listOf("cached", "loaded_missing"), claims)
    }

    @Test
    fun cachedMaterializationClaimsSkipInMemoryAndInFlightBuckets() {
        val claims = timelineCachedMaterializationClaims(
            requestedBuckets = listOf("cached", "loaded_missing", "in_flight", "memory"),
            cachedBuckets = setOf("cached", "in_flight"),
            loadedBuckets = setOf("loaded_missing", "memory"),
            materializingBuckets = setOf("in_flight"),
            inMemoryBuckets = setOf("memory")
        )

        assertEquals(listOf("cached", "loaded_missing"), claims)
    }

    @Test
    fun cachedMaterializationClaimsAllowInMemoryBucketUntilRenderLoaded() {
        val claims = timelineCachedMaterializationClaims(
            requestedBuckets = listOf("memory_not_loaded", "memory_loaded"),
            cachedBuckets = setOf("memory_not_loaded", "memory_loaded"),
            loadedBuckets = setOf("memory_loaded"),
            materializingBuckets = emptySet(),
            inMemoryBuckets = setOf("memory_not_loaded", "memory_loaded")
        )

        assertEquals(listOf("memory_not_loaded"), claims)
    }

    @Test
    fun staleMaterializedRollbackDoesNotUndoNewerSameGenerationPublish() {
        val bucket = "bucket"
        val generationStamp = TimelineRuntimeBucketGenerationStamp(bucketGeneration = 1L)
        val (oldPublished, oldRollback) = BucketData()
            .publishMaterializedBucketsWithStamps(mapOf(bucket to generationStamp))
        val (newerPublished, _) = oldPublished
            .publishMaterializedBucketsWithStamps(mapOf(bucket to generationStamp))

        val afterOldRollback = newerPublished.rollbackMaterializedBucketsIfStillOwned(oldRollback)

        assertEquals(setOf(bucket), afterOldRollback.cachedBuckets)
        assertEquals(setOf(bucket), afterOldRollback.loadedBuckets)
        assertEquals(2, afterOldRollback.materializationRevisions[bucket])
        assertEquals(
            TimelineMaterializedBucketPublishOwner(
                bucketGenerationStamp = generationStamp,
                materializationRevision = 2
            ),
            afterOldRollback.materializationPublishOwners[bucket]
        )
    }

    @Test
    fun staleMaterializedRollbackRestoresPriorNewerBucketStateOnlyWhenStillOwned() {
        val bucket = "bucket"
        val staleGenerationStamp = TimelineRuntimeBucketGenerationStamp(bucketGeneration = 0L)
        val newerOwner = TimelineMaterializedBucketPublishOwner(
            bucketGenerationStamp = TimelineRuntimeBucketGenerationStamp(bucketGeneration = 1L),
            materializationRevision = 7
        )
        val newerState = BucketData(
            cachedBuckets = setOf(bucket),
            loadedBuckets = setOf(bucket),
            materializationRevisions = mapOf(bucket to 7),
            materializationPublishOwners = mapOf(bucket to newerOwner)
        )
        val (stalePublished, staleRollback) = newerState
            .publishMaterializedBucketsWithStamps(mapOf(bucket to staleGenerationStamp))

        val afterRollback = stalePublished.rollbackMaterializedBucketsIfStillOwned(staleRollback)

        assertEquals(newerState.cachedBuckets, afterRollback.cachedBuckets)
        assertEquals(newerState.loadedBuckets, afterRollback.loadedBuckets)
        assertEquals(newerState.materializationRevisions, afterRollback.materializationRevisions)
        assertEquals(newerState.materializationPublishOwners, afterRollback.materializationPublishOwners)
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
