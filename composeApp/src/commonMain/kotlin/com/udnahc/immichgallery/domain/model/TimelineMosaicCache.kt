package com.udnahc.immichgallery.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.serialization.Serializable

const val TIMELINE_MOSAIC_COMPACT_COLUMN_COUNT = 4
const val TIMELINE_MOSAIC_LARGE_COLUMN_COUNT = 5
const val TIMELINE_MOSAIC_PROGRESS_BAND_BATCH_SIZE = 8
val TIMELINE_MOSAIC_PRECOMPUTE_GROUP_SIZES: Set<TimelineGroupSize> =
    setOf(TimelineGroupSize.MONTH, TimelineGroupSize.DAY)

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

@Serializable
data class TimelineMosaicSectionManifestEntry(
    val sectionKey: String,
    val assetCount: Int
)

data class TimelineMosaicBucketSkeleton(
    val timeBucket: String,
    val assetCount: Int,
    val placeholderHeight: Float,
    val sections: List<TimelineMosaicGeometrySummary>
) {
    val contentToken: Int = sections.hashCode() * 31 + placeholderHeight.hashCode()
}

data class TimelineMosaicSkeleton(
    val buckets: Map<String, TimelineMosaicBucketSkeleton>
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
    val missingBucketIds: Set<String>,
    val assignmentGeometryReadyBucketIds: Set<String> = completeBucketIds,
    val displayCacheReadyBucketIds: Set<String> = completeBucketIds,
    val missingSectionKeysByBucket: Map<String, Set<String>> = emptyMap()
)

data class TimelineMosaicRepairSectionInput(
    val assignments: List<MosaicBandAssignment>,
    val displayRecords: List<TimelineMosaicDisplayItemRecord>,
    val geometry: TimelineMosaicGeometrySummary
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
    val bucketGeometrySummaries: List<TimelineBucketGeometrySummary> = emptyList(),
    val bucketGeometrySummariesByGroup: Map<TimelineGroupSize, List<TimelineBucketGeometrySummary>> = emptyMap(),
    val sectionGeometrySummaries: List<TimelineMosaicGeometrySummary> = emptyList(),
    val sectionGeometrySummariesByGroup: Map<TimelineGroupSize, List<TimelineMosaicGeometrySummary>> = emptyMap(),
    val bucketSkeletons: Map<String, TimelineMosaicBucketSkeleton> = emptyMap(),
    val bucketSkeletonsByGroup: Map<TimelineGroupSize, Map<String, TimelineMosaicBucketSkeleton>> = emptyMap(),
    val failedBucketIdsByGroup: Map<TimelineGroupSize, Set<String>> = emptyMap()
) {
    fun resolvedBucketGeometrySummariesByGroup(
        primaryGroupSize: TimelineGroupSize
    ): Map<TimelineGroupSize, List<TimelineBucketGeometrySummary>> =
        if (bucketGeometrySummariesByGroup.isNotEmpty()) {
            bucketGeometrySummariesByGroup
        } else if (bucketGeometrySummaries.isNotEmpty()) {
            mapOf(primaryGroupSize to bucketGeometrySummaries)
        } else {
            emptyMap()
        }

    fun resolvedSectionGeometrySummariesByGroup(
        primaryGroupSize: TimelineGroupSize
    ): Map<TimelineGroupSize, List<TimelineMosaicGeometrySummary>> =
        if (sectionGeometrySummariesByGroup.isNotEmpty()) {
            sectionGeometrySummariesByGroup
        } else if (sectionGeometrySummaries.isNotEmpty()) {
            mapOf(primaryGroupSize to sectionGeometrySummaries)
        } else {
            emptyMap()
        }

    fun resolvedBucketSkeletonsByGroup(
        primaryGroupSize: TimelineGroupSize
    ): Map<TimelineGroupSize, Map<String, TimelineMosaicBucketSkeleton>> =
        if (bucketSkeletonsByGroup.isNotEmpty()) {
            bucketSkeletonsByGroup
        } else if (bucketSkeletons.isNotEmpty()) {
            mapOf(primaryGroupSize to bucketSkeletons)
        } else {
            emptyMap()
        }

    fun isCompleteFor(
        expectedBucketIds: Set<String>,
        requiredGroupSizes: Set<TimelineGroupSize> = TIMELINE_MOSAIC_PRECOMPUTE_GROUP_SIZES
    ): Boolean {
        if (failedBucketIds.isNotEmpty()) return false
        if (failedBucketIdsByGroup.values.any { it.isNotEmpty() }) return false
        if (!successfulBucketIds.containsAll(expectedBucketIds)) return false
        val primaryGroupSize = requiredGroupSizes.firstOrNull() ?: TimelineGroupSize.MONTH
        val summariesByGroup = resolvedBucketGeometrySummariesByGroup(primaryGroupSize)
        val skeletonsByGroup = resolvedBucketSkeletonsByGroup(primaryGroupSize)
        return requiredGroupSizes.all { group ->
            val summaryBuckets = summariesByGroup[group]?.map { it.timeBucket }?.toSet().orEmpty()
            val skeletonBuckets = skeletonsByGroup[group]?.keys.orEmpty()
            summaryBuckets.containsAll(expectedBucketIds) &&
                skeletonBuckets.containsAll(expectedBucketIds)
        }
    }
}

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

data class TimelineDaySection(
    val sectionKey: String,
    val date: LocalDate?,
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
    val sectionManifest: List<TimelineMosaicSectionManifestEntry> = emptyList(),
    val groupingZoneId: String = "",
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

fun TimelineMosaicSectionGeometryArtifact.toSummary(): TimelineMosaicGeometrySummary =
    TimelineMosaicGeometrySummary(
        timeBucket = timeBucket,
        sectionKey = sectionKey,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount,
        ranges = ranges
    )

fun TimelineMosaicBucketArtifacts.toSkeleton(
    groupSize: TimelineGroupSize,
    assetCount: Int
): TimelineMosaicBucketSkeleton? {
    val bucket = bucketGeometry ?: return null
    val manifest = bucket.sectionManifest
    if (manifest.sumOf { it.assetCount } != assetCount) return null
    if (manifest.map { it.sectionKey }.toSet().size != manifest.size) return null
    val sectionsByKey = sectionGeometry.associateBy { it.sectionKey }
    if (sectionsByKey.keys != manifest.map { it.sectionKey }.toSet()) return null
    val sections = manifest.map { entry ->
        val section = sectionsByKey[entry.sectionKey]?.toSummary() ?: return null
        if (!validateMosaicSectionGeometry(
                ranges = section.ranges,
                assetCount = entry.assetCount,
                placeholderHeight = section.placeholderHeight,
                spacing = bucket.geometryRequest.spacing
            )
        ) return null
        section
    }
    val expectedHeight = timelineBucketGeometryHeight(
        groupSize = groupSize,
        sectionGeometries = sections.map { summary ->
            SectionGeometry(
                keyScope = MosaicKeyScope(
                    owner = MosaicOwnerKey(MosaicOwnerScope.TIMELINE_BUCKET, timeBucket),
                    sectionKey = summary.sectionKey,
                    columnCount = columnCount,
                    familiesKey = familiesKey,
                    contentFingerprint = bucket.assetFingerprint,
                    generation = bucket.updatedAt
                ),
                placeholderHeight = summary.placeholderHeight,
                displayItemCount = summary.displayItemCount,
                ranges = summary.ranges
            )
        },
        spacing = bucket.geometryRequest.spacing
    )
    if (kotlin.math.abs(bucket.placeholderHeight - expectedHeight) > 0.5f) return null
    return TimelineMosaicBucketSkeleton(
        timeBucket = timeBucket,
        assetCount = assetCount,
        placeholderHeight = bucket.placeholderHeight,
        sections = sections
    )
}

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

fun orderedTimelineAssetsFingerprint(assets: List<Asset>): String {
    val hasher = OrderedFingerprintHasher()
    assets.forEach { asset ->
        hasher.addAsset(
            listOf(
                asset.id,
                asset.type.name,
                asset.fileName,
                asset.createdAt,
                asset.isFavorite.toString(),
                asset.stackCount.toString(),
                asset.aspectRatio.toString(),
                asset.isEdited.toString()
            )
        )
    }
    return hasher.result()
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
                .map { section ->
                    TimelineMosaicSection(
                        sectionKey = section.sectionKey,
                        label = section.sectionKey,
                        assets = section.assets
                    )
                }
        }

        else -> listOf(
            TimelineMosaicSection(
                sectionKey = timelineMonthMosaicSectionKey(timeBucket),
                label = timeBucket,
                assets = assets
            )
        )
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

fun validateMosaicSectionGeometry(
    ranges: List<MosaicSectionGeometryRange>,
    assetCount: Int,
    placeholderHeight: Float,
    spacing: Float,
    tolerance: Float = 0.5f
): Boolean {
    if (!mosaicGeometryRangesCoverSourceRange(ranges, assetCount)) return false
    if (assetCount == 0) return placeholderHeight == 0f
    if (placeholderHeight <= 0f) return false
    val rangesHeight = ranges.sumOf { it.height.toDouble() }.toFloat() +
            spacing * (ranges.size - 1).coerceAtLeast(0)
    return kotlin.math.abs(rangesHeight - placeholderHeight) <= tolerance
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

fun timelineDaySections(
    timeBucket: String,
    assets: List<Asset>
): List<TimelineDaySection> {
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
    val datedSections = grouped
        .filterKeys { it != null }
        .entries
        .sortedByDescending { it.key }
        .mapNotNull { (date, dayAssets) ->
            val d = date ?: return@mapNotNull null
            TimelineDaySection(
                sectionKey = timelineDayMosaicSectionKey(timeBucket, d.toString()),
                date = d,
                assets = dayAssets.sortedByDescending { it.createdAt }
            )
        }
    val unknownAssets = grouped[null].orEmpty()
    return if (unknownAssets.isEmpty()) {
        datedSections
    } else {
        datedSections + TimelineDaySection(
            sectionKey = timelineDayMosaicSectionKey(timeBucket, TIMELINE_UNKNOWN_DATE_SECTION_ID),
            date = null,
            assets = unknownAssets
        )
    }
}

// DAY section membership depends on the device zone, so DAY artifacts carry it as identity.
fun timelineMosaicGroupingZoneId(groupSize: TimelineGroupSize): String =
    if (groupSize == TimelineGroupSize.DAY) TimeZone.currentSystemDefault().id else ""

fun timelineMosaicCacheWidth(availableWidth: Float): Float =
    (availableWidth * TIMELINE_MOSAIC_WIDTH_QUANTUM).roundToInt() / TIMELINE_MOSAIC_WIDTH_QUANTUM

fun timelineDaySectionDisplayLabel(sectionKey: String): AssetGroupDisplayLabel? {
    val dayIdentity = sectionKey.substringAfter("|day|", missingDelimiterValue = "")
    if (dayIdentity.isEmpty()) return null
    if (dayIdentity == TIMELINE_UNKNOWN_DATE_SECTION_ID) return AssetGroupDisplayLabel.UnknownDate
    return try {
        AssetGroupDisplayLabel.Day(LocalDate.parse(dayIdentity))
    } catch (_: Exception) {
        null
    }
}

fun timelineMonthMosaicSectionKey(timeBucket: String): String = "$timeBucket|month"

fun timelineDayMosaicSectionKey(
    timeBucket: String,
    dayIdentity: String
): String =
    "$timeBucket|day|$dayIdentity"

const val TIMELINE_UNKNOWN_DATE_SECTION_ID = "unknown-date"
const val TIMELINE_MOSAIC_WIDTH_QUANTUM = 100f
