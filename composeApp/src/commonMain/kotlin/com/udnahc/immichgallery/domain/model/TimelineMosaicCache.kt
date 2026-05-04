package com.udnahc.immichgallery.domain.model

import kotlinx.serialization.Serializable

const val TIMELINE_MOSAIC_COMPACT_COLUMN_COUNT = 4
const val TIMELINE_MOSAIC_LARGE_COLUMN_COUNT = 5

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

data class TimelineMosaicCacheStatus(
    val assignments: List<TimelineMosaicAssignment>,
    val completeBucketIds: Set<String>,
    val missingBucketIds: Set<String>
)

data class TimelineMosaicPrecomputeResult(
    val successfulBucketIds: Set<String>,
    val failedBucketIds: Set<String>
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
