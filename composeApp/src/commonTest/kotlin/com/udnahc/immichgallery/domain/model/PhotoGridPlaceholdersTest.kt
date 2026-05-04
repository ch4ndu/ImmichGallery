package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoGridPlaceholdersTest {
    @Test
    fun placeholderItemsForHeightPreserveRequestedTotalHeight() {
        val placeholders = buildPhotoGridPlaceholderItemsForHeight(
            bucketIndex = 2,
            sectionLabel = "May 2026",
            estimatedHeight = 25_000f
        )

        assertEquals(3, placeholders.size)
        assertEquals(25_000f, placeholders.renderedSpan(), absoluteTolerance = 0.01f)
        assertTrue(placeholders.all { it.estimatedHeight <= 10_000f })
    }

    @Test
    fun displayItemHeightIncludesInterItemSpacing() {
        val items = listOf(
            RowItem(
                gridKey = "row_1",
                bucketIndex = 0,
                sectionLabel = "May 2026",
                photos = listOf(photoItem("a", aspectRatio = 1f)),
                rowHeight = 120f
            ),
            MosaicBandItem(
                gridKey = "mosaic_1",
                bucketIndex = 0,
                sectionLabel = "May 2026",
                tiles = emptyList(),
                bandHeight = 240f
            )
        )

        assertEquals(362f, estimatePhotoGridDisplayItemsHeight(items, spacing = GRID_SPACING_DP))
    }

    @Test
    fun projectedMosaicHeightMatchesFinalItemsWhenAssignmentsAreCached() {
        val assets = listOf(
            asset("asset_0", 1f),
            asset("asset_1", 1.4f),
            asset("asset_2", 0.8f),
            asset("asset_3", 1.2f),
            asset("asset_4", 1.1f),
            asset("asset_5", 0.9f),
        )
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f)
        val assignments = buildMosaicAssignments(
            assets = assets,
            layoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP
        )
        val finalItems = buildPhotoGridItemsWithMosaic(
            assets = assets,
            assignments = assignments,
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP,
            maxRowHeight = 300f
        )
        val finalHeight = estimatePhotoGridDisplayItemsHeight(finalItems, GRID_SPACING_DP)

        val placeholders = buildPhotoGridPlaceholderItemsForHeight(
            bucketIndex = 0,
            sectionLabel = "May 2026",
            estimatedHeight = finalHeight
        )

        assertEquals(
            expected = finalHeight,
            actual = placeholders.renderedSpan(),
            absoluteTolerance = 0.01f
        )
    }

    private fun List<PlaceholderItem>.renderedSpan(): Float =
        sumOf { it.estimatedHeight.toDouble() }.toFloat() + GRID_SPACING_DP * (size - 1).coerceAtLeast(0)

    private fun photoItem(id: String, aspectRatio: Float): PhotoItem =
        PhotoItem(
            gridKey = "p_$id",
            bucketIndex = 0,
            sectionLabel = "May 2026",
            asset = asset(id, aspectRatio)
        )

    private fun asset(id: String, aspectRatio: Float): Asset =
        Asset(
            id = id,
            type = AssetType.IMAGE,
            fileName = "$id.jpg",
            createdAt = "2026-05-03T00:00:00Z",
            thumbnailUrl = "",
            originalUrl = "",
            aspectRatio = aspectRatio
        )
}
