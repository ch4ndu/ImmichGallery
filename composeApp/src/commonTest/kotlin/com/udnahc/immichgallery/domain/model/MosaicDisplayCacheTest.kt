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
}
