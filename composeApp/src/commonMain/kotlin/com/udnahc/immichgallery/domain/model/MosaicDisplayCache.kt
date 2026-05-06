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
    val bands: List<MosaicDisplayBandRecord>,
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

fun List<MosaicDisplayBandRecord>.toMosaicDisplayItems(
    assets: List<Asset>,
    bucketIndex: Int,
    sectionLabel: String,
    keyPrefix: String = "mosaic_cache"
): List<PhotoGridDisplayItem> {
    val assetsById = assets.associateBy { it.id }
    return mapNotNull { band ->
        val tiles = band.tiles.mapNotNull { tile ->
            val asset = assetsById[tile.assetId] ?: return@mapNotNull null
            MosaicTile(
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
            )
        }
        MosaicBandItem(
            gridKey = "${keyPrefix}_${bucketIndex}_${sectionLabel}_${band.sourceStartIndex}",
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            tiles = tiles,
            bandHeight = band.bandHeight,
            sourceStartIndex = band.sourceStartIndex,
            sourceCount = band.sourceCount,
            kind = when (band.kind) {
                MosaicDisplayBandKind.REAL -> MosaicBandKind.REAL
                MosaicDisplayBandKind.FALLBACK -> MosaicBandKind.FALLBACK
            }
        )
    }
}

fun List<MosaicDisplayBandRecord>.coversOrderedAssets(assets: List<Asset>): Boolean {
    if (assets.isEmpty()) return isEmpty()
    if (isEmpty()) return false
    val expectedIds = assets.map { it.id }
    val knownIds = expectedIds.toSet()
    val seenIds = mutableSetOf<String>()
    var cursor = 0
    sortedBy { it.sourceStartIndex }.forEach { band ->
        if (band.sourceCount <= 0) return false
        if (band.sourceStartIndex != cursor) return false
        val endExclusive = band.sourceStartIndex + band.sourceCount
        if (endExclusive > assets.size) return false
        if (band.bandHeight <= 0f) return false
        if (band.tiles.size != band.sourceCount) return false
        val expectedSlice = expectedIds.subList(band.sourceStartIndex, endExclusive).toSet()
        val bandIds = mutableSetOf<String>()
        band.tiles.forEach { tile ->
            if (tile.assetId !in knownIds) return false
            if (!seenIds.add(tile.assetId)) return false
            if (!bandIds.add(tile.assetId)) return false
            if (tile.width <= 0f || tile.height <= 0f) return false
        }
        if (bandIds != expectedSlice) return false
        cursor = endExclusive
    }
    return cursor == assets.size
}

fun mosaicDisplayCacheFamiliesKey(families: Set<MosaicTemplateFamily>): String =
    families.normalizedMosaicFamilies().map { it.persistedId }.sorted().joinToString("|")

fun mosaicDisplayCacheWidthKey(width: Float): Int =
    width.roundToInt()

fun mosaicDisplayCacheDimensionKey(value: Float): Int =
    (value * 100f).roundToInt()
