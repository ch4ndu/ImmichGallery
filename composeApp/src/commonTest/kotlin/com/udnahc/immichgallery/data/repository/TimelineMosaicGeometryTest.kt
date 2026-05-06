package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineMosaicGeometryTest {
    @Test
    fun widthKeyRoundsToIntegerDp() {
        assertEquals(401, timelineMosaicGeometryWidthKey(400.6f))
        assertEquals(400, timelineMosaicGeometryWidthKey(400.4f))
    }

    @Test
    fun dimensionKeyPreservesTwoDecimalPlaces() {
        assertEquals(12346, timelineMosaicGeometryDimensionKey(123.456f))
        assertEquals(200, timelineMosaicGeometryDimensionKey(2f))
    }

    @Test
    fun geometryDisplayItemsUseMeasuredWidthInsteadOfCanonicalAssignmentWidth() {
        val assets = List(6) { index -> asset("a$index") }
        val narrowRequest = com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest(
            availableWidth = 500f,
            maxRowHeight = 1000f,
            spacing = GRID_SPACING_DP
        )
        val wideRequest = narrowRequest.copy(availableWidth = 1000f)
        val engine = MosaicRenderEngine()

        val narrowHeight = estimatePhotoGridDisplayItemsHeight(
            engine.projectSection(
                assets = assets,
                assignments = emptyList(),
                bucketIndex = 0,
                sectionLabel = "section",
                layoutSpec = requireNotNull(mosaicLayoutSpecForColumnCount(narrowRequest.availableWidth, 5)),
                spacing = narrowRequest.spacing,
                maxRowHeight = narrowRequest.maxRowHeight
            ),
            GRID_SPACING_DP
        )
        val wideHeight = estimatePhotoGridDisplayItemsHeight(
            engine.projectSection(
                assets = assets,
                assignments = emptyList(),
                bucketIndex = 0,
                sectionLabel = "section",
                layoutSpec = requireNotNull(mosaicLayoutSpecForColumnCount(wideRequest.availableWidth, 5)),
                spacing = wideRequest.spacing,
                maxRowHeight = wideRequest.maxRowHeight
            ),
            GRID_SPACING_DP
        )

        assertTrue(wideHeight > narrowHeight)
    }

    @Test
    fun unsyncedEmptyBucketWithPositiveMetadataCountIsRejected() {
        assertTrue(shouldRejectUnsyncedEmptyTimelineBucket(actualAssetCount = 0, expectedBucketCount = 12))
    }

    @Test
    fun syncedEmptyBucketWithZeroOrMissingMetadataCountIsAllowed() {
        assertEquals(false, shouldRejectUnsyncedEmptyTimelineBucket(actualAssetCount = 0, expectedBucketCount = 0))
        assertEquals(false, shouldRejectUnsyncedEmptyTimelineBucket(actualAssetCount = 0, expectedBucketCount = null))
        assertEquals(false, shouldRejectUnsyncedEmptyTimelineBucket(actualAssetCount = 3, expectedBucketCount = 12))
    }

    private fun asset(id: String): Asset =
        Asset(
            id = id,
            type = AssetType.IMAGE,
            fileName = "$id.jpg",
            createdAt = "2026-01-01T00:00:00Z",
            thumbnailUrl = "thumb/$id",
            originalUrl = "original/$id",
            aspectRatio = 1f
        )
}
