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

typealias TimelineMosaicDisplayBandKind = MosaicDisplayBandKind
typealias TimelineMosaicDisplayTileRecord = MosaicDisplayTileRecord
typealias TimelineMosaicDisplayBandRecord = MosaicDisplayBandRecord

data class TimelineMosaicDisplaySection(
    val timeBucket: String,
    val sectionKey: String,
    val bands: List<TimelineMosaicDisplayBandRecord>
)

data class TimelineMosaicGeometrySummary(
    val timeBucket: String,
    val sectionKey: String,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val bands: List<MosaicSectionGeometryBand> = emptyList()
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

fun List<TimelineMosaicDisplayBandRecord>.toTimelineMosaicDisplayItems(
    assets: List<Asset>,
    bucketIndex: Int,
    sectionLabel: String
): List<PhotoGridDisplayItem> =
    toMosaicDisplayItems(
        assets = assets,
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        keyPrefix = "mosaic_cache"
    )

data class TimelineMosaicPrecomputeResult(
    val successfulBucketIds: Set<String>,
    val failedBucketIds: Set<String>,
    val bucketGeometrySummaries: List<TimelineBucketGeometrySummary> = emptyList()
)

data class TimelineMosaicSectionPrecomputeRequest(
    val timeBucket: String,
    val sectionKey: String,
    val sectionLabel: String,
    val assets: List<Asset>,
    val bucketIndex: Int,
    val columnCount: Int,
    val familiesKey: String,
    val contentFingerprint: String,
    val generation: Long,
    val assignmentLayoutSpec: MosaicLayoutSpec,
    val displayLayoutSpec: MosaicLayoutSpec,
    val spacing: Float,
    val maxRowHeight: Float,
    val enabledFamilies: Set<MosaicTemplateFamily>
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
