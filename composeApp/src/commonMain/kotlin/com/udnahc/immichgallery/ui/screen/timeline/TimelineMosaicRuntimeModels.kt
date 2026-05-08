package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.MosaicAssignmentCheckpoint
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.ProgressChunk
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import com.udnahc.immichgallery.domain.model.MosaicSectionState as RuntimeMosaicState

internal data class MosaicCacheKey(
    val timeBucket: String,
    val sectionKey: String,
    val columnCount: Int,
    val assetRevision: Int,
    val familiesKey: String
)

internal data class TimelineMosaicSectionGeometry(
    val placeholderHeight: Float,
    val ranges: List<MosaicSectionGeometryRange>
)

internal data class TimelineMosaicPublishStamp(
    val globalGeneration: Long,
    val bucketGenerations: Map<String, Long>
)

internal data class PendingMosaicPublish(
    val stateUpdates: Map<MosaicCacheKey, RuntimeMosaicState>,
    val geometryUpdates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry>,
    val stamp: TimelineMosaicPublishStamp
)

internal class TimelineMosaicPublishBuffer {
    fun coalesceCurrent(
        pending: List<PendingMosaicPublish>,
        currentGlobalGeneration: Long,
        currentBucketGenerations: Map<String, Long>
    ): PendingMosaicPublish? {
        val accumulatedStates = LinkedHashMap<MosaicCacheKey, RuntimeMosaicState>()
        val accumulatedGeometry = LinkedHashMap<MosaicCacheKey, TimelineMosaicSectionGeometry>()
        pending.forEach { item ->
            if (item.stamp.globalGeneration != currentGlobalGeneration) return@forEach
            item.stateUpdates.forEach { (key, state) ->
                if (item.stamp.bucketGenerations[key.timeBucket] == (currentBucketGenerations[key.timeBucket]
                        ?: 0L)
                ) {
                    accumulatedStates[key] = state
                }
            }
            item.geometryUpdates.forEach { (key, geometry) ->
                if (item.stamp.bucketGenerations[key.timeBucket] == (currentBucketGenerations[key.timeBucket]
                        ?: 0L)
                ) {
                    accumulatedGeometry[key] = geometry
                }
            }
        }
        if (accumulatedStates.isEmpty() && accumulatedGeometry.isEmpty()) return null
        return PendingMosaicPublish(
            stateUpdates = accumulatedStates,
            geometryUpdates = accumulatedGeometry,
            stamp = TimelineMosaicPublishStamp(
                globalGeneration = currentGlobalGeneration,
                bucketGenerations = currentBucketGenerations.filterKeys { timeBucket ->
                    accumulatedStates.keys.any { it.timeBucket == timeBucket } ||
                            accumulatedGeometry.keys.any { it.timeBucket == timeBucket }
                }
            )
        )
    }
}

internal data class TimelineGeometryBucketSelection(
    val priorityBuckets: List<String>,
    val backgroundChunks: List<List<String>>
)

internal fun selectTimelineGeometryBuckets(
    orderedBuckets: List<String>,
    cachedBuckets: Set<String>,
    visibleBucketIndexes: List<Int>,
    targetBucketIndex: Int?,
    fallbackVisibleBucketIndexes: List<Int>,
    radius: Int,
    backgroundChunkSize: Int
): TimelineGeometryBucketSelection {
    if (orderedBuckets.isEmpty() || cachedBuckets.isEmpty()) {
        return TimelineGeometryBucketSelection(emptyList(), emptyList())
    }
    val cachedOrderedBuckets = orderedBuckets.filter { it in cachedBuckets }
    val priorityFromVisibility = orderedTimelineBucketIndexesForMosaic(
        visibleBucketIndexes = visibleBucketIndexes,
        targetBucketIndex = targetBucketIndex,
        fallbackVisibleBucketIndexes = fallbackVisibleBucketIndexes,
        bucketCount = orderedBuckets.size,
        radius = radius
    )
        .mapNotNull { index -> orderedBuckets.getOrNull(index) }
        .filter { it in cachedBuckets }
        .distinct()
    val fallbackPrioritySize = (1 + radius * 2).coerceAtLeast(1)
    val priorityBuckets = priorityFromVisibility.ifEmpty {
        cachedOrderedBuckets.take(fallbackPrioritySize)
    }
    val remaining = cachedOrderedBuckets.filter { it !in priorityBuckets }
    return TimelineGeometryBucketSelection(
        priorityBuckets = priorityBuckets,
        backgroundChunks = remaining.chunked(backgroundChunkSize.coerceAtLeast(1))
    )
}

internal data class ActiveMosaicConfig(
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val families: Set<MosaicTemplateFamily>
)

internal data class TimelineRuntimeMosaicSection(
    val sectionKey: String,
    val sectionLabel: String,
    val assets: List<Asset>
)

internal data class TimelineRuntimeMosaicResumeState(
    val checkpoint: MosaicAssignmentCheckpoint,
    val chunks: List<ProgressChunk>
)

internal data class DayGroupsCacheKey(
    val timeBucket: String,
    val assetRevision: Int
)

internal typealias RuntimeMosaicProgressChunk = ProgressChunk

internal class TimelineProgressiveMosaicBuffer {
    private val mutex = Mutex()
    private val chunksByKey = linkedMapOf<MosaicCacheKey, MutableList<RuntimeMosaicProgressChunk>>()

    suspend fun add(
        key: MosaicCacheKey,
        chunk: RuntimeMosaicProgressChunk
    ) {
        mutex.withLock {
            val chunks = chunksByKey.getOrPut(key) { mutableListOf() }
            val existingIndex = chunks.indexOfFirst {
                it.sourceStartIndex == chunk.sourceStartIndex &&
                        it.sourceEndExclusive == chunk.sourceEndExclusive
            }
            if (existingIndex >= 0) {
                chunks[existingIndex] = chunk
            } else {
                chunks.add(chunk)
                chunks.sortBy { it.sourceStartIndex }
            }
        }
    }

    suspend fun drainEligible(timeBuckets: Set<String>): Map<MosaicCacheKey, List<RuntimeMosaicProgressChunk>> {
        if (timeBuckets.isEmpty()) return emptyMap()
        return mutex.withLock {
            val eligibleKeys = chunksByKey.keys
                .filter { it.timeBucket in timeBuckets }
                .toList()
            eligibleKeys.associateWith { key ->
                chunksByKey.remove(key).orEmpty()
            }.filterValues { it.isNotEmpty() }
        }
    }

    suspend fun drainEligibleKeys(keys: Set<MosaicCacheKey>): Map<MosaicCacheKey, List<RuntimeMosaicProgressChunk>> {
        if (keys.isEmpty()) return emptyMap()
        return mutex.withLock {
            val eligibleKeys = chunksByKey.keys
                .filter { it in keys }
                .toList()
            eligibleKeys.associateWith { key ->
                chunksByKey.remove(key).orEmpty()
            }.filterValues { it.isNotEmpty() }
        }
    }

    suspend fun clearKeys(keys: Set<MosaicCacheKey>) {
        if (keys.isEmpty()) return
        mutex.withLock {
            keys.forEach(chunksByKey::remove)
        }
    }

    suspend fun clearBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        mutex.withLock {
            chunksByKey.keys.removeAll { it.timeBucket in timeBuckets }
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            chunksByKey.clear()
        }
    }

    suspend fun snapshotSize(): Int =
        mutex.withLock { chunksByKey.values.sumOf { it.size } }
}

internal fun RuntimeMosaicState?.cacheToken(): String =
    when (this) {
        null -> "missing"
        RuntimeMosaicState.Pending -> "pending"
        is RuntimeMosaicState.Partial -> "partial_${chunks.size}_${chunks.lastOrNull()?.sourceEndExclusive ?: 0}"
        is RuntimeMosaicState.Ready -> "ready_${assignments.size}_${assignments.hashCode()}_${displayRecords.size}_${displayRecords.hashCode()}"
        RuntimeMosaicState.Failed -> "failed"
    }

internal fun timelineAggregateGeometryPlaceholderHeight(
    aggregateGeometryHeight: Float?,
    sectionStates: List<RuntimeMosaicState?>
): Float? {
    val height = aggregateGeometryHeight?.takeIf { it > 0f } ?: return null
    return if (sectionStates.none { it is RuntimeMosaicState.Ready || it is RuntimeMosaicState.Partial }) {
        height
    } else {
        null
    }
}

internal fun timelineProgressiveMosaicStateUpdates(
    states: Map<MosaicCacheKey, RuntimeMosaicState>,
    updatesByKey: Map<MosaicCacheKey, List<RuntimeMosaicProgressChunk>>
): Map<MosaicCacheKey, RuntimeMosaicState> {
    var next = states
    updatesByKey.forEach { (key, runtimeChunks) ->
        next = when (val existing = next[key]) {
            is RuntimeMosaicState.Ready -> next
            is RuntimeMosaicState.Partial -> {
                val chunks = (existing.chunks + runtimeChunks)
                    .distinctBy { it.sourceStartIndex to it.sourceEndExclusive }
                    .sortedBy { it.sourceStartIndex }
                next + (key to RuntimeMosaicState.Partial(chunks))
            }

            else -> next + (key to RuntimeMosaicState.Partial(
                runtimeChunks
                    .distinctBy { it.sourceStartIndex to it.sourceEndExclusive }
                    .sortedBy { it.sourceStartIndex }
            ))
        }
    }
    return next
}

internal fun mosaicFamiliesKey(families: Set<MosaicTemplateFamily>): String =
    families.map { it.persistedId }.sorted().joinToString("|")

internal fun monthMosaicSectionKey(timeBucket: String): String = "$timeBucket|month"

internal fun timelineBucketGeometrySectionKey(
    timeBucket: String,
    groupSize: TimelineGroupSize,
    geometryRequest: TimelineMosaicGeometryRequest
): String =
    "$timeBucket|bucket|${groupSize.apiValue}|${timelineBucketGeometryRequestKey(geometryRequest)}"

internal fun timelineBucketGeometryRequestKey(request: TimelineMosaicGeometryRequest): String =
    "w=${request.availableWidth.roundToInt()}|" +
            "h=${(request.maxRowHeight * 100f).roundToInt()}|" +
            "s=${(request.spacing * 100f).roundToInt()}"

internal fun dayMosaicSectionKey(
    timeBucket: String,
    dayLabel: String
): String =
    "$timeBucket|day|$dayLabel"

internal fun timelinePlaceholderGridKey(
    prefix: String,
    timeBucket: String,
    sectionKey: String,
    chunkIndex: Int
): String = "${prefix}_${timeBucket}_${sectionKey}_$chunkIndex"

internal fun timelineMosaicBandGridKey(
    timeBucket: String,
    sectionKey: String,
    tileAssetIds: List<String>
): String = "mosaic_${timeBucket}_${sectionKey}_${tileAssetIds.joinToString("_")}"
