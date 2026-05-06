package com.udnahc.immichgallery.ui.screen.detail

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicDisplayBandKind
import com.udnahc.immichgallery.domain.model.MosaicDisplayBandRecord
import com.udnahc.immichgallery.domain.model.MosaicDisplayTileRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoGridMosaicRenderersTest {
    private val assetFingerprint = "asset-fingerprint"

    @Test
    fun cachedGroupReturnsResolvedRealBandsFromPersistentDisplayArtifacts() {
        val assets = sampleAssets(2)
        val group = IndexedAssetGroup(index = 0, label = "May 2026", assets = assets)
        val entry = cacheEntry(group, listOf(realBand(assets)), assetFingerprint)

        val cached = CachedPhotoGridMosaicRenderer().cachedGroup(
            group = group,
            assetFingerprint = assetFingerprint,
            cachedEntries = mapOf(
                DetailPersistentGroupKey(group.index, group.label, assetFingerprint) to entry
            )
        )

        checkNotNull(cached)
        assertEquals(1, cached.items.filterIsInstance<MosaicBandItem>().size)
        assertEquals(1, cached.resolvedBands.size)
        assertEquals(MosaicDisplayBandKind.REAL, cached.resolvedBands.single().kind)
        assertTrue(cached.resolvedBands.single().tiles.map { it.assetId }.containsAll(assets.map { it.id }))
    }

    @Test
    fun cachedGroupDoesNotReturnResolvedBandsForFallbackArtifacts() {
        val assets = sampleAssets(2)
        val group = IndexedAssetGroup(index = 0, label = "May 2026", assets = assets)
        val entry = cacheEntry(
            group = group,
            bands = listOf(realBand(assets).copy(kind = MosaicDisplayBandKind.FALLBACK)),
            assetFingerprint = assetFingerprint
        )

        val cached = CachedPhotoGridMosaicRenderer().cachedGroup(
            group = group,
            assetFingerprint = assetFingerprint,
            cachedEntries = mapOf(
                DetailPersistentGroupKey(group.index, group.label, assetFingerprint) to entry
            )
        )

        checkNotNull(cached)
        assertEquals(1, cached.items.filterIsInstance<MosaicBandItem>().size)
        assertTrue(cached.resolvedBands.isEmpty())
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

    private fun realBand(assets: List<Asset>): MosaicDisplayBandRecord =
        MosaicDisplayBandRecord(
            sourceStartIndex = 0,
            sourceCount = assets.size,
            bandHeight = 100f,
            kind = MosaicDisplayBandKind.REAL,
            tiles = assets.mapIndexed { index, asset ->
                MosaicDisplayTileRecord(
                    assetId = asset.id,
                    visualOrder = index,
                    x = index * 100f,
                    y = 0f,
                    width = 100f,
                    height = 100f
                )
            }
        )

    private fun cacheEntry(
        group: IndexedAssetGroup,
        bands: List<MosaicDisplayBandRecord>,
        assetFingerprint: String
    ): DetailMosaicCacheEntry =
        DetailMosaicCacheEntry(
            ownerType = DetailMosaicCacheOwnerType.ALBUM,
            ownerId = "album",
            groupSize = GroupSize.MONTH,
            columnCount = 4,
            sectionIndex = group.index,
            sectionKey = group.label,
            familiesKey = "FOUR_TILE",
            assetFingerprint = assetFingerprint,
            availableWidthKey = 360,
            cellHeightKey = 6000,
            maxRowHeightKey = 60000,
            spacingKey = 400,
            displayVersion = 1,
            bands = bands,
            displayItemCount = bands.size,
            placeholderHeight = bands.sumOf { it.bandHeight.toDouble() }.toFloat(),
            updatedAt = 0L
        )
}
