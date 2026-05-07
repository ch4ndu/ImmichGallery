package com.udnahc.immichgallery.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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

typealias TimelineMosaicDisplayItemRecord = MosaicDisplayItemRecord

data class TimelineMosaicDisplaySection(
    val timeBucket: String,
    val sectionKey: String,
    val displayRecords: List<TimelineMosaicDisplayItemRecord>
)

data class TimelineMosaicGeometrySummary(
    val timeBucket: String,
    val sectionKey: String,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val ranges: List<MosaicSectionGeometryRange> = emptyList()
)

data class TimelineBucketGeometrySummary(
    val timeBucket: String,
    val placeholderHeight: Float,
    val displayItemCount: Int
)

data class TimelineBucketSnapshot(
    val timeBucket: String,
    val assets: List<Asset>,
    val expectedCount: Int?
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

fun List<TimelineMosaicDisplayItemRecord>.toTimelinePhotoGridDisplayItems(
    assets: List<Asset>,
    bucketIndex: Int,
    sectionLabel: String
): List<PhotoGridDisplayItem> =
    toPhotoGridDisplayItems(
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

data class TimelineMosaicSection(
    val sectionKey: String,
    val label: String,
    val assets: List<Asset>
)

data class TimelineMosaicAssignmentArtifact(
    val timeBucket: String,
    val groupMode: String,
    val sectionKey: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val assignments: List<MosaicBandAssignment>,
    val updatedAt: Long
)

data class TimelineMosaicDisplayArtifact(
    val timeBucket: String,
    val groupMode: String,
    val sectionKey: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val geometryRequest: TimelineMosaicGeometryRequest,
    val displayRecords: List<TimelineMosaicDisplayItemRecord>,
    val displayItemCount: Int,
    val placeholderHeight: Float,
    val updatedAt: Long
)

data class TimelineMosaicSectionGeometryArtifact(
    val timeBucket: String,
    val groupMode: String,
    val sectionKey: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val geometryRequest: TimelineMosaicGeometryRequest,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val ranges: List<MosaicSectionGeometryRange>,
    val updatedAt: Long
)

data class TimelineMosaicBucketGeometryArtifact(
    val timeBucket: String,
    val groupMode: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val geometryRequest: TimelineMosaicGeometryRequest,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val updatedAt: Long
)

data class TimelineMosaicBucketArtifacts(
    val timeBucket: String,
    val groupMode: String,
    val columnCount: Int,
    val familiesKey: String,
    val assignments: List<TimelineMosaicAssignmentArtifact>,
    val displayCache: List<TimelineMosaicDisplayArtifact> = emptyList(),
    val sectionGeometry: List<TimelineMosaicSectionGeometryArtifact> = emptyList(),
    val bucketGeometry: TimelineMosaicBucketGeometryArtifact? = null
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

fun orderedTimelineAssetsFingerprint(assets: List<Asset>): String =
    assets.joinToString(separator = "\n") { asset ->
        listOf(
            asset.id,
            asset.type.name,
            asset.fileName,
            asset.createdAt,
            asset.isFavorite.toString(),
            asset.stackCount.toString(),
            asset.aspectRatio.toString(),
            asset.isEdited.toString()
        ).joinToString(separator = "\u001f")
    }

fun shouldRejectUnsyncedEmptyTimelineBucket(
    actualAssetCount: Int,
    expectedBucketCount: Int?
): Boolean = actualAssetCount == 0 && (expectedBucketCount ?: 0) > 0

fun timelineMosaicSections(
    timeBucket: String,
    groupSize: TimelineGroupSize,
    assets: List<Asset>
): List<TimelineMosaicSection> =
    when {
        assets.isEmpty() -> emptyList()
        groupSize == TimelineGroupSize.DAY -> {
            timelineDaySections(timeBucket, assets)
                .ifEmpty {
                    listOf(TimelineMosaicSection(
                        sectionKey = timelineMonthMosaicSectionKey(timeBucket),
                        label = timeBucket,
                        assets = assets
                    ))
                }
        }
        else -> listOf(TimelineMosaicSection(
            sectionKey = timelineMonthMosaicSectionKey(timeBucket),
            label = timeBucket,
            assets = assets
        ))
    }

fun mosaicGeometryRangesCoverSourceRange(
    ranges: List<MosaicSectionGeometryRange>,
    assetCount: Int
): Boolean {
    if (assetCount == 0) return ranges.isEmpty()
    if (ranges.isEmpty()) return false
    var cursor = 0
    ranges.sortedBy { it.sourceStartIndex }.forEach { range ->
        if (range.sourceStartIndex != cursor || range.sourceCount <= 0 || range.height <= 0f) return false
        cursor += range.sourceCount
        if (cursor > assetCount) return false
    }
    return cursor == assetCount
}

fun timelineBucketGeometryHeight(
    groupSize: TimelineGroupSize,
    sectionGeometries: List<SectionGeometry>,
    spacing: Float
): Float {
    if (sectionGeometries.isEmpty()) return 0f
    val sectionHeights = sectionGeometries.sumOf { it.placeholderHeight.toDouble() }.toFloat()
    return when (groupSize) {
        TimelineGroupSize.DAY -> {
            val sectionCount = sectionGeometries.size
            sectionHeights +
                PHOTO_GRID_SECTION_HEADER_HEIGHT_DP * sectionCount +
                spacing * (sectionCount * 2 - 1).coerceAtLeast(0)
        }
        else -> sectionGeometries.firstOrNull()?.placeholderHeight ?: 0f
    }
}

fun timelineBucketGeometryDisplayItemCount(
    groupSize: TimelineGroupSize,
    sectionGeometries: List<SectionGeometry>
): Int =
    when (groupSize) {
        TimelineGroupSize.DAY ->
            sectionGeometries.size + sectionGeometries.sumOf { geometry ->
                photoGridPlaceholderChunkCount(geometry.placeholderHeight)
            }
        else ->
            sectionGeometries.firstOrNull()?.placeholderHeight
                ?.let(::photoGridPlaceholderChunkCount)
                ?: 0
    }

private fun timelineDaySections(timeBucket: String, assets: List<Asset>): List<TimelineMosaicSection> {
    val tz = TimeZone.currentSystemDefault()
    val grouped = assets.groupBy { asset ->
        try {
            Instant.parse(asset.createdAt).toLocalDateTime(tz).date
        } catch (_: Exception) {
            try {
                LocalDate.parse(asset.createdAt.take(10))
            } catch (_: Exception) {
                null
            }
        }
    }
    return grouped
        .filterKeys { it != null }
        .entries
        .sortedByDescending { it.key }
        .mapNotNull { (date, dayAssets) ->
            val d = date ?: return@mapNotNull null
            val monthName = d.month.name.lowercase()
            val label = "${d.dayOfMonth} $monthName ${d.year}"
            TimelineMosaicSection(
                sectionKey = timelineDayMosaicSectionKey(timeBucket, label),
                label = label,
                assets = dayAssets.sortedByDescending { it.createdAt }
            )
        }
}

fun timelineMonthMosaicSectionKey(timeBucket: String): String = "$timeBucket|month"

fun timelineDayMosaicSectionKey(timeBucket: String, dayLabel: String): String =
    "$timeBucket|day|$dayLabel"
