package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MosaicDisplayCacheTest {
    @Test
    fun viewConfigDefaultsEnableCacheAndDisableMosaicZoom() {
        val config = ViewConfig()

        assertTrue(config.cacheMosaicResults)
        assertTrue(config.disableZoomWhenMosaicEnabled)
    }

    @Test
    fun cacheDimensionKeysMatchExpectedPrecision() {
        assertEquals(401, mosaicDisplayCacheWidthKey(400.6f))
        assertEquals(12346, mosaicDisplayCacheDimensionKey(123.456f))
    }

    @Test
    fun familiesKeyIsNormalizedAndStable() {
        val key = mosaicDisplayCacheFamiliesKey(
            setOf(MosaicTemplateFamily.SIX_TILE, MosaicTemplateFamily.FOUR_TILE)
        )

        assertEquals("FOUR_TILE|SIX_TILE", key)
    }

    @Test
    fun displayRecordsMustCoverOrderedAssetsWithoutGapsOrUnknownIds() {
        val assets = sampleAssets(3)
        val validRecords = listOf(
            MosaicDisplayItemRecord(
                kind = MosaicDisplayItemRecordKind.REAL_BAND,
                sourceStartIndex = 0,
                sourceCount = 2,
                height = 100f,
                tiles = listOf(
                    MosaicDisplayTileRecord("asset_0", 0, 0f, 0f, 100f, 100f),
                    MosaicDisplayTileRecord("asset_1", 1, 100f, 0f, 100f, 100f)
                )
            ),
            MosaicDisplayItemRecord(
                kind = MosaicDisplayItemRecordKind.MOSAIC_FALLBACK_ROW,
                sourceStartIndex = 2,
                sourceCount = 1,
                height = 100f,
                assetIds = listOf("asset_2")
            )
        )

        assertTrue(validRecords.displayRecordsCoverOrderedAssets(assets))
        assertTrue(!validRecords.drop(1).displayRecordsCoverOrderedAssets(assets))
        assertTrue(
            !validRecords.mapIndexed { index, record ->
                if (index == 1) record.copy(assetIds = listOf("missing")) else record
            }.displayRecordsCoverOrderedAssets(assets)
        )
    }

    @Test
    fun displayRecordsRejectStoredRecordOrderThatDoesNotMatchSourceOrder() {
        val assets = sampleAssets(3)
        val records = listOf(
            fallbackRecord(sourceStartIndex = 1, assetIds = listOf("asset_1")),
            fallbackRecord(sourceStartIndex = 0, assetIds = listOf("asset_0")),
            fallbackRecord(sourceStartIndex = 2, assetIds = listOf("asset_2"))
        )

        assertTrue(!records.displayRecordsCoverOrderedAssets(assets))
    }

    @Test
    fun displayRecordsRejectFallbackRowsWithWrongAssetOrder() {
        val assets = sampleAssets(3)
        val records = listOf(
            MosaicDisplayItemRecord(
                kind = MosaicDisplayItemRecordKind.MOSAIC_FALLBACK_ROW,
                sourceStartIndex = 0,
                sourceCount = 3,
                height = 100f,
                assetIds = listOf("asset_1", "asset_0", "asset_2")
            )
        )

        assertTrue(!records.displayRecordsCoverOrderedAssets(assets))
    }

    @Test
    fun displayRecordsAllowRealBandVisualOrderToDifferFromSourceOrder() {
        val assets = sampleAssets(2)
        val records = listOf(
            MosaicDisplayItemRecord(
                kind = MosaicDisplayItemRecordKind.REAL_BAND,
                sourceStartIndex = 0,
                sourceCount = 2,
                height = 100f,
                tiles = listOf(
                    MosaicDisplayTileRecord("asset_1", 0, 100f, 0f, 100f, 100f),
                    MosaicDisplayTileRecord("asset_0", 1, 0f, 0f, 100f, 100f)
                )
            )
        )

        assertTrue(records.displayRecordsCoverOrderedAssets(assets))
    }

    @Test
    fun displayRecordsRejectInvalidRealBandVisualOrder() {
        val assets = sampleAssets(2)
        val duplicateVisualOrder = listOf(
            MosaicDisplayItemRecord(
                kind = MosaicDisplayItemRecordKind.REAL_BAND,
                sourceStartIndex = 0,
                sourceCount = 2,
                height = 100f,
                tiles = listOf(
                    MosaicDisplayTileRecord("asset_0", 0, 0f, 0f, 100f, 100f),
                    MosaicDisplayTileRecord("asset_1", 0, 100f, 0f, 100f, 100f)
                )
            )
        )
        val outOfRangeVisualOrder = duplicateVisualOrder.map { record ->
            record.copy(tiles = record.tiles.mapIndexed { index, tile -> tile.copy(visualOrder = index + 1) })
        }

        assertTrue(!duplicateVisualOrder.displayRecordsCoverOrderedAssets(assets))
        assertTrue(!outOfRangeVisualOrder.displayRecordsCoverOrderedAssets(assets))
    }

    @Test
    fun resolvedSectionDisplayRecordsConvertCompleteRealBands() {
        val assets = sampleAssets(3)
        val records = resolvedSectionDisplayRecordsOrEmpty(
            displayItems = listOf(
                mosaicBand(
                    assets = assets,
                    sourceStartIndex = 0,
                    sourceCount = 2
                ),
                mosaicBand(
                    assets = assets,
                    sourceStartIndex = 2,
                    sourceCount = 1
                )
            ),
            assets = assets
        )

        assertEquals(2, records.size)
        assertEquals(listOf(0, 2), records.map { it.sourceStartIndex })
        assertEquals(listOf(2, 1), records.map { it.sourceCount })
        assertTrue(records.all { it.kind == MosaicDisplayItemRecordKind.REAL_BAND })
        assertTrue(records.displayRecordsCoverOrderedAssets(assets))
    }

    @Test
    fun resolvedSectionDisplayRecordsRejectFallbackBands() {
        val assets = sampleAssets(2)
        val records = resolvedSectionDisplayRecordsOrEmpty(
            displayItems = listOf(
                mosaicBand(
                    assets = assets,
                    sourceStartIndex = 0,
                    sourceCount = 2,
                    kind = MosaicBandKind.FALLBACK
                )
            ),
            assets = assets
        )

        assertTrue(records.isEmpty())
    }

    @Test
    fun resolvedSectionDisplayRecordsRejectNonDisplayItems() {
        val assets = sampleAssets(1)
        val records = resolvedSectionDisplayRecordsOrEmpty(
            displayItems = listOf(
                HeaderItem(
                    gridKey = "header",
                    bucketIndex = 0,
                    sectionLabel = "section",
                    label = "section"
                ),
                mosaicBand(
                    assets = assets,
                    sourceStartIndex = 0,
                    sourceCount = 1
                )
            ),
            assets = assets
        )

        assertTrue(records.isEmpty())
    }

    @Test
    fun resolvedSectionDisplayRecordsRejectInvalidCoverage() {
        val assets = sampleAssets(2)
        val records = resolvedSectionDisplayRecordsOrEmpty(
            displayItems = listOf(
                mosaicBand(
                    assets = assets,
                    sourceStartIndex = 1,
                    sourceCount = 1
                )
            ),
            assets = assets
        )

        assertTrue(records.isEmpty())
    }

    private fun sampleAssets(count: Int): List<Asset> =
        List(count) { index ->
            Asset(
                id = "asset_$index",
                type = AssetType.IMAGE,
                fileName = "asset_$index.jpg",
                createdAt = "2026-05-03T00:00:00Z",
                thumbnailUrl = "",
                originalUrl = "",
                aspectRatio = 1f
            )
        }

    private fun mosaicBand(
        assets: List<Asset>,
        sourceStartIndex: Int,
        sourceCount: Int,
        kind: MosaicBandKind = MosaicBandKind.REAL
    ): MosaicBandItem {
        val bandAssets = assets.subList(sourceStartIndex, sourceStartIndex + sourceCount)
        return MosaicBandItem(
            gridKey = "band_$sourceStartIndex",
            bucketIndex = 0,
            sectionLabel = "section",
            tiles = bandAssets.mapIndexed { index, asset ->
                MosaicTile(
                    photo = PhotoItem(
                        gridKey = "photo_${asset.id}",
                        bucketIndex = 0,
                        sectionLabel = "section",
                        asset = asset
                    ),
                    x = index * 100f,
                    y = 0f,
                    width = 100f,
                    height = 100f,
                    visualOrder = index
                )
            },
            bandHeight = 100f,
            sourceStartIndex = sourceStartIndex,
            sourceCount = sourceCount,
            kind = kind
        )
    }

    private fun fallbackRecord(
        sourceStartIndex: Int,
        assetIds: List<String>
    ): MosaicDisplayItemRecord =
        MosaicDisplayItemRecord(
            kind = MosaicDisplayItemRecordKind.MOSAIC_FALLBACK_ROW,
            sourceStartIndex = sourceStartIndex,
            sourceCount = assetIds.size,
            height = 100f,
            assetIds = assetIds
        )
}
