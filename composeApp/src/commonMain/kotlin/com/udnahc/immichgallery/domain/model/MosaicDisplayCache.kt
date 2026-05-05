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

fun mosaicDisplayCacheFamiliesKey(families: Set<MosaicTemplateFamily>): String =
    families.normalizedMosaicFamilies().map { it.persistedId }.sorted().joinToString("|")

fun mosaicDisplayCacheWidthKey(width: Float): Int =
    width.roundToInt()

fun mosaicDisplayCacheDimensionKey(value: Float): Int =
    (value * 100f).roundToInt()
