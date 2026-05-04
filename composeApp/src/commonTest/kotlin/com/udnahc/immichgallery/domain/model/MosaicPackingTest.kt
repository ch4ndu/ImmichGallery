package com.udnahc.immichgallery.domain.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MosaicPackingTest {
    @Test
    fun buildsRuntimeAssignmentsForAllSupportedColumnCounts() {
        val assets = sampleAssets(8)

        for (columnCount in SUPPORTED_MOSAIC_COLUMN_COUNTS) {
            val assignments = buildMosaicAssignments(
                assets = assets,
                layoutSpec = layoutSpec(columnCount),
                spacing = GRID_SPACING_DP
            )

            assertTrue(assignments.isNotEmpty(), "Expected runtime assignments for columnCount=$columnCount")
            assertTrue(assignments.all { it.sourceCount in 4..6 })
        }
    }

    @Test
    fun unsupportedColumnCountsReturnNoAssignments() {
        val assets = sampleAssets(8)

        for (columnCount in listOf(1, 7)) {
            val assignments = buildMosaicAssignments(
                assets = assets,
                layoutSpec = layoutSpec(columnCount),
                spacing = GRID_SPACING_DP
            )

            assertTrue(assignments.isEmpty(), "Expected no assignments for columnCount=$columnCount")
        }
    }

    @Test
    fun enabledTemplateFamiliesLimitAssignmentSizes() {
        val assignments = buildMosaicAssignments(
            assets = sampleAssets(8),
            layoutSpec = layoutSpec(4),
            spacing = GRID_SPACING_DP,
            enabledFamilies = setOf(MosaicTemplateFamily.FOUR_TILE)
        )

        assertTrue(assignments.isNotEmpty())
        assertTrue(assignments.all { it.sourceCount == 4 })
    }

    @Test
    fun emptyTemplateFamiliesReturnNoAssignments() {
        val assignments = buildMosaicAssignments(
            assets = sampleAssets(8),
            layoutSpec = layoutSpec(4),
            spacing = GRID_SPACING_DP,
            enabledFamilies = emptySet()
        )

        assertTrue(assignments.isEmpty())
    }

    @Test
    fun mosaicGeometryPreservesAspectAndDoesNotOverlap() {
        val assets = sampleAssets(6)
        val assignments = buildMosaicAssignments(
            assets = assets,
            layoutSpec = layoutSpec(4),
            spacing = GRID_SPACING_DP
        )
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 600f, cellHeight = 150f)

        val items = buildPhotoGridItemsWithMosaic(
            assets = assets,
            assignments = assignments,
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP,
            maxRowHeight = 400f
        )

        val band = assertIs<MosaicBandItem>(items.first())
        assertTrue(abs((band.tiles.maxOf { it.x + it.width }) - 600f) < 0.1f)
        for (tile in band.tiles) {
            assertTrue(abs((tile.width / tile.height) - tile.photo.asset.aspectRatio) < 0.01f)
        }
        for (i in band.tiles.indices) {
            for (j in i + 1 until band.tiles.size) {
                assertFalse(band.tiles[i].overlaps(band.tiles[j]))
            }
        }
    }

    @Test
    fun nearestColumnCountRoundsAndClampsToSupportedRange() {
        assertEquals(2, nearestMosaicColumnCount(220f, 100f))
        assertEquals(4, nearestMosaicColumnCount(360f, 100f))
        assertEquals(6, nearestMosaicColumnCount(900f, 100f))
    }

    @Test
    fun mosaicLayoutSpecUsesTargetHeightOnlyToChooseColumnCount() {
        val spec = assertIs<MosaicLayoutSpec>(mosaicLayoutSpecFor(452.35602f, 95.38198f))

        assertEquals(5, spec.columnCount)
        assertEquals(452.35602f, spec.availableWidth)
        assertEquals(452.35602f / 5, spec.cellHeight)
        assertTrue(spec.cellHeight < 95.38198f)
    }

    @Test
    fun denseZoomColumnCountsCanBuildMosaicAssignments() {
        val layoutSpec = assertIs<MosaicLayoutSpec>(mosaicLayoutSpecFor(452.35602f, 95.38198f))
        val assignments = buildMosaicAssignments(
            assets = sampleAssets(8),
            layoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP
        )

        assertEquals(5, layoutSpec.columnCount)
        assertTrue(assignments.isNotEmpty())
    }

    @Test
    fun mosaicBandAtFiveTimesCellHeightRemainsValid() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 5, availableWidth = 500f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 4, aspectRatio = 0.75f),
            assignments = listOf(manualAssignment(sourceStartIndex = 0, sourceCount = 4)),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val band = assertIs<MosaicBandItem>(items.single())
        assertTrue(band.bandHeight > layoutSpec.cellHeight * 4f)
        assertTrue(band.bandHeight <= layoutSpec.cellHeight * MOSAIC_MAX_BAND_HEIGHT_TARGET_MULTIPLIER)
    }

    @Test
    fun displayValidationUsesMosaicCellHeightInsteadOfTinyTargetHeight() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 5, availableWidth = 500f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 4, aspectRatio = 0.75f),
            assignments = listOf(manualAssignment(sourceStartIndex = 0, sourceCount = 4)),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        assertIs<MosaicBandItem>(items.single())
    }

    @Test
    fun fallbackRowBeforeLaterMosaicCompletesWithoutDemotingMosaic() {
        val assets = sampleAssets(count = 8, aspectRatio = 1f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = assets,
            assignments = listOf(manualAssignment(sourceStartIndex = 2, sourceCount = 4)),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f),
            spacing = 0f,
            maxRowHeight = 400f,
            promoteWideImages = false
        )

        val row = assertIs<RowItem>(items.first())
        assertTrue(row.isComplete)
        assertEquals((0..1).map { "asset_$it" }, row.photos.map { it.asset.id })
        assertIs<MosaicBandItem>(items[1])
        assertEquals(collectedAssetIds(items).distinct(), collectedAssetIds(items))
    }

    @Test
    fun fallbackPackingDemotesLaterMosaicWhenBoundaryWouldLeaveIncompleteRow() {
        val assets = sampleAssets(count = 8, aspectRatio = 1f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = assets,
            assignments = listOf(manualAssignment(sourceStartIndex = 1, sourceCount = 4)),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f),
            spacing = 0f,
            maxRowHeight = 400f,
            promoteWideImages = false
        )

        val row = assertIs<RowItem>(items.first())
        assertTrue(row.isComplete)
        assertEquals((0..1).map { "asset_$it" }, row.photos.map { it.asset.id })
        assertTrue(items.none { it is MosaicBandItem })
        assertEquals(collectedAssetIds(items).distinct(), collectedAssetIds(items))
    }

    @Test
    fun mosaicFallbackDoesNotEmitNonFinalIncompleteRowsOrSinglePhotoCompleteRows() {
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(aspectRatios = listOf(4f, 1f, 1f, 1f, 1f, 1f)),
            assignments = listOf(manualAssignment(sourceStartIndex = 2, sourceCount = 4)),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = MosaicLayoutSpec(columnCount = 3, availableWidth = 300f, cellHeight = 100f),
            spacing = 0f,
            maxRowHeight = 400f
        )

        val firstRow = assertIs<RowItem>(items.first())
        assertTrue(firstRow.isComplete)
        assertEquals(listOf("asset_0", "asset_1"), firstRow.photos.map { it.asset.id })
        assertTrue(items.dropLast(1).filterIsInstance<RowItem>().all { it.isComplete })
        assertTrue(items.filterIsInstance<RowItem>().none { it.isComplete && it.photos.size == 1 })
        assertIs<MosaicBandItem>(items.last())
    }

    @Test
    fun mosaicFallbackCanIntentionallyRenderFullSpanJustifiedRows() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(aspectRatios = listOf(3f, 3f)),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val row = assertIs<RowItem>(items.single())
        assertTrue(row.isComplete)
        assertEquals(listOf("asset_0", "asset_1"), row.photos.map { it.asset.id })
        assertTrue(abs(row.contentWidth(spacing = 0f) - layoutSpec.availableWidth) < 0.1f)
    }

    @Test
    fun mosaicFallbackCanLeaveFinalWidePhotoFullSpanButIncomplete() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(aspectRatios = listOf(400f / mosaicFallbackMinRowHeight(layoutSpec))),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val row = assertIs<RowItem>(items.single())
        assertFalse(row.isComplete)
        assertEquals(listOf("asset_0"), row.photos.map { it.asset.id })
        assertEquals(mosaicFallbackMinRowHeight(layoutSpec), row.rowHeight)
        assertTrue(abs(row.contentWidth(spacing = 0f) - layoutSpec.availableWidth) < 0.1f)
    }

    @Test
    fun emptyAssignmentsFallbackPacksRowsUsingMosaicFallbackTarget() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 6, availableWidth = 600f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 10, aspectRatio = 1f),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val firstRow = assertIs<RowItem>(items.first())
        assertEquals(8, firstRow.photos.size)
        assertEquals(mosaicFallbackMinRowHeight(layoutSpec), firstRow.rowHeight)
    }

    @Test
    fun wideMosaicFallbackRowKeepsAspectCorrectHeight() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 3, availableWidth = 300f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(aspectRatios = listOf(4f, 1f, 1f, 1f, 1f, 1f)),
            assignments = listOf(manualAssignment(sourceStartIndex = 2, sourceCount = 4)),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val firstRow = assertIs<RowItem>(items.first())
        val mosaic = assertIs<MosaicBandItem>(items.last())
        assertTrue(firstRow.isComplete)
        assertEquals(listOf("asset_0", "asset_1"), firstRow.photos.map { it.asset.id })
        assertTrue(mosaicFallbackMinRowHeight(layoutSpec, listOf(mosaic.bandHeight)) > mosaicFallbackMinRowHeight(layoutSpec))
        assertEquals(60f, firstRow.rowHeight)
    }

    @Test
    fun largeSyntheticSectionBuildsMultipleAssignments() {
        val assignments = buildMosaicAssignments(
            assets = sampleAssets(120),
            layoutSpec = layoutSpec(6),
            spacing = GRID_SPACING_DP
        )

        assertTrue(assignments.size >= 10)
        assertTrue(assignments.sumOf { it.sourceCount } >= 60)
    }

    @Test
    fun finalFallbackRowMayRemainIncomplete() {
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 2, aspectRatio = 1f),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f),
            spacing = 0f,
            maxRowHeight = 400f,
            promoteWideImages = false
        )

        val row = assertIs<RowItem>(items.single())
        assertFalse(row.isComplete)
        assertEquals(listOf("asset_0", "asset_1"), row.photos.map { it.asset.id })
    }

    private fun sampleAssets(count: Int): List<Asset> {
        return sampleAssets(count = count, aspectRatio = null)
    }

    private fun layoutSpec(columnCount: Int): MosaicLayoutSpec =
        MosaicLayoutSpec(
            columnCount = columnCount,
            availableWidth = columnCount * MOSAIC_CANONICAL_CELL_SIZE,
            cellHeight = MOSAIC_CANONICAL_CELL_SIZE
        )

    private fun sampleAssets(count: Int, aspectRatio: Float?): List<Asset> {
        val aspects = listOf(1f, 1.4f, 0.75f, 1.8f, 0.9f, 1.2f, 1f, 1.6f)
        return List(count) { index ->
            Asset(
                id = "asset_$index",
                type = AssetType.IMAGE,
                fileName = "asset_$index.jpg",
                createdAt = "2026-05-03T00:00:00Z",
                thumbnailUrl = "",
                originalUrl = "",
                aspectRatio = aspectRatio ?: aspects[index % aspects.size]
            )
        }
    }

    private fun sampleAssets(aspectRatios: List<Float>): List<Asset> {
        return aspectRatios.mapIndexed { index, aspectRatio ->
            Asset(
                id = "asset_$index",
                type = AssetType.IMAGE,
                fileName = "asset_$index.jpg",
                createdAt = "2026-05-03T00:00:00Z",
                thumbnailUrl = "",
                originalUrl = "",
                aspectRatio = aspectRatio
            )
        }
    }

    private fun manualAssignment(
        sourceStartIndex: Int,
        sourceCount: Int
    ): MosaicBandAssignment =
        MosaicBandAssignment(
            bandIndex = 0,
            sourceStartIndex = sourceStartIndex,
            sourceCount = sourceCount,
            templateId = "4_feature_left_stack_3",
            tiles = (0 until sourceCount).map { index ->
                MosaicTileAssignment(
                    assetId = "asset_${sourceStartIndex + index}",
                    visualOrder = index
                )
            }
        )

    private fun collectedAssetIds(items: List<PhotoGridDisplayItem>): List<String> =
        items.flatMap { item ->
            when (item) {
                is RowItem -> item.photos.map { it.asset.id }
                is MosaicBandItem -> item.tiles.map { it.photo.asset.id }
                else -> emptyList()
            }
        }

    private fun RowItem.contentWidth(spacing: Float): Float =
        photos.sumOf { (it.asset.aspectRatio * rowHeight).toDouble() }.toFloat() +
            spacing * (photos.size - 1)

    private fun MosaicTile.overlaps(other: MosaicTile): Boolean {
        val separated = x + width <= other.x ||
            other.x + other.width <= x ||
            y + height <= other.y ||
            other.y + other.height <= y
        return !separated
    }
}
