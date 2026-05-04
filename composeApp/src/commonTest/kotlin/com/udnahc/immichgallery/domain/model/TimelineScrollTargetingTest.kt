package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineScrollTargetingTest {
    @Test
    fun visibleBucketIndexesKeepTopToBottomDistinctOrder() {
        val displayItems = listOf(
            header(bucketIndex = 0),
            placeholder(bucketIndex = 0),
            header(bucketIndex = 1),
            placeholder(bucketIndex = 1),
            placeholder(bucketIndex = 1),
            header(bucketIndex = 2)
        )

        val buckets = visibleBucketIndexesForDisplayIndexes(
            displayItems = displayItems,
            visibleItemIndexes = listOf(1, 2, 3, 5)
        )
        val indexedBuckets = visibleBucketIndexesForDisplayIndexes(
            displayIndex = buildTimelineDisplayIndex(displayItems),
            visibleItemIndexes = listOf(1, 2, 3, 5)
        )

        assertEquals(listOf(0, 1, 2), buckets)
        assertEquals(buckets, indexedBuckets)
    }

    @Test
    fun photoGridDisplayIndexCanBeSharedByDetailScreens() {
        val displayItems: List<PhotoGridDisplayItem> = listOf(
            header(bucketIndex = 0),
            placeholder(bucketIndex = 0),
            header(bucketIndex = 1),
            placeholder(bucketIndex = 1)
        )
        val displayIndex = buildPhotoGridDisplayIndex(displayItems)

        val buckets = visibleBucketIndexesForDisplayIndexes(
            displayIndex = displayIndex,
            visibleItemIndexes = listOf(1, 2, 3)
        )

        assertEquals(listOf(0, 1), buckets)
    }

    @Test
    fun scrollFractionMapsToExpectedBuckets() {
        val pageIndex = TimelinePageIndex(
            bucketStartPages = listOf(0, 10, 30),
            bucketPageCounts = listOf(10, 20, 10),
            totalPages = 40
        )
        val displayItems = listOf(
            header(bucketIndex = 0),
            placeholder(bucketIndex = 0),
            header(bucketIndex = 1),
            placeholder(bucketIndex = 1),
            placeholder(bucketIndex = 1),
            header(bucketIndex = 2),
            placeholder(bucketIndex = 2)
        )

        val displayIndex = buildTimelineDisplayIndex(displayItems)

        assertEquals(0, timelineScrollTargetForFraction(pageIndex, displayItems, 0f)?.bucketIndex)
        assertEquals(1, timelineScrollTargetForFraction(pageIndex, displayItems, 0.5f)?.bucketIndex)
        assertEquals(2, timelineScrollTargetForFraction(pageIndex, displayItems, 1f)?.bucketIndex)
        assertEquals(0, timelineScrollTargetForFraction(pageIndex, displayIndex, 0f)?.bucketIndex)
        assertEquals(1, timelineScrollTargetForFraction(pageIndex, displayIndex, 0.5f)?.bucketIndex)
        assertEquals(2, timelineScrollTargetForFraction(pageIndex, displayIndex, 1f)?.bucketIndex)
    }

    @Test
    fun bucketLabelFractionUsesPageCountsNotDisplayItemCounts() {
        val pageIndex = TimelinePageIndex(
            bucketStartPages = listOf(0, 10, 110),
            bucketPageCounts = listOf(10, 100, 10),
            totalPages = 120
        )

        assertEquals(1, bucketIndexForTimelineFraction(pageIndex, 0.5f))
    }

    @Test
    fun displayIndexMapsBackToPageFraction() {
        val pageIndex = TimelinePageIndex(
            bucketStartPages = listOf(0, 10, 30),
            bucketPageCounts = listOf(10, 20, 10),
            totalPages = 40
        )
        val displayItems = listOf(
            header(bucketIndex = 0),
            placeholder(bucketIndex = 0),
            header(bucketIndex = 1),
            placeholder(bucketIndex = 1),
            placeholder(bucketIndex = 1),
            header(bucketIndex = 2)
        )

        val timelineDisplayIndex = buildTimelineDisplayIndex(displayItems)

        assertEquals(0.25f, timelineScrollFractionForDisplayIndex(pageIndex, displayItems, displayIndex = 2))
        assertEquals(
            0.25f,
            timelineScrollFractionForDisplayIndex(pageIndex, timelineDisplayIndex, displayIndex = 2)
        )
    }

    @Test
    fun scrollFractionReturnsNullWhenPageIndexIsEmpty() {
        assertNull(timelineScrollTargetForFraction(TimelinePageIndex(), emptyList(), 0.5f))
    }

    @Test
    fun estimatedDisplayIndexLandsInsideBucket() {
        val displayItems = listOf(
            header(bucketIndex = 0),
            placeholder(bucketIndex = 0),
            header(bucketIndex = 1),
            placeholder(bucketIndex = 1),
            placeholder(bucketIndex = 1),
            placeholder(bucketIndex = 1)
        )

        val displayIndex = buildTimelineDisplayIndex(displayItems)

        assertEquals(4, estimatedDisplayIndexForBucketFraction(displayItems, bucketIndex = 1, bucketFraction = 0.5f))
        assertEquals(4, estimatedDisplayIndexForBucketFraction(displayIndex, bucketIndex = 1, bucketFraction = 0.5f))
    }

    @Test
    fun priorityOrderKeepsTargetVisibleNearbyThenBackground() {
        val priority = prioritizedTimelineBucketIndexes(
            bucketCount = 8,
            targetBucketIndex = 3,
            visibleBucketIndexes = listOf(5, 6),
            nearbyRadius = 1
        )

        assertEquals(listOf(3, 5, 6, 2, 4, 7, 0, 1), priority)
    }

    private fun header(bucketIndex: Int): HeaderItem =
        HeaderItem(
            gridKey = "h_$bucketIndex",
            bucketIndex = bucketIndex,
            sectionLabel = "$bucketIndex",
            label = "$bucketIndex"
        )

    private fun placeholder(bucketIndex: Int): PlaceholderItem =
        PlaceholderItem(
            gridKey = "p_$bucketIndex",
            bucketIndex = bucketIndex,
            sectionLabel = "$bucketIndex"
        )
}
