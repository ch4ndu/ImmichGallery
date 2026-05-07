package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MosaicRenderEngineTest {
    private val engine = MosaicRenderEngine()

    @Test
    fun progressiveChunksUseSharedEngineAndProjectPlaceholdersForUnresolvedRanges() = runTest {
        val chunks = mutableListOf<ProgressChunk>()
        val request = request(sampleAssets(80))

        val result = engine.computeSection(
            request = request,
            onProgressChunk = chunks::add
        )

        val ready = assertIs<MosaicSectionResult.Ready>(result).value
        assertTrue(chunks.size > 1)
        assertEquals(ready.assignments, chunks.flatMap { it.assignments })

        val partialItems = engine.projectPartialSection(
            assets = request.assets,
            chunks = chunks.take(1),
            bucketIndex = request.bucketIndex,
            sectionLabel = request.sectionLabel,
            layoutSpec = request.displayLayoutSpec,
            spacing = request.spacing,
            maxRowHeight = request.maxRowHeight
        )

        assertTrue(partialItems.any { it is MosaicBandItem && it.kind == MosaicBandKind.REAL })
        assertTrue(partialItems.any { it is PlaceholderItem })
        assertTrue(partialItems.none { it is MosaicBandItem && it.kind == MosaicBandKind.FALLBACK })
        assertTrue(partialItems.none { it is RowItem })
    }

    @Test
    fun sectionAndAggregateGeometryAreStableForIdenticalInputs() = runTest {
        val request = request(sampleAssets(12))
        val ready = assertIs<MosaicSectionResult.Ready>(engine.computeSection(request)).value

        val first = engine.computeSectionGeometry(request.keyScope, ready.displayItems, request.spacing)
        val second = engine.computeSectionGeometry(request.keyScope, ready.displayItems, request.spacing)
        val aggregate = engine.computeAggregateGeometry(
            owner = request.keyScope.owner,
            key = "album",
            sectionGeometries = listOf(first, second),
            spacing = request.spacing
        )

        assertEquals(first, second)
        assertEquals(first.placeholderHeight * 2 + request.spacing, aggregate.placeholderHeight)
        assertEquals(first.displayItemCount * 2, aggregate.displayItemCount)
    }

    @Test
    fun geometryBackedPartialProjectionPreservesReadyHeight() {
        val request = request(sampleAssets(4))
        val geometryRanges = listOf(
            MosaicSectionGeometryRange(sourceStartIndex = 0, sourceCount = 2, height = 140f),
            MosaicSectionGeometryRange(sourceStartIndex = 2, sourceCount = 2, height = 160f)
        )
        val expectedHeight = 140f + request.spacing + 160f
        val partialItems = engine.projectPartialSectionWithGeometry(
            assets = request.assets,
            chunks = emptyList(),
            geometryRanges = geometryRanges,
            bucketIndex = request.bucketIndex,
            sectionLabel = request.sectionLabel,
            layoutSpec = request.displayLayoutSpec,
            spacing = request.spacing,
            maxRowHeight = request.maxRowHeight
        )

        assertTrue(requireNotNull(partialItems).any { it is PlaceholderItem })
        assertEquals(
            expectedHeight,
            estimatePhotoGridDisplayItemsHeight(partialItems, request.spacing),
            absoluteTolerance = 0.5f
        )
    }

    @Test
    fun partialProjectionUsesPlaceholdersForUnstableChunksAcrossRanges() {
        val request = request(sampleAssets(12))
        val chunks = listOf(
            ProgressChunk(
                keyScope = request.keyScope,
                sectionLabel = request.sectionLabel,
                sourceStartIndex = 0,
                sourceEndExclusive = 4,
                assignments = emptyList()
            ),
            ProgressChunk(
                keyScope = request.keyScope,
                sectionLabel = request.sectionLabel,
                sourceStartIndex = 4,
                sourceEndExclusive = 8,
                assignments = emptyList()
            )
        )

        val items = engine.projectPartialSection(
            assets = request.assets,
            chunks = chunks,
            bucketIndex = request.bucketIndex,
            sectionLabel = request.sectionLabel,
            layoutSpec = request.displayLayoutSpec,
            spacing = request.spacing,
            maxRowHeight = request.maxRowHeight
        )
        assertTrue(items.all { it is PlaceholderItem })
        assertEquals(items.size, items.map { it.gridKey }.toSet().size)
    }

    @Test
    fun strictPartialProjectionUsesPlaceholdersForInvalidChunks() {
        val request = request(sampleAssets(12))
        val chunks = listOf(
            ProgressChunk(
                keyScope = request.keyScope,
                sectionLabel = request.sectionLabel,
                sourceStartIndex = 0,
                sourceEndExclusive = 4,
                assignments = emptyList()
            )
        )

        val items = engine.projectPartialSectionWithPlaceholders(
            assets = request.assets,
            chunks = chunks,
            bucketIndex = request.bucketIndex,
            sectionLabel = request.sectionLabel,
            layoutSpec = request.displayLayoutSpec,
            spacing = request.spacing,
            maxRowHeight = request.maxRowHeight
        )

        assertTrue(items.any { it is PlaceholderItem })
        assertTrue(items.none { it is MosaicBandItem && it.kind == MosaicBandKind.FALLBACK })
        assertTrue(items.none { it is RowItem })
    }

    @Test
    fun readyProjectionMayUseFallbackRowsAfterCompletion() {
        val request = request(sampleAssets(5))

        val items = engine.projectReadySection(
            assets = request.assets,
            assignments = emptyList(),
            bucketIndex = request.bucketIndex,
            sectionLabel = request.sectionLabel,
            layoutSpec = request.displayLayoutSpec,
            spacing = request.spacing,
            maxRowHeight = request.maxRowHeight
        )

        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it is RowItem })
        assertTrue(items.all { it is RowItem && it.kind == RowItemKind.MOSAIC_FALLBACK })
        assertEquals(
            request.assets.map { it.id },
            items.filterIsInstance<RowItem>().flatMap { row -> row.photos.map { it.asset.id } }
        )
    }

    private fun request(assets: List<Asset>): MosaicSectionRequest {
        val layoutSpec = MosaicLayoutSpec(
            columnCount = 4,
            availableWidth = 400f,
            cellHeight = 100f
        )
        return MosaicSectionRequest(
            keyScope = MosaicKeyScope(
                owner = MosaicOwnerKey(MosaicOwnerScope.ALBUM, "album_1"),
                sectionKey = "section_1",
                columnCount = 4,
                familiesKey = mosaicDisplayCacheFamiliesKey(MosaicTemplateFamily.defaultSet()),
                contentFingerprint = assets.joinToString("|") { it.id }
            ),
            assets = assets,
            bucketIndex = 0,
            sectionLabel = "May 2026",
            assignmentLayoutSpec = layoutSpec,
            displayLayoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP,
            maxRowHeight = 500f,
            progressBandBatchSize = 2
        )
    }

    private fun sampleAssets(count: Int): List<Asset> {
        val aspects = listOf(1f, 1.4f, 0.75f, 1.8f, 0.9f, 1.2f, 1f, 1.6f)
        return List(count) { index ->
            Asset(
                id = "asset_$index",
                type = AssetType.IMAGE,
                fileName = "asset_$index.jpg",
                createdAt = "2026-05-03T00:00:00Z",
                thumbnailUrl = "",
                originalUrl = "",
                aspectRatio = aspects[index % aspects.size]
            )
        }
    }
}
