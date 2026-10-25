package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelineMosaicArtifactBuilderTest {
    private val builder = TimelineMosaicArtifactBuilder()
    private val request = TimelineMosaicGeometryRequest(
        availableWidth = 500f,
        maxRowHeight = 1000f,
        spacing = GRID_SPACING_DP
    )

    @Test
    fun monthBuildEmitsAllArtifactTypes() {
        val assets = sampleAssets(2)
        val section = TimelineMosaicSection("2026-01|month", "2026-01", assets)
        val artifacts = builder.build(
            snapshot = TimelineBucketSnapshot("2026-01", assets, expectedCount = 2),
            groupSize = TimelineGroupSize.MONTH,
            groupMode = TimelineGroupSize.MONTH.apiValue,
            columnCount = 5,
            familiesKey = "FOUR_TILE",
            geometryRequest = request,
            sections = listOf(section),
            readySections = listOf(readySection(section, height = 200f)),
            cacheDisplayResults = true,
            updatedAt = 1L
        )

        requireNotNull(artifacts)
        assertEquals(1, artifacts.assignments.size)
        assertEquals(1, artifacts.displayCache.size)
        assertEquals(1, artifacts.sectionGeometry.size)
        assertEquals(200f, artifacts.bucketGeometry?.placeholderHeight)
        assertEquals(1, artifacts.bucketGeometry?.displayItemCount)
    }

    @Test
    fun dayBucketGeometryUsesHeadersSpacingAndPlaceholderChunkCounts() {
        val firstAssets = sampleAssets(1, prefix = "a")
        val secondAssets = sampleAssets(1, prefix = "b")
        val first = TimelineMosaicSection("2026-01-02|day", "2 january 2026", firstAssets)
        val second = TimelineMosaicSection("2026-01-01|day", "1 january 2026", secondAssets)
        val artifacts = builder.build(
            snapshot = TimelineBucketSnapshot("2026-01", firstAssets + secondAssets, expectedCount = 2),
            groupSize = TimelineGroupSize.DAY,
            groupMode = TimelineGroupSize.DAY.apiValue,
            columnCount = 5,
            familiesKey = "FOUR_TILE",
            geometryRequest = request,
            sections = listOf(first, second),
            readySections = listOf(
                readySection(first, height = 120f),
                readySection(second, height = 150f)
            ),
            cacheDisplayResults = true,
            updatedAt = 1L
        )

        requireNotNull(artifacts)
        val itemCount = 4
        val expectedHeight = 120f + 150f +
            PHOTO_GRID_SECTION_HEADER_HEIGHT_DP * 2 +
            GRID_SPACING_DP * (itemCount - 1)
        assertEquals(expectedHeight, artifacts.bucketGeometry?.placeholderHeight)
        assertEquals(itemCount, artifacts.bucketGeometry?.displayItemCount)
    }

    @Test
    fun dayBucketGeometryDisplayCountIncludesTallPlaceholderChunks() {
        val assets = sampleAssets(1)
        val section = TimelineMosaicSection("2026-01-01|day", "1 january 2026", assets)
        val artifacts = builder.build(
            snapshot = TimelineBucketSnapshot("2026-01", assets, expectedCount = 1),
            groupSize = TimelineGroupSize.DAY,
            groupMode = TimelineGroupSize.DAY.apiValue,
            columnCount = 5,
            familiesKey = "FOUR_TILE",
            geometryRequest = request,
            sections = listOf(section),
            readySections = listOf(readySection(section, height = 25_000f)),
            cacheDisplayResults = true,
            updatedAt = 1L
        )

        requireNotNull(artifacts)
        assertEquals(4, artifacts.bucketGeometry?.displayItemCount)
        assertEquals(
            25_000f + PHOTO_GRID_SECTION_HEADER_HEIGHT_DP + GRID_SPACING_DP,
            artifacts.bucketGeometry?.placeholderHeight
        )
    }

    @Test
    fun cacheOffSkipsDisplayArtifactsButKeepsGeometry() {
        val assets = sampleAssets(1)
        val section = TimelineMosaicSection("2026-01|month", "2026-01", assets)
        val artifacts = builder.build(
            snapshot = TimelineBucketSnapshot("2026-01", assets, expectedCount = 1),
            groupSize = TimelineGroupSize.MONTH,
            groupMode = TimelineGroupSize.MONTH.apiValue,
            columnCount = 5,
            familiesKey = "FOUR_TILE",
            geometryRequest = request,
            sections = listOf(section),
            readySections = listOf(readySection(section, height = 100f)),
            cacheDisplayResults = false,
            updatedAt = 1L
        )

        requireNotNull(artifacts)
        assertTrue(artifacts.displayCache.isEmpty())
        assertEquals(1, artifacts.assignments.size)
        assertEquals(1, artifacts.sectionGeometry.size)
        assertEquals(100f, artifacts.bucketGeometry?.placeholderHeight)
    }

    @Test
    fun invalidSectionGeometryFailsWholeBucket() {
        val assets = sampleAssets(2)
        val section = TimelineMosaicSection("2026-01|month", "2026-01", assets)
        val ready = readySection(section, height = 100f).copy(
            geometry = SectionGeometry(
                keyScope = keyScope(section),
                placeholderHeight = 100f,
                displayItemCount = 1,
                ranges = listOf(MosaicSectionGeometryRange(1, 1, 100f))
            )
        )

        val artifacts = builder.build(
            snapshot = TimelineBucketSnapshot("2026-01", assets, expectedCount = 2),
            groupSize = TimelineGroupSize.MONTH,
            groupMode = TimelineGroupSize.MONTH.apiValue,
            columnCount = 5,
            familiesKey = "FOUR_TILE",
            geometryRequest = request,
            sections = listOf(section),
            readySections = listOf(ready),
            cacheDisplayResults = true,
            updatedAt = 1L
        )

        assertEquals(null, artifacts)
    }

    @Test
    fun daySectionsKeepUnparseableAssetsLastInSourceOrder() {
        val assets = listOf(
            sampleAsset("valid-new", "2026-01-02T12:00:00Z"),
            sampleAsset("unknown-first", "not-a-date"),
            sampleAsset("valid-old", "2026-01-01T12:00:00Z"),
            sampleAsset("unknown-second", "")
        )

        val sections = timelineDaySections("2026-01", assets)

        assertEquals(3, sections.size)
        assertEquals(listOf("valid-new"), sections[0].assets.map { it.id })
        assertEquals(listOf("valid-old"), sections[1].assets.map { it.id })
        assertEquals(null, sections[2].date)
        assertEquals(
            listOf("unknown-first", "unknown-second"),
            sections[2].assets.map { it.id }
        )
        assertTrue(sections[2].sectionKey.endsWith(TIMELINE_UNKNOWN_DATE_SECTION_ID))
        assertEquals(assets.map { it.id }.toSet(), sections.flatMap { it.assets }.map { it.id }.toSet())
    }

    @Test
    fun allUnparseableAssetsProduceOneUnknownSection() {
        val assets = listOf(
            sampleAsset("first", "bad"),
            sampleAsset("second", "also-bad")
        )

        val sections = timelineMosaicSections("2026-01", TimelineGroupSize.DAY, assets)

        assertEquals(1, sections.size)
        assertTrue(sections.single().sectionKey.endsWith(TIMELINE_UNKNOWN_DATE_SECTION_ID))
        assertEquals(listOf("first", "second"), sections.single().assets.map { it.id })
    }

    @Test
    fun artifactsToSkeletonProducesValidatedMonthAndDaySkeletons() {
        val firstAssets = sampleAssets(1, prefix = "a")
        val secondAssets = sampleAssets(2, prefix = "b")
        val first = TimelineMosaicSection("2026-01|day|2026-01-02", "2026-01|day|2026-01-02", firstAssets)
        val second = TimelineMosaicSection("2026-01|day|2026-01-01", "2026-01|day|2026-01-01", secondAssets)
        val dayArtifacts = builder.build(
            snapshot = TimelineBucketSnapshot("2026-01", firstAssets + secondAssets, expectedCount = 3),
            groupSize = TimelineGroupSize.DAY,
            groupMode = TimelineGroupSize.DAY.apiValue,
            columnCount = 5,
            familiesKey = "FOUR_TILE",
            geometryRequest = request,
            sections = listOf(first, second),
            readySections = listOf(
                readySection(first, height = 120f),
                readySection(second, height = 150f)
            ),
            cacheDisplayResults = true,
            updatedAt = 1L
        )
        requireNotNull(dayArtifacts)
        val daySkeleton = assertNotNull(dayArtifacts.toSkeleton(TimelineGroupSize.DAY, assetCount = 3))
        assertEquals("2026-01", daySkeleton.timeBucket)
        assertEquals(3, daySkeleton.assetCount)
        assertEquals(listOf(first.sectionKey, second.sectionKey), daySkeleton.sections.map { it.sectionKey })
        assertEquals(listOf(120f, 150f), daySkeleton.sections.map { it.placeholderHeight })
    }

    @Test
    fun precomputeResultIsCompleteRequiresBothMonthAndDaySummariesAndSkeletons() {
        val monthSummary = TimelineBucketGeometrySummary("2026-01", 200f, 1)
        val daySummary = TimelineBucketGeometrySummary("2026-01", 260f, 2)
        val monthSkeleton = TimelineMosaicBucketSkeleton(
            timeBucket = "2026-01",
            assetCount = 2,
            placeholderHeight = 200f,
            sections = listOf(
                TimelineMosaicGeometrySummary(
                    timeBucket = "2026-01",
                    sectionKey = "2026-01|month",
                    placeholderHeight = 200f,
                    displayItemCount = 1,
                    ranges = listOf(MosaicSectionGeometryRange(0, 2, 200f))
                )
            )
        )
        val daySkeleton = TimelineMosaicBucketSkeleton(
            timeBucket = "2026-01",
            assetCount = 2,
            placeholderHeight = 260f,
            sections = listOf(
                TimelineMosaicGeometrySummary(
                    timeBucket = "2026-01",
                    sectionKey = "2026-01|day|2026-01-01",
                    placeholderHeight = 200f,
                    displayItemCount = 1,
                    ranges = listOf(MosaicSectionGeometryRange(0, 2, 200f))
                )
            )
        )

        val complete = TimelineMosaicPrecomputeResult(
            successfulBucketIds = setOf("2026-01"),
            failedBucketIds = emptySet(),
            bucketGeometrySummaries = listOf(monthSummary),
            bucketGeometrySummariesByGroup = mapOf(
                TimelineGroupSize.MONTH to listOf(monthSummary),
                TimelineGroupSize.DAY to listOf(daySummary)
            ),
            bucketSkeletons = mapOf("2026-01" to monthSkeleton),
            bucketSkeletonsByGroup = mapOf(
                TimelineGroupSize.MONTH to mapOf("2026-01" to monthSkeleton),
                TimelineGroupSize.DAY to mapOf("2026-01" to daySkeleton)
            )
        )
        assertTrue(complete.isCompleteFor(setOf("2026-01")))

        val missingDaySkeleton = complete.copy(
            bucketSkeletonsByGroup = mapOf(
                TimelineGroupSize.MONTH to mapOf("2026-01" to monthSkeleton)
            )
        )
        assertFalse(missingDaySkeleton.isCompleteFor(setOf("2026-01")))

        val failedDayGroup = complete.copy(
            failedBucketIdsByGroup = mapOf(
                TimelineGroupSize.MONTH to emptySet(),
                TimelineGroupSize.DAY to setOf("2026-01")
            )
        )
        assertFalse(failedDayGroup.isCompleteFor(setOf("2026-01")))
    }

    private fun readySection(section: TimelineMosaicSection, height: Float): SectionReady =
        SectionReady(
            keyScope = keyScope(section),
            assignments = emptyList(),
            displayItems = listOf(fallbackRow(section, height)),
            geometry = SectionGeometry(
                keyScope = keyScope(section),
                placeholderHeight = height,
                displayItemCount = 1,
                ranges = listOf(MosaicSectionGeometryRange(0, section.assets.size, height))
            )
        )

    private fun fallbackRow(section: TimelineMosaicSection, height: Float): RowItem =
        RowItem(
            gridKey = "fallback_${section.sectionKey}",
            bucketIndex = 0,
            sectionLabel = section.label,
            photos = section.assets.map { asset ->
                PhotoItem(
                    gridKey = "p_${asset.id}",
                    bucketIndex = 0,
                    sectionLabel = section.label,
                    asset = asset
                )
            },
            rowHeight = height,
            isComplete = false,
            kind = RowItemKind.MOSAIC_FALLBACK,
            sourceStartIndex = 0,
            sourceCount = section.assets.size
        )

    private fun keyScope(section: TimelineMosaicSection): MosaicKeyScope =
        MosaicKeyScope(
            owner = MosaicOwnerKey(MosaicOwnerScope.TIMELINE_BUCKET, "2026-01"),
            sectionKey = section.sectionKey,
            columnCount = 5,
            familiesKey = "FOUR_TILE",
            contentFingerprint = "fp"
        )

    private fun sampleAssets(count: Int, prefix: String = "asset"): List<Asset> =
        List(count) { index ->
            Asset(
                id = "${prefix}_$index",
                type = AssetType.IMAGE,
                fileName = "${prefix}_$index.jpg",
                createdAt = "2026-01-01T00:00:00Z",
                thumbnailUrl = "",
                originalUrl = "",
                aspectRatio = 1f
            )
        }

    private fun sampleAsset(id: String, createdAt: String): Asset =
        Asset(
            id = id,
            type = AssetType.IMAGE,
            fileName = "$id.jpg",
            createdAt = createdAt,
            thumbnailUrl = "",
            originalUrl = "",
            aspectRatio = 1f
        )
}
