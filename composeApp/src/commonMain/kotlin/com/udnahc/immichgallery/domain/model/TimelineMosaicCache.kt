package com.udnahc.immichgallery.domain.model

import kotlinx.serialization.Serializable

const val TIMELINE_MOSAIC_COMPACT_COLUMN_COUNT = 4
const val TIMELINE_MOSAIC_LARGE_COLUMN_COUNT = 5
const val TIMELINE_MOSAIC_PROGRESS_BAND_BATCH_SIZE = 8

@Serializable
data class MosaicTileAssignmentDto(
    val assetId: String,
    val visualOrder: Int
)

@Serializable
data class MosaicBandAssignmentDto(
    val bandIndex: Int,
    val sourceStartIndex: Int,
    val sourceCount: Int,
    val templateId: String,
    val tiles: List<MosaicTileAssignmentDto>
)

data class TimelineMosaicAssignment(
    val timeBucket: String,
    val sectionKey: String,
    val assignments: List<MosaicBandAssignment>
)

@Serializable
enum class TimelineMosaicDisplayBandKind {
    REAL,
    FALLBACK
}

@Serializable
data class TimelineMosaicDisplayTileRecord(
    val assetId: String,
    val visualOrder: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

@Serializable
data class TimelineMosaicDisplayBandRecord(
    val sourceStartIndex: Int,
    val sourceCount: Int,
    val bandHeight: Float,
    val kind: TimelineMosaicDisplayBandKind,
    val tiles: List<TimelineMosaicDisplayTileRecord>
)

data class TimelineMosaicDisplaySection(
    val timeBucket: String,
    val sectionKey: String,
    val bands: List<TimelineMosaicDisplayBandRecord>
)

data class TimelineMosaicGeometrySummary(
    val timeBucket: String,
    val sectionKey: String,
    val placeholderHeight: Float,
    val displayItemCount: Int
)

data class TimelineBucketGeometrySummary(
    val timeBucket: String,
    val placeholderHeight: Float,
    val displayItemCount: Int
)

data class TimelineMosaicGeometryRequest(
    val availableWidth: Float,
    val maxRowHeight: Float,
    val spacing: Float
)

data class TimelineMosaicCacheStatus(
    val assignments: List<TimelineMosaicAssignment>,
    val geometrySummaries: List<TimelineMosaicGeometrySummary> = emptyList(),
    val displaySections: List<TimelineMosaicDisplaySection> = emptyList(),
    val completeBucketIds: Set<String>,
    val missingBucketIds: Set<String>
)

fun List<TimelineMosaicDisplayBandRecord>.toMosaicDisplayItems(
    assets: List<Asset>,
    bucketIndex: Int,
    sectionLabel: String
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
            gridKey = "mosaic_cache_${bucketIndex}_${sectionLabel}_${band.sourceStartIndex}",
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            tiles = tiles,
            bandHeight = band.bandHeight,
            sourceStartIndex = band.sourceStartIndex,
            sourceCount = band.sourceCount,
            kind = when (band.kind) {
                TimelineMosaicDisplayBandKind.REAL -> MosaicBandKind.REAL
                TimelineMosaicDisplayBandKind.FALLBACK -> MosaicBandKind.FALLBACK
            }
        )
    }
}

data class TimelineMosaicPrecomputeResult(
    val successfulBucketIds: Set<String>,
    val failedBucketIds: Set<String>
)

data class TimelineMosaicProgressChunk(
    val timeBucket: String,
    val sectionKey: String,
    val sectionLabel: String,
    val sourceStartIndex: Int,
    val sourceEndExclusive: Int,
    val assignments: List<MosaicBandAssignment>
)

fun MosaicBandAssignment.toDto(): MosaicBandAssignmentDto =
    MosaicBandAssignmentDto(
        bandIndex = bandIndex,
        sourceStartIndex = sourceStartIndex,
        sourceCount = sourceCount,
        templateId = templateId,
        tiles = tiles.map { tile ->
            MosaicTileAssignmentDto(
                assetId = tile.assetId,
                visualOrder = tile.visualOrder
            )
        }
    )

fun MosaicBandAssignmentDto.toDomain(): MosaicBandAssignment =
    MosaicBandAssignment(
        bandIndex = bandIndex,
        sourceStartIndex = sourceStartIndex,
        sourceCount = sourceCount,
        templateId = templateId,
        tiles = tiles.map { tile ->
            MosaicTileAssignment(
                assetId = tile.assetId,
                visualOrder = tile.visualOrder
            )
        }
    )

fun timelineMosaicFamiliesKey(families: Set<MosaicTemplateFamily>): String =
    families.map { it.persistedId }.sorted().joinToString("|")

fun timelineMonthMosaicSectionKey(timeBucket: String): String = "$timeBucket|month"

fun timelineDayMosaicSectionKey(timeBucket: String, dayLabel: String): String =
    "$timeBucket|day|$dayLabel"
