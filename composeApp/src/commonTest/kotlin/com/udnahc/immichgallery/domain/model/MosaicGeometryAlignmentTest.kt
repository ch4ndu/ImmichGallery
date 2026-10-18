package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MosaicGeometryAlignmentTest {
    private val engine = MosaicRenderEngine()

    @Test
    fun completedItemsAlignOnlyWhenSliceAndHeightMatch() {
        val ranges = listOf(range(0, 2, 120f), range(2, 3, 80f), range(5, 1, 60f))
        val band = band(start = 0, count = 2, height = 120f)
        val fallback = fallbackRow(start = 2, count = 3, height = 80.004f)
        val wrongHeight = band(start = 5, count = 1, height = 61f)

        val aligned = engine.alignToGeometryRanges(listOf(band, fallback, wrongHeight), ranges)

        assertSame(band, aligned.ranges[0].item)
        assertSame(fallback, aligned.ranges[1].item)
        assertNull(aligned.ranges[2].item)
        assertFalse(aligned.allMatched)
    }

    @Test
    fun shiftedBoundariesWithEqualTotalHeightDoNotAlign() {
        val ranges = listOf(range(0, 2, 100f), range(2, 2, 100f))

        val aligned = engine.alignToGeometryRanges(
            listOf(band(0, 3, 100f), band(3, 1, 100f)),
            ranges
        )

        assertTrue(aligned.ranges.all { it.item == null })
    }

    @Test
    fun fallbackBandsAndStandardRowsNeverAlign() {
        val ranges = listOf(range(0, 2, 100f))
        val fallbackBand = band(0, 2, 100f).copy(kind = MosaicBandKind.FALLBACK)
        val standardRow = fallbackRow(0, 2, 100f).copy(kind = RowItemKind.STANDARD)

        assertNull(engine.alignToGeometryRanges(listOf(fallbackBand), ranges).ranges.single().item)
        assertNull(engine.alignToGeometryRanges(listOf(standardRow), ranges).ranges.single().item)
    }

    @Test
    fun alignmentPreservesSourceOrder() {
        val ranges = listOf(range(3, 1, 50f), range(0, 3, 90f))

        val aligned = engine.alignToGeometryRanges(emptyList(), ranges)

        assertEquals(listOf(0, 3), aligned.ranges.map { it.range.sourceStartIndex })
    }

    private fun range(start: Int, count: Int, height: Float) =
        MosaicSectionGeometryRange(sourceStartIndex = start, sourceCount = count, height = height)

    private fun band(start: Int, count: Int, height: Float) = MosaicBandItem(
        gridKey = "band_$start",
        bucketIndex = 0,
        sectionLabel = "s",
        tiles = emptyList(),
        bandHeight = height,
        sourceStartIndex = start,
        sourceCount = count
    )

    private fun fallbackRow(start: Int, count: Int, height: Float) = RowItem(
        gridKey = "row_$start",
        bucketIndex = 0,
        sectionLabel = "s",
        photos = emptyList(),
        rowHeight = height,
        kind = RowItemKind.MOSAIC_FALLBACK,
        sourceStartIndex = start,
        sourceCount = count
    )
}
