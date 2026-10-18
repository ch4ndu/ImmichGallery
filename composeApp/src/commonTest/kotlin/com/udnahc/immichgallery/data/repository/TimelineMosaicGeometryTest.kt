package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.TimelineMosaicSkeletonRows
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketSnapshotEntity
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
import com.udnahc.immichgallery.domain.model.PHOTO_GRID_SECTION_HEADER_HEIGHT_DP
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.shouldRejectUnsyncedEmptyTimelineBucket
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TimelineMosaicGeometryTest {
    @Test
    fun widthKeyPreservesHundredthsOfDp() {
        assertEquals(40060, timelineMosaicGeometryWidthKey(400.6f))
        assertEquals(40040, timelineMosaicGeometryWidthKey(400.4f))
        assertEquals(40000, timelineMosaicGeometryWidthKey(400.001f))
    }

    @Test
    fun hashedFingerprintMatchesBetweenRoomRowsAndDomainAssetsAndTracksOrder() {
        val entities = listOf(assetEntity("a"), assetEntity("b"))
        val domainAssets = entities.map { it.toDomain("https://server") }

        assertEquals(
            orderedAssetsFingerprint(entities),
            com.udnahc.immichgallery.domain.model.orderedTimelineAssetsFingerprint(domainAssets)
        )
        assertEquals(16, orderedAssetsFingerprint(entities).length)
        assertTrue(orderedAssetsFingerprint(entities) != orderedAssetsFingerprint(entities.reversed()))
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
    fun skeletonRejectsBucketWhoseSnapshotFingerprintIsStale() {
        val skeleton = validatedTimelineMosaicSkeleton(
            rows = skeletonRows(snapshotFingerprint = "current", artifactFingerprint = "stale"),
            groupSize = TimelineGroupSize.MONTH,
            geometryRequest = skeletonRequest,
            groupingZoneId = ""
        )

        assertEquals(emptyMap(), skeleton)
    }

    @Test
    fun skeletonPreservesMatchingRangesWithoutReadingAssets() {
        val skeleton = validatedTimelineMosaicSkeleton(
            rows = skeletonRows(),
            groupSize = TimelineGroupSize.MONTH,
            geometryRequest = skeletonRequest,
            groupingZoneId = ""
        )

        val bucket = skeleton.getValue("2026-01")
        assertEquals(2, bucket.assetCount)
        assertEquals(302f, bucket.placeholderHeight)
        assertEquals(
            listOf(
                MosaicSectionGeometryRange(sourceStartIndex = 0, sourceCount = 1, height = 100f),
                MosaicSectionGeometryRange(sourceStartIndex = 1, sourceCount = 1, height = 200f)
            ),
            bucket.sections.single().ranges
        )
    }

    @Test
    fun skeletonRejectsHeightThatOmitsRangeSpacing() {
        val rows = skeletonRows()
        val skeleton = validatedTimelineMosaicSkeleton(
            rows = rows.copy(sectionGeometry = rows.sectionGeometry.map { it.copy(placeholderHeight = 300f) }),
            groupSize = TimelineGroupSize.MONTH,
            geometryRequest = skeletonRequest,
            groupingZoneId = ""
        )

        assertEquals(emptyMap(), skeleton)
    }

    @Test
    fun skeletonRejectsBucketWithMissingManifestSection() {
        val skeleton = validatedTimelineMosaicSkeleton(
            rows = skeletonRows().copy(sectionGeometry = emptyList()),
            groupSize = TimelineGroupSize.MONTH,
            geometryRequest = skeletonRequest,
            groupingZoneId = ""
        )

        assertEquals(emptyMap(), skeleton)
    }

    @Test
    fun skeletonRejectsMalformedManifestAndSnapshotCountMismatch() {
        val rows = skeletonRows()
        val malformed = rows.copy(bucketGeometry = rows.bucketGeometry.map { it.copy(sectionManifestJson = "{") })
        val countMismatch = rows.copy(snapshots = rows.snapshots.map { it.copy(assetCount = 3) })

        listOf(malformed, countMismatch).forEach { candidate ->
            assertEquals(
                emptyMap(),
                validatedTimelineMosaicSkeleton(
                    rows = candidate,
                    groupSize = TimelineGroupSize.MONTH,
                    geometryRequest = skeletonRequest,
                    groupingZoneId = ""
                )
            )
        }
    }

    @Test
    fun skeletonAcceptsExplicitlyEmptyBucket() {
        val rows = TimelineMosaicSkeletonRows(
            snapshots = listOf(TimelineBucketSnapshotEntity("2026-02", 0, "empty", 1L)),
            bucketGeometry = listOf(
                bucketGeometryEntity("2026-02", "empty", manifestJson = "[]").copy(placeholderHeight = 0f)
            ),
            sectionGeometry = emptyList()
        )

        val skeleton = validatedTimelineMosaicSkeleton(
            rows = rows,
            groupSize = TimelineGroupSize.MONTH,
            geometryRequest = skeletonRequest,
            groupingZoneId = ""
        )

        assertEquals(emptyList(), skeleton.getValue("2026-02").sections)
    }

    @Test
    fun daySkeletonRejectsRowsWrittenInAnotherTimeZone() {
        val rows = skeletonRows()
        val skeleton = validatedTimelineMosaicSkeleton(
            rows = rows.copy(bucketGeometry = rows.bucketGeometry.map { it.copy(groupingZoneId = "Europe/Paris") }),
            groupSize = TimelineGroupSize.MONTH,
            geometryRequest = skeletonRequest,
            groupingZoneId = "America/Chicago"
        )

        assertEquals(emptyMap(), skeleton)
    }

    @Test
    fun displayRecordsMustMatchGeometryRangesOneToOne() {
        val ranges = listOf(
            MosaicSectionGeometryRange(sourceStartIndex = 0, sourceCount = 2, height = 100f),
            MosaicSectionGeometryRange(sourceStartIndex = 2, sourceCount = 3, height = 150f)
        )
        val matching = listOf(record(0, 2, 100f), record(2, 3, 150.005f))
        val shiftedBoundary = listOf(record(0, 3, 100f), record(3, 2, 150f))
        val differentHeight = listOf(record(0, 2, 100f), record(2, 3, 151f))

        assertTrue(displayRecordsMatchGeometryRanges(matching, ranges))
        assertFalse(displayRecordsMatchGeometryRanges(shiftedBoundary, ranges))
        assertFalse(displayRecordsMatchGeometryRanges(differentHeight, ranges))
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
            geometrySpacing = GRID_SPACING_DP,
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
            geometrySpacing = GRID_SPACING_DP,
            baseUrl = "https://server"
        )

        assertEquals(emptySet(), status.assignmentGeometryReadyBucketIds)
        assertEquals(setOf(timeBucket), status.missingBucketIds)
        assertEquals(emptySet(), status.completeBucketIds)
        assertEquals(1, status.assignments.size)
        assertEquals(emptyList(), status.geometrySummaries)
        assertEquals(setOf(sectionKey), status.missingSectionKeysByBucket[timeBucket])
    }

    @Test
    fun cacheStatusRejectsOnlyCorruptDaySectionAndPreservesValidSibling() {
        val timeBucket = "2026-01"
        val firstSectionKey = "$timeBucket|day|2026-01-01"
        val secondSectionKey = "$timeBucket|day|2026-01-02"
        val entities = listOf(
            assetEntity("a", "2026-01-01T12:00:00Z"),
            assetEntity("b", "2026-01-01T12:01:00Z"),
            assetEntity("c", "2026-01-02T12:00:00Z"),
            assetEntity("d", "2026-01-02T12:01:00Z")
        )
        val fingerprint = orderedAssetsFingerprint(entities)
        val validAssignment = assignmentEntity(timeBucket, firstSectionKey, fingerprint)
            .copy(groupMode = TimelineGroupSize.DAY.apiValue)
        val corruptAssignment = assignmentEntity(timeBucket, secondSectionKey, fingerprint)
            .copy(groupMode = TimelineGroupSize.DAY.apiValue, assignmentsJson = "not-json")
        val firstGeometry = geometryEntity(timeBucket, firstSectionKey, fingerprint)
            .copy(groupMode = TimelineGroupSize.DAY.apiValue)
        val secondGeometry = geometryEntity(timeBucket, secondSectionKey, fingerprint)
            .copy(groupMode = TimelineGroupSize.DAY.apiValue)
        val bucketHeight = 302f * 2 + PHOTO_GRID_SECTION_HEADER_HEIGHT_DP * 2 + GRID_SPACING_DP * 3

        val status = buildTimelineMosaicCacheStatus(
            timeBuckets = setOf(timeBucket),
            groupSize = TimelineGroupSize.DAY,
            assetsByBucket = mapOf(timeBucket to entities),
            rowsByBucketSection = mapOf(
                (timeBucket to firstSectionKey) to validAssignment,
                (timeBucket to secondSectionKey) to corruptAssignment
            ),
            geometryRowsByBucketSection = mapOf(
                (timeBucket to firstSectionKey) to firstGeometry,
                (timeBucket to secondSectionKey) to secondGeometry
            ),
            bucketGeometryRowsByBucket = mapOf(
                timeBucket to bucketGeometryEntity(timeBucket, fingerprint).copy(
                    groupMode = TimelineGroupSize.DAY.apiValue,
                    placeholderHeight = bucketHeight
                )
            ),
            displayRowsByBucketSection = emptyMap(),
            geometryRequired = true,
            includeDisplayCache = true,
            geometrySpacing = GRID_SPACING_DP,
            baseUrl = "https://server"
        )

        assertEquals(setOf(timeBucket), status.missingBucketIds)
        assertEquals(setOf(secondSectionKey), status.missingSectionKeysByBucket[timeBucket])
        assertEquals(listOf(firstSectionKey), status.assignments.map { it.sectionKey })
        assertEquals(listOf(firstSectionKey), status.geometrySummaries.map { it.sectionKey })
    }

    @Test
    fun artifactFingerprintGuardRejectsCurrentBucketAssetMismatch() {
        val currentAssets = listOf(assetEntity("a"), assetEntity("b"))
        val staleArtifact = bucketGeometryArtifact(
            assetFingerprint = orderedAssetsFingerprint(listOf(assetEntity("a")))
        )

        assertEquals(
            false,
            timelineMosaicArtifactFingerprintMatchesCurrentAssets(
                currentAssets,
                staleArtifact.assetFingerprint
            )
        )
    }

    @Test
    fun artifactFingerprintGuardAcceptsCurrentBucketAssetMatch() {
        val currentAssets = listOf(assetEntity("a"), assetEntity("b"))
        val currentArtifact = bucketGeometryArtifact(
            assetFingerprint = orderedAssetsFingerprint(currentAssets)
        )

        assertEquals(
            true,
            timelineMosaicArtifactFingerprintMatchesCurrentAssets(
                currentAssets,
                currentArtifact.assetFingerprint
            )
        )
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

    private fun assetEntity(
        id: String,
        createdAt: String = "2026-01-01T00:00:00Z"
    ): AssetEntity =
        AssetEntity(
            id = id,
            type = "IMAGE",
            fileName = "$id.jpg",
            createdAt = createdAt,
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
            placeholderHeight = 302f,
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
            assignmentsJson = Json.encodeToString(emptyList<com.udnahc.immichgallery.domain.model.MosaicBandAssignmentDto>()),
            updatedAt = 1L
        )

    private val skeletonRequest = com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest(
        availableWidth = 10f,
        maxRowHeight = 1000f,
        spacing = GRID_SPACING_DP
    )

    private fun skeletonRows(
        snapshotFingerprint: String = "hash",
        artifactFingerprint: String = snapshotFingerprint
    ): TimelineMosaicSkeletonRows =
        TimelineMosaicSkeletonRows(
            snapshots = listOf(TimelineBucketSnapshotEntity("2026-01", 2, snapshotFingerprint, 1L)),
            bucketGeometry = listOf(bucketGeometryEntity("2026-01", artifactFingerprint)),
            sectionGeometry = listOf(geometryEntity("2026-01", "2026-01|month", artifactFingerprint))
        )

    private fun record(start: Int, count: Int, height: Float) =
        com.udnahc.immichgallery.domain.model.MosaicDisplayItemRecord(
            kind = com.udnahc.immichgallery.domain.model.MosaicDisplayItemRecordKind.REAL_BAND,
            sourceStartIndex = start,
            sourceCount = count,
            height = height
        )

    private fun bucketGeometryEntity(
        timeBucket: String,
        assetFingerprint: String,
        manifestJson: String = """[{"sectionKey":"$timeBucket|month","assetCount":2}]"""
    ): TimelineBucketGeometryEntity =
        TimelineBucketGeometryEntity(
            timeBucket = timeBucket,
            groupMode = TimelineGroupSize.MONTH.apiValue,
            columnCount = 5,
            familiesKey = "four_tile",
            assetFingerprint = assetFingerprint,
            availableWidthKey = 1000,
            geometryVersion = 3,
            placeholderHeight = 302f,
            displayItemCount = 2,
            maxRowHeightKey = 100000,
            spacingKey = timelineMosaicGeometryDimensionKey(GRID_SPACING_DP),
            sectionManifestJson = manifestJson,
            groupingZoneId = "",
            updatedAt = 1L
        )

    private fun bucketGeometryArtifact(
        assetFingerprint: String
    ): TimelineBucketGeometryEntity = bucketGeometryEntity("2026-01", assetFingerprint)
}
