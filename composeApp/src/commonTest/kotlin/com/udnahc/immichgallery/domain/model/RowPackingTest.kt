package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RowPackingTest {
    @Test
    fun defaultTargetRowHeightUsesThreeColumnsWhenWidthIsMeasured() {
        assertEquals(120f, defaultTargetRowHeightForWidth(360f))
    }

    @Test
    fun unmeasuredViewportBoundsDoNotClampSavedRowHeight() {
        val savedHeight = 220f
        val bounds = rowHeightBoundsForViewport(0f)

        assertEquals(savedHeight, bounds.clamp(savedHeight))
    }

    @Test
    fun wideImageAtRowStartPromotesWhenUnderTargetAndViewportCaps() {
        val rows = packIntoRows(
            assets = listOf(asset("wide", aspectRatio = 2f)),
            availableWidth = 300f,
            targetRowHeight = 120f,
            spacing = 2f,
            maxRowHeight = 220f
        )

        assertEquals(1, rows.size)
        assertTrue(rows.single().isComplete)
        assertEquals(listOf("wide"), rows.single().assetIds())
        assertEquals(150f, rows.single().rowHeight)
    }

    @Test
    fun wideImageAtRowStartDoesNotPromoteWhenAboveTargetMultiplier() {
        val rows = packIntoRows(
            assets = listOf(asset("wide", aspectRatio = 2f), asset("regular", aspectRatio = 1f)),
            availableWidth = 300f,
            targetRowHeight = 80f,
            spacing = 2f,
            maxRowHeight = 220f
        )

        assertEquals(1, rows.size)
        assertEquals(listOf("wide", "regular"), rows.single().assetIds())
        assertFalse(rows.single().isComplete)
        assertEquals(80f, rows.single().rowHeight)
    }

    @Test
    fun wideImageAfterPendingPhotosPacksNormally() {
        val rows = packIntoRows(
            assets = listOf(asset("regular", aspectRatio = 1f), asset("wide", aspectRatio = 2f)),
            availableWidth = 300f,
            targetRowHeight = 120f,
            spacing = 2f,
            maxRowHeight = 220f
        )

        assertEquals(1, rows.size)
        assertTrue(rows.single().isComplete)
        assertEquals(listOf("regular", "wide"), rows.single().assetIds())
        assertEquals((300f - 2f) / 3f, rows.single().rowHeight)
    }

    @Test
    fun wideImageExceedingViewportMaxPacksNormally() {
        val rows = packIntoRows(
            assets = listOf(asset("wide", aspectRatio = 2f), asset("regular", aspectRatio = 1f)),
            availableWidth = 300f,
            targetRowHeight = 120f,
            spacing = 2f,
            maxRowHeight = 100f
        )

        assertEquals(1, rows.size)
        assertTrue(rows.single().isComplete)
        assertEquals(listOf("wide", "regular"), rows.single().assetIds())
        assertEquals((300f - 2f) / 3f, rows.single().rowHeight)
    }

    @Test
    fun wideImagePromotionCanBeDisabledForMosaicFallbackRows() {
        val rows = packIntoRows(
            assets = listOf(asset("wide", aspectRatio = 2f), asset("regular", aspectRatio = 1f)),
            availableWidth = 300f,
            targetRowHeight = 120f,
            spacing = 2f,
            maxRowHeight = 220f,
            promoteWideImages = false
        )

        assertEquals(1, rows.size)
        assertTrue(rows.single().isComplete)
        assertEquals(listOf("wide", "regular"), rows.single().assetIds())
        assertEquals((300f - 2f) / 3f, rows.single().rowHeight)
    }

    @Test
    fun omittedMinimumCompletePhotosStillAllowsSinglePhotoJustifiedRow() {
        val rows = packIntoRows(
            assets = listOf(asset("wide", aspectRatio = 4f)),
            availableWidth = 300f,
            targetRowHeight = 100f,
            spacing = 0f,
            maxRowHeight = 220f,
            promoteWideImages = false
        )

        assertEquals(1, rows.size)
        assertTrue(rows.single().isComplete)
        assertEquals(listOf("wide"), rows.single().assetIds())
        assertEquals(75f, rows.single().rowHeight)
    }

    @Test
    fun minimumCompletePhotosRequiresSecondPhotoBeforeCompletingWideRow() {
        val rows = packIntoRows(
            assets = listOf(asset("wide", aspectRatio = 4f), asset("regular", aspectRatio = 1f)),
            availableWidth = 300f,
            targetRowHeight = 100f,
            spacing = 0f,
            maxRowHeight = 220f,
            promoteWideImages = false,
            minCompleteRowPhotos = 2
        )

        assertEquals(1, rows.size)
        assertTrue(rows.single().isComplete)
        assertEquals(listOf("wide", "regular"), rows.single().assetIds())
        assertEquals(60f, rows.single().rowHeight)
    }

    @Test
    fun minimumCompletePhotosAllowsFinalSinglePhotoIncompleteRow() {
        val rows = packIntoRows(
            assets = listOf(asset("wide", aspectRatio = 4f)),
            availableWidth = 300f,
            targetRowHeight = 100f,
            spacing = 0f,
            maxRowHeight = 220f,
            promoteWideImages = false,
            minCompleteRowPhotos = 2
        )

        assertEquals(1, rows.size)
        assertFalse(rows.single().isComplete)
        assertEquals(listOf("wide"), rows.single().assetIds())
        assertEquals(100f, rows.single().rowHeight)
    }

    @Test
    fun regularFinalIncompleteRowBehaviorRemainsUnchanged() {
        val rows = packIntoRows(
            assets = listOf(asset("a", aspectRatio = 1f), asset("b", aspectRatio = 1f)),
            availableWidth = 300f,
            targetRowHeight = 100f,
            spacing = 2f,
            maxRowHeight = 220f
        )

        assertEquals(1, rows.size)
        assertFalse(rows.single().isComplete)
        assertEquals(listOf("a", "b"), rows.single().assetIds())
        assertEquals(100f, rows.single().rowHeight)
    }

    private fun asset(id: String, aspectRatio: Float): Asset =
        Asset(
            id = id,
            type = AssetType.IMAGE,
            fileName = "$id.jpg",
            createdAt = "2026-05-02T00:00:00Z",
            thumbnailUrl = "",
            originalUrl = "",
            aspectRatio = aspectRatio
        )

    private fun RowItem.assetIds(): List<String> = photos.map { it.asset.id }
}
