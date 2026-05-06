package com.udnahc.immichgallery.ui.screen.detail

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicDisplayBandRecord
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.coversOrderedAssets
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.resolvedSectionDisplayBandsOrEmpty
import com.udnahc.immichgallery.domain.model.toDisplayRecord
import com.udnahc.immichgallery.domain.model.toMosaicDisplayItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

internal data class IndexedAssetGroup(
    val index: Int,
    val label: String,
    val assets: List<Asset>
)

internal data class DetailPersistentGroupKey(
    val sectionIndex: Int,
    val sectionKey: String,
    val assetFingerprint: String
)

internal data class CachedPhotoGridMosaicGroup(
    val items: List<PhotoGridDisplayItem>,
    val resolvedBands: List<MosaicDisplayBandRecord>
)

internal class CachedPhotoGridMosaicRenderer {
    fun cachedGroup(
        group: IndexedAssetGroup,
        assetFingerprint: String,
        cachedEntries: Map<DetailPersistentGroupKey, DetailMosaicCacheEntry>
    ): CachedPhotoGridMosaicGroup? {
        val entry = cachedEntries[DetailPersistentGroupKey(group.index, group.label, assetFingerprint)] ?: return null
        if (!entry.bands.coversOrderedAssets(group.assets)) return null
        val bands = entry.bands.toMosaicDisplayItems(
            assets = group.assets,
            bucketIndex = group.index,
            sectionLabel = group.label,
            keyPrefix = "detail_mosaic_cache"
        )
        if (bands.isEmpty() && entry.displayItemCount > 0) return null
        val items = groupHeaderItems(group) + bands
        return CachedPhotoGridMosaicGroup(
            items = items,
            resolvedBands = resolvedSectionDisplayBandsOrEmpty(bands, group.assets)
        )
    }

    fun cachedGroupItems(
        group: IndexedAssetGroup,
        assetFingerprint: String,
        cachedEntries: Map<DetailPersistentGroupKey, DetailMosaicCacheEntry>
    ): List<PhotoGridDisplayItem>? =
        cachedGroup(group, assetFingerprint, cachedEntries)?.items
}

internal class RuntimePhotoGridMosaicRenderer {
    fun groupCacheEntry(
        group: IndexedAssetGroup,
        groupItems: List<PhotoGridDisplayItem>,
        assetFingerprint: String,
        lookup: DetailMosaicCacheLookup
    ): DetailMosaicCacheEntry? {
        val bands = groupItems.filterIsInstance<MosaicBandItem>()
            .map { it.toDisplayRecord() }
        if (bands.isEmpty()) return null
        return DetailMosaicCacheEntry(
            ownerType = lookup.ownerType,
            ownerId = lookup.ownerId,
            groupSize = lookup.groupSize,
            columnCount = lookup.columnCount,
            sectionIndex = group.index,
            sectionKey = group.label,
            familiesKey = lookup.familiesKey,
            assetFingerprint = assetFingerprint,
            availableWidthKey = lookup.availableWidthKey,
            cellHeightKey = lookup.cellHeightKey,
            maxRowHeightKey = lookup.maxRowHeightKey,
            spacingKey = lookup.spacingKey,
            displayVersion = lookup.displayVersion,
            bands = bands,
            displayItemCount = groupItems.size,
            placeholderHeight = estimatePhotoGridDisplayItemsHeight(groupItems, GRID_SPACING_DP),
            updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        )
    }

    fun writeGroupCacheAsync(
        scope: CoroutineScope,
        group: IndexedAssetGroup,
        groupItems: List<PhotoGridDisplayItem>,
        assetFingerprint: String,
        lookup: DetailMosaicCacheLookup,
        upsert: suspend (DetailMosaicCacheEntry) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val entry = groupCacheEntry(group, groupItems, assetFingerprint, lookup) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { upsert(entry) }.onFailure(onFailure)
        }
    }
}

internal fun groupHeaderItems(group: IndexedAssetGroup): List<PhotoGridDisplayItem> =
    if (group.label.isNotEmpty()) {
        listOf(HeaderItem(
            gridKey = "h_${group.index}_${group.label}",
            bucketIndex = group.index,
            sectionLabel = group.label,
            label = group.label
        ))
    } else {
        emptyList()
    }
