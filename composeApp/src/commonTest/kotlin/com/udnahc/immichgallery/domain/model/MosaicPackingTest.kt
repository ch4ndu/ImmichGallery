package com.udnahc.immichgallery.domain.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
            assertTrue(assignments.all { it.sourceCount in 4..8 })
        }
    }

    @Test
    fun unsupportedColumnCountsReturnNoAssignments() {
        val assets = sampleAssets(8)

        for (columnCount in listOf(0, 1, 9)) {
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
    fun assignmentValidationAllowsSourceGapsButRejectsWrongSliceIds() {
        val assets = sampleAssets(8)
        val validGap = manualAssignment(sourceStartIndex = 2, sourceCount = 4)

        assertTrue(
            validateMosaicAssignments(
                listOf(validGap),
                assets,
                MosaicTemplateFamily.defaultSet()
            )
        )
        assertFalse(
            validateMosaicAssignments(
                listOf(
                    validGap.copy(
                        tiles = validGap.tiles.mapIndexed { index, tile ->
                            if (index == 0) tile.copy(assetId = "asset_0") else tile
                        }
                    )
                ),
                assets,
                MosaicTemplateFamily.defaultSet()
            )
        )
    }

    @Test
    fun readyProjectionDoesNotReserveAssetsForWrongSourceSlice() {
        val assets = sampleAssets(8)
        val invalid = manualAssignment(0, 4).copy(
            tiles = (4..7).mapIndexed { index, sourceIndex ->
                MosaicTileAssignment("asset_$sourceIndex", index)
            }
        )

        val items = buildPhotoGridItemsWithMosaic(
            assets = assets,
            assignments = listOf(invalid),
            bucketIndex = 0,
            sectionLabel = "section",
            layoutSpec = MosaicLayoutSpec(4, 400f, 100f),
            spacing = 0f,
            maxRowHeight = 400f
        )

        assertEquals(assets.map { it.id }, collectedAssetIds(items))
        assertEquals(assets.map { it.id }.distinct(), collectedAssetIds(items))
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
    fun sourceCountsBelowFourReturnNoAssignments() {
        for (count in 1..3) {
            val assignments = buildMosaicAssignments(
                assets = sampleAssets(count),
                layoutSpec = layoutSpec(4),
                spacing = GRID_SPACING_DP
            )

            assertTrue(assignments.isEmpty(), "Expected no assignments for source count=$count")
        }
    }

    @Test
    fun sevenAndEightTileFamiliesBuildAssignments() {
        listOf(
            MosaicTemplateFamily.SEVEN_TILE to 7,
            MosaicTemplateFamily.EIGHT_TILE to 8
        ).forEach { (family, count) ->
            val assignments = buildMosaicAssignments(
                assets = sampleAssets(count),
                layoutSpec = layoutSpec(4),
                spacing = GRID_SPACING_DP,
                enabledFamilies = setOf(family)
            )

            assertTrue(assignments.isNotEmpty(), "Expected assignments for $family")
            assertEquals(count, assignments.first().sourceCount)
        }
    }

    @Test
    fun sevenAndEightTileCandidatePermutationsStayBounded() {
        assertEquals(720, mosaicCandidatePermutations(sampleAssets(6), 6).size)
        assertTrue(mosaicCandidatePermutations(sampleAssets(7), 7).size <= 8)
        assertTrue(mosaicCandidatePermutations(sampleAssets(8), 8).size <= 8)
    }

    @Test
    fun exhaustiveCandidatePermutationsArePrecomputedOnce() {
        val first = mosaicCandidatePermutations(sampleAssets(6), 6)
        val second = mosaicCandidatePermutations(sampleAssets(6), 6)

        assertSame(first, second)
        assertSame(first.first(), second.first())
    }

    @Test
    fun everyTemplateSolvesNestedSpacingExactlyAtMeasuredWidth() {
        val width = 451.25f
        val spacing = 2.25f

        MOSAIC_TEMPLATES.forEach { template ->
            val aspects = FloatArray(template.slotCount) { slot ->
                listOf(0.48f, 2.1f, 0.72f, 1.65f, 0.9f, 1.25f, 0.58f, 1.9f)[slot]
            }
            val height = template.root.heightForWidth(width, aspects, spacing)
            val rects = template.root.layout(0f, 0f, width, aspects, spacing)

            assertTrue(height.isFinite() && height > 0f, template.id)
            assertTrue(abs(template.root.widthForHeight(height, aspects, spacing) - width) < 0.01f, template.id)
            assertEquals(template.slotCount, rects.size, template.id)
            assertTrue(abs(rects.maxOf { it.x + it.width } - width) < 0.01f, template.id)
            assertTrue(abs(rects.maxOf { it.y + it.height } - height) < 0.01f, template.id)
            rects.forEach { rect ->
                assertTrue(rect.x >= 0f && rect.y >= 0f, template.id)
                assertTrue(rect.x + rect.width <= width + 0.01f, template.id)
                assertTrue(rect.y + rect.height <= height + 0.01f, template.id)
                assertTrue(abs(rect.width / rect.height - aspects[rect.slot]) < 0.01f, template.id)
            }
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    assertFalse(rects[i].overlaps(rects[j]), template.id)
                }
            }
        }
    }

    @Test
    fun portraitHeavyTwoColumnMosaicFillsExactWidthWithoutOverlap() {
        val assets = sampleAssets(count = 4, aspectRatio = 0.5f)
        val layoutSpec = MosaicLayoutSpec(columnCount = 2, availableWidth = 240f, cellHeight = 120f)
        val assignments = buildMosaicAssignments(
            assets = assets,
            layoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP,
            enabledFamilies = setOf(MosaicTemplateFamily.FOUR_TILE)
        )

        val band = assertIs<MosaicBandItem>(
            buildPhotoGridItemsWithMosaic(
                assets = assets,
                assignments = assignments,
                bucketIndex = 0,
                sectionLabel = "portrait",
                layoutSpec = layoutSpec,
                spacing = GRID_SPACING_DP,
                maxRowHeight = 600f
            ).single()
        )
        assertTrue(abs(band.tiles.maxOf { it.x + it.width } - layoutSpec.availableWidth) < 0.01f)
        assertTrue(abs(band.tiles.maxOf { it.y + it.height } - band.bandHeight) < 0.01f)
        for (i in band.tiles.indices) {
            for (j in i + 1 until band.tiles.size) {
                assertFalse(band.tiles[i].overlaps(band.tiles[j]))
            }
        }
    }

    @Test
    fun progressiveAssignmentsEmitContiguousBandChunks() = runTest {
        val chunks = mutableListOf<MosaicAssignmentProgressChunk>()
        val assignments = buildMosaicAssignmentsWithProgress(
            assets = sampleAssets(80),
            layoutSpec = layoutSpec(4),
            spacing = GRID_SPACING_DP,
            progressBandBatchSize = 2,
            maxRowHeight = 500f,
            onProgressChunk = { chunk -> chunks.add(chunk) }
        )

        assertTrue(assignments.isNotEmpty())
        assertTrue(chunks.size > 1)
        assertEquals(assignments, chunks.flatMap { it.assignments })
        var cursor = 0
        chunks.forEach { chunk ->
            assertEquals(cursor, chunk.sourceStartIndex)
            assertTrue(chunk.sourceEndExclusive > chunk.sourceStartIndex)
            cursor = chunk.sourceEndExclusive
        }
        assertEquals(
            assignments.last().sourceStartIndex + assignments.last().sourceCount,
            cursor
        )
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
        assertEquals(8, nearestMosaicColumnCount(900f, 100f))
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
    fun timelineMosaicLayoutSpecUsesFixedColumnCount() {
        val spec = assertIs<MosaicLayoutSpec>(mosaicLayoutSpecForColumnCount(600f, 4))

        assertEquals(4, spec.columnCount)
        assertEquals(600f, spec.availableWidth)
        assertEquals(150f, spec.cellHeight)
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
    fun fallbackRowBeforeLaterMosaicPreservesLaterMosaic() {
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

        val fallback = assertIs<RowItem>(items.first())
        assertEquals(RowItemKind.MOSAIC_FALLBACK, fallback.kind)
        assertEquals((0..1).map { "asset_$it" }, fallback.photos.map { it.asset.id })
        assertIs<MosaicBandItem>(items[1])
        assertEquals(collectedAssetIds(items).distinct(), collectedAssetIds(items))
    }

    @Test
    fun fallbackRowDoesNotDemoteLaterMosaicForSmallGap() {
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

        val fallback = assertIs<RowItem>(items.first())
        val mosaic = assertIs<MosaicBandItem>(items[1])
        assertEquals(RowItemKind.MOSAIC_FALLBACK, fallback.kind)
        assertEquals(MosaicBandKind.REAL, mosaic.kind)
        assertEquals(listOf("asset_0"), fallback.photos.map { it.asset.id })
        assertEquals(collectedAssetIds(items).distinct(), collectedAssetIds(items))
    }

    @Test
    fun mosaicFallbackEmitsOnlyFallbackRowsAndRealMosaicBands() {
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(aspectRatios = listOf(4f, 1f, 1f, 1f, 1f, 1f)),
            assignments = listOf(manualAssignment(sourceStartIndex = 2, sourceCount = 4)),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = MosaicLayoutSpec(columnCount = 3, availableWidth = 300f, cellHeight = 100f),
            spacing = 0f,
            maxRowHeight = 400f
        )

        assertTrue(items.isNotEmpty())
        assertTrue(items.all { item ->
            item is MosaicBandItem ||
                (item is RowItem && item.kind == RowItemKind.MOSAIC_FALLBACK)
        })
        assertEquals((0..5).map { "asset_$it" }, collectedAssetIds(items))
    }

    @Test
    fun twoAssetFallbackRendersSingleFallbackRow() {
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
        assertEquals(RowItemKind.MOSAIC_FALLBACK, row.kind)
        assertEquals(listOf("asset_0", "asset_1"), row.photos.map { it.asset.id })
        assertTrue(row.isComplete)
    }

    @Test
    fun singleAssetFallbackUsesSingleFallbackRow() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(aspectRatios = listOf(400f / layoutSpec.cellHeight)),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val row = assertIs<RowItem>(items.single())
        assertEquals(RowItemKind.MOSAIC_FALLBACK, row.kind)
        assertEquals(listOf("asset_0"), row.photos.map { it.asset.id })
    }

    @Test
    fun singleAssetEmptyAssignmentSectionUsesSmallGroupFallbackHeight() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 1, aspectRatio = 1f),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val row = assertIs<RowItem>(items.single())
        assertEquals(RowItemKind.MOSAIC_FALLBACK, row.kind)
        assertEquals(listOf("asset_0"), row.photos.map { it.asset.id })
        assertEquals(layoutSpec.cellHeight * 3f, row.rowHeight)
    }

    @Test
    fun smallGroupFallbackHeightIsClampedByMaxRowHeight() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 1, aspectRatio = 1f),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 150f
        )

        val row = assertIs<RowItem>(items.single())
        assertEquals(RowItemKind.MOSAIC_FALLBACK, row.kind)
        assertEquals(150f, row.rowHeight)
    }

    @Test
    fun twoAssetEmptyAssignmentSectionUsesSmallGroupFallbackHeight() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 2, aspectRatio = 1f),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val row = assertIs<RowItem>(items.single())
        assertEquals(RowItemKind.MOSAIC_FALLBACK, row.kind)
        assertEquals(listOf("asset_0", "asset_1"), row.photos.map { it.asset.id })
        assertEquals(layoutSpec.cellHeight * 3f, row.rowHeight)
    }

    @Test
    fun fourAssetEmptyAssignmentSectionUsesFallbackRows() {
        val layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f)
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 4, aspectRatio = 1f),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = layoutSpec,
            spacing = 0f,
            maxRowHeight = 400f
        )

        val rows = items.map { assertIs<RowItem>(it) }
        assertTrue(rows.all { it.kind == RowItemKind.MOSAIC_FALLBACK })
        assertEquals((0..3).map { "asset_$it" }, rows.flatMap { row -> row.photos.map { it.asset.id } })
        assertTrue(rows.all { it.rowHeight >= layoutSpec.cellHeight * 3f })
    }

    @Test
    fun emptyAssignmentsFallbackPacksBandsUsingFullMosaicCellHeight() {
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

        val rows = items.map { assertIs<RowItem>(it) }
        assertEquals(2, rows.size)
        assertEquals(6, rows.first().photos.size)
        assertEquals(layoutSpec.cellHeight, rows.first().rowHeight)
        assertEquals((0..9).map { "asset_$it" }, collectedAssetIds(items))
    }

    @Test
    fun wideMosaicFallbackBandUsesRepresentativeHeight() {
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
        assertEquals(RowItemKind.MOSAIC_FALLBACK, firstRow.kind)
        assertEquals(listOf("asset_0", "asset_1"), firstRow.photos.map { it.asset.id })
        assertTrue(mosaicFallbackMinRowHeight(layoutSpec, listOf(mosaic.bandHeight)) > mosaicFallbackMinRowHeight(layoutSpec))
        assertEquals(
            mosaicFallbackRowMinHeight(layoutSpec, assetCount = 6, realMosaicBandHeights = listOf(mosaic.bandHeight)),
            firstRow.rowHeight
        )
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
    fun finalFallbackRowMayContainFewerThanColumnCountAssets() {
        val items = buildPhotoGridItemsWithMosaic(
            assets = sampleAssets(count = 3, aspectRatio = 1f),
            assignments = emptyList(),
            bucketIndex = 0,
            sectionLabel = "May 2026",
            layoutSpec = MosaicLayoutSpec(columnCount = 4, availableWidth = 400f, cellHeight = 100f),
            spacing = 0f,
            maxRowHeight = 400f,
            promoteWideImages = false
        )

        val lastRow = assertIs<RowItem>(items.last())
        assertEquals(RowItemKind.MOSAIC_FALLBACK, lastRow.kind)
        assertEquals((0..2).map { "asset_$it" }, collectedAssetIds(items))
        assertEquals(listOf("asset_2"), lastRow.photos.map { it.asset.id })
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

    private fun MosaicTile.overlaps(other: MosaicTile): Boolean {
        val separated = x + width <= other.x ||
            other.x + other.width <= x ||
            y + height <= other.y ||
            other.y + other.height <= y
        return !separated
    }

    private fun TileRect.overlaps(other: TileRect): Boolean {
        val separated = x + width <= other.x ||
            other.x + other.width <= x ||
            y + height <= other.y ||
            other.y + other.height <= y
        return !separated
    }
}
