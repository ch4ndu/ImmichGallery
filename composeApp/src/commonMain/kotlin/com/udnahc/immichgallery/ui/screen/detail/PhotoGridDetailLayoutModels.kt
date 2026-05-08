package com.udnahc.immichgallery.ui.screen.detail

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifacts
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicSectionGeometryEntry
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicAssignmentCheckpoint
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.ProgressChunk
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowItemKind
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.groupAssets

internal const val VISIBLE_GROUP_PREFETCH_RADIUS = 1
internal const val DEFERRED_MOSAIC_FLUSH_DELAY_MS = 120L
internal const val DETAIL_MOSAIC_DISPLAY_CACHE_VERSION = 1
internal const val DETAIL_MOSAIC_GEOMETRY_VERSION = 1

internal fun detailRetryDelayMs(attempts: Int): Long {
    var delay = 250L
    repeat((attempts - 1).coerceIn(0, 4)) {
        delay *= 2
    }
    return minOf(delay, 5_000L)
}

internal fun List<PhotoGridDisplayItem>.isFinalMosaicGroupDisplay(): Boolean =
    isNotEmpty() &&
            filterNot { it is HeaderItem }.let { photoItems ->
                photoItems.isNotEmpty() &&
                        photoItems.none { it is PlaceholderItem } &&
                        photoItems.all { item ->
                            item is MosaicBandItem ||
                                    (item is RowItem && item.kind == RowItemKind.MOSAIC_FALLBACK)
                        }
            }

internal data class KeyedAssetGroup(
    val group: IndexedAssetGroup,
    val assetFingerprint: String,
    val cacheKey: DetailGroupDisplayCacheKey
)

internal sealed interface DetailRuntimeMosaicGroupState {
    val checkpoint: MosaicAssignmentCheckpoint
    val chunks: List<ProgressChunk>
    val attempts: Int

    data class InFlight(
        override val checkpoint: MosaicAssignmentCheckpoint,
        override val chunks: List<ProgressChunk>,
        override val attempts: Int
    ) : DetailRuntimeMosaicGroupState

    data class Partial(
        override val checkpoint: MosaicAssignmentCheckpoint,
        override val chunks: List<ProgressChunk>,
        override val attempts: Int
    ) : DetailRuntimeMosaicGroupState

    data class RetryableFailure(
        override val checkpoint: MosaicAssignmentCheckpoint,
        override val chunks: List<ProgressChunk>,
        override val attempts: Int
    ) : DetailRuntimeMosaicGroupState
}

internal data class DetailDisplayCacheKey(
    val availableWidth: Float,
    val targetRowHeight: Float,
    val maxRowHeight: Float,
    val groupSize: GroupSize,
    val viewConfig: ViewConfig,
    val assetRevision: Int
)

internal data class CachedDisplayItems(
    val key: DetailDisplayCacheKey,
    val items: List<PhotoGridDisplayItem>
)

internal data class DetailGroupDisplayCacheKey(
    val bucketIndex: Int,
    val sectionLabel: String,
    val groupSize: GroupSize,
    val availableWidth: Float,
    val targetRowHeight: Float,
    val maxRowHeight: Float,
    val viewConfig: ViewConfig,
    val assets: List<DetailAssetFingerprint>
)

internal data class DetailGroupFingerprint(
    val label: String,
    val assets: List<DetailAssetFingerprint>
)

internal data class DetailAssetFingerprint(
    val id: String,
    val type: String,
    val fileName: String,
    val createdAt: String,
    val isFavorite: Boolean,
    val stackCount: Int,
    val aspectRatio: Float,
    val isEdited: Boolean
)

internal fun PhotoGridDetailLayoutSnapshot.displayCacheKey(assetRevision: Int): DetailDisplayCacheKey =
    DetailDisplayCacheKey(
        availableWidth = availableWidth,
        targetRowHeight = targetRowHeight,
        maxRowHeight = rowHeightBounds.max,
        groupSize = groupSize,
        viewConfig = viewConfig.normalized,
        assetRevision = assetRevision
    )

internal fun PhotoGridDetailLayoutSnapshot.groupDisplayCacheKey(
    bucketIndex: Int,
    sectionLabel: String,
    assets: List<Asset>,
    mosaicCellHeight: Float
): DetailGroupDisplayCacheKey =
    DetailGroupDisplayCacheKey(
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        groupSize = groupSize,
        availableWidth = availableWidth,
        targetRowHeight = mosaicCellHeight,
        maxRowHeight = rowHeightBounds.max,
        viewConfig = viewConfig.normalized,
        assets = assets.detailFingerprint()
    )

internal fun List<Asset>.detailGroupFingerprints(groupSize: GroupSize): List<DetailGroupFingerprint> =
    groupAssets(this, groupSize).map { group ->
        DetailGroupFingerprint(
            label = group.label,
            assets = group.assets.detailFingerprint()
        )
    }

internal fun List<Asset>.detailFingerprint(): List<DetailAssetFingerprint> =
    map { asset ->
        DetailAssetFingerprint(
            id = asset.id,
            type = asset.type.name,
            fileName = asset.fileName,
            createdAt = asset.createdAt,
            isFavorite = asset.isFavorite,
            stackCount = asset.stackCount,
            aspectRatio = asset.aspectRatio,
            isEdited = asset.isEdited
        )
    }

internal fun List<Asset>.detailPersistentFingerprint(): String {
    var hash = FNV_64_OFFSET_BASIS
    forEach { asset ->
        hash = hash.update(asset.id)
        hash = hash.update(asset.type.name)
        hash = hash.update(asset.fileName)
        hash = hash.update(asset.createdAt)
        hash = hash.update(asset.isFavorite.toString())
        hash = hash.update(asset.stackCount.toString())
        hash = hash.update(asset.aspectRatio.toString())
        hash = hash.update(asset.isEdited.toString())
    }
    return hash.toULong().toString(16)
}

private fun Long.update(value: String): Long {
    var hash = this
    value.forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= FNV_64_PRIME
    }
    hash = hash xor '|'.code.toLong()
    hash *= FNV_64_PRIME
    return hash
}

internal fun DetailMosaicArtifacts.strictReadyDisplayCache(
    ownerFingerprint: String,
    columnCount: Int
): Map<DetailPersistentGroupKey, DetailMosaicCacheEntry> {
    val assignmentKeys = assignments.mapTo(mutableSetOf()) {
        if (it.columnCount != columnCount) return@mapTo DetailPersistentGroupKey(-1, "", "")
        DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
    }
    val geometryKeys = sectionGeometry.mapTo(mutableSetOf()) {
        if (it.columnCount != columnCount) return@mapTo DetailPersistentGroupKey(-1, "", "")
        DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
    }
    return displayCache.associateBy {
        if (it.columnCount != columnCount) return@associateBy DetailPersistentGroupKey(-1, "", "")
        DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
    }.filterKeys { key ->
        key.assetFingerprint.isNotEmpty() && key in assignmentKeys && key in geometryKeys
    }
}

internal fun DetailMosaicArtifacts.strictSectionGeometry(
    ownerFingerprint: String,
    columnCount: Int
): Map<DetailPersistentGroupKey, DetailMosaicSectionGeometryEntry> {
    return sectionGeometry.associateBy {
        if (it.columnCount != columnCount || it.assetFingerprint.isEmpty() || it.placeholderHeight <= 0f) {
            return@associateBy DetailPersistentGroupKey(-1, "", "")
        }
        DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
    }.filterKeys { it.assetFingerprint.isNotEmpty() }
}

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
