package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.GeometryAlignedRange
import com.udnahc.immichgallery.domain.model.GeometryAlignedSection
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketSkeleton
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineSkeletonStateTest {
    @Test
    fun gateStaysClosedUntilSkeletonForCurrentIdentityLoads() {
        val loading = TimelineSkeletonState().startingLoad(identity)

        assertFalse(timelineSkeletonGateOpen(loading, identity))
        assertTrue(timelineSkeletonGateOpen(loading, required = null))
        val loaded = loading.withLoadedBuckets(identity, mapOf("b" to skeleton("b", 100f)), null, emptyMap())
        assertTrue(timelineSkeletonGateOpen(loaded, identity))
        assertFalse(timelineSkeletonGateOpen(loaded, identity.copy(columnCount = 6)))
        assertTrue(timelineSkeletonGateOpen(loading.copy(status = TimelineSkeletonLoadStatus.Failed), identity))
    }

    @Test
    fun olderFullReadCannotOverwriteNewerBucketReload() {
        val loaded = TimelineSkeletonState().startingLoad(identity)
            .withLoadedBuckets(identity, mapOf("a" to skeleton("a", 100f)), null, emptyMap())
        val fullReadOrigin = loaded.bucketEpochs
        val reloading = loaded.withBumpedEpochs(setOf("a"))
        val reloaded = reloading.withLoadedBuckets(
            identity,
            mapOf("a" to skeleton("a", 200f)),
            setOf("a"),
            reloading.bucketEpochs
        )

        val afterStaleFullRead = reloaded.withLoadedBuckets(
            identity,
            mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 50f)),
            null,
            fullReadOrigin
        )

        assertEquals(200f, afterStaleFullRead.buckets.getValue("a").placeholderHeight)
        assertEquals(50f, afterStaleFullRead.buckets.getValue("b").placeholderHeight)
    }

    @Test
    fun bucketReloadRemovesBucketThatNoLongerValidates() {
        val loaded = TimelineSkeletonState().startingLoad(identity)
            .withLoadedBuckets(identity, mapOf("a" to skeleton("a", 100f)), null, emptyMap())
            .withBumpedEpochs(setOf("a"))

        val reloaded = loaded.withLoadedBuckets(identity, emptyMap(), setOf("a"), loaded.bucketEpochs)

        assertNull(reloaded.buckets["a"])
    }

    @Test
    fun readForPreviousIdentityIsIgnored() {
        val loaded = TimelineSkeletonState().startingLoad(identity.copy(columnCount = 6))

        val result = loaded.withLoadedBuckets(identity, mapOf("a" to skeleton("a", 100f)), null, emptyMap())

        assertEquals(loaded, result)
    }

    @Test
    fun contentTokenChangesWhenRangeBoundariesChangeWithEqualTotals() {
        val first = TimelineMosaicSectionGeometry(202f, listOf(range(0, 2, 100f), range(2, 2, 100f)))
        val second = TimelineMosaicSectionGeometry(202f, listOf(range(0, 3, 100f), range(3, 1, 100f)))

        assertTrue(first.contentToken != second.contentToken)
    }

    @Test
    fun daySkeletonEmitsFinalHeaderAndRangeKeys() {
        val skeleton = TimelineMosaicBucketSkeleton(
            timeBucket = "2026-01",
            assetCount = 5,
            placeholderHeight = 0f,
            sections = listOf(
                section("2026-01|day|2026-01-31", range(0, 2, 120f), range(2, 1, 80f)),
                section("2026-01|day|unknown-date", range(0, 2, 90f))
            )
        )

        val items = timelineSkeletonBucketItems("2026-01", 3, TimelineGroupSize.DAY, skeleton)

        assertEquals(
            listOf(
                "dh_2026-01_2026-01|day|2026-01-31",
                "mr_2026-01_2026-01|day|2026-01-31_0_2",
                "mr_2026-01_2026-01|day|2026-01-31_2_1",
                "dh_2026-01_2026-01|day|unknown-date",
                "mr_2026-01_2026-01|day|unknown-date_0_2"
            ),
            items.map { it.gridKey }
        )
        assertEquals(listOf(120f, 80f, 90f), items.filterIsInstance<PlaceholderItem>().map { it.estimatedHeight })
        assertTrue(items.filterIsInstance<HeaderItem>().all { it.assetGroupDisplayLabel != null })
    }

    @Test
    fun alignedItemsKeepPlaceholderKeyAndHeightWhenBandArrives() {
        val ranges = listOf(range(0, 2, 120f), range(2, 3, 80f))
        val band = MosaicBandItem(
            gridKey = "engine-key",
            bucketIndex = 0,
            sectionLabel = "s",
            tiles = emptyList(),
            bandHeight = 120f,
            sourceStartIndex = 0,
            sourceCount = 2
        )

        val pending = timelineGeometryAlignedItems(
            "2026-01", "s", 0, "s",
            GeometryAlignedSection(ranges.map { GeometryAlignedRange(it, null) })
        )
        val partial = timelineGeometryAlignedItems(
            "2026-01", "s", 0, "s",
            GeometryAlignedSection(listOf(GeometryAlignedRange(ranges[0], band), GeometryAlignedRange(ranges[1], null)))
        )

        assertEquals(pending.map { it.gridKey }, partial.map { it.gridKey })
        assertEquals(120f, (partial[0] as MosaicBandItem).bandHeight)
        assertEquals(80f, (partial[1] as PlaceholderItem).estimatedHeight)
    }

    private val identity = TimelineSkeletonIdentity(
        groupSize = TimelineGroupSize.MONTH,
        columnCount = 4,
        familiesKey = "four_tile",
        geometryRequest = TimelineMosaicGeometryRequest(400f, 1000f, GRID_SPACING_DP),
        groupingZoneId = ""
    )

    private fun range(start: Int, count: Int, height: Float) =
        MosaicSectionGeometryRange(sourceStartIndex = start, sourceCount = count, height = height)

    private fun section(sectionKey: String, vararg ranges: MosaicSectionGeometryRange) =
        TimelineMosaicGeometrySummary(
            timeBucket = "2026-01",
            sectionKey = sectionKey,
            placeholderHeight = ranges.sumOf { it.height.toDouble() }.toFloat(),
            displayItemCount = ranges.size,
            ranges = ranges.toList()
        )

    private fun skeleton(timeBucket: String, height: Float) =
        TimelineMosaicBucketSkeleton(
            timeBucket = timeBucket,
            assetCount = 1,
            placeholderHeight = height,
            sections = listOf(section("$timeBucket|month", range(0, 1, height)))
        )
}
