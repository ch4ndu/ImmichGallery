package com.udnahc.immichgallery.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class MosaicDisplayBandKind {
    REAL,
    FALLBACK
}

@Serializable
data class MosaicDisplayTileRecord(
    val assetId: String,
    val visualOrder: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

@Serializable
data class MosaicDisplayBandRecord(
    val sourceStartIndex: Int,
    val sourceCount: Int,
    val bandHeight: Float,
    val kind: MosaicDisplayBandKind,
    val tiles: List<MosaicDisplayTileRecord>
)

@Serializable
enum class MosaicDisplayItemRecordKind {
    REAL_BAND,
    MOSAIC_FALLBACK_ROW
}

@Serializable
data class MosaicDisplayItemRecord(
    val kind: MosaicDisplayItemRecordKind,
    val sourceStartIndex: Int,
    val sourceCount: Int,
    val height: Float,
    val isComplete: Boolean = true,
    val assetIds: List<String> = emptyList(),
    val tiles: List<MosaicDisplayTileRecord> = emptyList()
)

enum class DetailMosaicCacheOwnerType {
    ALBUM,
    PERSON
}

data class DetailMosaicCacheLookup(
    val ownerType: DetailMosaicCacheOwnerType,
    val ownerId: String,
    val groupSize: GroupSize,
    val columnCount: Int = 0,
    val familiesKey: String,
    val availableWidthKey: Int,
    val cellHeightKey: Int,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val displayVersion: Int
)

data class DetailMosaicCacheEntry(
    val ownerType: DetailMosaicCacheOwnerType,
    val ownerId: String,
    val groupSize: GroupSize,
    val columnCount: Int,
    val sectionIndex: Int,
    val sectionKey: String,
    val familiesKey: String,
    val assetFingerprint: String,
    val availableWidthKey: Int,
    val cellHeightKey: Int,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val displayVersion: Int,
    val displayRecords: List<MosaicDisplayItemRecord>,
    val displayItemCount: Int,
    val placeholderHeight: Float,
    val updatedAt: Long
)

data class DetailMosaicAssignmentEntry(
    val ownerType: DetailMosaicCacheOwnerType,
    val ownerId: String,
    val groupSize: GroupSize,
    val columnCount: Int,
    val sectionIndex: Int,
    val sectionKey: String,
    val familiesKey: String,
    val assetFingerprint: String,
    val assignments: List<MosaicBandAssignment>,
    val updatedAt: Long
)

data class DetailMosaicSectionGeometryEntry(
    val ownerType: DetailMosaicCacheOwnerType,
    val ownerId: String,
    val groupSize: GroupSize,
    val columnCount: Int,
    val sectionIndex: Int,
    val sectionKey: String,
    val familiesKey: String,
    val assetFingerprint: String,
    val availableWidthKey: Int,
    val cellHeightKey: Int,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val geometryVersion: Int,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val ranges: List<MosaicSectionGeometryRange> = emptyList(),
    val updatedAt: Long
)

data class DetailMosaicAggregateGeometryEntry(
    val ownerType: DetailMosaicCacheOwnerType,
    val ownerId: String,
    val groupSize: GroupSize,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val availableWidthKey: Int,
    val cellHeightKey: Int,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val geometryVersion: Int,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val updatedAt: Long
)

data class DetailMosaicArtifacts(
    val assignments: List<DetailMosaicAssignmentEntry> = emptyList(),
    val displayCache: List<DetailMosaicCacheEntry> = emptyList(),
    val sectionGeometry: List<DetailMosaicSectionGeometryEntry> = emptyList(),
    val aggregateGeometry: DetailMosaicAggregateGeometryEntry? = null
)

data class DetailMosaicArtifactsUpsert(
    val assignments: List<DetailMosaicAssignmentEntry> = emptyList(),
    val displayCache: List<DetailMosaicCacheEntry> = emptyList(),
    val sectionGeometry: List<DetailMosaicSectionGeometryEntry> = emptyList(),
    val aggregateGeometry: DetailMosaicAggregateGeometryEntry? = null
)

fun MosaicBandItem.toDisplayRecord(): MosaicDisplayBandRecord =
    MosaicDisplayBandRecord(
        sourceStartIndex = sourceStartIndex,
        sourceCount = sourceCount,
        bandHeight = bandHeight,
        kind = when (kind) {
            MosaicBandKind.REAL -> MosaicDisplayBandKind.REAL
            MosaicBandKind.FALLBACK -> MosaicDisplayBandKind.FALLBACK
        },
        tiles = tiles.map { tile ->
            MosaicDisplayTileRecord(
                assetId = tile.photo.asset.id,
                visualOrder = tile.visualOrder,
                x = tile.x,
                y = tile.y,
                width = tile.width,
                height = tile.height
            )
        }
    )

fun MosaicBandItem.toDisplayItemRecordOrNull(): MosaicDisplayItemRecord? {
    if (kind != MosaicBandKind.REAL || bandHeight <= 0f || sourceCount <= 0) return null
    val tileRecords = tiles.map { tile ->
        if (tile.width <= 0f || tile.height <= 0f) return null
        MosaicDisplayTileRecord(
            assetId = tile.photo.asset.id,
            visualOrder = tile.visualOrder,
            x = tile.x,
            y = tile.y,
            width = tile.width,
            height = tile.height
        )
    }
    if (tileRecords.size != sourceCount) return null
    return MosaicDisplayItemRecord(
        kind = MosaicDisplayItemRecordKind.REAL_BAND,
        sourceStartIndex = sourceStartIndex,
        sourceCount = sourceCount,
        height = bandHeight,
        tiles = tileRecords
    )
}

fun RowItem.toFallbackDisplayItemRecordOrNull(): MosaicDisplayItemRecord? {
    if (kind != RowItemKind.MOSAIC_FALLBACK || rowHeight <= 0f || sourceCount <= 0) return null
    val assetIds = photos.map { it.asset.id }
    if (assetIds.size != sourceCount) return null
    return MosaicDisplayItemRecord(
        kind = MosaicDisplayItemRecordKind.MOSAIC_FALLBACK_ROW,
        sourceStartIndex = sourceStartIndex,
        sourceCount = sourceCount,
        height = rowHeight,
        isComplete = isComplete,
        assetIds = assetIds
    )
}

fun resolvedSectionDisplayRecordsOrEmpty(
    displayItems: List<PhotoGridDisplayItem>,
    assets: List<Asset>
): List<MosaicDisplayItemRecord> {
    if (assets.isNotEmpty() && displayItems.isEmpty()) return emptyList()
    val records = displayItems.map { item ->
        when (item) {
            is MosaicBandItem -> item.toDisplayItemRecordOrNull()
            is RowItem -> item.toFallbackDisplayItemRecordOrNull()
            else -> null
        } ?: return emptyList()
    }
    return records.takeIf { it.displayRecordsCoverOrderedAssets(assets) && it.size == displayItems.size }.orEmpty()
}

fun resolvedRealDisplayBandsOrEmpty(
    displayItems: List<PhotoGridDisplayItem>,
    assets: List<Asset>
): List<MosaicDisplayBandRecord> {
    val bands = displayItems.filterIsInstance<MosaicBandItem>()
    if (bands.size != displayItems.size) return emptyList()
    if (bands.any { it.kind != MosaicBandKind.REAL }) return emptyList()
    val records = bands.map { it.toDisplayRecord() }
    return records.takeIf { it.realDisplayBandsCoverOrderedAssets(assets) }.orEmpty()
}

fun List<MosaicDisplayItemRecord>.toPhotoGridDisplayItems(
    assets: List<Asset>,
    bucketIndex: Int,
    sectionLabel: String,
    keyPrefix: String = "mosaic_cache"
): List<PhotoGridDisplayItem> {
    val assetsById = assets.associateBy { it.id }
    return mapNotNull { record ->
        when (record.kind) {
            MosaicDisplayItemRecordKind.REAL_BAND -> {
                if (record.tiles.size != record.sourceCount) return@mapNotNull null
                val tiles = mutableListOf<MosaicTile>()
                record.tiles.forEach { tile ->
                    val asset = assetsById[tile.assetId] ?: return@mapNotNull null
                    tiles.add(MosaicTile(
                        photo = PhotoItem(
                            gridKey = "p_${asset.id}",
                            bucketIndex = bucketIndex,
                            sectionLabel = sectionLabel,
                            asset = asset
                        ),
                        x = tile.x,
                        y = tile.y,
                        width = tile.width,
                        height = tile.height,
                        visualOrder = tile.visualOrder
                    ))
                }
                MosaicBandItem(
                    gridKey = "${keyPrefix}_${bucketIndex}_${sectionLabel}_${record.sourceStartIndex}",
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    tiles = tiles,
                    bandHeight = record.height,
                    sourceStartIndex = record.sourceStartIndex,
                    sourceCount = record.sourceCount,
                    kind = MosaicBandKind.REAL
                )
            }
            MosaicDisplayItemRecordKind.MOSAIC_FALLBACK_ROW -> {
                if (record.assetIds.size != record.sourceCount) return@mapNotNull null
                val photos = record.assetIds.map { assetId ->
                    val asset = assetsById[assetId] ?: return@mapNotNull null
                    PhotoItem(
                        gridKey = "p_${asset.id}",
                        bucketIndex = bucketIndex,
                        sectionLabel = sectionLabel,
                        asset = asset
                    )
                }
                RowItem(
                    gridKey = fallbackRowGridKey(bucketIndex, sectionLabel, record.sourceStartIndex),
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    photos = photos,
                    rowHeight = record.height,
                    isComplete = record.isComplete,
                    kind = RowItemKind.MOSAIC_FALLBACK,
                    sourceStartIndex = record.sourceStartIndex,
                    sourceCount = record.sourceCount
                )
            }
        }
    }
}

fun List<MosaicDisplayBandRecord>.toRealMosaicDisplayItems(
    assets: List<Asset>,
    bucketIndex: Int,
    sectionLabel: String,
    keyPrefix: String = "mosaic_cache"
): List<PhotoGridDisplayItem> {
    val assetsById = assets.associateBy { it.id }
    return mapNotNull { band ->
        if (band.kind != MosaicDisplayBandKind.REAL) return@mapNotNull null
        if (band.tiles.size != band.sourceCount) return@mapNotNull null
        val tiles = mutableListOf<MosaicTile>()
        band.tiles.forEach { tile ->
            val asset = assetsById[tile.assetId] ?: return@mapNotNull null
            tiles.add(MosaicTile(
                photo = PhotoItem(
                    gridKey = "p_${asset.id}",
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    asset = asset
                ),
                x = tile.x,
                y = tile.y,
                width = tile.width,
                height = tile.height,
                visualOrder = tile.visualOrder
            ))
        }
        MosaicBandItem(
            gridKey = "${keyPrefix}_${bucketIndex}_${sectionLabel}_${band.sourceStartIndex}",
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            tiles = tiles,
            bandHeight = band.bandHeight,
            sourceStartIndex = band.sourceStartIndex,
            sourceCount = band.sourceCount,
            kind = MosaicBandKind.REAL
        )
    }
}

fun List<MosaicDisplayItemRecord>.displayRecordsCoverOrderedAssets(assets: List<Asset>): Boolean {
    if (assets.isEmpty()) return isEmpty()
    if (isEmpty()) return false
    val expectedIds = assets.map { it.id }
    val knownIds = expectedIds.toSet()
    val seenIds = mutableSetOf<String>()
    var cursor = 0
    for (record in this) {
        if (record.sourceCount <= 0 || record.height <= 0f) return false
        if (record.sourceStartIndex != cursor) return false
        val endExclusive = record.sourceStartIndex + record.sourceCount
        if (endExclusive > assets.size) return false
        val expectedSlice = expectedIds.subList(record.sourceStartIndex, endExclusive)
        val recordIds = when (record.kind) {
            MosaicDisplayItemRecordKind.REAL_BAND -> {
                if (record.tiles.size != record.sourceCount || record.assetIds.isNotEmpty()) return false
                val visualOrders = mutableSetOf<Int>()
                record.tiles.map { tile ->
                    if (tile.assetId !in knownIds) return false
                    if (tile.width <= 0f || tile.height <= 0f) return false
                    if (tile.visualOrder !in 0 until record.sourceCount) return false
                    if (!visualOrders.add(tile.visualOrder)) return false
                    tile.assetId
                }
            }
            MosaicDisplayItemRecordKind.MOSAIC_FALLBACK_ROW -> {
                if (record.assetIds.size != record.sourceCount || record.tiles.isNotEmpty()) return false
                if (record.assetIds != expectedSlice) return false
                record.assetIds
            }
        }
        val recordIdSet = mutableSetOf<String>()
        recordIds.forEach { assetId ->
            if (assetId !in knownIds) return false
            if (!seenIds.add(assetId)) return false
            if (!recordIdSet.add(assetId)) return false
        }
        if (record.kind == MosaicDisplayItemRecordKind.REAL_BAND &&
            recordIdSet != expectedSlice.toSet()
        ) return false
        cursor = endExclusive
    }
    return cursor == assets.size
}

fun List<MosaicDisplayBandRecord>.realDisplayBandsCoverOrderedAssets(assets: List<Asset>): Boolean {
    if (assets.isEmpty()) return isEmpty()
    if (isEmpty()) return false
    return map {
        if (it.kind != MosaicDisplayBandKind.REAL) return false
        MosaicDisplayItemRecord(
            kind = MosaicDisplayItemRecordKind.REAL_BAND,
            sourceStartIndex = it.sourceStartIndex,
            sourceCount = it.sourceCount,
            height = it.bandHeight,
            tiles = it.tiles
        )
    }.displayRecordsCoverOrderedAssets(assets)
}

fun fallbackRowGridKey(bucketIndex: Int, sectionLabel: String, sourceStartIndex: Int): String =
    "mosaic_fallback_row_${bucketIndex}_${sectionLabel.hashCode()}_$sourceStartIndex"

fun mosaicDisplayCacheFamiliesKey(families: Set<MosaicTemplateFamily>): String =
    families.normalizedMosaicFamilies().map { it.persistedId }.sorted().joinToString("|")

fun mosaicDisplayCacheWidthKey(width: Float): Int =
    width.roundToInt()

fun mosaicDisplayCacheDimensionKey(value: Float): Int =
    (value * 100f).roundToInt()
