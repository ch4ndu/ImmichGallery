package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
