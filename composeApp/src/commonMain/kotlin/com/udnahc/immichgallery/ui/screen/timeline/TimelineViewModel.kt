package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.detail.ClearDetailMosaicCacheAction
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.action.timeline.ClearTimelineMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.LoadBucketAssetsAction
import com.udnahc.immichgallery.domain.action.timeline.PrepareTimelineMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.SyncAllTimelineAssetsAction
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineMosaicCacheStatusUseCase
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandAssignment
import com.udnahc.immichgallery.domain.model.MosaicDisplayItemRecord
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicAssignmentCheckpoint
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
import com.udnahc.immichgallery.domain.model.MosaicLayoutSpec
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicSectionRequest
import com.udnahc.immichgallery.domain.model.MosaicSectionResult
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.ProgressChunk
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.TimelineScrollTarget
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicProgressChunk
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildTimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItems
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItemsForHeight
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_GRID_COLUMN_COUNT
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.mosaicFallbackRowHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecFor
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.model.resolvedSectionDisplayRecordsOrEmpty
import com.udnahc.immichgallery.domain.model.timelineScrollTargetForFraction
import com.udnahc.immichgallery.domain.model.bucketIndexForTimelineFraction
import com.udnahc.immichgallery.domain.model.displayRecordsCoverOrderedAssets
import com.udnahc.immichgallery.domain.model.toPhotoGridDisplayItems
import com.udnahc.immichgallery.domain.model.MosaicSectionState as RuntimeMosaicState
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketGeometryUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineMosaicSectionGeometryUseCase
import com.udnahc.immichgallery.ui.model.ConnectionUiMessage
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import com.udnahc.immichgallery.ui.util.TimelineMosaicDispatcherProvider
import com.udnahc.immichgallery.ui.util.TimelineMosaicQueueActor
import com.udnahc.immichgallery.ui.util.TimelineMosaicQueueCommand
import com.udnahc.immichgallery.ui.util.TimelineMosaicQueueEffect
import com.udnahc.immichgallery.ui.util.TimelineMosaicQueueRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import org.lighthousegames.logging.logging

@Immutable
data class YearMarker(val fraction: Float, val year: String)

@Immutable
data class DayGroup(val label: String, val assets: List<Asset>)

@Immutable
data class TimelineState(
    val groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    val isLoading: Boolean = false,
    val error: String? = null,
    val displayItems: List<TimelineDisplayItem> = emptyList(),
    val displayIndex: TimelineDisplayIndex = TimelineDisplayIndex(),
    val yearMarkers: List<YearMarker> = emptyList(),
    val totalItemCount: Int = 0,
    val cumulativeItemCounts: List<Int> = emptyList(),
    val bucketDisplayLabels: List<String> = emptyList(),
    val pageIndex: TimelinePageIndex = TimelinePageIndex(),
    val buckets: List<TimelineBucket> = emptyList(),
    val viewConfig: ViewConfig = ViewConfig(),
    val bannerError: ConnectionUiMessage? = null,
    val bannerSuccess: ConnectionUiMessage? = null,
    val lastSyncedAt: Long? = null,
    val isSyncing: Boolean = false
)

@Immutable
data class TimelineOverlayState(
    val pageIndex: TimelinePageIndex = TimelinePageIndex(),
    val buckets: List<TimelineBucket> = emptyList()
)

enum class TimelineBucketTargetReason {
    VisibleScroll,
    ScrollSettled,
    ScrollbarDrag,
    ScrollbarStop
}

// Temporary local experiment: when true, a completed cold Timeline cache is
// refreshed only by explicit manual sync. Cached launch, visible/target loads,
// retry, and Mosaic settings preparation use Room cache only.
private const val DISABLE_TIMELINE_NON_MANUAL_SERVER_SYNC = true

internal enum class TimelineStartupMode {
    Cold,
    CachedLaunch,
    ManualRefresh
}

internal fun timelineStartupMode(
    hasCachedBuckets: Boolean,
    hasCompletedColdSync: Boolean,
    isFullRefresh: Boolean
): TimelineStartupMode {
    val hasWarmTimelineCache = hasCachedBuckets && hasCompletedColdSync
    return when {
        !hasWarmTimelineCache -> TimelineStartupMode.Cold
        isFullRefresh -> TimelineStartupMode.ManualRefresh
        else -> TimelineStartupMode.CachedLaunch
    }
}

internal enum class TimelineRefreshQueuePriority {
    Normal,
    Visible,
    ScrollbarDrag,
    ScrollbarStop
}

internal enum class TimelineServerRefreshSource {
    ColdSync,
    ManualRefresh,
    CachedLaunch,
    Visible,
    Target,
    Retry,
    ExplicitBucketLoad,
    BlockingMosaicPrepare
}

internal fun allowTimelineServerRefresh(
    disableNonManualServerSync: Boolean,
    source: TimelineServerRefreshSource
): Boolean =
    !disableNonManualServerSync ||
        source == TimelineServerRefreshSource.ColdSync ||
        source == TimelineServerRefreshSource.ManualRefresh

private fun allowTimelineServerRefresh(source: TimelineServerRefreshSource): Boolean =
    allowTimelineServerRefresh(DISABLE_TIMELINE_NON_MANUAL_SERVER_SYNC, source)

internal enum class TimelineMosaicWorkSource {
    RenderDemand,
    SyncPrecompute
}

internal fun shouldComputeRuntimeTimelineMosaic(
    cacheMosaicResults: Boolean,
    source: TimelineMosaicWorkSource
): Boolean =
    !cacheMosaicResults && source == TimelineMosaicWorkSource.RenderDemand

internal data class TimelineRefreshQueueOrdering(
    val pendingBuckets: List<String>,
    val pendingDragBuckets: Set<String>
)

internal data class TimelineMosaicScrollSelection(
    val runnableBuckets: List<String>,
    val deferredBuckets: List<String>
)

internal fun selectTimelineMosaicBucketsForScroll(
    requestedBuckets: List<String>,
    visibleOrTargetBuckets: List<String>,
    cacheMosaicResults: Boolean,
    isScrollActive: Boolean
): TimelineMosaicScrollSelection {
    val orderedRequested = requestedBuckets.distinct()
    if (!isScrollActive) {
        return TimelineMosaicScrollSelection(
            runnableBuckets = orderedRequested,
            deferredBuckets = emptyList()
        )
    }
    if (!cacheMosaicResults) {
        return TimelineMosaicScrollSelection(
            runnableBuckets = emptyList(),
            deferredBuckets = orderedRequested
        )
    }
    val visibleSet = visibleOrTargetBuckets.toSet()
    val runnable = orderedRequested.filter { it in visibleSet }
    return TimelineMosaicScrollSelection(
        runnableBuckets = runnable,
        deferredBuckets = orderedRequested.filter { it !in visibleSet }
    )
}

internal fun orderedTimelineBucketIndexesForMosaic(
    visibleBucketIndexes: List<Int>,
    targetBucketIndex: Int?,
    fallbackVisibleBucketIndexes: List<Int>,
    bucketCount: Int,
    radius: Int
): List<Int> {
    if (bucketCount <= 0) return emptyList()
    val seeds = buildList {
        visibleBucketIndexes.forEach { index ->
            if (index in 0 until bucketCount && index !in this) add(index)
        }
        targetBucketIndex?.let { target ->
            if (target in 0 until bucketCount && target !in this) add(target)
        }
        if (isEmpty()) {
            fallbackVisibleBucketIndexes.forEach { index ->
                if (index in 0 until bucketCount && index !in this) add(index)
            }
        }
    }
    return buildList {
        seeds.forEach { seed ->
            if (seed !in this) add(seed)
        }
        seeds.forEach { seed ->
            for (distance in 1..radius) {
                val before = seed - distance
                val after = seed + distance
                if (before in 0 until bucketCount && before !in this) add(before)
                if (after in 0 until bucketCount && after !in this) add(after)
            }
        }
    }
}

internal fun timelineCachedMaterializationChunks(
    requestedBuckets: List<String>,
    priorityBuckets: List<String>,
    prefetchChunkSize: Int
): List<List<String>> {
    if (requestedBuckets.isEmpty()) return emptyList()
    val orderedRequested = requestedBuckets.distinct()
    val priorityChunk = priorityBuckets
        .filter { it in orderedRequested }
        .distinct()
    val remaining = orderedRequested.filter { it !in priorityChunk }
    val boundedChunkSize = prefetchChunkSize.coerceAtLeast(1)
    return buildList {
        if (priorityChunk.isNotEmpty()) add(priorityChunk)
        remaining.chunked(boundedChunkSize).forEach(::add)
    }
}

internal fun timelineCachedMaterializationClaims(
    requestedBuckets: List<String>,
    cachedBuckets: Set<String>,
    loadedBuckets: Set<String>,
    materializingBuckets: Set<String>,
    inMemoryBuckets: Set<String>
): List<String> =
    requestedBuckets.filter { timeBucket ->
        timeBucket !in inMemoryBuckets &&
            timeBucket !in materializingBuckets &&
            (timeBucket in cachedBuckets || timeBucket in loadedBuckets)
    }

internal fun mergeTimelineMosaicRequestOrder(
    priorityBuckets: List<String>,
    deferredBuckets: Collection<String>
): List<String> =
    buildList {
        priorityBuckets.forEach { bucket ->
            if (bucket !in this) add(bucket)
        }
        deferredBuckets.forEach { bucket ->
            if (bucket !in this) add(bucket)
        }
    }

internal fun reorderTimelineRefreshQueue(
    pendingBuckets: List<String>,
    pendingDragBuckets: Set<String>,
    bucketsToQueue: List<String>,
    priority: TimelineRefreshQueuePriority
): TimelineRefreshQueueOrdering {
    val queuedSet = bucketsToQueue.toSet()
    val pending = pendingBuckets.toMutableList()
    val dragBuckets = pendingDragBuckets.toMutableSet()
    pending.removeAll(queuedSet)
    when (priority) {
        TimelineRefreshQueuePriority.Normal -> {
            dragBuckets.removeAll(queuedSet)
            pending.addAll(bucketsToQueue)
        }
        TimelineRefreshQueuePriority.Visible -> {
            dragBuckets.removeAll(queuedSet)
            pending.addAll(0, bucketsToQueue)
        }
        TimelineRefreshQueuePriority.ScrollbarDrag -> {
            val olderDragBuckets = pending.filter { it in dragBuckets }
            pending.removeAll(olderDragBuckets.toSet())
            pending.addAll(0, bucketsToQueue)
            pending.addAll(olderDragBuckets)
            dragBuckets.addAll(bucketsToQueue)
        }
        TimelineRefreshQueuePriority.ScrollbarStop -> {
            val olderDragBuckets = pending.filter { it in dragBuckets && it !in queuedSet }
            pending.removeAll(olderDragBuckets.toSet())
            dragBuckets.removeAll(queuedSet)
            pending.addAll(0, bucketsToQueue)
            val insertIndex = bucketsToQueue.size.coerceAtMost(pending.size)
            pending.addAll(insertIndex, olderDragBuckets)
        }
    }
    return TimelineRefreshQueueOrdering(
        pendingBuckets = pending,
        pendingDragBuckets = dragBuckets
    )
}

@Immutable
private data class BucketData(
    val buckets: List<TimelineBucket> = emptyList(),
    // Cached buckets have Room refs available, but are not necessarily
    // materialized into bucketAssetsCache/display rows yet.
    val cachedBuckets: Set<String> = emptySet(),
    val geometryReadyBuckets: Set<MosaicCacheKey> = emptySet(),
    val loadedBuckets: Set<String> = emptySet(),
    val loadingBuckets: Set<String> = emptySet(),
    val failedBuckets: Set<String> = emptySet(),
    // Render revisions track in-memory materialization. They intentionally do
    // not participate in Mosaic artifact keys; persisted Mosaic cache identity
    // remains tied to ordered content revisions.
    val materializationRevisions: Map<String, Int> = emptyMap(),
    // Content revisions are per bucket on purpose. Launch sync can refresh
    // every bucket successfully while changing none of them; a global revision
    // would make every packed row and Mosaic assignment look stale anyway.
    val assetRevisions: Map<String, Int> = emptyMap()
)

private fun BucketData.assetRevisionFor(timeBucket: String): Int =
    assetRevisions[timeBucket] ?: 0

private fun BucketData.materializationRevisionFor(timeBucket: String): Int =
    materializationRevisions[timeBucket] ?: 0

private fun Map<String, Int>.incrementRevision(timeBucket: String): Map<String, Int> =
    this + (timeBucket to ((this[timeBucket] ?: 0) + 1))

private fun Map<String, Int>.incrementRevisions(timeBuckets: Set<String>): Map<String, Int> {
    if (timeBuckets.isEmpty()) return this
    return buildMap {
        putAll(this@incrementRevisions)
        timeBuckets.forEach { timeBucket ->
            put(timeBucket, (this@incrementRevisions[timeBucket] ?: 0) + 1)
        }
    }
}

@Immutable
private data class UiConfig(
    val groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    val availableWidth: Float = 0f,
    val availableHeight: Float = 0f,
    val isLoading: Boolean = false,
    val error: String? = null,
    val bannerError: ConnectionUiMessage? = null,
    val bannerSuccess: ConnectionUiMessage? = null,
    val lastSyncedAt: Long? = null,
    val isSyncing: Boolean = false,
    val mosaicAnchorBucketIndex: Int = 0,
    val mosaicColumnCount: Int = ViewConfig().mosaicColumnCount,
    val viewConfig: ViewConfig = ViewConfig(),
    val activeMosaicConfig: ActiveMosaicConfig? = null
)

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
                if (item.stamp.bucketGenerations[key.timeBucket] == (currentBucketGenerations[key.timeBucket] ?: 0L)) {
                    accumulatedStates[key] = state
                }
            }
            item.geometryUpdates.forEach { (key, geometry) ->
                if (item.stamp.bucketGenerations[key.timeBucket] == (currentBucketGenerations[key.timeBucket] ?: 0L)) {
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

private data class ActiveMosaicConfig(
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val families: Set<MosaicTemplateFamily>
)

private data class TimelineRuntimeMosaicSection(
    val sectionKey: String,
    val sectionLabel: String,
    val assets: List<Asset>
)

private data class TimelineRuntimeMosaicResumeState(
    val checkpoint: MosaicAssignmentCheckpoint,
    val chunks: List<ProgressChunk>
)

private data class DayGroupsCacheKey(
    val timeBucket: String,
    val assetRevision: Int
)

internal typealias RuntimeMosaicProgressChunk = ProgressChunk

internal class TimelineProgressiveMosaicBuffer {
    private val mutex = Mutex()
    private val chunksByKey = linkedMapOf<MosaicCacheKey, MutableList<RuntimeMosaicProgressChunk>>()

    suspend fun add(key: MosaicCacheKey, chunk: RuntimeMosaicProgressChunk) {
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

private fun RuntimeMosaicState?.cacheToken(): String =
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

private fun mosaicFamiliesKey(families: Set<MosaicTemplateFamily>): String =
    families.map { it.persistedId }.sorted().joinToString("|")

private fun monthMosaicSectionKey(timeBucket: String): String = "$timeBucket|month"

internal fun timelineBucketGeometrySectionKey(
    timeBucket: String,
    groupSize: TimelineGroupSize,
    geometryRequest: TimelineMosaicGeometryRequest
): String = "$timeBucket|bucket|${groupSize.apiValue}|${timelineBucketGeometryRequestKey(geometryRequest)}"

internal fun timelineBucketGeometryRequestKey(request: TimelineMosaicGeometryRequest): String =
    "w=${request.availableWidth.roundToInt()}|" +
        "h=${(request.maxRowHeight * 100f).roundToInt()}|" +
        "s=${(request.spacing * 100f).roundToInt()}"

private fun dayMosaicSectionKey(timeBucket: String, dayLabel: String): String =
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

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(
    private val getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
    private val getBucketAssetsUseCase: GetBucketAssetsUseCase,
    private val loadBucketAssetsAction: LoadBucketAssetsAction,
    private val syncAllTimelineAssetsAction: SyncAllTimelineAssetsAction,
    private val prepareTimelineMosaicCacheAction: PrepareTimelineMosaicCacheAction,
    private val getTimelineMosaicCacheStatusUseCase: GetTimelineMosaicCacheStatusUseCase,
    private val getTimelineBucketGeometryUseCase: GetTimelineBucketGeometryUseCase,
    private val getTimelineMosaicSectionGeometryUseCase: GetTimelineMosaicSectionGeometryUseCase,
    private val clearTimelineMosaicCacheAction: ClearTimelineMosaicCacheAction,
    private val clearDetailMosaicCacheAction: ClearDetailMosaicCacheAction,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetFileNameUseCase: GetAssetFileNameUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTimelineGroupSizeUseCase: GetTimelineGroupSizeUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTimelineGroupSizeAction: SetTimelineGroupSizeAction,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction,
    private val timelineMosaicDispatcherProvider: TimelineMosaicDispatcherProvider
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    var lastViewedAssetId: String? by mutableStateOf(null)
    var lastViewedBucket: String? by mutableStateOf(null)

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    suspend fun getAssetFileName(assetId: String, fallback: String): Result<String> =
        getAssetFileNameUseCase(assetId, fallback)

    override fun onCleared() {
        super.onCleared()
        timelineMosaicQueue.close()
        timelineMosaicDispatcherProvider.close()
        mosaicPublishChannel.close()
    }

    private val log = logging("TimelineViewModel")
    private var syncJob: Job? = null
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope)
    private var visibleBucketIndexes: List<Int> = emptyList()
    private var lastVisibleBucketIndexes: List<Int> = emptyList()
    private var lastTargetBucketIndex: Int? = null
    private var lastLoggedZoomColumnCount: Int? = null
    private var hasSavedTargetRowHeight = false
    private var savedTargetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT
    private val dayGroupsCache = mutableMapOf<DayGroupsCacheKey, List<DayGroup>>()
    private val mosaicRenderEngine = MosaicRenderEngine()

    private val _bucketData = MutableStateFlow(BucketData())
    private val _uiConfig: MutableStateFlow<UiConfig>
    private val _mosaicStates = MutableStateFlow<Map<MosaicCacheKey, RuntimeMosaicState>>(emptyMap())
    private val _mosaicGeometryStates = MutableStateFlow<Map<MosaicCacheKey, TimelineMosaicSectionGeometry>>(emptyMap())
    private val _bucketGeometryStates = MutableStateFlow<Map<MosaicCacheKey, Float>>(emptyMap())
    private val progressiveMosaicBuffer = TimelineProgressiveMosaicBuffer()
    private var progressiveMosaicFlushJob: Job? = null

    // Exposed directly (not through debounced pipeline) so UI sees it immediately
    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()
    private val _buildError = MutableStateFlow<ConnectionUiMessage?>(null)
    val buildError: StateFlow<ConnectionUiMessage?> = _buildError.asStateFlow()

    // Single source of truth for `timeBucket -> assets`, shared between the
    // grid's buildDisplayItems pipeline AND the overlay's pager. Using
    // SnapshotStateMap so the overlay reads it reactively — when a bucket is
    // loaded, the pager re-renders on the same frame buildDisplayItems sees
    // the new data. This prevents the exit shared-element animation from
    // failing after the user pages into an unloaded bucket and dismisses:
    // previously the overlay had the asset (in its private cache) but the
    // grid's displayItems still showed a PlaceholderItem with no ThumbnailCell
    // to land on.
    //
    // Thread-safety: SnapshotStateMap is safe for concurrent access. Writes
    // happen from Dispatchers.IO (loadBucketAssetsInternal on action success)
    // and the bounded mosaic worker pool; reads happen from Default (build
    // pipeline) and the UI thread (overlay pager).
    val bucketAssetsCache: SnapshotStateMap<String, List<Asset>> = mutableStateMapOf()

    // Derived-item caches are layout-dependent, still single-threaded on Default.
    private var cachedBucketItems: Map<String, List<TimelineDisplayItem>> = emptyMap()
    private var cachedFlatItems: List<TimelineDisplayItem> = emptyList()
    private var cachedGroupSize: TimelineGroupSize? = null
    private var cachedAvailableWidth: Float? = null
    private var cachedTargetRowHeight: Float? = null
    private var cachedMaxRowHeight: Float? = null
    private var cachedMosaicColumnCount: Int? = null
    private var cachedViewConfig: ViewConfig? = null
    private var cachedBucketOrder: List<String> = emptyList()
    private var cachedPageIndexKey: TimelinePageIndexCacheKey? = null
    private var cachedPageIndex: TimelinePageIndex = TimelinePageIndex()
    private var cachedScrollbarDataKey: TimelineScrollbarDataCacheKey? = null
    private var cachedScrollbarData: ScrollbarData = ScrollbarData()
    private var cachedDisplayIndexItems: List<TimelineDisplayItem>? = null
    private var cachedDisplayIndex: TimelineDisplayIndex = TimelineDisplayIndex()
    private val refreshQueueMutex = Mutex()
    private val pendingVisibleRefreshBuckets = mutableListOf<String>()
    private val pendingScrollbarDragRefreshBuckets = mutableSetOf<String>()
    private val materializingCachedBucketIds = mutableSetOf<String>()
    private val serverRefreshedBucketIds = mutableSetOf<String>()
    private val staleBucketIds = mutableSetOf<String>()
    private val refreshingBucketIds = mutableSetOf<String>()
    private var visibleRefreshJob: Job? = null
    private val timelineMosaicQueue = TimelineMosaicQueueActor(viewModelScope)
    private val timelineMosaicWorkerMutex = Mutex()
    private val activeTimelineMosaicJobs = mutableMapOf<Long, Job>()
    private val isTimelineScrollActive = MutableStateFlow(false)
    private val mosaicRuntimeGeneration = MutableStateFlow(0L)
    private val mosaicBucketGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val deferredScrollMosaicStateUpdates = mutableMapOf<MosaicCacheKey, RuntimeMosaicState>()
    private val deferredScrollMosaicGeometryUpdates = mutableMapOf<MosaicCacheKey, TimelineMosaicSectionGeometry>()
    // Materialization runs that complete during active scroll route their
    // _bucketData publishes through this buffer instead of firing combine
    // mid-gesture. Drained in onScrollInProgressChanged(false).
    private val deferredScrollMaterializedBuckets = mutableSetOf<String>()
    // Coalesces per-bucket publishMosaicUpdates calls so the
    // _bucketData/_uiConfig/_mosaicStates combine fires once per drain cycle
    // instead of once per bucket. The drain coroutine accumulates whatever
    // publishes have arrived since the last emission, then publishes them in a
    // single _mosaicStates update before suspending for the next item.
    private val mosaicPublishChannel = Channel<PendingMosaicPublish>(Channel.UNLIMITED)
    private val mosaicPublishBuffer = TimelineMosaicPublishBuffer()
    private data class TimelineMosaicReadyMemo(
        val recordsRef: List<MosaicDisplayItemRecord>,
        val assetsRef: List<Asset>,
        val items: List<TimelineDisplayItem>,
        val covers: Boolean
    )
    // Memoizes the result of toPhotoGridDisplayItems + coverage validation per
    // Ready section. Reference identity on display records and assets means the memo is
    // hit whenever the underlying state has not been replaced; replacing a
    // Ready entry produces a new displayRecords list, and replacing materialized
    // assets produces a new asset list, so identity diverging is exactly the
    // invalidation signal we want.
    private val readyMosaicMemoByKey = mutableMapOf<MosaicCacheKey, TimelineMosaicReadyMemo>()
    private var deferredScrollActiveMosaicConfig: ActiveMosaicConfig? = null
    private val runtimeMosaicResumeStates = mutableMapOf<MosaicCacheKey, TimelineRuntimeMosaicResumeState>()
    private var bucketGeometryCacheJob: Job? = null
    private var bucketGeometryCacheGeneration = 0L
    // Closed (false) until a coordinated bucket+section geometry read completes
    // for the current cached buckets. The mosaic queue's first request waits on
    // this gate so placeholders use exact per-band heights before bands arrive.
    private val timelineGeometryReady = MutableStateFlow(false)
    private var manualRefreshActive = false

    val state: StateFlow<TimelineState>
    val overlayState: StateFlow<TimelineOverlayState>

    val labelProvider: (Float) -> String? = { fraction ->
        val s = state.value
        bucketIndexForTimelineFraction(s.pageIndex, fraction)
            ?.let { index -> s.bucketDisplayLabels.getOrNull(index) }
    }

    init {
        val saved = getTimelineGroupSizeUseCase()
        val initialSize = TimelineGroupSize.entries.find { it.apiValue == saved }
            ?: TimelineGroupSize.MONTH
        hasSavedTargetRowHeight = getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.TIMELINE)
        savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.TIMELINE)
        val initialTargetRowHeight = savedTargetRowHeight
        val initialViewConfig = getViewConfigUseCase()

        _uiConfig = MutableStateFlow(
            UiConfig(
                groupSize = initialSize,
                targetRowHeight = initialTargetRowHeight,
                mosaicColumnCount = initialViewConfig.mosaicColumnCount,
                viewConfig = initialViewConfig
            )
        )

        viewModelScope.launch(Dispatchers.Default) {
            getTimelineGroupSizeUseCase.observe().collect { saved ->
                val size = TimelineGroupSize.entries.find { it.apiValue == saved }
                    ?: TimelineGroupSize.MONTH
                if (size != _uiConfig.value.groupSize) {
                    _uiConfig.update { it.copy(groupSize = size) }
                    requestVisibleTimelineMosaicCacheRead()
                    requestPersistedBucketGeometryForCachedBuckets()
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            getViewConfigUseCase.observe().collect { saved ->
                val config = saved.normalized
                if (config != _uiConfig.value.viewConfig) {
                    _uiConfig.update {
                        it.copy(
                            viewConfig = config,
                            mosaicColumnCount = config.mosaicColumnCount
                        )
                    }
                    requestVisibleTimelineMosaicCacheRead()
                    requestPersistedBucketGeometryForCachedBuckets()
                }
            }
        }

        // Bucket-load signals must propagate to displayItems IMMEDIATELY so
        // the grid has the dismissed asset's RowItem composed before the
        // shared-element exit animation starts. Layout changes (pinch-to-zoom
        // spamming targetRowHeight updates) stay debounced so we don't rebuild
        // on every intermediate frame. Combining them at the StateFlow level
        // means _bucketData updates bypass the debounce.
        @OptIn(FlowPreview::class)
        state = combine(
            _bucketData,
            _uiConfig.debounce(200),
            _mosaicStates,
            _mosaicGeometryStates,
            _bucketGeometryStates
        ) { data, config, mosaicStates, mosaicGeometryStates, bucketGeometryStates ->
            buildTimelineState(data, config, mosaicStates, mosaicGeometryStates, bucketGeometryStates)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineState(
                groupSize = initialSize,
                targetRowHeight = initialTargetRowHeight,
                viewConfig = initialViewConfig
            ))

        overlayState = state
            .map { TimelineOverlayState(pageIndex = it.pageIndex, buckets = it.buckets) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineOverlayState())

        viewModelScope.launch(Dispatchers.Default) {
            for (effect in timelineMosaicQueue.effects) {
                handleTimelineMosaicQueueEffect(effect)
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            for (firstItem in mosaicPublishChannel) {
                val pending = mutableListOf(firstItem)
                while (true) {
                    val next = mosaicPublishChannel.tryReceive().getOrNull() ?: break
                    pending.add(next)
                }
                val publish = mosaicPublishBuffer.coalesceCurrent(
                    pending = pending,
                    currentGlobalGeneration = mosaicRuntimeGeneration.value,
                    currentBucketGenerations = mosaicBucketGenerations.value
                ) ?: continue
                if (publish.geometryUpdates.isNotEmpty()) {
                    _mosaicGeometryStates.update { states -> states + publish.geometryUpdates }
                }
                if (publish.stateUpdates.isNotEmpty()) {
                    _mosaicStates.update { states -> states + publish.stateUpdates }
                    progressiveMosaicBuffer.clearKeys(publish.stateUpdates.keys)
                }
                if (publish.geometryUpdates.isNotEmpty()) {
                    scheduleProgressiveMosaicFlush(immediate = true)
                }
            }
        }

        // Observe Room for buckets (reactive SSOT)
        viewModelScope.launch(Dispatchers.IO) {
            // Pre-populate cachedBuckets from Room metadata. Actual rows are
            // materialized only for visible/nearby buckets so a warm launch
            // does not read and pack every bucket before the user scrolls.
            val alreadyLoaded = getTimelineBucketsUseCase.getLoadedBucketIds()
            if (alreadyLoaded.isNotEmpty()) {
                _bucketData.update { it.copy(cachedBuckets = it.cachedBuckets + alreadyLoaded) }
            }

            getTimelineBucketsUseCase.observe().collect { buckets ->
                log.d { "Room emitted ${buckets.size} buckets" }
                val bucketIds = buckets.map { it.timeBucket }.toSet()
                _bucketData.update { data ->
                    data.copy(
                        buckets = buckets,
                        cachedBuckets = data.cachedBuckets.filter { it in bucketIds }.toSet(),
                        geometryReadyBuckets = data.geometryReadyBuckets.filter { it.timeBucket in bucketIds }.toSet(),
                        loadedBuckets = data.loadedBuckets.filter { it in bucketIds }.toSet(),
                        loadingBuckets = data.loadingBuckets.filter { it in bucketIds }.toSet(),
                        failedBuckets = data.failedBuckets.filter { it in bucketIds }.toSet(),
                        materializationRevisions = data.materializationRevisions.filterKeys { it in bucketIds },
                        assetRevisions = data.assetRevisions.filterKeys { it in bucketIds }
                    )
                }
                requestVisibleTimelineMosaicCacheRead()
                requestPersistedBucketGeometryForCachedBuckets()
            }
        }

        // Initial sync from network
        syncFromServer()
    }

    fun dismissBannerError() {
        _uiConfig.update { it.copy(bannerError = null) }
    }

    fun dismissBannerSuccess() {
        _uiConfig.update { it.copy(bannerSuccess = null) }
    }

    fun refreshAll() {
        syncFromServer(isFullRefresh = true)
    }

    fun setGroupSize(size: TimelineGroupSize) {
        if (size == _uiConfig.value.groupSize) return
        setTimelineGroupSizeAction(size.apiValue)
        // Signal full cache invalidation via _uiConfig change — buildDisplayItems
        // detects groupSize mismatch and clears all caches on Dispatchers.Default
        _uiConfig.update { it.copy(groupSize = size) }
        clearProgressiveMosaicState()
        requestVisibleTimelineMosaicCacheRead()
    }

    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        if (normalized == _uiConfig.value.viewConfig) return
        setViewConfigAction(normalized)
        _uiConfig.update {
            it.copy(
                viewConfig = normalized,
                mosaicColumnCount = normalized.mosaicColumnCount,
                activeMosaicConfig = if (normalized.cacheMosaicResults) it.activeMosaicConfig else null
            )
        }
        clearProgressiveMosaicState()
        requestVisibleTimelineMosaicCacheRead()
    }

    suspend fun prepareMosaicViewConfig(config: ViewConfig): Result<Unit> = withContext(Dispatchers.Default) {
        val normalized = config.normalized
        if (!normalized.cacheMosaicResults || !normalized.mosaicEnabled) return@withContext Result.success(Unit)
        val snapshot = _uiConfig.value
        val data = _bucketData.value
        val buckets = data.buckets
        if (buckets.isEmpty()) return@withContext Result.success(Unit)
        val geometryRequest = timelineMosaicGeometryRequest(
            availableWidth = snapshot.availableWidth,
            maxRowHeight = snapshot.rowHeightBounds.max,
            columnCount = normalized.mosaicColumnCount
        ) ?: return@withContext Result.failure(IllegalStateException("Timeline geometry metrics are not ready"))
        log.d {
            "Blocking Timeline Mosaic prepare started buckets=${buckets.size} " +
                "group=${snapshot.groupSize} columns=${normalized.mosaicColumnCount} " +
                "families=${normalized.mosaicFamilies}"
        }
        beginManualRefresh()
        try {
            val assetResult = if (allowTimelineServerRefresh(TimelineServerRefreshSource.BlockingMosaicPrepare)) {
                syncAllTimelineAssetsWithRetry(buckets, context = "blocking-mosaic-prepare")
                    .getOrElse { return@withContext Result.failure(it) }
            } else {
                log.d { "Blocking Timeline Mosaic prepare using cached bucket assets; server sync disabled by experiment" }
                TimelineAssetSyncResult(
                    successfulBucketIds = buckets.map { it.timeBucket }.toSet(),
                    failedBucketIds = emptySet()
                )
            }
            if (assetResult.failedBucketIds.isNotEmpty()) {
                Result.failure(IllegalStateException(
                    "Failed to sync Timeline buckets=${assetResult.failedBucketIds.size}"
                ))
            } else {
                val changedSuccessfulBucketIds = assetResult.changedBucketIds intersect assetResult.successfulBucketIds
                clearTimelineMosaicCacheAction.buckets(changedSuccessfulBucketIds)
                val precomputeResult = prepareTimelineMosaicCacheAction(
                    timeBuckets = assetResult.successfulBucketIds,
                    groupSize = snapshot.groupSize,
                    columnCount = normalized.mosaicColumnCount,
                    families = normalized.mosaicFamilies,
                    geometryRequest = geometryRequest,
                    cacheDisplayResults = true
                ).getOrElse { return@withContext Result.failure(it) }
                if (precomputeResult.failedBucketIds.isNotEmpty()) {
                    return@withContext Result.failure(IllegalStateException(
                        "Failed to prepare Timeline Mosaic buckets=${precomputeResult.failedBucketIds.size}"
                    ))
                }
                publishBlockingMosaicPrepareResult(
                    successfulBucketIds = precomputeResult.successfulBucketIds,
                    changedBucketIds = changedSuccessfulBucketIds,
                    bucketGeometrySummaries = precomputeResult.bucketGeometrySummaries,
                    groupSize = snapshot.groupSize,
                    columnCount = normalized.mosaicColumnCount,
                    families = normalized.mosaicFamilies,
                    geometryRequest = geometryRequest
                )
                log.d {
                    "Blocking Timeline Mosaic prepare completed buckets=${precomputeResult.successfulBucketIds.size}"
                }
                Result.success(Unit)
            }
        } finally {
            endManualRefresh()
        }
    }

    fun setAvailableWidth(widthDp: Float) {
        val current = _uiConfig.value
        setTimelineLayoutMetrics(
            widthDp = widthDp,
            viewportHeightDp = current.availableHeight,
            mosaicColumnCount = current.mosaicColumnCount
        )
    }

    fun setMosaicColumnCount(columnCount: Int) {
        val current = _uiConfig.value
        setTimelineLayoutMetrics(
            widthDp = current.availableWidth,
            viewportHeightDp = current.availableHeight,
            mosaicColumnCount = columnCount
        )
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        val current = _uiConfig.value
        setTimelineLayoutMetrics(
            widthDp = current.availableWidth,
            viewportHeightDp = heightDp,
            mosaicColumnCount = current.mosaicColumnCount
        )
    }

    fun setTimelineLayoutMetrics(
        widthDp: Float,
        viewportHeightDp: Float,
        mosaicColumnCount: Int
    ) {
        val current = _uiConfig.value
        val widthChanged = widthDp != current.availableWidth
        val heightChanged = viewportHeightDp != current.availableHeight
        val columnChanged = mosaicColumnCount != current.mosaicColumnCount
        if (!widthChanged && !heightChanged && !columnChanged) return
        val mark = TimeSource.Monotonic.markNow()
        val bounds = rowHeightBoundsForViewport(viewportHeightDp)
        val targetRowHeight = targetRowHeightForConfig(widthDp, bounds)
        val targetChanged = targetRowHeight != current.targetRowHeight || bounds != current.rowHeightBounds
        _uiConfig.update {
            it.copy(
                availableWidth = widthDp,
                availableHeight = viewportHeightDp,
                rowHeightBounds = bounds,
                targetRowHeight = targetRowHeight,
                mosaicColumnCount = mosaicColumnCount
            )
        }
        val mosaicEnabled = current.viewConfig.mosaicEnabled
        val invalidatesMosaic = mosaicEnabled && (widthChanged || heightChanged || columnChanged || targetChanged)
        if (invalidatesMosaic) {
            clearProgressiveMosaicState()
        }
        if (mosaicEnabled && (widthChanged || columnChanged)) {
            requestVisibleTimelineMosaicCacheRead()
        }
        if (mosaicEnabled && (widthChanged || heightChanged || columnChanged || targetChanged)) {
            requestPersistedBucketGeometryForCachedBuckets()
        }
        log.d {
            "Timeline layout metrics updated widthChanged=$widthChanged heightChanged=$heightChanged " +
                "columnChanged=$columnChanged mosaicInvalidated=$invalidatesMosaic elapsed=${mark.elapsedNow()}"
        }
    }

    fun setTargetRowHeight(height: Float) {
        val current = _uiConfig.value
        if (current.viewConfig.mosaicEnabled && current.viewConfig.disableZoomWhenMosaicEnabled) return
        val clamped = current.rowHeightBounds.clamp(height)
        if (clamped == current.targetRowHeight) return
        val previousLayoutSpec = mosaicLayoutSpecFor(current.availableWidth, current.targetRowHeight)
        val previousColumnCount = previousLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT
        val previousUseMosaic = shouldUseMosaicLayout(previousLayoutSpec, current.viewConfig)
        val nextLayoutSpec = mosaicLayoutSpecFor(current.availableWidth, clamped)
        val nextColumnCount = nextLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT
        val nextUseMosaic = shouldUseMosaicLayout(nextLayoutSpec, current.viewConfig)
        hasSavedTargetRowHeight = true
        savedTargetRowHeight = clamped
        rowHeightPersistenceRunner.launch(debounce = true) {
            setTargetRowHeightAction(RowHeightScope.TIMELINE, clamped)
        }
        if (nextColumnCount != lastLoggedZoomColumnCount) {
            lastLoggedZoomColumnCount = nextColumnCount
            log.d {
                "Timeline zoom columnCount=$nextColumnCount targetRowHeight=$clamped " +
                    "availableWidth=${current.availableWidth} effectiveMosaicEnabled=$nextUseMosaic"
            }
        }
        _uiConfig.update { it.copy(targetRowHeight = clamped) }
        if (previousColumnCount != nextColumnCount || previousUseMosaic != nextUseMosaic) {
            requestVisibleTimelineMosaicCacheRead()
        }
    }

    private fun targetRowHeightForConfig(availableWidth: Float, bounds: RowHeightBounds): Float {
        val preferred = if (hasSavedTargetRowHeight) {
            savedTargetRowHeight
        } else {
            defaultTargetRowHeightForWidth(availableWidth)
        }
        return bounds.clamp(preferred)
    }

    fun loadBucketAssets(timeBucket: String, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            enqueueVisibleBucketRefreshes(
                timeBuckets = listOf(timeBucket),
                force = force,
                source = TimelineServerRefreshSource.ExplicitBucketLoad
            )
        }
    }

    fun onVisibleBucketsChanged(
        bucketIndexes: List<Int>,
        reason: TimelineBucketTargetReason = TimelineBucketTargetReason.VisibleScroll
    ) {
        val snapshot = state.value
        val validBucketIndexes = bucketIndexes
            .filter { it in snapshot.buckets.indices }
            .distinct()
        if (validBucketIndexes.isEmpty()) return
        if (validBucketIndexes == lastVisibleBucketIndexes && reason == TimelineBucketTargetReason.VisibleScroll) {
            return
        }
        lastVisibleBucketIndexes = validBucketIndexes
        visibleBucketIndexes = validBucketIndexes
        log.d { "Timeline visible buckets changed reason=$reason buckets=$validBucketIndexes" }
        loadBucketIndexes(
            bucketIndexes = bucketIndexesForVisiblePriority(validBucketIndexes),
            priority = TimelineRefreshQueuePriority.Visible,
            source = TimelineServerRefreshSource.Visible
        )
        requestVisibleTimelineMosaicCacheRead()
        scheduleProgressiveMosaicFlush(immediate = true)
        updateMosaicAnchor(validBucketIndexes.first(), reason)
    }

    fun onViewportBucketTargeted(
        bucketIndex: Int,
        reason: TimelineBucketTargetReason = TimelineBucketTargetReason.ScrollbarStop
    ) {
        val snapshot = state.value
        if (bucketIndex !in snapshot.buckets.indices) return
        lastTargetBucketIndex = bucketIndex
        log.d { "Timeline target bucket changed reason=$reason bucket=$bucketIndex" }
        val priority = when (reason) {
            TimelineBucketTargetReason.ScrollbarDrag -> TimelineRefreshQueuePriority.ScrollbarDrag
            TimelineBucketTargetReason.ScrollbarStop -> TimelineRefreshQueuePriority.ScrollbarStop
            TimelineBucketTargetReason.VisibleScroll,
            TimelineBucketTargetReason.ScrollSettled -> TimelineRefreshQueuePriority.Visible
        }
        loadBucketIndexes(
            bucketIndexes = bucketIndexesNearPriority(bucketIndex),
            priority = priority,
            source = TimelineServerRefreshSource.Target
        )
        requestVisibleTimelineMosaicCacheRead()
        scheduleProgressiveMosaicFlush(immediate = true)
        updateMosaicAnchor(bucketIndex, reason)
    }

    fun onScrollInProgressChanged(inProgress: Boolean) {
        if (inProgress == isTimelineScrollActive.value) return
        isTimelineScrollActive.value = inProgress
        if (inProgress) {
            timelineMosaicQueue.send(TimelineMosaicQueueCommand.PauseForScroll)
            log.d { "Timeline Mosaic work paused for active scroll" }
        } else {
            publishDeferredScrollMosaicUpdates()
            drainDeferredScrollMaterialization()
            val priorityBuckets = orderedVisibleLoadedTimelineBuckets()
            timelineMosaicQueue.send(TimelineMosaicQueueCommand.ResumeAfterScroll(priorityBuckets))
            log.d { "Timeline Mosaic work resumed after scroll priorityBuckets=${priorityBuckets.size}" }
            requestPersistedBucketGeometryForCachedBuckets()
            scheduleProgressiveMosaicFlush(immediate = true)
        }
    }

    private fun drainDeferredScrollMaterialization() {
        if (deferredScrollMaterializedBuckets.isEmpty()) return
        val pending = deferredScrollMaterializedBuckets.toSet()
        deferredScrollMaterializedBuckets.clear()
        val published = publishMaterializedCachedBuckets(pending)
        if (published.isNotEmpty() && _uiConfig.value.viewConfig.mosaicEnabled) {
            requestTimelineMosaicCacheReadForBuckets(
                timeBuckets = published,
                source = TimelineMosaicWorkSource.SyncPrecompute
            )
        }
    }

    fun scrollTargetForFraction(fraction: Float): TimelineScrollTarget? {
        val snapshot = state.value
        return timelineScrollTargetForFraction(snapshot.pageIndex, snapshot.displayIndex, fraction)
    }

    private fun updateMosaicAnchor(bucketIndex: Int, reason: TimelineBucketTargetReason) {
        // Intentional no-op. mosaicAnchorBucketIndex is no longer read by any
        // scheduling or rendering decision. Earlier this function called
        // _uiConfig.update which fired the debounced combine pipeline on every
        // visibility change during scroll — a state-rebuild + recomposition
        // for no behavioral effect. Kept as a function (not deleted at the
        // call sites) so the existing onVisibleBucketsChanged / on*Targeted
        // callers don't need to change.
    }

    private fun advanceTimelineMosaicRuntimeGeneration(reason: String) {
        mosaicRuntimeGeneration.update { it + 1L }
        log.d { "Timeline Mosaic runtime generation advanced reason=$reason generation=${mosaicRuntimeGeneration.value}" }
    }

    private fun advanceTimelineMosaicBucketGeneration(timeBucket: String, reason: String) {
        mosaicBucketGenerations.update { generations ->
            generations + (timeBucket to ((generations[timeBucket] ?: 0L) + 1L))
        }
        log.d {
            "Timeline Mosaic bucket generation advanced bucket=$timeBucket reason=$reason " +
                "generation=${mosaicBucketGenerations.value[timeBucket]}"
        }
    }

    private fun mosaicPublishStampFor(keys: Set<MosaicCacheKey>): TimelineMosaicPublishStamp =
        TimelineMosaicPublishStamp(
            globalGeneration = mosaicRuntimeGeneration.value,
            bucketGenerations = keys
                .map { it.timeBucket }
                .distinct()
                .associateWith { timeBucket -> mosaicBucketGenerations.value[timeBucket] ?: 0L }
        )

    private fun clearProgressiveMosaicState() {
        advanceTimelineMosaicRuntimeGeneration("progressive-clear")
        progressiveMosaicFlushJob?.cancel()
        progressiveMosaicFlushJob = null
        timelineMosaicQueue.send(TimelineMosaicQueueCommand.ClearAll)
        viewModelScope.launch(Dispatchers.Default) {
            cancelAllTimelineMosaicWorkers()
        }
        viewModelScope.launch(Dispatchers.Default) {
            progressiveMosaicBuffer.clearAll()
        }
        runtimeMosaicResumeStates.clear()
        deferredScrollMosaicStateUpdates.clear()
        deferredScrollMosaicGeometryUpdates.clear()
        deferredScrollActiveMosaicConfig = null
        _mosaicStates.update { states ->
            states.filterValues { it !is RuntimeMosaicState.Partial }
        }
    }

    private suspend fun clearAllTimelineMosaicRuntimeState() {
        advanceTimelineMosaicRuntimeGeneration("runtime-clear-all")
        progressiveMosaicFlushJob?.cancel()
        progressiveMosaicFlushJob = null
        timelineMosaicQueue.send(TimelineMosaicQueueCommand.ClearAll)
        cancelAllTimelineMosaicWorkers()
        bucketGeometryCacheJob?.cancel()
        bucketGeometryCacheJob = null
        progressiveMosaicBuffer.clearAll()
        runtimeMosaicResumeStates.clear()
        deferredScrollMosaicStateUpdates.clear()
        deferredScrollMosaicGeometryUpdates.clear()
        deferredScrollActiveMosaicConfig = null
        _mosaicStates.value = emptyMap()
        _mosaicGeometryStates.value = emptyMap()
        _bucketGeometryStates.value = emptyMap()
        _bucketData.update { data -> data.copy(geometryReadyBuckets = emptySet()) }
        ++bucketGeometryCacheGeneration
        // Force the next mosaic queue request to wait for fresh geometry so
        // post-clear placeholders are not built from stale or empty heights.
        timelineGeometryReady.value = false
    }

    private fun loadBucketIndexes(
        bucketIndexes: List<Int>,
        priority: TimelineRefreshQueuePriority = TimelineRefreshQueuePriority.Normal,
        source: TimelineServerRefreshSource = TimelineServerRefreshSource.Visible
    ) {
        val buckets = state.value.buckets
        val timeBuckets = bucketIndexes
            .filter { it in buckets.indices }
            .distinct()
            .map { index -> buckets[index].timeBucket }
        viewModelScope.launch(Dispatchers.IO) {
            enqueueVisibleBucketRefreshes(
                timeBuckets = timeBuckets,
                force = false,
                priority = priority,
                source = source
            )
        }
    }

    private fun bucketIndexesNear(bucketIndex: Int): List<Int> {
        val bucketCount = state.value.buckets.size
        return ((bucketIndex - PREFETCH_BUCKET_RADIUS)..(bucketIndex + PREFETCH_BUCKET_RADIUS))
            .filter { it in 0 until bucketCount }
    }

    private fun bucketIndexesNearPriority(bucketIndex: Int): List<Int> =
        buildList {
            add(bucketIndex)
            for (distance in 1..PREFETCH_BUCKET_RADIUS) {
                add(bucketIndex - distance)
                add(bucketIndex + distance)
            }
        }
            .filter { it in 0 until state.value.buckets.size }
            .distinct()

    private fun bucketIndexesForVisiblePriority(bucketIndexes: List<Int>): List<Int> =
        buildList {
            bucketIndexes.forEach { index ->
                if (index !in this) add(index)
            }
            bucketIndexes
                .flatMap(::bucketIndexesNear)
                .forEach { index ->
                    if (index !in this) add(index)
                }
        }

    fun retryBucket(timeBucket: String) {
        viewModelScope.launch(Dispatchers.IO) {
            log.d { "Retrying bucket: $timeBucket" }
            _bucketData.update { it.copy(failedBuckets = it.failedBuckets - timeBucket) }
            loadBucketAssetsIfNeeded(
                timeBucket = timeBucket,
                force = true,
                keepCachedRowsOnFailure = false,
                source = TimelineServerRefreshSource.Retry
            )
        }
    }

    /**
     * Resolve a scroll-back target for the grid after returning from the pager.
     * Prefers the exact asset if it's materialized in displayItems; otherwise
     * falls back to the first displayItem (placeholder or row) of the asset's
     * bucket, so the user lands in the right region even if the bucket hasn't
     * rebuilt yet.
     */
    fun getDisplayItemIndexForReturn(assetId: String?, bucketTimeBucket: String?): Int? {
        val s = state.value
        if (assetId != null) {
            val direct = s.displayItems.indexOfFirst { item ->
                when (item) {
                    is PhotoItem -> item.asset.id == assetId
                    is RowItem -> item.photos.any { it.asset.id == assetId }
                    is MosaicBandItem -> item.tiles.any { it.photo.asset.id == assetId }
                    else -> false
                }
            }
            if (direct >= 0) return direct
        }
        if (bucketTimeBucket != null) {
            val bucketIdx = s.buckets.indexOfFirst { it.timeBucket == bucketTimeBucket }
            if (bucketIdx >= 0) {
                val fallback = s.displayItems.indexOfFirst { it.bucketIndex == bucketIdx }
                if (fallback >= 0) return fallback
            }
        }
        return null
    }

    suspend fun getGlobalPhotoIndex(assetId: String): Int? {
        val s = state.value
        s.pageIndex.loadedAssetPageIndexes[assetId]?.let { return it }
        // Fast path: in-memory cache. The clicked photo's bucket must already
        // be loaded (it's visible on screen), so this hits immediately and
        // avoids a Room round-trip that delayed the shared-element transition.
        val cached = withContext(Dispatchers.Default) {
            var globalIndex = 0
            for ((bucketIndex, bucket) in s.buckets.withIndex()) {
                val assets = bucketAssetsCache[bucket.timeBucket]
                if (assets != null) {
                    val localIndex = assets.indexOfFirst { it.id == assetId }
                    if (localIndex >= 0) return@withContext globalIndex + localIndex
                }
                globalIndex += s.pageIndex.bucketPageCounts.getOrElse(bucketIndex) { bucket.count }
            }
            null
        }
        if (cached != null) return cached
        // Fallback: query Room for any buckets not in the cache (e.g. user
        // deep-scrolled and we evicted old buckets).
        var globalIndex = 0
        for ((bucketIndex, bucket) in s.buckets.withIndex()) {
            val assets = withContext(Dispatchers.IO) {
                getBucketAssetsUseCase(bucket.timeBucket)
            }
            val localIndex = assets.indexOfFirst { it.id == assetId }
            if (localIndex >= 0) return globalIndex + localIndex
            globalIndex += s.pageIndex.bucketPageCounts.getOrElse(bucketIndex) { bucket.count }
        }
        return null
    }

    // --- Private ---

    private fun syncFromServer(isFullRefresh: Boolean = false) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val hasCachedBuckets = getTimelineBucketsUseCase.hasCachedBuckets()
            val hasCompletedColdSync = getTimelineBucketsUseCase.hasCompletedColdTimelineSync()
            val startupMode = timelineStartupMode(
                hasCachedBuckets = hasCachedBuckets,
                hasCompletedColdSync = hasCompletedColdSync,
                isFullRefresh = isFullRefresh
            )
            val isColdSync = startupMode == TimelineStartupMode.Cold
            val isCachedManualRefresh = startupMode == TimelineStartupMode.ManualRefresh
            val syncMode = when (startupMode) {
                TimelineStartupMode.Cold -> "cold"
                TimelineStartupMode.CachedLaunch -> "cached-launch"
                TimelineStartupMode.ManualRefresh -> "manual-refresh"
            }

            val hadBannerError = _uiConfig.value.bannerError != null
            log.d {
                "Timeline sync started mode=$syncMode hasCachedBuckets=$hasCachedBuckets " +
                    "hasCompletedColdSync=$hasCompletedColdSync isFullRefresh=$isFullRefresh"
            }

            if (isColdSync) {
                _isBuilding.value = true
                _buildError.value = null
                log.d { "Timeline cold sync waiting for full-screen metrics before server sync" }
                val metrics = _uiConfig
                    .filter { it.availableWidth > 0f }
                    .first()
                log.d {
                    "Timeline cold sync metrics ready width=${metrics.availableWidth} " +
                        "height=${metrics.availableHeight} targetRowHeight=${metrics.targetRowHeight} " +
                        "mosaicColumns=${metrics.mosaicColumnCount}"
                }
            } else {
                _uiConfig.update { it.copy(isSyncing = true, bannerError = null, bannerSuccess = null) }
            }

            val lastSync = getTimelineBucketsUseCase.getLastSyncedAt()
            _uiConfig.update { it.copy(lastSyncedAt = lastSync) }
            log.d { "Timeline sync lastSyncedAt=$lastSync mode=$syncMode" }

            if (startupMode == TimelineStartupMode.CachedLaunch &&
                !allowTimelineServerRefresh(TimelineServerRefreshSource.CachedLaunch)
            ) {
                _uiConfig.update {
                    it.copy(
                        isSyncing = false,
                        bannerSuccess = if (hadBannerError) ConnectionUiMessage.ConnectedToServer else null
                    )
                }
                log.d { "Timeline cached launch server sync skipped by cache-only warm launch experiment" }
                return@launch
            }

            if (isCachedManualRefresh) {
                log.d { "Timeline manual refresh waiting for visible refresh queue to pause" }
                beginManualRefresh()
                if (!_uiConfig.value.viewConfig.cacheMosaicResults) {
                    clearAllMosaicCachesForCacheOffManualSync()
                }
            }
            try {
                log.d { "Timeline bucket metadata sync started mode=$syncMode" }
                getTimelineBucketsUseCase.sync().fold(
                    onSuccess = { syncResult ->
                        val buckets = syncResult.buckets
                        log.d {
                            "Timeline bucket metadata sync completed mode=$syncMode buckets=${buckets.size} " +
                                "stale=${syncResult.staleBucketIds.size} removed=${syncResult.removedBucketIds.size}"
                        }
                        rememberBucketMetadataChanges(syncResult.staleBucketIds, syncResult.removedBucketIds)
                        invalidateRemovedBuckets(syncResult.removedBucketIds)
                        clearTimelineMosaicCacheAction.buckets(syncResult.removedBucketIds)

                        if (isColdSync) {
                            _bucketData.update { it.copy(buckets = buckets) }
                            log.d { "Timeline cold asset sync started buckets=${buckets.size}" }
                            val coldConfig = _uiConfig.value
                            val shouldPrepareMosaic = coldConfig.viewConfig.mosaicEnabled &&
                                coldConfig.viewConfig.cacheMosaicResults
                            val geometryRequest = if (shouldPrepareMosaic) {
                                timelineMosaicGeometryRequest(coldConfig, coldConfig.mosaicColumnCount)
                            } else {
                                null
                            }
                            if (shouldPrepareMosaic && geometryRequest == null) {
                                log.e { "Timeline cold sync cannot start without Mosaic geometry request" }
                                _isBuilding.value = false
                                _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                _uiConfig.update { it.copy(isSyncing = false) }
                            } else {
                                syncAllTimelineAssetsWithRetry(buckets, context = "cold-sync").fold(
                                    onSuccess = coldAssetSuccess@ { assetResult ->
                                        log.d {
                                            "Timeline cold asset sync completed successful=${assetResult.successfulBucketIds.size} " +
                                                "failed=${assetResult.failedBucketIds.size} changed=${assetResult.changedBucketIds.size}"
                                        }
                                        if (assetResult.failedBucketIds.isNotEmpty()) {
                                            _isBuilding.value = false
                                            _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                            _uiConfig.update { it.copy(isSyncing = false) }
                                            log.d {
                                                "Timeline cold sync finished with failed buckets=${assetResult.failedBucketIds.size} " +
                                                    "after retry"
                                            }
                                        } else {
                                            clearTimelineMosaicCacheAction.buckets(assetResult.changedBucketIds intersect assetResult.successfulBucketIds)
                                            if (!shouldPrepareMosaic) {
                                                publishColdSyncAssetsOnly(assetResult.successfulBucketIds)
                                                try {
                                                    getTimelineBucketsUseCase.markColdTimelineSyncComplete()
                                                    assetResult.successfulBucketIds.forEach { markBucketServerRefreshed(it) }
                                                    _isBuilding.value = false
                                                    _buildError.value = null
                                                    _uiConfig.update { it.copy(isSyncing = false) }
                                                    requestVisibleTimelineMosaicCacheRead()
                                                    log.d {
                                                        "Timeline cold sync finished without Mosaic cache prepare buckets=${buckets.size}"
                                                    }
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    log.e(e) { "Failed to mark Timeline cold sync complete" }
                                                    _isBuilding.value = false
                                                    _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                                    _uiConfig.update { it.copy(isSyncing = false) }
                                                }
                                                return@coldAssetSuccess
                                            }
                                            val requiredGeometryRequest = geometryRequest ?: run {
                                                log.e { "Timeline cold sync lost Mosaic geometry request" }
                                                _isBuilding.value = false
                                                _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                                _uiConfig.update { it.copy(isSyncing = false) }
                                                return@coldAssetSuccess
                                            }
                                            prepareTimelineMosaicCacheAction(
                                                timeBuckets = assetResult.successfulBucketIds,
                                                groupSize = coldConfig.groupSize,
                                                columnCount = coldConfig.mosaicColumnCount,
                                                families = coldConfig.viewConfig.mosaicFamilies,
                                                geometryRequest = requiredGeometryRequest,
                                                cacheDisplayResults = coldConfig.viewConfig.cacheMosaicResults
                                            ).fold(
                                                onSuccess = { mosaicResult ->
                                                    val geometryReadyIds = mosaicResult.bucketGeometrySummaries.map { it.timeBucket }.toSet()
                                                    val missingGeometryCount = mosaicResult.successfulBucketIds.count { it !in geometryReadyIds }
                                                    if (mosaicResult.failedBucketIds.isNotEmpty() || missingGeometryCount > 0) {
                                                        _isBuilding.value = false
                                                        _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                                        _uiConfig.update { it.copy(isSyncing = false) }
                                                        log.d {
                                                            "Timeline cold sync finished with mosaicFailed=${mosaicResult.failedBucketIds.size} " +
                                                                "missingGeometry=$missingGeometryCount"
                                                        }
                                                        return@fold
                                                    }
                                                    publishColdSyncResult(
                                                        successfulBucketIds = mosaicResult.successfulBucketIds,
                                                        bucketGeometrySummaries = mosaicResult.bucketGeometrySummaries,
                                                        groupSize = coldConfig.groupSize,
                                                        columnCount = coldConfig.mosaicColumnCount,
                                                        families = coldConfig.viewConfig.mosaicFamilies,
                                                        geometryRequest = requiredGeometryRequest
                                                    )
                                                    try {
                                                        getTimelineBucketsUseCase.markColdTimelineSyncComplete()
                                                        assetResult.successfulBucketIds.forEach { markBucketServerRefreshed(it) }
                                                        _isBuilding.value = false
                                                        _buildError.value = null
                                                        _uiConfig.update { it.copy(isSyncing = false) }
                                                        log.d { "Timeline cold sync finished successfully buckets=${buckets.size}" }
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        log.e(e) { "Failed to mark Timeline cold sync complete" }
                                                        _isBuilding.value = false
                                                        _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                                        _uiConfig.update { it.copy(isSyncing = false) }
                                                    }
                                                },
                                                onFailure = { e ->
                                                    log.e(e) { "Failed cold Timeline Mosaic prepare" }
                                                    _isBuilding.value = false
                                                    _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                                    _uiConfig.update { it.copy(isSyncing = false) }
                                                }
                                            )
                                        }
                                    },
                                    onFailure = { e ->
                                        log.e(e) { "Failed full first timeline asset sync" }
                                        _isBuilding.value = false
                                        _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                        _uiConfig.update { it.copy(isSyncing = false) }
                                        log.d { "Timeline cold sync finished with asset-sync failure" }
                                    }
                                )
                            }
                        } else if (isFullRefresh) {
                            log.d { "Timeline manual refresh asset sync starting buckets=${buckets.size}" }
                            syncCachedBucketsManually(buckets, hadBannerError)
                        } else {
                            // Cached launch only refreshes bucket metadata. Asset
                            // requests are deferred to the visible-bucket queue
                            // so launch sync does not compete with scrolling.
                            _uiConfig.update {
                                it.copy(
                                    isSyncing = false,
                                    bannerSuccess = if (hadBannerError) ConnectionUiMessage.ConnectedToServer else null
                                )
                            }
                            log.d { "Timeline cached launch metadata sync finished; enqueueing visible bucket refreshes" }
                            enqueueCurrentVisibleBucketRefreshes()
                        }
                    },
                    onFailure = { e ->
                        log.e(e) { "Failed to sync buckets from server" }
                        if (isColdSync) {
                            _isBuilding.value = false
                            _buildError.value = ConnectionUiMessage.NoConnectionToServer
                            log.d { "Timeline cold sync finished with bucket metadata failure" }
                        } else {
                            _uiConfig.update {
                                it.copy(
                                    isSyncing = false,
                                    bannerError = ConnectionUiMessage.CannotConnectToServer
                                )
                            }
                            log.d { "Timeline cached sync finished with bucket metadata failure mode=$syncMode" }
                        }
                    }
                )
            } finally {
                if (isCachedManualRefresh) {
                    endManualRefresh()
                    log.d { "Timeline manual refresh finished; resuming visible refresh queue" }
                    enqueueCurrentVisibleBucketRefreshes()
                }
            }
        }
    }

    private suspend fun beginManualRefresh() {
        refreshQueueMutex.withLock {
            manualRefreshActive = true
            pendingVisibleRefreshBuckets.clear()
            pendingScrollbarDragRefreshBuckets.clear()
        }
        // Manual refresh owns bucket asset sync. Let any already-running
        // visible refresh finish, then prevent the queue from starting the next
        // bucket until manual refresh ends.
        visibleRefreshJob?.join()
    }

    private suspend fun endManualRefresh() {
        refreshQueueMutex.withLock {
            manualRefreshActive = false
        }
    }

    private suspend fun clearAllMosaicCachesForCacheOffManualSync() {
        val mark = TimeSource.Monotonic.markNow()
        log.d { "Timeline cache-off manual sync clearing all Mosaic caches" }
        clearAllTimelineMosaicRuntimeState()
        clearTimelineMosaicCacheAction.all()
        clearDetailMosaicCacheAction.all()
        log.d { "Timeline cache-off manual sync cleared all Mosaic caches elapsed=${mark.elapsedNow()}" }
    }

    private suspend fun syncAllTimelineAssetsWithRetry(
        buckets: List<TimelineBucket>,
        context: String
    ): Result<TimelineAssetSyncResult> {
        val first = syncAllTimelineAssetsAction(buckets).getOrElse { return Result.failure(it) }
        if (first.failedBucketIds.isEmpty()) return Result.success(first)
        val retryBuckets = buckets.filter { it.timeBucket in first.failedBucketIds }
        log.d { "Timeline asset sync retry context=$context buckets=${retryBuckets.size}" }
        val retry = syncAllTimelineAssetsAction(retryBuckets).getOrElse { return Result.failure(it) }
        val retrySuccessful = retry.successfulBucketIds
        val finalSuccessful = first.successfulBucketIds + retrySuccessful
        val finalFailed = (first.failedBucketIds - retrySuccessful) + retry.failedBucketIds
        val finalChanged = first.changedBucketIds + retry.changedBucketIds
        return Result.success(
            TimelineAssetSyncResult(
                successfulBucketIds = finalSuccessful,
                failedBucketIds = finalFailed,
                changedBucketIds = finalChanged
            )
        )
    }

    private suspend fun rememberBucketMetadataChanges(
        staleBuckets: Set<String>,
        removedBuckets: Set<String>
    ) {
        refreshQueueMutex.withLock {
            staleBucketIds.removeAll(removedBuckets)
            staleBucketIds.addAll(staleBuckets)
            serverRefreshedBucketIds.removeAll(staleBuckets + removedBuckets)
            pendingVisibleRefreshBuckets.removeAll(removedBuckets)
            pendingScrollbarDragRefreshBuckets.removeAll(removedBuckets)
            materializingCachedBucketIds.removeAll(removedBuckets)
        }
        val invalidGeometryBuckets = staleBuckets + removedBuckets
        if (invalidGeometryBuckets.isNotEmpty()) {
            _bucketData.update { data ->
                data.copy(
                    geometryReadyBuckets = data.geometryReadyBuckets
                        .filter { it.timeBucket !in invalidGeometryBuckets }
                        .toSet()
                )
            }
            _bucketGeometryStates.update { states ->
                states.filterKeys { it.timeBucket !in invalidGeometryBuckets }
            }
        }
    }

    private suspend fun markBucketServerRefreshed(timeBucket: String) {
        refreshQueueMutex.withLock {
            serverRefreshedBucketIds.add(timeBucket)
            staleBucketIds.remove(timeBucket)
            pendingVisibleRefreshBuckets.remove(timeBucket)
            pendingScrollbarDragRefreshBuckets.remove(timeBucket)
        }
    }

    private suspend fun enqueueCurrentVisibleBucketRefreshes() {
        val buckets = state.value.buckets
        val timeBuckets = visibleBucketIndexes
            .flatMap(::bucketIndexesNear)
            .distinct()
            .mapNotNull { index -> buckets.getOrNull(index)?.timeBucket }
        enqueueVisibleBucketRefreshes(
            timeBuckets = timeBuckets,
            force = false,
            priority = TimelineRefreshQueuePriority.Visible,
            source = TimelineServerRefreshSource.Visible
        )
    }

    private suspend fun enqueueVisibleBucketRefreshes(
        timeBuckets: List<String>,
        force: Boolean,
        priority: TimelineRefreshQueuePriority = TimelineRefreshQueuePriority.Normal,
        source: TimelineServerRefreshSource
    ) {
        if (timeBuckets.isEmpty()) return
        val requestedBuckets = timeBuckets.distinct()
        materializeCachedBucketsIfAvailable(requestedBuckets)
        if (!allowTimelineServerRefresh(source)) {
            log.d {
                "Timeline server bucket refresh skipped by cache-only warm launch experiment " +
                    "source=$source buckets=${requestedBuckets.size}"
            }
            return
        }
        refreshQueueMutex.withLock {
            if (!manualRefreshActive) {
                val data = _bucketData.value
                val bucketsToQueue = requestedBuckets.filter { timeBucket ->
                    shouldQueueVisibleBucketRefresh(timeBucket, data, force)
                }
                if (bucketsToQueue.isNotEmpty()) {
                    reorderPendingVisibleRefreshBuckets(bucketsToQueue, priority)
                }
                val shouldStartQueue = pendingVisibleRefreshBuckets.isNotEmpty() &&
                    (visibleRefreshJob == null || visibleRefreshJob?.isCompleted == true)
                if (shouldStartQueue) {
                    visibleRefreshJob = viewModelScope.launch(Dispatchers.IO) {
                        drainVisibleRefreshQueue()
                    }
                }
            }
        }
    }

    private fun reorderPendingVisibleRefreshBuckets(
        bucketsToQueue: List<String>,
        priority: TimelineRefreshQueuePriority
    ) {
        val ordering = reorderTimelineRefreshQueue(
            pendingBuckets = pendingVisibleRefreshBuckets,
            pendingDragBuckets = pendingScrollbarDragRefreshBuckets,
            bucketsToQueue = bucketsToQueue,
            priority = priority
        )
        pendingVisibleRefreshBuckets.clear()
        pendingVisibleRefreshBuckets.addAll(ordering.pendingBuckets)
        pendingScrollbarDragRefreshBuckets.clear()
        pendingScrollbarDragRefreshBuckets.addAll(ordering.pendingDragBuckets)
    }

    private fun shouldQueueVisibleBucketRefresh(
        timeBucket: String,
        data: BucketData,
        force: Boolean
    ): Boolean {
        if (timeBucket in refreshingBucketIds) return false
        if (timeBucket in data.loadingBuckets) return false
        if (!force &&
            timeBucket in data.failedBuckets &&
            timeBucket !in data.loadedBuckets &&
            timeBucket !in data.cachedBuckets
        ) {
            return false
        }
        if (force) return true
        if (timeBucket in staleBucketIds) return true
        if (timeBucket !in data.loadedBuckets) return true
        return timeBucket !in serverRefreshedBucketIds
    }

    private suspend fun drainVisibleRefreshQueue() {
        while (true) {
            val nextBucket = refreshQueueMutex.withLock {
                if (manualRefreshActive) {
                    visibleRefreshJob = null
                    return
                }
                if (pendingVisibleRefreshBuckets.isEmpty()) {
                    visibleRefreshJob = null
                    return
                }
                pendingVisibleRefreshBuckets.removeAt(0)
            }
            loadBucketAssetsIfNeeded(
                timeBucket = nextBucket,
                force = true,
                keepCachedRowsOnFailure = true,
                source = TimelineServerRefreshSource.Visible
            )
        }
    }

    private suspend fun loadBucketAssetsIfNeeded(
        timeBucket: String,
        force: Boolean = false,
        keepCachedRowsOnFailure: Boolean = false,
        source: TimelineServerRefreshSource
    ): Boolean {
        val materializedCachedRows = materializeCachedBucketIfAvailable(timeBucket)
        if (!allowTimelineServerRefresh(source)) {
            log.d {
                "Timeline bucket server refresh skipped by cache-only warm launch experiment " +
                    "source=$source bucket=$timeBucket materializedCachedRows=$materializedCachedRows"
            }
            return materializedCachedRows
        }
        val showRenderLoading = timeBucket !in _bucketData.value.loadedBuckets
        if (!claimBucketRefresh(timeBucket, force, showRenderLoading)) return false
        return loadBucketAssetsInternal(
            timeBucket = timeBucket,
            keepCachedRowsOnFailure = keepCachedRowsOnFailure || materializedCachedRows,
            showRenderLoading = showRenderLoading
        )
    }

    private suspend fun materializeCachedBucketIfAvailable(timeBucket: String): Boolean {
        if (bucketAssetsCache[timeBucket] != null && timeBucket in _bucketData.value.loadedBuckets) return true
        return timeBucket in materializeCachedBucketsIfAvailable(listOf(timeBucket))
    }

    private suspend fun materializeCachedBucketsIfAvailable(timeBuckets: List<String>): Set<String> {
        if (timeBuckets.isEmpty()) return emptySet()
        // Materialization runs Room reads, writes bucketAssetsCache, and
        // publishes _bucketData updates. Each publish fires the combine flow
        // and triggers buildDisplayItems + LazyColumn re-measurement on Main —
        // the dominant cause of jank during fast list scrolling. Mosaic queue
        // work is already paused during active scroll; defer materialization
        // the same way. When the scroll settles, TimelineScreen's
        // ScrollSettled visibility callback re-routes through
        // onVisibleBucketsChanged → enqueueVisibleBucketRefreshes →
        // materializeCachedBucketsIfAvailable, which then does the work.
        // Unloaded buckets render exact aggregate-geometry placeholders during
        // scroll so the visible content height stays stable.
        if (isTimelineScrollActive.value) return emptySet()
        val mark = TimeSource.Monotonic.markNow()
        val requestedBuckets = timeBuckets.distinct()
        val chunks = timelineCachedMaterializationChunks(
            requestedBuckets = requestedBuckets,
            priorityBuckets = orderedVisibleOrTargetTimelineBuckets(),
            prefetchChunkSize = CACHED_BUCKET_MATERIALIZATION_CHUNK_SIZE
        )
        val materialized = mutableSetOf<String>()
        var roomReadCount = 0
        var publishChunkCount = 0
        // The first chunk is the visible/target priority chunk — publishing it
        // immediately keeps on-screen content responsive. Subsequent prefetch
        // chunks are offscreen, so we accumulate their loaded buckets and
        // publish them in one trailing _bucketData.update at the end of the
        // run. This collapses the warmup wave from N chunk publishes into 2,
        // dropping per-chunk combine emissions during the hot scroll window.
        val deferredPublishBuckets = linkedSetOf<String>()
        chunks.forEachIndexed { chunkIndex, chunk ->
            val claimed = claimCachedMaterializationBuckets(chunk)
            if (claimed.isEmpty()) {
                if (chunkIndex < chunks.lastIndex) yield()
                return@forEachIndexed
            }
            try {
                val loadedAssets = linkedMapOf<String, List<Asset>>()
                for (timeBucket in claimed) {
                    val cachedAssets = bucketAssetsCache[timeBucket]
                    val assets = if (cachedAssets != null) {
                        cachedAssets
                    } else {
                        roomReadCount++
                        try {
                            getBucketAssetsUseCase(timeBucket)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.e(e) { "Failed to materialize cached Timeline bucket=$timeBucket" }
                            emptyList()
                        }
                    }
                    if (assets.isNotEmpty()) {
                        loadedAssets[timeBucket] = assets
                    }
                }
                if (loadedAssets.isNotEmpty()) {
                    loadedAssets.forEach { (timeBucket, assets) ->
                        bucketAssetsCache[timeBucket] = assets
                    }
                    val loadedBuckets = loadedAssets.keys.toSet()
                    if (chunkIndex == 0) {
                        val published = publishMaterializedCachedBuckets(loadedBuckets)
                        if (published.isNotEmpty()) {
                            materialized.addAll(published)
                            publishChunkCount++
                            if (_uiConfig.value.viewConfig.mosaicEnabled) {
                                requestTimelineMosaicCacheReadForBuckets(published)
                                if (!isTimelineScrollActive.value) {
                                    loadPersistedActiveMosaicAssignmentsForBuckets(published)
                                }
                            }
                        }
                    } else {
                        deferredPublishBuckets.addAll(loadedBuckets)
                    }
                }
            } finally {
                releaseCachedMaterializationBuckets(claimed)
            }
            if (chunkIndex < chunks.lastIndex) yield()
        }
        if (deferredPublishBuckets.isNotEmpty()) {
            val published = publishMaterializedCachedBuckets(deferredPublishBuckets)
            if (published.isNotEmpty()) {
                materialized.addAll(published)
                publishChunkCount++
                if (_uiConfig.value.viewConfig.mosaicEnabled) {
                    requestTimelineMosaicCacheReadForBuckets(published)
                    if (!isTimelineScrollActive.value) {
                        loadPersistedActiveMosaicAssignmentsForBuckets(published)
                    }
                }
            }
        }
        if (materialized.isNotEmpty() || roomReadCount > 0) {
            log.d {
                "Timeline cached materialization completed requested=${requestedBuckets.size} " +
                    "materialized=${materialized.size} roomReads=$roomReadCount " +
                    "publishChunks=$publishChunkCount elapsed=${mark.elapsedNow()}"
            }
        }
        return materialized
    }

    private suspend fun claimCachedMaterializationBuckets(timeBuckets: List<String>): List<String> =
        refreshQueueMutex.withLock {
            val data = _bucketData.value
            timelineCachedMaterializationClaims(
                requestedBuckets = timeBuckets,
                cachedBuckets = data.cachedBuckets,
                loadedBuckets = data.loadedBuckets,
                materializingBuckets = materializingCachedBucketIds,
                inMemoryBuckets = bucketAssetsCache.keys
            ).also { claimed ->
                materializingCachedBucketIds.addAll(claimed)
            }
        }

    private suspend fun releaseCachedMaterializationBuckets(timeBuckets: Collection<String>) {
        refreshQueueMutex.withLock {
            materializingCachedBucketIds.removeAll(timeBuckets.toSet())
        }
    }

    private fun publishMaterializedCachedBuckets(timeBuckets: Set<String>): Set<String> {
        if (timeBuckets.isEmpty()) return emptySet()
        // If a materialization run finishes while the user is actively
        // scrolling, the _bucketData.update would otherwise fire combine
        // mid-gesture and cause LazyColumn re-measurement during scroll. Defer
        // the publish to onScrollInProgressChanged(false) where it drains
        // alongside mosaic publishes — same pattern.
        if (isTimelineScrollActive.value) {
            deferredScrollMaterializedBuckets.addAll(timeBuckets)
            return emptySet()
        }
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets + timeBuckets,
                loadedBuckets = data.loadedBuckets + timeBuckets,
                failedBuckets = data.failedBuckets - timeBuckets,
                materializationRevisions = data.materializationRevisions.incrementRevisions(timeBuckets)
            )
        }
        return timeBuckets
    }

    private suspend fun claimBucketRefresh(
        timeBucket: String,
        force: Boolean = false,
        showRenderLoading: Boolean
    ): Boolean {
        val claimed = refreshQueueMutex.withLock {
            val data = _bucketData.value
            if (timeBucket in refreshingBucketIds ||
                (!force && showRenderLoading && (
                    timeBucket in data.loadedBuckets ||
                        timeBucket in data.loadingBuckets ||
                        timeBucket in data.failedBuckets
                    ))
            ) {
                false
            } else {
                refreshingBucketIds.add(timeBucket)
                true
            }
        }
        if (claimed && showRenderLoading) {
            _bucketData.update { data ->
                data.copy(
                    loadingBuckets = data.loadingBuckets + timeBucket,
                    failedBuckets = data.failedBuckets - timeBucket
                )
            }
        }
        return claimed
    }

    private suspend fun releaseBucketRefresh(timeBucket: String) {
        refreshQueueMutex.withLock {
            refreshingBucketIds.remove(timeBucket)
        }
    }

    private suspend fun loadBucketAssetsInternal(
        timeBucket: String,
        keepCachedRowsOnFailure: Boolean = false,
        showRenderLoading: Boolean = true
    ): Boolean {
        val hadCachedRows = timeBucket in _bucketData.value.loadedBuckets
        val hadAssetCache = bucketAssetsCache[timeBucket] != null
        log.d { "Syncing assets for bucket: $timeBucket" }
        try {
            return loadBucketAssetsAction(timeBucket).fold(
                onSuccess = { syncResult ->
                    log.d { "Synced assets for bucket: $timeBucket" }
                    // Sync success and content change are intentionally
                    // separate. Cached background refreshes should not move the
                    // render-facing state unless ordered persisted assets
                    // changed, but a newly visible cached bucket still needs to
                    // be marked materialized once its Room rows are available.
                    var publishedLoadResult = false
                    var shouldPublishLoadResult = false
                    var shouldRequestMosaicRead = false
                    val failedMosaicBuckets = when {
                        syncResult.changed -> {
                            invalidateRuntimeMosaicAssignments(timeBucket)
                            clearTimelineMosaicCacheAction.buckets(setOf(timeBucket))
                            val assets = getBucketAssetsUseCase(timeBucket)
                            bucketAssetsCache[timeBucket] = assets
                            publishLoadedBucketResult(timeBucket, changed = true)
                            publishedLoadResult = true
                            shouldRequestMosaicRead = true
                            val failedBuckets = precomputeTimelineMosaicForBuckets(setOf(timeBucket))
                            val failedActiveBuckets = precomputeActiveTimelineMosaicForBuckets(setOf(timeBucket))
                            markActiveTimelineMosaicFailedForBuckets(failedActiveBuckets)
                            failedBuckets
                        }
                        bucketAssetsCache[timeBucket] == null -> {
                            bucketAssetsCache[timeBucket] = getBucketAssetsUseCase(timeBucket)
                            shouldPublishLoadResult = true
                            shouldRequestMosaicRead = true
                            emptySet()
                        }
                        hadCachedRows && hadAssetCache -> {
                            clearBucketLoadingState(timeBucket)
                            log.d { "Timeline bucket unchanged; render state preserved bucket=$timeBucket" }
                            emptySet()
                        }
                        else -> {
                            shouldPublishLoadResult = true
                            shouldRequestMosaicRead = true
                            emptySet()
                        }
                    }
                    if (!publishedLoadResult && shouldPublishLoadResult) {
                        publishLoadedBucketResult(timeBucket, syncResult.changed)
                    }
                    if (shouldRequestMosaicRead && _uiConfig.value.viewConfig.mosaicEnabled) {
                        requestTimelineMosaicCacheReadForBuckets(
                            timeBuckets = setOf(timeBucket),
                            source = TimelineMosaicWorkSource.SyncPrecompute
                        )
                        if (!isTimelineScrollActive.value) {
                            loadPersistedActiveMosaicAssignmentsForBuckets(setOf(timeBucket))
                        }
                    }
                    markTimelineMosaicFailedForBuckets(failedMosaicBuckets)
                    markBucketServerRefreshed(timeBucket)
                    true
                },
                onFailure = { e ->
                    log.e(e) { "Failed to sync assets for bucket: $timeBucket" }
                    publishBucketLoadFailure(
                        timeBucket = timeBucket,
                        keepCachedRows = keepCachedRowsOnFailure && hadCachedRows,
                        showRenderLoading = showRenderLoading
                    )
                    false
                }
            )
        } finally {
            releaseBucketRefresh(timeBucket)
        }
    }

    private fun clearBucketLoadingState(timeBucket: String) {
        _bucketData.update { data ->
            if (timeBucket in data.loadingBuckets) {
                data.copy(loadingBuckets = data.loadingBuckets - timeBucket)
            } else {
                data
            }
        }
    }

    private fun publishLoadedBucketResult(timeBucket: String, changed: Boolean) {
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets + timeBucket,
                loadingBuckets = data.loadingBuckets - timeBucket,
                loadedBuckets = data.loadedBuckets + timeBucket,
                assetRevisions = if (changed) {
                    data.assetRevisions.incrementRevision(timeBucket)
                } else {
                    data.assetRevisions
                }
            )
        }
    }

    private fun publishBucketLoadFailure(
        timeBucket: String,
        keepCachedRows: Boolean,
        showRenderLoading: Boolean
    ) {
        if (!showRenderLoading) return
        _bucketData.update { data ->
            data.copy(
                loadingBuckets = data.loadingBuckets - timeBucket,
                failedBuckets = if (keepCachedRows) {
                    data.failedBuckets - timeBucket
                } else {
                    data.failedBuckets + timeBucket
                }
            )
        }
    }

    private fun invalidateRemovedBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        timeBuckets.forEach { timeBucket ->
            bucketAssetsCache.remove(timeBucket)
            invalidateRuntimeMosaicAssignments(timeBucket)
        }
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets - timeBuckets,
                geometryReadyBuckets = data.geometryReadyBuckets
                    .filter { it.timeBucket !in timeBuckets }
                    .toSet(),
                loadedBuckets = data.loadedBuckets - timeBuckets,
                loadingBuckets = data.loadingBuckets - timeBuckets,
                failedBuckets = data.failedBuckets - timeBuckets,
                materializationRevisions = data.materializationRevisions.filterKeys { it !in timeBuckets },
                // Bucket-count changes alter placeholder height even before
                // assets are loaded, so structural bucket invalidation also
                // bumps the per-bucket content revision.
                assetRevisions = data.assetRevisions.incrementRevisions(timeBuckets)
            )
        }
        requestVisibleTimelineMosaicCacheRead()
    }

    private fun buildTimelineState(
        data: BucketData,
        config: UiConfig,
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>,
        mosaicGeometryStates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry>,
        bucketGeometryStates: Map<MosaicCacheKey, Float>
    ): TimelineState {
        val mark = TimeSource.Monotonic.markNow()
        val mosaicLayoutSpec = timelineMosaicLayoutSpec(config)
        val displayItems = buildDisplayItems(
            data,
            config.groupSize,
            config.availableWidth,
            config.targetRowHeight,
            config.rowHeightBounds.max,
            mosaicLayoutSpec,
            config.viewConfig,
            mosaicStates,
            mosaicGeometryStates,
            bucketGeometryStates
        )
        val pageIndex = pageIndexForState(data)
        val scrollbarData = scrollbarDataForState(data.buckets, pageIndex)
        val displayIndex = displayIndexForItems(displayItems)
        val elapsed = mark.elapsedNow()
        if (elapsed.inWholeMilliseconds >= TIMELINE_REBUILD_LOG_THRESHOLD_MS) {
            log.d {
                "Timeline state rebuilt buckets=${data.buckets.size} loaded=${data.loadedBuckets.size} " +
                    "items=${displayItems.size} rowMode=${!config.viewConfig.mosaicEnabled} elapsed=$elapsed"
            }
        }

        return TimelineState(
            groupSize = config.groupSize,
            targetRowHeight = config.targetRowHeight,
            rowHeightBounds = config.rowHeightBounds,
            isLoading = config.isLoading,
            error = config.error,
            displayItems = displayItems,
            displayIndex = displayIndex,
            yearMarkers = scrollbarData.yearMarkers,
            totalItemCount = scrollbarData.totalItemCount,
            cumulativeItemCounts = scrollbarData.cumulativeItemCounts,
            bucketDisplayLabels = scrollbarData.bucketDisplayLabels,
            pageIndex = pageIndex,
            buckets = data.buckets,
            viewConfig = config.viewConfig,
            bannerError = config.bannerError,
            bannerSuccess = config.bannerSuccess,
            lastSyncedAt = config.lastSyncedAt,
            isSyncing = config.isSyncing
        )
    }

    private fun pageIndexForState(data: BucketData): TimelinePageIndex {
        val key = TimelinePageIndexCacheKey(
            bucketKeys = data.buckets.map { bucket ->
                val assets = bucketAssetsCache[bucket.timeBucket]
                TimelinePageBucketKey(
                    timeBucket = bucket.timeBucket,
                    count = assets?.size ?: bucket.count,
                    assetRevision = data.assetRevisionFor(bucket.timeBucket),
                    materializationRevision = data.materializationRevisionFor(bucket.timeBucket),
                    loaded = assets != null
                )
            }
        )
        if (key == cachedPageIndexKey) return cachedPageIndex
        return computePageIndex(data.buckets).also { pageIndex ->
            cachedPageIndexKey = key
            cachedPageIndex = pageIndex
        }
    }

    private fun computePageIndex(buckets: List<TimelineBucket>): TimelinePageIndex {
        val starts = ArrayList<Int>(buckets.size)
        val counts = ArrayList<Int>(buckets.size)
        val assetIndexes = mutableMapOf<String, Int>()
        var start = 0
        for (bucket in buckets) {
            starts.add(start)
            val assets = bucketAssetsCache[bucket.timeBucket]
            val count = assets?.size ?: bucket.count
            counts.add(count)
            assets?.forEachIndexed { index, asset ->
                assetIndexes[asset.id] = start + index
            }
            start += count
        }
        return TimelinePageIndex(
            bucketStartPages = starts,
            bucketPageCounts = counts,
            totalPages = start,
            loadedAssetPageIndexes = assetIndexes
        )
    }

    private fun scrollbarDataForState(
        buckets: List<TimelineBucket>,
        pageIndex: TimelinePageIndex
    ): ScrollbarData {
        val key = TimelineScrollbarDataCacheKey(
            bucketLabels = buckets.map { it.displayLabel },
            pageIndex = pageIndex
        )
        if (key == cachedScrollbarDataKey) return cachedScrollbarData
        return computeScrollbarData(buckets, pageIndex).also { scrollbarData ->
            cachedScrollbarDataKey = key
            cachedScrollbarData = scrollbarData
        }
    }

    private fun displayIndexForItems(displayItems: List<TimelineDisplayItem>): TimelineDisplayIndex {
        if (displayItems === cachedDisplayIndexItems) return cachedDisplayIndex
        return buildTimelineDisplayIndex(displayItems).also { displayIndex ->
            cachedDisplayIndexItems = displayItems
            cachedDisplayIndex = displayIndex
        }
    }

    /**
     * Runs exclusively on Dispatchers.Default (via flowOn in the combine pipeline).
     * All cache reads/writes are confined to this single thread context.
     */
    private fun buildDisplayItems(
        data: BucketData,
        groupSize: TimelineGroupSize,
        availableWidth: Float,
        targetRowHeight: Float,
        maxRowHeight: Float,
        mosaicLayoutSpec: MosaicLayoutSpec?,
        viewConfig: ViewConfig,
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>,
        mosaicGeometryStates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry>,
        bucketGeometryStates: Map<MosaicCacheKey, Float>
    ): List<TimelineDisplayItem> {
        val mosaicColumnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT
        val bucketOrder = data.buckets.map { it.timeBucket }
        // Layout change invalidates only the DERIVED display items (which are
        // sized against width/rowHeight/groupSize). The raw bucket assets in
        // bucketAssetsCache are layout-independent and must survive pinch-to-
        // zoom without forcing a Room re-query for every bucket.
        //
        // Bucket order is also part of the derived cache identity because every
        // display item stores bucketIndex. Reusing old items after insert/remove
        // or reorder would leave click, scroll, and return targeting pointing at
        // the wrong bucket even when the bucket's own assets did not change.
        if (groupSize != cachedGroupSize ||
            availableWidth != cachedAvailableWidth ||
            targetRowHeight != cachedTargetRowHeight ||
            maxRowHeight != cachedMaxRowHeight ||
            mosaicColumnCount != cachedMosaicColumnCount ||
            viewConfig != cachedViewConfig ||
            bucketOrder != cachedBucketOrder
        ) {
            cachedBucketItems = emptyMap()
            cachedFlatItems = emptyList()
            readyMosaicMemoByKey.clear()
            cachedGroupSize = groupSize
            cachedAvailableWidth = availableWidth
            cachedTargetRowHeight = targetRowHeight
            cachedMaxRowHeight = maxRowHeight
            cachedMosaicColumnCount = mosaicColumnCount
            cachedViewConfig = viewConfig
            cachedBucketOrder = bucketOrder
        }

        val bucketGeometryRequest = timelineMosaicGeometryRequest(
            availableWidth = availableWidth,
            maxRowHeight = maxRowHeight,
            columnCount = mosaicColumnCount
        )
        var anyChanged = false
        val newCache = mutableMapOf<String, List<TimelineDisplayItem>>()

        for ((index, bucket) in data.buckets.withIndex()) {
            val timeBucket = bucket.timeBucket
            val useMosaic = shouldUseMosaicLayout(mosaicLayoutSpec, viewConfig)
            val assetRevision = data.assetRevisionFor(timeBucket)
            val bucketGeometryKey = bucketGeometryRequest?.let {
                bucketGeometryCacheKey(
                    timeBucket = timeBucket,
                    groupSize = groupSize,
                    columnCount = mosaicColumnCount,
                    assetRevision = assetRevision,
                    families = viewConfig.mosaicFamilies,
                    geometryRequest = it
                )
            }
            val isRenderReady = timeBucket in data.loadedBuckets && bucketAssetsCache[timeBucket] != null
            val materializationRevision = data.materializationRevisionFor(timeBucket)
            val stateKey = when {
                timeBucket in data.failedBuckets -> "failed"
                isRenderReady -> "loaded"
                useMosaic && bucketGeometryKey != null && bucketGeometryKey in data.geometryReadyBuckets -> "geometry_ready"
                else -> "placeholder"
            }
            val layoutKey = mosaicLayoutKeyForBucket(
                timeBucket = timeBucket,
                isLoaded = isRenderReady,
                isFailed = timeBucket in data.failedBuckets,
                groupSize = groupSize,
                mosaicColumnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                viewConfig = viewConfig,
                useMosaic = useMosaic,
                mosaicStates = mosaicStates
            )
            val geometryKey = mosaicGeometryLayoutKeyForBucket(
                timeBucket = timeBucket,
                isLoaded = isRenderReady,
                groupSize = groupSize,
                mosaicColumnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                viewConfig = viewConfig,
                useMosaic = useMosaic,
                mosaicGeometryStates = mosaicGeometryStates,
                bucketGeometryStates = bucketGeometryStates,
                bucketGeometryRequest = bucketGeometryRequest
            )
            val cacheKey = "${timeBucket}_${stateKey}_${assetRevision}_${materializationRevision}_${layoutKey}_$geometryKey"

            val cached = cachedBucketItems[cacheKey]
            if (cached != null) {
                newCache[cacheKey] = cached
            } else {
                anyChanged = true
                val bucketItems = buildBucketItems(
                    data,
                    index,
                    bucket,
                    groupSize,
                    availableWidth,
                    targetRowHeight,
                    maxRowHeight,
                    mosaicLayoutSpec,
                    viewConfig,
                    useMosaic,
                    mosaicStates,
                    mosaicGeometryStates,
                    bucketGeometryStates,
                    bucketGeometryRequest
                )
                newCache[cacheKey] = bucketItems
            }
        }

        if (!anyChanged && cachedFlatItems.isNotEmpty() &&
            newCache.size == cachedBucketItems.size
        ) {
            cachedBucketItems = newCache
            return cachedFlatItems
        }

        val totalSize = newCache.values.sumOf { it.size }
        val items = ArrayList<TimelineDisplayItem>(totalSize)
        for ((index, bucket) in data.buckets.withIndex()) {
            val timeBucket = bucket.timeBucket
            val useMosaic = shouldUseMosaicLayout(mosaicLayoutSpec, viewConfig)
            val assetRevision = data.assetRevisionFor(timeBucket)
            val bucketGeometryKey = bucketGeometryRequest?.let {
                bucketGeometryCacheKey(
                    timeBucket = timeBucket,
                    groupSize = groupSize,
                    columnCount = mosaicColumnCount,
                    assetRevision = assetRevision,
                    families = viewConfig.mosaicFamilies,
                    geometryRequest = it
                )
            }
            val isRenderReady = timeBucket in data.loadedBuckets && bucketAssetsCache[timeBucket] != null
            val materializationRevision = data.materializationRevisionFor(timeBucket)
            val stateKey = when {
                timeBucket in data.failedBuckets -> "failed"
                isRenderReady -> "loaded"
                useMosaic && bucketGeometryKey != null && bucketGeometryKey in data.geometryReadyBuckets -> "geometry_ready"
                else -> "placeholder"
            }
            val layoutKey = mosaicLayoutKeyForBucket(
                timeBucket = timeBucket,
                isLoaded = isRenderReady,
                isFailed = timeBucket in data.failedBuckets,
                groupSize = groupSize,
                mosaicColumnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                viewConfig = viewConfig,
                useMosaic = useMosaic,
                mosaicStates = mosaicStates
            )
            val geometryKey = mosaicGeometryLayoutKeyForBucket(
                timeBucket = timeBucket,
                isLoaded = isRenderReady,
                groupSize = groupSize,
                mosaicColumnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                viewConfig = viewConfig,
                useMosaic = useMosaic,
                mosaicGeometryStates = mosaicGeometryStates,
                bucketGeometryStates = bucketGeometryStates,
                bucketGeometryRequest = bucketGeometryRequest
            )
            val cacheKey = "${timeBucket}_${stateKey}_${assetRevision}_${materializationRevision}_${layoutKey}_$geometryKey"
            newCache[cacheKey]?.let { items.addAll(it) }
        }

        cachedBucketItems = newCache
        cachedFlatItems = items
        return items
    }

    private fun mosaicLayoutKeyForBucket(
        timeBucket: String,
        isLoaded: Boolean,
        isFailed: Boolean,
        groupSize: TimelineGroupSize,
        mosaicColumnCount: Int,
        assetRevision: Int,
        viewConfig: ViewConfig,
        useMosaic: Boolean,
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>
    ): String {
        if (!useMosaic || !isLoaded || isFailed) return "rows"
        val familiesKey = mosaicFamiliesKey(viewConfig.mosaicFamilies)
        if (groupSize != TimelineGroupSize.DAY) {
            val key = MosaicCacheKey(
                timeBucket = timeBucket,
                sectionKey = monthMosaicSectionKey(timeBucket),
                columnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                familiesKey = familiesKey
            )
            return "mosaic_${mosaicStates[key].cacheToken()}"
        }
        val assets = bucketAssetsCache[timeBucket] ?: return "mosaic_days_unloaded"
        val dayGroups = dayGroupsForBucket(timeBucket, assetRevision, assets)
        if (dayGroups.isEmpty()) return "mosaic_empty_days"
        return dayGroups.joinToString(
            separator = ";",
            prefix = "mosaic_days_"
        ) { dayGroup ->
            val key = MosaicCacheKey(
                timeBucket = timeBucket,
                sectionKey = dayMosaicSectionKey(timeBucket, dayGroup.label),
                columnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                familiesKey = familiesKey
            )
            "${dayGroup.label}:${mosaicStates[key].cacheToken()}"
        }
    }

    private fun bucketGeometryCacheKey(
        timeBucket: String,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        assetRevision: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ): MosaicCacheKey =
        mosaicCacheKey(
            timeBucket = timeBucket,
            sectionKey = timelineBucketGeometrySectionKey(timeBucket, groupSize, geometryRequest),
            columnCount = columnCount,
            assetRevision = assetRevision,
            families = families
        )

    private fun mosaicGeometryLayoutKeyForBucket(
        timeBucket: String,
        isLoaded: Boolean,
        groupSize: TimelineGroupSize,
        mosaicColumnCount: Int,
        assetRevision: Int,
        viewConfig: ViewConfig,
        useMosaic: Boolean,
        mosaicGeometryStates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry>,
        bucketGeometryStates: Map<MosaicCacheKey, Float>,
        bucketGeometryRequest: TimelineMosaicGeometryRequest?
    ): String {
        if (!useMosaic) return "geometry_off"
        val familiesKey = mosaicFamiliesKey(viewConfig.mosaicFamilies)
        if (!isLoaded) {
            val request = bucketGeometryRequest ?: return "bucket_geometry_missing_request"
            val key = bucketGeometryCacheKey(
                timeBucket = timeBucket,
                groupSize = groupSize,
                columnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                families = viewConfig.mosaicFamilies,
                geometryRequest = request
            )
            return "bucket_geometry_${bucketGeometryStates[key] ?: "missing"}"
        }
        if (groupSize != TimelineGroupSize.DAY) {
            val key = MosaicCacheKey(
                timeBucket = timeBucket,
                sectionKey = monthMosaicSectionKey(timeBucket),
                columnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                familiesKey = familiesKey
            )
            return "geometry_${mosaicGeometryStates[key]?.let { "${it.placeholderHeight}|${it.ranges.size}" } ?: "missing"}"
        }
        val assets = bucketAssetsCache[timeBucket] ?: return "geometry_days_unloaded"
        val dayGroups = dayGroupsForBucket(timeBucket, assetRevision, assets)
        return dayGroups.joinToString(
            separator = ";",
            prefix = "geometry_days_"
        ) { dayGroup ->
            val key = MosaicCacheKey(
                timeBucket = timeBucket,
                sectionKey = dayMosaicSectionKey(timeBucket, dayGroup.label),
                columnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                familiesKey = familiesKey
            )
            "${dayGroup.label}:${mosaicGeometryStates[key]?.let { "${it.placeholderHeight}|${it.ranges.size}" } ?: "missing"}"
        }
    }

    private fun buildBucketItems(
        data: BucketData,
        index: Int,
        bucket: TimelineBucket,
        groupSize: TimelineGroupSize,
        availableWidth: Float,
        targetRowHeight: Float,
        maxRowHeight: Float,
        mosaicLayoutSpec: MosaicLayoutSpec?,
        viewConfig: ViewConfig,
        useMosaic: Boolean,
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>,
        mosaicGeometryStates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry>,
        bucketGeometryStates: Map<MosaicCacheKey, Float>,
        bucketGeometryRequest: TimelineMosaicGeometryRequest?
    ): List<TimelineDisplayItem> {
        val timeBucket = bucket.timeBucket
        val isFailed = timeBucket in data.failedBuckets
        val isLoaded = timeBucket in data.loadedBuckets && bucketAssetsCache[timeBucket] != null
        val monthLabel = bucket.displayLabel
        val items = mutableListOf<TimelineDisplayItem>()

        items.add(HeaderItem(
            gridKey = "h_$timeBucket",
            bucketIndex = index,
            sectionLabel = monthLabel,
            label = monthLabel
        ))

        when {
            isFailed -> {
                items.add(ErrorItem(
                    gridKey = "err_$timeBucket",
                    bucketIndex = index,
                    sectionLabel = monthLabel,
                    timeBucket = timeBucket
                ))
            }
            !isLoaded -> {
                val cachedAssets = bucketAssetsCache[timeBucket]
                val bucketGeometryKey = bucketGeometryRequest?.let {
                    bucketGeometryCacheKey(
                        timeBucket = timeBucket,
                        groupSize = groupSize,
                        columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                        assetRevision = data.assetRevisionFor(timeBucket),
                        families = viewConfig.mosaicFamilies,
                        geometryRequest = it
                    )
                }
                val bucketGeometryHeight = bucketGeometryKey?.let { bucketGeometryStates[it] }
                if (useMosaic && bucketGeometryHeight != null) {
                    addGeometryPlaceholderItems(
                        items = items,
                        timeBucket = timeBucket,
                        sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                        bucketIndex = index,
                        sectionLabel = monthLabel,
                        placeholderHeight = bucketGeometryHeight
                    )
                } else if (useMosaic && bucketGeometryKey != null && bucketGeometryKey in data.geometryReadyBuckets) {
                    // Exact geometry is known to be empty for this bucket.
                } else if (useMosaic && mosaicLayoutSpec != null && !cachedAssets.isNullOrEmpty()) {
                    addProjectedMosaicPlaceholderItems(
                        items = items,
                        timeBucket = timeBucket,
                        sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                        bucketIndex = index,
                        sectionLabel = monthLabel,
                        assets = cachedAssets,
                        assignments = null,
                        mosaicLayoutSpec = mosaicLayoutSpec,
                        maxRowHeight = maxRowHeight
                    )
                } else {
                    addPlaceholderItems(
                        items = items,
                        timeBucket = timeBucket,
                        sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                        bucketIndex = index,
                        sectionLabel = monthLabel,
                        assetCount = bucket.count,
                        groupSize = groupSize,
                        availableWidth = availableWidth,
                        targetRowHeight = if (useMosaic && mosaicLayoutSpec != null) {
                            mosaicLayoutSpec.cellHeight
                        } else {
                            targetRowHeight
                        }
                    )
                }
            }
            else -> {
                // Projection is deliberately cache-only. If cached Room refs
                // exist but the visible-bucket materializer has not published
                // assets into memory yet, this branch is not entered and the
                // bucket renders placeholders instead of blocking on Room.
                val assets = bucketAssetsCache[timeBucket].orEmpty()
                if (assets.isNotEmpty()) {
                    val assetRevision = data.assetRevisionFor(timeBucket)
                    val dayGroupList = if (groupSize == TimelineGroupSize.DAY) {
                        dayGroupsForBucket(timeBucket, assetRevision, assets)
                    } else null
                    val aggregateGeometryHeight = bucketGeometryRequest?.let {
                        bucketGeometryStates[
                            bucketGeometryCacheKey(
                                timeBucket = timeBucket,
                                groupSize = groupSize,
                                columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                                assetRevision = assetRevision,
                                families = viewConfig.mosaicFamilies,
                                geometryRequest = it
                            )
                        ]
                    }

                    if (!dayGroupList.isNullOrEmpty()) {
                        val daySectionStates = dayGroupList.map { dayGroup ->
                            mosaicStates[
                                mosaicCacheKey(
                                    timeBucket = timeBucket,
                                    sectionKey = dayMosaicSectionKey(timeBucket, dayGroup.label),
                                    columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                                    assetRevision = assetRevision,
                                    families = viewConfig.mosaicFamilies
                                )
                            ]
                        }
                        val aggregatePlaceholderHeight = timelineAggregateGeometryPlaceholderHeight(
                            aggregateGeometryHeight = aggregateGeometryHeight,
                            sectionStates = daySectionStates
                        )
                        if (useMosaic && mosaicLayoutSpec != null && aggregatePlaceholderHeight != null) {
                            addGeometryPlaceholderItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                                bucketIndex = index,
                                sectionLabel = monthLabel,
                                placeholderHeight = aggregatePlaceholderHeight
                            )
                            return items
                        }
                        for (dayGroup in dayGroupList) {
                            val dayLabel = dayGroup.label
                            val daySectionKey = dayMosaicSectionKey(timeBucket, dayLabel)
                            items.add(HeaderItem(
                                gridKey = "dh_${timeBucket}_$daySectionKey",
                                bucketIndex = index,
                                sectionLabel = dayLabel,
                                label = dayLabel
                            ))
                            val dayMosaicKey = mosaicCacheKey(
                                timeBucket = timeBucket,
                                sectionKey = daySectionKey,
                                columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                                assetRevision = assetRevision,
                                families = viewConfig.mosaicFamilies
                            )
                            val dayMosaicState = mosaicStates[dayMosaicKey]
                            when {
                                useMosaic &&
                                    mosaicLayoutSpec != null &&
                                    (dayMosaicState == null || dayMosaicState == RuntimeMosaicState.Pending) -> {
                                    addStrictMosaicPlaceholderItems(
                                        items = items,
                                        timeBucket = timeBucket,
                                        sectionKey = daySectionKey,
                                        bucketIndex = index,
                                        sectionLabel = dayLabel,
                                        assets = dayGroup.assets,
                                        mosaicLayoutSpec = mosaicLayoutSpec,
                                        sectionGeometry = mosaicGeometryStates[dayMosaicKey]
                                    )
                                }
                                useMosaic && dayMosaicState is RuntimeMosaicState.Partial && mosaicLayoutSpec != null -> {
                                    addPartialMosaicItems(
                                        items = items,
                                        timeBucket = timeBucket,
                                        sectionKey = daySectionKey,
                                        bucketIndex = index,
                                        sectionLabel = dayLabel,
                                        assets = dayGroup.assets,
                                        partial = dayMosaicState,
                                        mosaicLayoutSpec = mosaicLayoutSpec,
                                        maxRowHeight = maxRowHeight,
                                        sectionGeometry = mosaicGeometryStates[dayMosaicKey]
                                    )
                                }
                                useMosaic && dayMosaicState is RuntimeMosaicState.Ready && mosaicLayoutSpec != null -> {
                                    items.addAll(
                                        remapTimelineMosaicBandKeys(
                                            items = readyMosaicDisplayItems(
                                                cacheKey = dayMosaicKey,
                                                assets = dayGroup.assets,
                                                state = dayMosaicState,
                                                bucketIndex = index,
                                                sectionLabel = dayLabel,
                                                mosaicLayoutSpec = mosaicLayoutSpec,
                                                maxRowHeight = maxRowHeight,
                                                families = viewConfig.mosaicFamilies
                                            ),
                                            timeBucket = timeBucket,
                                            sectionKey = daySectionKey
                                        )
                                    )
                                }
                                useMosaic && dayMosaicState == RuntimeMosaicState.Failed && mosaicLayoutSpec != null -> {
                                    addStrictMosaicPlaceholderItems(
                                        items = items,
                                        timeBucket = timeBucket,
                                        sectionKey = daySectionKey,
                                        bucketIndex = index,
                                        sectionLabel = dayLabel,
                                        assets = dayGroup.assets,
                                        mosaicLayoutSpec = mosaicLayoutSpec,
                                        sectionGeometry = mosaicGeometryStates[dayMosaicKey]
                                    )
                                }
                                useMosaic -> {
                                    addPlaceholderItems(
                                        items = items,
                                        timeBucket = timeBucket,
                                        sectionKey = daySectionKey,
                                        bucketIndex = index,
                                        sectionLabel = dayLabel,
                                        assetCount = dayGroup.assets.size,
                                        groupSize = TimelineGroupSize.MONTH,
                                        availableWidth = availableWidth,
                                        targetRowHeight = targetRowHeight
                                    )
                                }
                                else -> {
                                    items.addAll(packIntoRows(
                                        dayGroup.assets, index, dayLabel,
                                        availableWidth,
                                        if (useMosaic && mosaicLayoutSpec != null) {
                                            mosaicFallbackRowHeight(
                                                layoutSpec = mosaicLayoutSpec,
                                                assetCount = dayGroup.assets.size,
                                                maxRowHeight = maxRowHeight
                                            )
                                        } else {
                                            targetRowHeight
                                        },
                                        GRID_SPACING_DP,
                                        maxRowHeight,
                                        promoteWideImages = if (viewConfig.mosaicEnabled) {
                                            MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
                                        } else {
                                            true
                                        },
                                        minCompleteRowPhotos = if (viewConfig.mosaicEnabled) {
                                            MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                                        } else {
                                            1
                                        }
                                    ))
                                }
                            }
                        }
                    } else {
                        val monthSectionKey = monthMosaicSectionKey(timeBucket)
                        val monthMosaicKey = mosaicCacheKey(
                            timeBucket = timeBucket,
                            sectionKey = monthSectionKey,
                            columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                            assetRevision = assetRevision,
                            families = viewConfig.mosaicFamilies
                        )
                        val monthMosaicState = mosaicStates[monthMosaicKey]
                        val aggregatePlaceholderHeight = timelineAggregateGeometryPlaceholderHeight(
                            aggregateGeometryHeight = aggregateGeometryHeight,
                            sectionStates = listOf(monthMosaicState)
                        )
                        if (useMosaic && mosaicLayoutSpec != null && aggregatePlaceholderHeight != null) {
                            addGeometryPlaceholderItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                                bucketIndex = index,
                                sectionLabel = monthLabel,
                                placeholderHeight = aggregatePlaceholderHeight
                            )
                            return items
                        }
                        if (useMosaic &&
                            mosaicLayoutSpec != null &&
                            (monthMosaicState == null || monthMosaicState == RuntimeMosaicState.Pending)
                        ) {
                            addStrictMosaicPlaceholderItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = monthSectionKey,
                                bucketIndex = index,
                                sectionLabel = monthLabel,
                                assets = assets,
                                mosaicLayoutSpec = mosaicLayoutSpec,
                                sectionGeometry = mosaicGeometryStates[monthMosaicKey]
                            )
                            return items
                        }
                        if (useMosaic && monthMosaicState is RuntimeMosaicState.Partial && mosaicLayoutSpec != null) {
                            addPartialMosaicItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = monthSectionKey,
                                bucketIndex = index,
                                sectionLabel = monthLabel,
                                assets = assets,
                                partial = monthMosaicState,
                                mosaicLayoutSpec = mosaicLayoutSpec,
                                maxRowHeight = maxRowHeight,
                                sectionGeometry = mosaicGeometryStates[monthMosaicKey]
                            )
                            return items
                        }
                        if (useMosaic && monthMosaicState is RuntimeMosaicState.Ready && mosaicLayoutSpec != null) {
                            items.addAll(
                                remapTimelineMosaicBandKeys(
                                    items = readyMosaicDisplayItems(
                                        cacheKey = monthMosaicKey,
                                        assets = assets,
                                        state = monthMosaicState,
                                        bucketIndex = index,
                                        sectionLabel = monthLabel,
                                        mosaicLayoutSpec = mosaicLayoutSpec,
                                        maxRowHeight = maxRowHeight,
                                        families = viewConfig.mosaicFamilies
                                    ),
                                    timeBucket = timeBucket,
                                    sectionKey = monthSectionKey
                                )
                            )
                        } else if (useMosaic && monthMosaicState == RuntimeMosaicState.Failed && mosaicLayoutSpec != null) {
                            addStrictMosaicPlaceholderItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = monthSectionKey,
                                bucketIndex = index,
                                sectionLabel = monthLabel,
                                assets = assets,
                                mosaicLayoutSpec = mosaicLayoutSpec,
                                sectionGeometry = mosaicGeometryStates[monthMosaicKey]
                            )
                        } else if (useMosaic) {
                            addPlaceholderItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = monthSectionKey,
                                bucketIndex = index,
                                sectionLabel = monthLabel,
                                assetCount = assets.size,
                                groupSize = TimelineGroupSize.MONTH,
                                availableWidth = availableWidth,
                                targetRowHeight = targetRowHeight
                            )
                        } else {
                            items.addAll(packIntoRows(
                                assets, index, monthLabel,
                                availableWidth,
                                targetRowHeight,
                                GRID_SPACING_DP,
                                maxRowHeight,
                                promoteWideImages = true,
                                minCompleteRowPhotos = 1
                            ))
                        }
                    }
                } else {
                    // Loaded but empty — show header only (no placeholder trap)
                }
            }
        }
        return items
    }

    private fun addPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        assetCount: Int,
        groupSize: TimelineGroupSize,
        availableWidth: Float,
        targetRowHeight: Float
    ) {
        val estimatedDayHeaders = if (groupSize == TimelineGroupSize.DAY) {
            (assetCount / 3).coerceAtLeast(1)
        } else {
            0
        }
        items.addAll(
            remapTimelinePlaceholderKeys(
                placeholders = buildPhotoGridPlaceholderItems(
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                assetCount = assetCount,
                availableWidth = availableWidth,
                targetRowHeight = targetRowHeight,
                estimatedHeaderCount = estimatedDayHeaders
                ),
                prefix = "pl",
                timeBucket = timeBucket,
                sectionKey = sectionKey
            )
        )
    }

    private fun addProjectedMosaicPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>?,
        mosaicLayoutSpec: MosaicLayoutSpec?,
        maxRowHeight: Float
    ) {
        if (assets.isNotEmpty() && mosaicLayoutSpec != null) {
            val projectedItems = if (assignments != null) {
                mosaicRenderEngine.projectSection(
                    assets = assets,
                    assignments = assignments,
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    layoutSpec = mosaicLayoutSpec,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = maxRowHeight
                )
            } else {
                mosaicRenderEngine.projectSection(
                    assets = assets,
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    assignments = emptyList(),
                    layoutSpec = mosaicLayoutSpec,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = maxRowHeight
                )
            }
            val projectedHeight = estimatePhotoGridDisplayItemsHeight(projectedItems, GRID_SPACING_DP)
            if (projectedHeight > 0f) {
                items.addAll(
                    remapTimelinePlaceholderKeys(
                        placeholders = buildPhotoGridPlaceholderItemsForHeight(
                        bucketIndex = bucketIndex,
                        sectionLabel = sectionLabel,
                        estimatedHeight = projectedHeight
                        ),
                        prefix = "pl",
                        timeBucket = timeBucket,
                        sectionKey = sectionKey
                    )
                )
                return
            }
        }
        addPlaceholderItems(
            items = items,
            timeBucket = timeBucket,
            sectionKey = sectionKey,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            assetCount = assets.size,
            groupSize = TimelineGroupSize.MONTH,
            availableWidth = mosaicLayoutSpec?.availableWidth ?: 0f,
            targetRowHeight = mosaicLayoutSpec?.cellHeight ?: DEFAULT_TARGET_ROW_HEIGHT
        )
    }

    private fun readyMosaicDisplayItems(
        cacheKey: MosaicCacheKey,
        assets: List<Asset>,
        state: RuntimeMosaicState.Ready,
        bucketIndex: Int,
        sectionLabel: String,
        mosaicLayoutSpec: MosaicLayoutSpec,
        maxRowHeight: Float,
        families: Set<MosaicTemplateFamily>
    ): List<TimelineDisplayItem> {
        val memo = readyMosaicMemoByKey[cacheKey]
        if (memo != null && memo.recordsRef === state.displayRecords && memo.assetsRef === assets) {
            return memo.items
        }
        val cachedItems = state.displayRecords.toPhotoGridDisplayItems(
            assets = assets,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel
        )
        val covers = state.displayRecords.displayRecordsCoverOrderedAssets(assets)
        val resolved = if (covers && cachedItems.isNotEmpty()) {
            cachedItems
        } else {
            projectReadyMosaicItems(
                assets = assets,
                assignments = state.assignments,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                mosaicLayoutSpec = mosaicLayoutSpec,
                maxRowHeight = maxRowHeight,
                families = families
            )
        }
        readyMosaicMemoByKey[cacheKey] = TimelineMosaicReadyMemo(
            recordsRef = state.displayRecords,
            assetsRef = assets,
            items = resolved,
            covers = covers
        )
        return resolved
    }

    private fun projectReadyMosaicItems(
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>,
        bucketIndex: Int,
        sectionLabel: String,
        mosaicLayoutSpec: MosaicLayoutSpec,
        maxRowHeight: Float,
        families: Set<MosaicTemplateFamily>
    ): List<TimelineDisplayItem> {
        if (assets.isEmpty()) return emptyList()
        return mosaicRenderEngine.projectReadySection(
            assets = assets,
            assignments = assignments,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            layoutSpec = mosaicLayoutSpec,
            spacing = GRID_SPACING_DP,
            maxRowHeight = maxRowHeight
        )
    }

    private fun addPartialMosaicItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        assets: List<Asset>,
        partial: RuntimeMosaicState.Partial,
        mosaicLayoutSpec: MosaicLayoutSpec,
        maxRowHeight: Float,
        sectionGeometry: TimelineMosaicSectionGeometry?
    ) {
        val geometry = sectionGeometry
        if (geometry == null) {
            addPlaceholderItems(
                items = items,
                timeBucket = timeBucket,
                sectionKey = sectionKey,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                assetCount = assets.size,
                groupSize = TimelineGroupSize.MONTH,
                availableWidth = mosaicLayoutSpec.availableWidth,
                targetRowHeight = mosaicLayoutSpec.cellHeight
            )
            return
        }
        val exactPartialItems = geometry
            .takeIf { it.ranges.isNotEmpty() }
            ?.let {
                mosaicRenderEngine.projectPartialSectionWithGeometry(
                    assets = assets,
                    chunks = partial.chunks,
                    geometryRanges = it.ranges,
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    layoutSpec = mosaicLayoutSpec,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = maxRowHeight
                )
            }
        if (exactPartialItems == null) {
            addGeometryPlaceholderItems(
                items = items,
                timeBucket = timeBucket,
                sectionKey = sectionKey,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                placeholderHeight = geometry.placeholderHeight
            )
            return
        }
        items.addAll(
            remapTimelineMosaicBandKeys(
                items = exactPartialItems,
                timeBucket = timeBucket,
                sectionKey = sectionKey
            )
        )
    }

    private fun addStrictMosaicPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        assets: List<Asset>,
        mosaicLayoutSpec: MosaicLayoutSpec,
        sectionGeometry: TimelineMosaicSectionGeometry?
    ) {
        // We render a single placeholder per section sized to the aggregate
        // placeholderHeight (sum of band heights + internal spacing). The
        // disk row also carries per-band heights, which we previously used to
        // emit one placeholder per band — that pushed LazyColumn item count up
        // ~10-15x per section and made fast scrolling janky on warm regions.
        // The aggregate height already matches the eventual sum of bands, so
        // the section's total height is preserved across the placeholder→bands
        // transition; the only loss is item-level identity continuity, which
        // LazyColumn handles cleanly when total height doesn't shift.
        val height = sectionGeometry?.placeholderHeight
        if (height != null && height > 0f) {
            addGeometryPlaceholderItems(
                items = items,
                timeBucket = timeBucket,
                sectionKey = sectionKey,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                placeholderHeight = height
            )
            return
        }
        addPlaceholderItems(
            items = items,
            timeBucket = timeBucket,
            sectionKey = sectionKey,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            assetCount = assets.size,
            groupSize = TimelineGroupSize.MONTH,
            availableWidth = mosaicLayoutSpec.availableWidth,
            targetRowHeight = mosaicLayoutSpec.cellHeight
        )
    }

    private fun addGeometryPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        placeholderHeight: Float
    ) {
        items.addAll(
            remapTimelinePlaceholderKeys(
                placeholders = buildPhotoGridPlaceholderItemsForHeight(
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                estimatedHeight = placeholderHeight,
                externalSpacing = GRID_SPACING_DP
                ),
                prefix = "pl",
                timeBucket = timeBucket,
                sectionKey = sectionKey
            )
        )
    }

    private fun remapTimelinePlaceholderKeys(
        placeholders: List<PlaceholderItem>,
        prefix: String,
        timeBucket: String,
        sectionKey: String
    ): List<PlaceholderItem> =
        placeholders.mapIndexed { chunkIndex, item ->
            item.copy(gridKey = timelinePlaceholderGridKey(prefix, timeBucket, sectionKey, chunkIndex))
        }

    private fun remapTimelineMosaicBandKeys(
        items: List<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String
    ): List<TimelineDisplayItem> =
        items.mapIndexed { index, item ->
            when (item) {
                is MosaicBandItem -> {
                    val tileAssetIds = item.tiles.map { tile -> tile.photo.asset.id }
                    item.copy(gridKey = timelineMosaicBandGridKey(timeBucket, sectionKey, tileAssetIds))
                }
                is PlaceholderItem -> item.copy(
                    gridKey = timelinePlaceholderGridKey("plp", timeBucket, sectionKey, index)
                )
                else -> item
            }
        }

    private fun timelinePlaceholderSectionKey(timeBucket: String, groupSize: TimelineGroupSize): String =
        "$timeBucket|placeholder|${groupSize.apiValue}"

    private suspend fun syncCachedBucketsManually(buckets: List<TimelineBucket>, hadBannerError: Boolean) {
        var failed = false
        for (bucket in buckets) {
            val success = loadBucketAssetsIfNeeded(
                timeBucket = bucket.timeBucket,
                force = true,
                keepCachedRowsOnFailure = true,
                source = TimelineServerRefreshSource.ManualRefresh
            )
            if (!success) failed = true
        }
        _uiConfig.update {
            it.copy(
                isSyncing = false,
                bannerError = if (failed) ConnectionUiMessage.CannotConnectToServer else null,
                bannerSuccess = if (!failed && hadBannerError) ConnectionUiMessage.ConnectedToServer else null
            )
        }
    }

    private fun publishColdSyncResult(
        successfulBucketIds: Set<String>,
        bucketGeometrySummaries: List<com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ) {
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets + successfulBucketIds,
                loadingBuckets = data.loadingBuckets - successfulBucketIds,
                failedBuckets = data.failedBuckets - successfulBucketIds
            )
        }
        publishBucketGeometrySummaries(
            summaries = bucketGeometrySummaries,
            groupSize = groupSize,
            columnCount = columnCount,
            families = families,
            geometryRequest = geometryRequest
        )
    }

    private fun publishColdSyncAssetsOnly(successfulBucketIds: Set<String>) {
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets + successfulBucketIds,
                loadingBuckets = data.loadingBuckets - successfulBucketIds,
                failedBuckets = data.failedBuckets - successfulBucketIds
            )
        }
    }

    private suspend fun publishAssetSyncResultWithMosaic(
        successfulBucketIds: Set<String>,
        failedBucketIds: Set<String>,
        changedBucketIds: Set<String>
    ) {
        val changedSuccessfulBucketIds = changedBucketIds intersect successfulBucketIds
        log.d {
            "Timeline publishing asset sync result successful=${successfulBucketIds.size} " +
                "failed=${failedBucketIds.size} changed=${changedBucketIds.size} " +
                "changedSuccessful=${changedSuccessfulBucketIds.size}"
        }
        publishAssetSyncResult(successfulBucketIds, failedBucketIds, changedBucketIds)
        clearTimelineMosaicCacheAction.buckets(changedSuccessfulBucketIds)
        val failedMosaicBuckets = precomputeTimelineMosaicForBuckets(changedSuccessfulBucketIds)
        val failedActiveMosaicBuckets = precomputeActiveTimelineMosaicForBuckets(changedSuccessfulBucketIds)
        requestTimelineMosaicCacheReadForBuckets(
            timeBuckets = changedSuccessfulBucketIds,
            source = TimelineMosaicWorkSource.SyncPrecompute
        )
        loadPersistedActiveMosaicAssignmentsForBuckets(changedSuccessfulBucketIds)
        markTimelineMosaicFailedForBuckets(failedMosaicBuckets)
        markActiveTimelineMosaicFailedForBuckets(failedActiveMosaicBuckets)
        log.d {
            "Timeline asset sync result published mosaicFailed=${failedMosaicBuckets.size} " +
                "activeMosaicFailed=${failedActiveMosaicBuckets.size}"
        }
    }

    private fun publishBlockingMosaicPrepareResult(
        successfulBucketIds: Set<String>,
        changedBucketIds: Set<String>,
        bucketGeometrySummaries: List<com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ) {
        val changedSuccessfulBucketIds = changedBucketIds intersect successfulBucketIds
        changedSuccessfulBucketIds.forEach { timeBucket ->
            bucketAssetsCache.remove(timeBucket)
            invalidateRuntimeMosaicAssignments(timeBucket)
        }
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets + successfulBucketIds,
                loadingBuckets = data.loadingBuckets - successfulBucketIds,
                failedBuckets = data.failedBuckets - successfulBucketIds,
                assetRevisions = data.assetRevisions.incrementRevisions(changedSuccessfulBucketIds)
            )
        }
        publishBucketGeometrySummaries(
            summaries = bucketGeometrySummaries,
            groupSize = groupSize,
            columnCount = columnCount,
            families = families,
            geometryRequest = geometryRequest
        )
    }

    private fun publishAssetSyncResult(
        successfulBucketIds: Set<String>,
        failedBucketIds: Set<String>,
        changedBucketIds: Set<String>
    ) {
        val changedSuccessfulBucketIds = changedBucketIds intersect successfulBucketIds
        // Successful sync means loaded/failed state changed; changed sync means
        // the persisted ordered assets changed and derived layout must be
        // recalculated for that bucket. Keep these separate so no-op launch
        // syncs do not repack the whole timeline.
        changedSuccessfulBucketIds.forEach { timeBucket ->
            bucketAssetsCache.remove(timeBucket)
            invalidateRuntimeMosaicAssignments(timeBucket)
        }
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets + successfulBucketIds - failedBucketIds,
                loadedBuckets = data.loadedBuckets + successfulBucketIds - failedBucketIds,
                loadingBuckets = data.loadingBuckets - successfulBucketIds - failedBucketIds,
                failedBuckets = data.failedBuckets + failedBucketIds - successfulBucketIds,
                assetRevisions = data.assetRevisions.incrementRevisions(changedSuccessfulBucketIds)
            )
        }
    }

    private fun invalidateRuntimeMosaicAssignments(timeBucket: String) {
        advanceTimelineMosaicBucketGeneration(timeBucket, "bucket-invalidate")
        timelineMosaicQueue.send(TimelineMosaicQueueCommand.InvalidateBucket(timeBucket))
        dayGroupsCache.keys.removeAll { it.timeBucket == timeBucket }
        runtimeMosaicResumeStates.keys.removeAll { it.timeBucket == timeBucket }
        deferredScrollMosaicStateUpdates.keys.removeAll { it.timeBucket == timeBucket }
        deferredScrollMosaicGeometryUpdates.keys.removeAll { it.timeBucket == timeBucket }
        viewModelScope.launch(Dispatchers.Default) {
            progressiveMosaicBuffer.clearBuckets(setOf(timeBucket))
        }
        _mosaicStates.update { states ->
            states.filterKeys { it.timeBucket != timeBucket }
        }
        _mosaicGeometryStates.update { states ->
            states.filterKeys { it.timeBucket != timeBucket }
        }
        _bucketGeometryStates.update { states ->
            states.filterKeys { it.timeBucket != timeBucket }
        }
    }

    private fun requestVisibleTimelineMosaicCacheRead() {
        if (!timelineGeometryReady.value) {
            // Defer the queue request until exact per-band geometry is loaded.
            // Once the geometry phase completes, this re-fires and the queue
            // proceeds normally. Subsequent calls (post-init) bypass the gate
            // because timelineGeometryReady remains true.
            viewModelScope.launch(Dispatchers.Default) {
                timelineGeometryReady.first { it }
                requestTimelineMosaicCacheReadForBuckets(
                    timeBuckets = orderedVisibleLoadedTimelineBuckets(),
                    source = TimelineMosaicWorkSource.RenderDemand
                )
            }
            return
        }
        requestTimelineMosaicCacheReadForBuckets(
            timeBuckets = orderedVisibleLoadedTimelineBuckets(),
            source = TimelineMosaicWorkSource.RenderDemand
        )
    }

    private fun orderedVisibleOrTargetLoadedTimelineBuckets(): List<String> {
        val loadedBuckets = _bucketData.value.loadedBuckets
        return orderedVisibleOrTargetTimelineBuckets()
            .filter { timeBucket -> timeBucket in loadedBuckets }
            .distinct()
    }

    private fun orderedVisibleOrTargetTimelineBuckets(): List<String> {
        val snapshot = state.value
        val indexes = buildList {
            visibleBucketIndexes.forEach { index ->
                if (index !in this) add(index)
            }
            lastTargetBucketIndex?.let { target ->
                if (target !in this) add(target)
            }
        }.ifEmpty { lastVisibleBucketIndexes }
        return indexes
            .mapNotNull { index -> snapshot.buckets.getOrNull(index)?.timeBucket }
            .distinct()
    }

    private fun requestPersistedBucketGeometryForCachedBuckets() {
        val data = _bucketData.value
        val orderedBuckets = data.buckets.map { it.timeBucket }
        val selection = selectTimelineGeometryBuckets(
            orderedBuckets = orderedBuckets,
            cachedBuckets = data.cachedBuckets,
            visibleBucketIndexes = visibleBucketIndexes,
            targetBucketIndex = lastTargetBucketIndex,
            fallbackVisibleBucketIndexes = lastVisibleBucketIndexes,
            radius = PREFETCH_BUCKET_RADIUS,
            backgroundChunkSize = CACHED_BUCKET_MATERIALIZATION_CHUNK_SIZE
        )
        if (selection.priorityBuckets.isEmpty()) {
            timelineGeometryReady.value = true
            return
        }
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) {
            timelineGeometryReady.value = true
            return
        }
        if (!config.viewConfig.cacheMosaicResults) {
            timelineGeometryReady.value = true
            return
        }
        val geometryRequest = timelineMosaicGeometryRequest(config, config.mosaicColumnCount) ?: run {
            timelineGeometryReady.value = true
            return
        }
        // Close the gate while the new geometry phase runs. The mosaic queue
        // will defer its first request until both bucket-aggregate and
        // per-section reads complete.
        timelineGeometryReady.value = false
        val generation = ++bucketGeometryCacheGeneration
        bucketGeometryCacheJob?.cancel()
        bucketGeometryCacheJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                readAndPublishPersistedTimelineGeometry(
                    buckets = selection.priorityBuckets,
                    config = config,
                    geometryRequest = geometryRequest,
                    generation = generation
                )
            } finally {
                if (generation == bucketGeometryCacheGeneration &&
                    sameBucketGeometryReadConfig(config, geometryRequest, _uiConfig.value)
                ) {
                    timelineGeometryReady.value = true
                }
            }
            log.d { "Timeline priority geometry phase complete buckets=${selection.priorityBuckets.size}" }
            if (!isTimelineScrollActive.value) {
                for (chunk in selection.backgroundChunks) {
                    if (generation != bucketGeometryCacheGeneration ||
                        !sameBucketGeometryReadConfig(config, geometryRequest, _uiConfig.value)
                    ) {
                        return@launch
                    }
                    readAndPublishPersistedTimelineGeometry(
                        buckets = chunk,
                        config = config,
                        geometryRequest = geometryRequest,
                        generation = generation
                    )
                    yield()
                }
            }
        }
    }

    private suspend fun readAndPublishPersistedTimelineGeometry(
        buckets: List<String>,
        config: UiConfig,
        geometryRequest: TimelineMosaicGeometryRequest,
        generation: Long
    ) {
        if (buckets.isEmpty()) return
        val (bucketResult, sectionResult) = coroutineScope {
            val bucketDeferred = async(Dispatchers.IO) {
                getTimelineBucketGeometryUseCase(
                    timeBuckets = buckets.toSet(),
                    groupSize = config.groupSize,
                    columnCount = config.mosaicColumnCount,
                    families = config.viewConfig.mosaicFamilies,
                    geometryRequest = geometryRequest
                )
            }
            val sectionDeferred = async(Dispatchers.IO) {
                getTimelineMosaicSectionGeometryUseCase(
                    timeBuckets = buckets.toSet(),
                    groupSize = config.groupSize,
                    columnCount = config.mosaicColumnCount,
                    families = config.viewConfig.mosaicFamilies,
                    geometryRequest = geometryRequest
                )
            }
            bucketDeferred.await() to sectionDeferred.await()
        }
        if (generation != bucketGeometryCacheGeneration) {
            log.d {
                "Timeline geometry read ignored due to stale generation " +
                    "expected=$generation actual=$bucketGeometryCacheGeneration"
            }
            return
        }
        if (!sameBucketGeometryReadConfig(config, geometryRequest, _uiConfig.value)) {
            log.d { "Timeline geometry read ignored due to stale config" }
            return
        }
        bucketResult.onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "Failed to load persisted Timeline bucket geometry" }
        }.onSuccess { summaries ->
            publishBucketGeometrySummaries(
                summaries = summaries,
                groupSize = config.groupSize,
                columnCount = config.mosaicColumnCount,
                families = config.viewConfig.mosaicFamilies,
                geometryRequest = geometryRequest
            )
        }
        sectionResult.onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "Failed to load persisted Timeline section geometry" }
        }.onSuccess { summaries ->
            publishSectionGeometrySummaries(
                summaries = summaries,
                columnCount = config.mosaicColumnCount,
                families = config.viewConfig.mosaicFamilies
            )
        }
        log.d {
            "Timeline geometry chunk read completed buckets=${buckets.size} " +
                "generation=$generation"
        }
    }

    private fun publishSectionGeometrySummaries(
        summaries: List<com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary>,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>
    ) {
        if (summaries.isEmpty()) return
        val revisions = _bucketData.value.assetRevisions
        val updates = summaries.associate { summary ->
            mosaicCacheKey(
                timeBucket = summary.timeBucket,
                sectionKey = summary.sectionKey,
                columnCount = columnCount,
                assetRevision = revisions[summary.timeBucket] ?: 0,
                families = families
            ) to TimelineMosaicSectionGeometry(
                placeholderHeight = summary.placeholderHeight,
                ranges = summary.ranges
            )
        }
        _mosaicGeometryStates.update { states -> states + updates }
        scheduleProgressiveMosaicFlush(immediate = true)
    }

    private fun sameBucketGeometryReadConfig(
        expectedConfig: UiConfig,
        expectedRequest: TimelineMosaicGeometryRequest,
        actualConfig: UiConfig
    ): Boolean {
        if (!sameMosaicReadConfig(expectedConfig, actualConfig)) return false
        val actualRequest = timelineMosaicGeometryRequest(actualConfig, actualConfig.mosaicColumnCount)
            ?: return false
        return expectedRequest == actualRequest
    }

    private fun publishBucketGeometrySummaries(
        summaries: List<com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ) {
        if (summaries.isEmpty()) return
        val revisions = _bucketData.value.assetRevisions
        val updates = summaries.associate { geometry ->
            bucketGeometryCacheKey(
                timeBucket = geometry.timeBucket,
                groupSize = groupSize,
                columnCount = columnCount,
                assetRevision = revisions[geometry.timeBucket] ?: 0,
                families = families,
                geometryRequest = geometryRequest
            ) to geometry.placeholderHeight
        }
        _bucketGeometryStates.update { states -> states + updates }
        val readyBuckets = updates.keys
        _bucketData.update { data ->
            data.copy(geometryReadyBuckets = data.geometryReadyBuckets + readyBuckets)
        }
        log.d {
            "Timeline bucket geometry published group=$groupSize columns=$columnCount " +
                "summaries=${summaries.size}"
        }
    }

    private fun visibleLoadedTimelineBuckets(): Set<String> {
        return orderedVisibleLoadedTimelineBuckets().toSet()
    }

    private fun orderedVisibleLoadedTimelineBuckets(): List<String> {
        val snapshot = state.value
        val loadedBuckets = _bucketData.value.loadedBuckets
        return orderedTimelineBucketIndexesForMosaic(
            visibleBucketIndexes = visibleBucketIndexes,
            targetBucketIndex = lastTargetBucketIndex,
            fallbackVisibleBucketIndexes = lastVisibleBucketIndexes,
            bucketCount = snapshot.buckets.size,
            radius = PREFETCH_BUCKET_RADIUS
        )
            .mapNotNull { index -> snapshot.buckets.getOrNull(index)?.timeBucket }
            .filter { timeBucket -> timeBucket in loadedBuckets }
            .distinct()
    }

    private fun requestTimelineMosaicCacheReadForBuckets(
        timeBuckets: Collection<String>,
        source: TimelineMosaicWorkSource = TimelineMosaicWorkSource.RenderDemand
    ) {
        if (timeBuckets.isEmpty()) return
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return
        if (!config.viewConfig.cacheMosaicResults && source != TimelineMosaicWorkSource.RenderDemand) {
            log.d { "Timeline Mosaic sync-source request skipped while cache results are off buckets=${timeBuckets.size}" }
            return
        }
        val loadedBuckets = _bucketData.value.loadedBuckets
        val loadedRequestedBuckets = timeBuckets.filter { it in loadedBuckets }.distinct()
        if (loadedRequestedBuckets.isEmpty()) return
        val visibleOrTargetBuckets = orderedVisibleOrTargetLoadedTimelineBuckets()
        val selection = selectTimelineMosaicBucketsForScroll(
            requestedBuckets = loadedRequestedBuckets,
            visibleOrTargetBuckets = visibleOrTargetBuckets,
            cacheMosaicResults = config.viewConfig.cacheMosaicResults,
            isScrollActive = isTimelineScrollActive.value
        )
        if (selection.deferredBuckets.isNotEmpty()) {
            timelineMosaicQueue.send(
                TimelineMosaicQueueCommand.Defer(
                    selection.deferredBuckets.map { TimelineMosaicQueueRequest(it, source) }
                )
            )
            log.d { "Timeline Mosaic deferred during scroll buckets=${selection.deferredBuckets.size}" }
        }
        val requestedBuckets = selection.runnableBuckets
        if (requestedBuckets.isEmpty()) return
        enqueueTimelineMosaicBucketsForRead(
            timeBuckets = requestedBuckets,
            visiblePriority = requestedBuckets.any { it in visibleOrTargetBuckets.toSet() },
            source = source
        )
    }

    private fun enqueueTimelineMosaicBucketsForRead(
        timeBuckets: List<String>,
        visiblePriority: Boolean,
        source: TimelineMosaicWorkSource
    ) {
        val uniqueBuckets = timeBuckets.distinct()
        if (uniqueBuckets.isEmpty()) return
        timelineMosaicQueue.send(
            TimelineMosaicQueueCommand.Enqueue(
                requests = uniqueBuckets.map { TimelineMosaicQueueRequest(it, source) },
                visiblePriority = visiblePriority,
                visibleOrTargetBuckets = orderedVisibleOrTargetLoadedTimelineBuckets().toSet()
            )
        )
    }

    private suspend fun handleTimelineMosaicQueueEffect(effect: TimelineMosaicQueueEffect) {
        when (effect) {
            is TimelineMosaicQueueEffect.StartWork -> startTimelineMosaicWorker(effect.request, effect.token)
            is TimelineMosaicQueueEffect.CancelWork -> cancelTimelineMosaicWorker(effect.request, effect.token)
        }
    }

    private suspend fun startTimelineMosaicWorker(
        request: TimelineMosaicQueueRequest,
        token: Long
    ) {
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                runTimelineMosaicQueueWork(request, token)
            } finally {
                timelineMosaicWorkerMutex.withLock {
                    activeTimelineMosaicJobs.remove(token)
                }
                timelineMosaicQueue.send(TimelineMosaicQueueCommand.WorkerFinished(request, token))
            }
        }
        timelineMosaicWorkerMutex.withLock {
            activeTimelineMosaicJobs[token] = job
        }
        job.start()
    }

    private suspend fun cancelTimelineMosaicWorker(
        request: TimelineMosaicQueueRequest,
        token: Long
    ) {
        val job = timelineMosaicWorkerMutex.withLock {
            activeTimelineMosaicJobs.remove(token)
        }
        job?.cancel()
        log.d { "Timeline Mosaic worker cancelled bucket=${request.timeBucket} source=${request.source} token=$token" }
    }

    private suspend fun cancelAllTimelineMosaicWorkers() {
        val jobs = timelineMosaicWorkerMutex.withLock {
            activeTimelineMosaicJobs.values.toList().also {
                activeTimelineMosaicJobs.clear()
            }
        }
        jobs.forEach { it.cancel() }
    }

    private suspend fun isActiveTimelineMosaicWorker(token: Long?): Boolean {
        if (token == null) return true
        return timelineMosaicWorkerMutex.withLock {
            activeTimelineMosaicJobs[token]?.isActive == true
        }
    }

    private suspend fun runTimelineMosaicQueueWork(
        request: TimelineMosaicQueueRequest,
        token: Long
    ) {
        val timeBucket = request.timeBucket
        if (isTimelineScrollActive.value) {
            timelineMosaicQueue.send(TimelineMosaicQueueCommand.Defer(listOf(request)))
            return
        }
        val config = _uiConfig.value
        if (config.viewConfig.cacheMosaicResults) {
            loadPersistedMosaicAssignmentsForBuckets(
                timeBuckets = setOf(timeBucket),
                expectedWorkToken = token,
                expectedConfig = config,
                source = request.source
            )
        } else if (request.source == TimelineMosaicWorkSource.RenderDemand) {
            computeRuntimeTimelineMosaicForBuckets(
                timeBuckets = setOf(timeBucket),
                expectedWorkToken = token,
                expectedConfig = config
            )
        } else {
            log.d { "Timeline Mosaic sync-source queue item skipped while cache results are off bucket=$timeBucket" }
        }
    }

    // Sync precompute persists cache artifacts; render-demand work is started
    // by TimelineMosaicQueueActor effects so queue state stays single-owner.
    private suspend fun precomputeTimelineMosaicForBuckets(timeBuckets: Set<String>): Set<String> {
        if (timeBuckets.isEmpty()) return emptySet()
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return emptySet()
        if (!config.viewConfig.cacheMosaicResults) {
            log.d { "Timeline Mosaic sync precompute skipped while cache results are off buckets=${timeBuckets.size}" }
            return emptySet()
        }
        val geometryRequest = timelineMosaicGeometryRequest(config)
        log.d {
            "Timeline Mosaic precompute started buckets=${timeBuckets.size} group=${config.groupSize} " +
                "columns=${config.mosaicColumnCount} families=${config.viewConfig.mosaicFamilies} " +
                "geometryWidth=${geometryRequest?.availableWidth} geometryMaxRowHeight=${geometryRequest?.maxRowHeight}"
        }
        val expectedRevisions = _bucketData.value.assetRevisions
        return prepareTimelineMosaicCacheAction(
            timeBuckets = timeBuckets,
            groupSize = config.groupSize,
            columnCount = config.mosaicColumnCount,
            families = config.viewConfig.mosaicFamilies,
            geometryRequest = geometryRequest,
            cacheDisplayResults = config.viewConfig.cacheMosaicResults,
            onProgressChunk = { chunk ->
                publishProgressiveMosaicChunk(
                    chunk = chunk,
                    expectedConfig = config,
                    expectedGeometryRequest = geometryRequest,
                    expectedRevisions = expectedRevisions
                )
            }
        ).fold(
            onSuccess = { result ->
                if (result.failedBucketIds.isNotEmpty()) {
                    log.e { "Failed to precompute Timeline Mosaic buckets=${result.failedBucketIds}" }
                }
                log.d {
                    "Timeline Mosaic precompute completed requested=${timeBuckets.size} " +
                        "successful=${result.successfulBucketIds.size} failed=${result.failedBucketIds.size}"
                }
                result.failedBucketIds
            },
            onFailure = { e ->
                log.e(e) { "Failed to precompute Timeline Mosaic assignments" }
                timeBuckets
            }
        )
    }

    private suspend fun publishProgressiveMosaicChunk(
        chunk: TimelineMosaicProgressChunk,
        expectedConfig: UiConfig,
        expectedGeometryRequest: TimelineMosaicGeometryRequest?,
        expectedRevisions: Map<String, Int>
    ) {
        val currentConfig = _uiConfig.value
        if (!sameMosaicReadConfig(expectedConfig, currentConfig)) return
        if (expectedGeometryRequest != null &&
            timelineMosaicGeometryRequest(currentConfig, expectedConfig.mosaicColumnCount) != expectedGeometryRequest
        ) {
            return
        }
        val expectedRevision = expectedRevisions[chunk.timeBucket] ?: 0
        if (_bucketData.value.assetRevisionFor(chunk.timeBucket) != expectedRevision) return
        if (chunk.sourceEndExclusive <= chunk.sourceStartIndex || chunk.assignments.isEmpty()) return
        if (isTimelineScrollActive.value) {
            timelineMosaicQueue.send(
                TimelineMosaicQueueCommand.Defer(
                    listOf(TimelineMosaicQueueRequest(chunk.timeBucket, TimelineMosaicWorkSource.SyncPrecompute))
                )
            )
            return
        }
        val key = mosaicCacheKey(
            timeBucket = chunk.timeBucket,
            sectionKey = chunk.sectionKey,
            columnCount = expectedConfig.mosaicColumnCount,
            assetRevision = expectedRevision,
            families = expectedConfig.viewConfig.mosaicFamilies
        )
        val runtimeChunk = RuntimeMosaicProgressChunk(
            keyScope = MosaicKeyScope(
                owner = MosaicOwnerKey(MosaicOwnerScope.TIMELINE_BUCKET, chunk.timeBucket),
                sectionKey = chunk.sectionKey,
                columnCount = expectedConfig.mosaicColumnCount,
                familiesKey = key.familiesKey,
                contentFingerprint = expectedRevision.toString(),
                generation = expectedRevisions.hashCode().toLong()
            ),
            sectionLabel = chunk.sectionLabel,
            sourceStartIndex = chunk.sourceStartIndex,
            sourceEndExclusive = chunk.sourceEndExclusive,
            assignments = chunk.assignments
        )
        progressiveMosaicBuffer.add(key, runtimeChunk)
        if (chunk.timeBucket in visibleLoadedTimelineBuckets()) {
            scheduleProgressiveMosaicFlush(immediate = false)
        }
    }

    private fun scheduleProgressiveMosaicFlush(immediate: Boolean) {
        if (isTimelineScrollActive.value) return
        if (immediate) {
            progressiveMosaicFlushJob?.cancel()
            progressiveMosaicFlushJob = viewModelScope.launch(Dispatchers.Default) {
                flushProgressiveMosaicBuffer()
            }
            return
        }
        if (progressiveMosaicFlushJob?.isActive == true) return
        progressiveMosaicFlushJob = viewModelScope.launch(Dispatchers.Default) {
            delay(PROGRESSIVE_MOSAIC_FLUSH_DELAY_MS)
            flushProgressiveMosaicBuffer()
        }
    }

    private suspend fun flushProgressiveMosaicBuffer() {
        val eligibleBuckets = visibleLoadedTimelineBuckets()
        val eligibleKeys = _mosaicGeometryStates.value
            .filter { (key, geometry) -> key.timeBucket in eligibleBuckets && geometry.ranges.isNotEmpty() }
            .keys
        val updatesByKey = progressiveMosaicBuffer.drainEligibleKeys(eligibleKeys)
        if (updatesByKey.isEmpty()) return
        _mosaicStates.update { states ->
            timelineProgressiveMosaicStateUpdates(states, updatesByKey)
        }
        log.d {
            "Timeline progressive Mosaic chunks flushed buckets=${eligibleBuckets.size} " +
                "sections=${updatesByKey.size} chunks=${updatesByKey.values.sumOf { it.size }}"
        }
    }

    private suspend fun precomputeActiveTimelineMosaicForBuckets(timeBuckets: Set<String>): Set<String> {
        return emptySet()
    }

    private suspend fun computeRuntimeTimelineMosaicForBuckets(
        timeBuckets: Set<String>,
        expectedWorkToken: Long? = null,
        expectedConfig: UiConfig? = null
    ): Set<String> {
        if (timeBuckets.isEmpty()) return emptySet()
        if (isTimelineScrollActive.value) {
            timelineMosaicQueue.send(
                TimelineMosaicQueueCommand.Defer(
                    timeBuckets.map { TimelineMosaicQueueRequest(it, TimelineMosaicWorkSource.RenderDemand) }
                )
            )
            return emptySet()
        }
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return emptySet()
        if (expectedConfig != null && !sameMosaicReadConfig(expectedConfig, config)) return emptySet()
        val layoutSpec = mosaicLayoutSpecForColumnCount(config.availableWidth, config.mosaicColumnCount)
            ?: return emptySet()
        val columnCount = config.mosaicColumnCount
        val families = config.viewConfig.mosaicFamilies
        val revisions = _bucketData.value.assetRevisions
        val bucketIndexes = _bucketData.value.buckets.mapIndexed { index, bucket -> bucket.timeBucket to index }.toMap()
        val stateUpdates = mutableMapOf<MosaicCacheKey, RuntimeMosaicState>()
        val geometryUpdates = mutableMapOf<MosaicCacheKey, TimelineMosaicSectionGeometry>()
        val failedBuckets = mutableSetOf<String>()
        for (timeBucket in timeBuckets) {
            if (!isActiveTimelineMosaicWorker(expectedWorkToken)) return failedBuckets
            if (expectedConfig != null && !sameMosaicReadConfig(expectedConfig, _uiConfig.value)) return failedBuckets
            val assetRevision = revisions[timeBucket] ?: 0
            val assets = getCachedOrLoadBucketAssets(timeBucket)
            if (assets.isEmpty()) continue
            val bucketIndex = bucketIndexes[timeBucket] ?: 0
            val sections = runtimeMosaicSections(
                timeBucket = timeBucket,
                groupSize = config.groupSize,
                assetRevision = assetRevision,
                assets = assets
            )
            for (section in sections) {
                val key = mosaicCacheKey(
                    timeBucket = timeBucket,
                    sectionKey = section.sectionKey,
                    columnCount = columnCount,
                    assetRevision = assetRevision,
                    families = families
                )
                val resumeState = runtimeMosaicResumeStates[key]
                var latestCheckpoint = resumeState?.checkpoint
                var progressChunks = resumeState?.chunks.orEmpty()
                val request = MosaicSectionRequest(
                    keyScope = MosaicKeyScope(
                        owner = MosaicOwnerKey(MosaicOwnerScope.TIMELINE_BUCKET, timeBucket),
                        sectionKey = section.sectionKey,
                        columnCount = columnCount,
                        familiesKey = key.familiesKey,
                        contentFingerprint = assetRevision.toString(),
                        generation = expectedWorkToken ?: 0L
                    ),
                    assets = section.assets,
                    bucketIndex = bucketIndex,
                    sectionLabel = section.sectionLabel,
                    assignmentLayoutSpec = layoutSpec,
                    displayLayoutSpec = layoutSpec,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = config.rowHeightBounds.max,
                    enabledFamilies = families
                )
                try {
                    when (val result = withContext(timelineMosaicDispatcherProvider.dispatcher) {
                        mosaicRenderEngine.computeSection(
                            request = request,
                            resumeCheckpoint = latestCheckpoint,
                            onCheckpoint = { checkpoint -> latestCheckpoint = checkpoint },
                            onProgressChunk = progress@ { chunk ->
                                if (isTimelineScrollActive.value) return@progress
                                if (chunk.sourceEndExclusive <= chunk.sourceStartIndex || chunk.assignments.isEmpty()) {
                                    return@progress
                                }
                                progressChunks = (progressChunks + chunk)
                                    .distinctBy { it.sourceStartIndex to it.sourceEndExclusive }
                                    .sortedBy { it.sourceStartIndex }
                                progressiveMosaicBuffer.add(key, chunk)
                                if (timeBucket in visibleLoadedTimelineBuckets()) {
                                    scheduleProgressiveMosaicFlush(immediate = false)
                                }
                            },
                            shouldContinue = {
                                if (isTimelineScrollActive.value) {
                                    throw CancellationException("Timeline Mosaic paused for scroll")
                                }
                            }
                        )
                    }) {
                        is MosaicSectionResult.Ready -> {
                            runtimeMosaicResumeStates.remove(key)
                            stateUpdates[key] = RuntimeMosaicState.Ready(
                                assignments = result.value.assignments,
                                displayRecords = resolvedSectionDisplayRecordsOrEmpty(
                                    displayItems = result.value.displayItems,
                                    assets = section.assets
                                )
                            )
                            geometryUpdates[key] = TimelineMosaicSectionGeometry(
                                placeholderHeight = result.value.geometry.placeholderHeight,
                                ranges = result.value.geometry.ranges
                            )
                        }
                        is MosaicSectionResult.Failed -> {
                            runtimeMosaicResumeStates.remove(key)
                            stateUpdates[key] = RuntimeMosaicState.Failed
                            failedBuckets.add(timeBucket)
                        }
                    }
                } catch (e: CancellationException) {
                    latestCheckpoint?.let { checkpoint ->
                        runtimeMosaicResumeStates[key] = TimelineRuntimeMosaicResumeState(
                            checkpoint = checkpoint,
                            chunks = progressChunks
                        )
                        timelineMosaicQueue.send(
                            TimelineMosaicQueueCommand.Defer(
                                listOf(TimelineMosaicQueueRequest(timeBucket, TimelineMosaicWorkSource.RenderDemand))
                            )
                        )
                        log.d { "Timeline runtime Mosaic checkpoint saved bucket=$timeBucket section=${section.sectionKey}" }
                    }
                    if (isTimelineScrollActive.value) return failedBuckets
                    throw e
                }
            }
        }
        if (!isActiveTimelineMosaicWorker(expectedWorkToken)) return failedBuckets
        publishMosaicUpdates(stateUpdates = stateUpdates, geometryUpdates = geometryUpdates)
        return failedBuckets
    }

    private fun publishMosaicUpdates(
        stateUpdates: Map<MosaicCacheKey, RuntimeMosaicState>,
        geometryUpdates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry> = emptyMap(),
        activeConfig: ActiveMosaicConfig? = null
    ) {
        if (stateUpdates.isEmpty() && geometryUpdates.isEmpty()) return
        if (isTimelineScrollActive.value) {
            deferredScrollMosaicStateUpdates.putAll(stateUpdates)
            deferredScrollMosaicGeometryUpdates.putAll(geometryUpdates)
            log.d {
                "Timeline Mosaic updates deferred during scroll states=${stateUpdates.size} " +
                    "geometry=${geometryUpdates.size}"
            }
            return
        }
        mosaicPublishChannel.trySend(
            PendingMosaicPublish(
                stateUpdates = stateUpdates,
                geometryUpdates = geometryUpdates,
                stamp = mosaicPublishStampFor(stateUpdates.keys + geometryUpdates.keys)
            )
        )
    }

    private fun publishDeferredScrollMosaicUpdates() {
        if (deferredScrollMosaicStateUpdates.isEmpty() &&
            deferredScrollMosaicGeometryUpdates.isEmpty() &&
            deferredScrollActiveMosaicConfig == null
        ) {
            return
        }
        val stateUpdates = deferredScrollMosaicStateUpdates.toMap()
        val geometryUpdates = deferredScrollMosaicGeometryUpdates.toMap()
        val activeConfig = deferredScrollActiveMosaicConfig
        deferredScrollMosaicStateUpdates.clear()
        deferredScrollMosaicGeometryUpdates.clear()
        deferredScrollActiveMosaicConfig = null
        publishMosaicUpdates(
            stateUpdates = stateUpdates,
            geometryUpdates = geometryUpdates,
            activeConfig = activeConfig
        )
        log.d {
            "Timeline deferred Mosaic updates published states=${stateUpdates.size} geometry=${geometryUpdates.size}"
        }
    }

    private fun runtimeMosaicSections(
        timeBucket: String,
        groupSize: TimelineGroupSize,
        assetRevision: Int,
        assets: List<Asset>
    ): List<TimelineRuntimeMosaicSection> =
        if (groupSize == TimelineGroupSize.DAY) {
            dayGroupsForBucket(timeBucket, assetRevision, assets).map { dayGroup ->
                TimelineRuntimeMosaicSection(
                    sectionKey = dayMosaicSectionKey(timeBucket, dayGroup.label),
                    sectionLabel = dayGroup.label,
                    assets = dayGroup.assets
                )
            }
        } else {
            listOf(
                TimelineRuntimeMosaicSection(
                    sectionKey = monthMosaicSectionKey(timeBucket),
                    sectionLabel = _bucketData.value.buckets
                        .firstOrNull { it.timeBucket == timeBucket }
                        ?.displayLabel
                        ?: timeBucket,
                    assets = assets
                )
            )
        }

    private suspend fun loadPersistedMosaicAssignmentsForBuckets(
        timeBuckets: Set<String>,
        expectedWorkToken: Long? = null,
        expectedConfig: UiConfig? = null,
        source: TimelineMosaicWorkSource = TimelineMosaicWorkSource.RenderDemand
    ) {
        if (timeBuckets.isEmpty()) return
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return
        if (!config.viewConfig.cacheMosaicResults) {
            if (shouldComputeRuntimeTimelineMosaic(config.viewConfig.cacheMosaicResults, source)) {
                computeRuntimeTimelineMosaicForBuckets(
                    timeBuckets = timeBuckets,
                    expectedWorkToken = expectedWorkToken,
                    expectedConfig = expectedConfig
                )
            } else {
                log.d { "Timeline persisted Mosaic sync-source read skipped while cache results are off buckets=${timeBuckets.size}" }
            }
            return
        }
        val columnCount = config.mosaicColumnCount
        val families = config.viewConfig.mosaicFamilies
        val geometryRequest = timelineMosaicGeometryRequest(config, columnCount)
        log.d {
            "Timeline persisted Mosaic read started buckets=${timeBuckets.size} group=${config.groupSize} " +
                "columns=$columnCount families=$families geometryWidth=${geometryRequest?.availableWidth}"
        }
        val result = getTimelineMosaicCacheStatusUseCase(
            timeBuckets = timeBuckets,
            groupSize = config.groupSize,
            columnCount = columnCount,
            families = families,
            geometryRequest = geometryRequest,
            includeDisplayCache = config.viewConfig.cacheMosaicResults
        )
        result.onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "Failed to load persisted Timeline Mosaic assignments" }
        }.onSuccess { status ->
            if (!isActiveTimelineMosaicWorker(expectedWorkToken)) {
                log.d { "Timeline persisted Mosaic read ignored due to inactive worker token=$expectedWorkToken" }
                return@onSuccess
            }
            if (expectedConfig != null && !sameMosaicReadConfig(expectedConfig, _uiConfig.value)) {
                log.d { "Timeline persisted Mosaic read ignored due to stale config" }
                return@onSuccess
            }
            val revisions = _bucketData.value.assetRevisions
            val publishableAssignments = if (config.viewConfig.cacheMosaicResults) {
                status.assignments.filter { it.timeBucket in status.assignmentGeometryReadyBucketIds }
            } else {
                status.assignments
            }
            val geometryUpdates = status.geometrySummaries.associate { geometry ->
                mosaicCacheKey(
                    timeBucket = geometry.timeBucket,
                    sectionKey = geometry.sectionKey,
                    columnCount = columnCount,
                    assetRevision = revisions[geometry.timeBucket] ?: 0,
                    families = families
                ) to TimelineMosaicSectionGeometry(
                    placeholderHeight = geometry.placeholderHeight,
                    ranges = geometry.ranges
                )
            }
            val displayRecordsBySection = status.displaySections.associateBy { it.timeBucket to it.sectionKey }
            val updates = publishableAssignments.associate { assignment ->
                mosaicCacheKey(
                    timeBucket = assignment.timeBucket,
                    sectionKey = assignment.sectionKey,
                    columnCount = columnCount,
                    assetRevision = revisions[assignment.timeBucket] ?: 0,
                    families = families
                ) to RuntimeMosaicState.Ready(
                    assignments = assignment.assignments,
                    displayRecords = displayRecordsBySection[assignment.timeBucket to assignment.sectionKey]
                        ?.displayRecords
                        .orEmpty()
                )
            }
            publishMosaicUpdates(
                stateUpdates = updates,
                geometryUpdates = geometryUpdates
            )
            if (status.missingBucketIds.isNotEmpty()) {
                if (source == TimelineMosaicWorkSource.RenderDemand) {
                    log.d {
                        "Timeline persisted Mosaic cache miss using runtime compute buckets=${status.missingBucketIds.size}"
                    }
                    computeRuntimeTimelineMosaicForBuckets(
                        timeBuckets = status.missingBucketIds,
                        expectedWorkToken = expectedWorkToken,
                        expectedConfig = expectedConfig ?: config
                    )
                } else {
                    log.d {
                        "Timeline persisted Mosaic cache miss left for render demand buckets=${status.missingBucketIds.size}"
                    }
                }
            }
            log.d {
                "Timeline persisted Mosaic read completed requested=${timeBuckets.size} " +
                    "assignments=${status.assignments.size} geometry=${status.geometrySummaries.size} " +
                    "display=${status.displaySections.size} " +
                    "assignmentGeometryReady=${status.assignmentGeometryReadyBucketIds.size} " +
                    "displayReady=${status.displayCacheReadyBucketIds.size} " +
                    "complete=${status.completeBucketIds.size} missing=${status.missingBucketIds.size}"
            }
        }
    }

    private fun sameMosaicReadConfig(expected: UiConfig, actual: UiConfig): Boolean =
        expected.groupSize == actual.groupSize &&
            expected.availableWidth == actual.availableWidth &&
            expected.rowHeightBounds.max == actual.rowHeightBounds.max &&
            expected.mosaicColumnCount == actual.mosaicColumnCount &&
            expected.viewConfig.mosaicEnabled == actual.viewConfig.mosaicEnabled &&
            expected.viewConfig.cacheMosaicResults == actual.viewConfig.cacheMosaicResults &&
            expected.viewConfig.mosaicFamilies.normalizedMosaicFamilies() ==
            actual.viewConfig.mosaicFamilies.normalizedMosaicFamilies()

    private suspend fun loadPersistedActiveMosaicAssignmentsForBuckets(timeBuckets: Set<String>) {
        return
    }

    private suspend fun markTimelineMosaicFailedForBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return
        val columnCount = config.mosaicColumnCount
        val families = config.viewConfig.mosaicFamilies
        val revisions = _bucketData.value.assetRevisions
        val updates = mutableMapOf<MosaicCacheKey, RuntimeMosaicState>()
        for (timeBucket in timeBuckets) {
            val assetRevision = revisions[timeBucket] ?: 0
            if (config.groupSize == TimelineGroupSize.DAY) {
                val assets = bucketAssetsCache[timeBucket] ?: getBucketAssetsUseCase(timeBucket)
                dayGroupsForBucket(timeBucket, assetRevision, assets).forEach { dayGroup ->
                    updates[mosaicCacheKey(
                        timeBucket = timeBucket,
                        sectionKey = dayMosaicSectionKey(timeBucket, dayGroup.label),
                        columnCount = columnCount,
                        assetRevision = assetRevision,
                        families = families
                    )] = RuntimeMosaicState.Failed
                }
            } else {
                updates[mosaicCacheKey(
                    timeBucket = timeBucket,
                    sectionKey = monthMosaicSectionKey(timeBucket),
                    columnCount = columnCount,
                    assetRevision = assetRevision,
                    families = families
                )] = RuntimeMosaicState.Failed
            }
        }
        if (updates.isNotEmpty()) {
            _mosaicStates.update { states -> states + updates }
            progressiveMosaicBuffer.clearKeys(updates.keys)
        }
    }

    private suspend fun markActiveTimelineMosaicFailedForBuckets(timeBuckets: Set<String>) {
        return
    }

    private suspend fun markTimelineMosaicFailedForConfig(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>
    ) {
        val revisions = _bucketData.value.assetRevisions
        val updates = mutableMapOf<MosaicCacheKey, RuntimeMosaicState>()
        for (timeBucket in timeBuckets) {
            val assetRevision = revisions[timeBucket] ?: 0
            if (groupSize == TimelineGroupSize.DAY) {
                val assets = bucketAssetsCache[timeBucket] ?: getBucketAssetsUseCase(timeBucket)
                dayGroupsForBucket(timeBucket, assetRevision, assets).forEach { dayGroup ->
                    updates[mosaicCacheKey(
                        timeBucket = timeBucket,
                        sectionKey = dayMosaicSectionKey(timeBucket, dayGroup.label),
                        columnCount = columnCount,
                        assetRevision = assetRevision,
                        families = families
                    )] = RuntimeMosaicState.Failed
                }
            } else {
                updates[mosaicCacheKey(
                    timeBucket = timeBucket,
                    sectionKey = monthMosaicSectionKey(timeBucket),
                    columnCount = columnCount,
                    assetRevision = assetRevision,
                    families = families
                )] = RuntimeMosaicState.Failed
            }
        }
        if (updates.isNotEmpty()) {
            _mosaicStates.update { states -> states + updates }
            progressiveMosaicBuffer.clearKeys(updates.keys)
        }
    }

    private fun timelineMosaicLayoutSpec(config: UiConfig): MosaicLayoutSpec? =
        if (config.viewConfig.mosaicEnabled) {
            mosaicLayoutSpecForColumnCount(
                config.availableWidth,
                config.mosaicColumnCount
            )
        } else {
            mosaicLayoutSpecFor(config.availableWidth, config.targetRowHeight)
        }

    private fun timelineMosaicGeometryRequest(
        config: UiConfig,
        columnCount: Int = config.mosaicColumnCount
    ): TimelineMosaicGeometryRequest? =
        timelineMosaicGeometryRequest(
            availableWidth = config.availableWidth,
            maxRowHeight = config.rowHeightBounds.max,
            columnCount = columnCount
        )

    private fun timelineMosaicGeometryRequest(
        availableWidth: Float,
        maxRowHeight: Float,
        columnCount: Int
    ): TimelineMosaicGeometryRequest? =
        if (availableWidth > 0f) {
            TimelineMosaicGeometryRequest(
                availableWidth = availableWidth,
                maxRowHeight = maxRowHeight,
                spacing = GRID_SPACING_DP
            ).takeIf { mosaicLayoutSpecForColumnCount(availableWidth, columnCount) != null }
        } else {
            null
        }

    private fun mosaicCacheKey(
        timeBucket: String,
        sectionKey: String,
        columnCount: Int,
        assetRevision: Int,
        families: Set<MosaicTemplateFamily>
    ): MosaicCacheKey =
        MosaicCacheKey(
            timeBucket = timeBucket,
            sectionKey = sectionKey,
            columnCount = columnCount,
            assetRevision = assetRevision,
            familiesKey = mosaicFamiliesKey(families)
        )

    private suspend fun getCachedOrLoadBucketAssets(timeBucket: String): List<Asset> =
        bucketAssetsCache[timeBucket] ?: getBucketAssetsUseCase(timeBucket).also { fetched ->
            bucketAssetsCache[timeBucket] = fetched
        }

    private fun shouldUseMosaicLayout(layoutSpec: MosaicLayoutSpec?, viewConfig: ViewConfig): Boolean =
        viewConfig.mosaicEnabled && layoutSpec != null

    private data class TimelinePageIndexCacheKey(
        val bucketKeys: List<TimelinePageBucketKey>
    )

    private data class TimelinePageBucketKey(
        val timeBucket: String,
        val count: Int,
        val assetRevision: Int,
        val materializationRevision: Int,
        val loaded: Boolean
    )

    private data class TimelineScrollbarDataCacheKey(
        val bucketLabels: List<String>,
        val pageIndex: TimelinePageIndex
    )

    private data class ScrollbarData(
        val yearMarkers: List<YearMarker> = emptyList(),
        val totalItemCount: Int = 0,
        val cumulativeItemCounts: List<Int> = emptyList(),
        val bucketDisplayLabels: List<String> = emptyList()
    )

    private fun computeScrollbarData(
        buckets: List<TimelineBucket>,
        pageIndex: TimelinePageIndex
    ): ScrollbarData {
        var cumulative = 0
        val cumulativeCounts = buckets.indices.map { index ->
            cumulative += pageIndex.bucketPageCounts.getOrElse(index) { buckets[index].count }
            cumulative
        }
        val totalItems = pageIndex.totalPages

        val markers = mutableListOf<YearMarker>()
        var lastYear = ""
        for (i in buckets.indices) {
            val year = buckets[i].displayLabel.substringAfterLast(" ", "")
            if (year != lastYear && year.isNotEmpty()) {
                val fraction = if (totalItems > 0) {
                    pageIndex.bucketStartPages.getOrElse(i) { 0 }.toFloat() / totalItems
                } else 0f
                markers.add(YearMarker(fraction.coerceIn(0f, 1f), year))
                lastYear = year
            }
        }

        return ScrollbarData(
            yearMarkers = markers,
            totalItemCount = totalItems,
            cumulativeItemCounts = cumulativeCounts,
            bucketDisplayLabels = buckets.map { it.displayLabel }
        )
    }

    private fun dayGroupsForBucket(
        timeBucket: String,
        assetRevision: Int,
        assets: List<Asset>
    ): List<DayGroup> {
        val key = DayGroupsCacheKey(timeBucket, assetRevision)
        return dayGroupsCache.getOrPut(key) {
            computeDayGroups(assets)
        }
    }

    private fun computeDayGroups(assets: List<Asset>): List<DayGroup> {
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
        val unparseable = grouped[null]?.size ?: 0
        if (unparseable > 0) {
            log.w { "$unparseable assets have unparseable createdAt dates" }
        }
        return grouped
            .filterKeys { it != null }
            .entries
            .sortedByDescending { it.key }
            .mapNotNull { (date, dayAssets) ->
                val d = date ?: return@mapNotNull null
                val monthName = d.month.name.lowercase()
                DayGroup(
                    label = "${d.dayOfMonth} $monthName ${d.year}",
                    assets = dayAssets.sortedByDescending { it.createdAt }
                )
            }
    }

    companion object {
        private const val PREFETCH_BUCKET_RADIUS = 2
        private const val CACHED_BUCKET_MATERIALIZATION_CHUNK_SIZE = 2
        private const val PROGRESSIVE_MOSAIC_FLUSH_DELAY_MS = 250L
        private const val TIMELINE_REBUILD_LOG_THRESHOLD_MS = 16L
    }
}
