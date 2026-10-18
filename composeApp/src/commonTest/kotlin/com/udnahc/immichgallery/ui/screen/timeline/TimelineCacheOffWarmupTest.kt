package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PHOTO_GRID_SECTION_HEADER_HEIGHT_DP
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.buildTimelineDisplayIndex
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineCacheOffWarmupTest {
    @Test
    fun warmupWalksNearestFirstAndStopsAtScreenBudgetPerDirection() {
        val heights = List(20) { 500f }

        val window = selectCacheOffWarmupBucketIndexes(
            settledIndexes = listOf(10),
            bucketHeights = heights,
            viewportHeight = 1000f,
            screensPerDirection = 2f,
            maxBucketsPerDirection = 6,
            isComputed = { false }
        )

        assertEquals(listOf(11, 9, 12, 8, 13, 7, 14, 6), window)
    }

    @Test
    fun warmupRespectsBucketCapAndSkipsComputedButCountsTheirHeight() {
        val heights = List(20) { 10f }

        val window = selectCacheOffWarmupBucketIndexes(
            settledIndexes = listOf(5, 6),
            bucketHeights = heights,
            viewportHeight = 1000f,
            screensPerDirection = 4f,
            maxBucketsPerDirection = 3,
            isComputed = { it == 8 || it == 4 }
        )

        assertEquals(listOf(7, 3, 9, 2), window)
    }

    @Test
    fun warmupStopsAtListEdges() {
        val window = selectCacheOffWarmupBucketIndexes(
            settledIndexes = listOf(0),
            bucketHeights = List(3) { 1f },
            viewportHeight = 1000f,
            screensPerDirection = 4f,
            maxBucketsPerDirection = 6,
            isComputed = { false }
        )

        assertEquals(listOf(1, 2), window)
    }

    @Test
    fun bucketOrdinalsSplitInProportionToEstimateHeightsAndCoverWholeRange() {
        val pieces = listOf(placeholder("a", 300f), placeholder("b", 100f), placeholder("c", 600f))

        val split = pieces.withBucketOrdinals(base = 40, count = 10)

        assertEquals(listOf(40, 43, 44), split.map { it.sourceStartIndex })
        assertEquals(listOf(3, 1, 6), split.map { it.sourceCount })
        assertEquals(true, split.all { it.sourceIsBucketOrdinal })
    }

    @Test
    fun bucketHeightsIncludeHeadersItemsAndSpacing() {
        val items = listOf(
            HeaderItem("h", 0, "s", "label"),
            placeholder("p", 100f).copy(bucketIndex = 0),
            placeholder("q", 50f).copy(bucketIndex = 1)
        )

        val heights = timelineBucketDisplayHeights(items, 2)

        assertEquals(PHOTO_GRID_SECTION_HEADER_HEIGHT_DP + 100f + GRID_SPACING_DP * 2, heights[0])
        assertEquals(50f + GRID_SPACING_DP, heights[1])
    }

    @Test
    fun timelineDisplayIndexMapsSectionLocalRangesAndEstimatesToBucketOrdinals() {
        val items = listOf(
            HeaderItem("h_b", 0, "b|month", "b"),
            HeaderItem("dh_1", 0, "b|day|1", "1"),
            band(start = 0, count = 3, section = "b|day|1"),
            band(start = 3, count = 2, section = "b|day|1"),
            HeaderItem("dh_2", 0, "b|day|2", "2"),
            placeholder("est", 400f).copy(
                sectionLabel = "b|day|2",
                sourceStartIndex = 5,
                sourceCount = 4,
                sourceIsBucketOrdinal = true
            ),
            HeaderItem("dh_3", 0, "b|day|3", "3"),
            placeholder("mr_range", 120f).copy(sectionLabel = "b|day|3", sourceStartIndex = 0, sourceCount = 6)
        )

        val index = buildTimelineDisplayIndex(items)

        assertEquals(listOf(-1, -1, 0, 3, -1, 5, -1, 9), index.bucketOrdinalStartByDisplayIndex)
        assertEquals(listOf(true, true, true, true, true, false, true, true), index.stableAnchorByDisplayIndex)
        assertEquals(400f, index.heightDpByDisplayIndex[5])
    }

    private fun placeholder(key: String, height: Float) =
        PlaceholderItem(gridKey = key, bucketIndex = 0, sectionLabel = "s", estimatedHeight = height)

    private fun band(start: Int, count: Int, section: String) = MosaicBandItem(
        gridKey = "mr_${section}_$start",
        bucketIndex = 0,
        sectionLabel = section,
        tiles = emptyList(),
        bandHeight = 100f,
        sourceStartIndex = start,
        sourceCount = count
    )
}
