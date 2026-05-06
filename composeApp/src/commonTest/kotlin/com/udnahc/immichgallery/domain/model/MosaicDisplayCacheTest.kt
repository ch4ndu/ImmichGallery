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
    fun displayBandsMustCoverOrderedAssetsWithoutGapsOrUnknownIds() {
        val assets = sampleAssets(3)
        val validBands = listOf(
            MosaicDisplayBandRecord(
                sourceStartIndex = 0,
                sourceCount = 2,
                bandHeight = 100f,
                kind = MosaicDisplayBandKind.REAL,
                tiles = listOf(
                    MosaicDisplayTileRecord("asset_0", 0, 0f, 0f, 100f, 100f),
                    MosaicDisplayTileRecord("asset_1", 1, 100f, 0f, 100f, 100f)
                )
            ),
            MosaicDisplayBandRecord(
                sourceStartIndex = 2,
                sourceCount = 1,
                bandHeight = 100f,
                kind = MosaicDisplayBandKind.FALLBACK,
                tiles = listOf(
                    MosaicDisplayTileRecord("asset_2", 0, 0f, 0f, 100f, 100f)
                )
            )
        )

        assertTrue(validBands.coversOrderedAssets(assets))
        assertTrue(!validBands.drop(1).coversOrderedAssets(assets))
        assertTrue(
            !validBands.mapIndexed { index, band ->
                if (index == 1) band.copy(tiles = listOf(band.tiles.first().copy(assetId = "missing"))) else band
            }.coversOrderedAssets(assets)
        )
    }

    @Test
    fun resolvedSectionDisplayBandsConvertCompleteRealBands() {
        val assets = sampleAssets(3)
        val records = resolvedSectionDisplayBandsOrEmpty(
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
        assertTrue(records.all { it.kind == MosaicDisplayBandKind.REAL })
        assertTrue(records.coversOrderedAssets(assets))
    }

    @Test
    fun resolvedSectionDisplayBandsRejectFallbackBands() {
        val assets = sampleAssets(2)
        val records = resolvedSectionDisplayBandsOrEmpty(
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
    fun resolvedSectionDisplayBandsRejectNonBandItems() {
        val assets = sampleAssets(1)
        val records = resolvedSectionDisplayBandsOrEmpty(
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
    fun resolvedSectionDisplayBandsRejectInvalidCoverage() {
        val assets = sampleAssets(2)
        val records = resolvedSectionDisplayBandsOrEmpty(
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
}
