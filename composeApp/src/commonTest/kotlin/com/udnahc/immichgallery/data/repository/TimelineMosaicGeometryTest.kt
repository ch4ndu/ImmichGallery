package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketGeometryEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicAssignmentEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicGeometryEntity
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.MosaicBandAssignment
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicTileAssignment
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.shouldRejectUnsyncedEmptyTimelineBucket
import com.udnahc.immichgallery.domain.model.toDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    @Test
    fun sectionGeometryValidationOmitsMismatchedAssetFingerprint() {
        val entities = listOf(assetEntity("a"), assetEntity("b"))
        val staleRow = geometryEntity(
            timeBucket = "2026-01",
            sectionKey = "2026-01|month",
            assetFingerprint = "stale"
        )

        val summaries = validatedTimelineMosaicGeometrySummaries(
            rows = listOf(staleRow),
            assetsByBucket = mapOf("2026-01" to entities),
            requestedTimeBuckets = setOf("2026-01"),
            groupSize = TimelineGroupSize.MONTH,
            maxRowHeightKey = 100000,
            spacingKey = timelineMosaicGeometryDimensionKey(GRID_SPACING_DP),
            baseUrl = "https://server"
        )

        assertEquals(emptyList(), summaries)
    }

    @Test
    fun sectionGeometryValidationPreservesMatchingGeometryRanges() {
        val entities = listOf(assetEntity("a"), assetEntity("b"))
        val row = geometryEntity(
            timeBucket = "2026-01",
            sectionKey = "2026-01|month",
            assetFingerprint = orderedAssetsFingerprint(entities)
        )

        val summaries = validatedTimelineMosaicGeometrySummaries(
            rows = listOf(row),
            assetsByBucket = mapOf("2026-01" to entities),
            requestedTimeBuckets = setOf("2026-01"),
            groupSize = TimelineGroupSize.MONTH,
            maxRowHeightKey = 100000,
            spacingKey = timelineMosaicGeometryDimensionKey(GRID_SPACING_DP),
            baseUrl = "https://server"
        )

        assertEquals(1, summaries.size)
        assertEquals(300f, summaries.first().placeholderHeight)
        assertEquals(
            listOf(
                MosaicSectionGeometryRange(sourceStartIndex = 0, sourceCount = 1, height = 100f),
                MosaicSectionGeometryRange(sourceStartIndex = 1, sourceCount = 1, height = 200f)
            ),
            summaries.first().ranges
        )
    }

    @Test
    fun cacheStatusTreatsMissingDisplayRowsAsRenderableAssignmentGeometryHit() {
        val timeBucket = "2026-01"
        val sectionKey = "$timeBucket|month"
        val entities = listOf(assetEntity("a"), assetEntity("b"))
        val fingerprint = orderedAssetsFingerprint(entities)

        val status = buildTimelineMosaicCacheStatus(
            timeBuckets = setOf(timeBucket),
            groupSize = TimelineGroupSize.MONTH,
            assetsByBucket = mapOf(timeBucket to entities),
            rowsByBucketSection = mapOf((timeBucket to sectionKey) to assignmentEntity(timeBucket, sectionKey, fingerprint)),
            geometryRowsByBucketSection = mapOf((timeBucket to sectionKey) to geometryEntity(timeBucket, sectionKey, fingerprint)),
            bucketGeometryRowsByBucket = mapOf(timeBucket to bucketGeometryEntity(timeBucket, fingerprint)),
            displayRowsByBucketSection = emptyMap(),
            geometryRequired = true,
            includeDisplayCache = true,
            baseUrl = "https://server"
        )

        assertEquals(setOf(timeBucket), status.assignmentGeometryReadyBucketIds)
        assertEquals(emptySet(), status.missingBucketIds)
        assertEquals(emptySet(), status.completeBucketIds)
        assertEquals(emptySet(), status.displayCacheReadyBucketIds)
        assertEquals(1, status.assignments.size)
        assertEquals(1, status.geometrySummaries.size)
        assertEquals(emptyList(), status.displaySections)
    }

    @Test
    fun cacheStatusTreatsMissingGeometryAsTrueCacheMiss() {
        val timeBucket = "2026-01"
        val sectionKey = "$timeBucket|month"
        val entities = listOf(assetEntity("a"), assetEntity("b"))
        val fingerprint = orderedAssetsFingerprint(entities)

        val status = buildTimelineMosaicCacheStatus(
            timeBuckets = setOf(timeBucket),
            groupSize = TimelineGroupSize.MONTH,
            assetsByBucket = mapOf(timeBucket to entities),
            rowsByBucketSection = mapOf((timeBucket to sectionKey) to assignmentEntity(timeBucket, sectionKey, fingerprint)),
            geometryRowsByBucketSection = emptyMap(),
            bucketGeometryRowsByBucket = mapOf(timeBucket to bucketGeometryEntity(timeBucket, fingerprint)),
            displayRowsByBucketSection = emptyMap(),
            geometryRequired = true,
            includeDisplayCache = true,
            baseUrl = "https://server"
        )

        assertEquals(emptySet(), status.assignmentGeometryReadyBucketIds)
        assertEquals(setOf(timeBucket), status.missingBucketIds)
        assertEquals(emptySet(), status.completeBucketIds)
        assertEquals(1, status.assignments.size)
        assertEquals(emptyList(), status.geometrySummaries)
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

    private fun assetEntity(id: String): AssetEntity =
        AssetEntity(
            id = id,
            type = "IMAGE",
            fileName = "$id.jpg",
            createdAt = "2026-01-01T00:00:00Z",
            isFavorite = false,
            stackCount = 1,
            aspectRatio = 1f
        )

    private fun geometryEntity(
        timeBucket: String,
        sectionKey: String,
        assetFingerprint: String
    ): TimelineMosaicGeometryEntity =
        TimelineMosaicGeometryEntity(
            timeBucket = timeBucket,
            groupMode = TimelineGroupSize.MONTH.apiValue,
            sectionKey = sectionKey,
            columnCount = 5,
            familiesKey = "four_tile",
            assetFingerprint = assetFingerprint,
            availableWidthKey = 1000,
            geometryVersion = 3,
            placeholderHeight = 300f,
            displayItemCount = 2,
            maxRowHeightKey = 100000,
            spacingKey = timelineMosaicGeometryDimensionKey(GRID_SPACING_DP),
            geometryRangesJson = """
                [
                  {"sourceStartIndex":0,"sourceCount":1,"height":100.0},
                  {"sourceStartIndex":1,"sourceCount":1,"height":200.0}
                ]
            """.trimIndent(),
            updatedAt = 1L
        )

    private fun assignmentEntity(
        timeBucket: String,
        sectionKey: String,
        assetFingerprint: String
    ): TimelineMosaicAssignmentEntity =
        TimelineMosaicAssignmentEntity(
            timeBucket = timeBucket,
            groupMode = TimelineGroupSize.MONTH.apiValue,
            sectionKey = sectionKey,
            columnCount = 5,
            familiesKey = "four_tile",
            assetFingerprint = assetFingerprint,
            assignmentsJson = Json.encodeToString(
                listOf(
                    MosaicBandAssignment(
                        bandIndex = 0,
                        sourceStartIndex = 0,
                        sourceCount = 2,
                        templateId = "two_equal",
                        tiles = listOf(
                            MosaicTileAssignment(assetId = "a", visualOrder = 0),
                            MosaicTileAssignment(assetId = "b", visualOrder = 1)
                        )
                    ).toDto()
                )
            ),
            updatedAt = 1L
        )

    private fun bucketGeometryEntity(
        timeBucket: String,
        assetFingerprint: String
    ): TimelineBucketGeometryEntity =
        TimelineBucketGeometryEntity(
            timeBucket = timeBucket,
            groupMode = TimelineGroupSize.MONTH.apiValue,
            columnCount = 5,
            familiesKey = "four_tile",
            assetFingerprint = assetFingerprint,
            availableWidthKey = 1000,
            geometryVersion = 3,
            placeholderHeight = 300f,
            displayItemCount = 2,
            maxRowHeightKey = 100000,
            spacingKey = timelineMosaicGeometryDimensionKey(GRID_SPACING_DP),
            updatedAt = 1L
        )
}
