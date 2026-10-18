package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.data.remote.TimelineApiCompatibilityException
import com.udnahc.immichgallery.data.repository.SessionOperationRejectedException
import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.data.repository.StaleSessionOperationException
import com.udnahc.immichgallery.domain.action.auth.DirectSessionOperationRunner
import com.udnahc.immichgallery.domain.action.auth.SessionOperationRunner
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.action.timeline.ClearTimelineMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.LoadBucketAssetsAction
import com.udnahc.immichgallery.domain.action.timeline.PrepareTimelineMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.RepairTimelineMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.SyncAllTimelineAssetsAction
import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncCoordinator
import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncEvent
import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncRequest
import com.udnahc.immichgallery.domain.action.timeline.TimelineManualSyncResult
import com.udnahc.immichgallery.domain.action.timeline.applyIfCurrentToTimelineOwner
import com.udnahc.immichgallery.domain.action.timeline.matchesActiveRequest
import com.udnahc.immichgallery.domain.action.timeline.requestId
import com.udnahc.immichgallery.domain.action.timeline.sessionStamp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetGroupDisplayLabel
import com.udnahc.immichgallery.domain.model.DEFAULT_GRID_COLUMN_COUNT
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
import com.udnahc.immichgallery.domain.model.MosaicAssignmentCheckpoint
import com.udnahc.immichgallery.domain.model.MosaicBandAssignment
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicDisplayItemRecord
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicLayoutSpec
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicSectionRequest
import com.udnahc.immichgallery.domain.model.MosaicSectionResult
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncCompatibilityException
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineDateTarget
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicProgressChunk
import com.udnahc.immichgallery.domain.model.TimelineMosaicRepairSectionInput
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.TimelineScrollTarget
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItems
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItemsForGeometryRanges
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItemsForHeight
import com.udnahc.immichgallery.domain.model.buildTimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.displayRecordsCoverOrderedAssets
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.mosaicFallbackRowHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecFor
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.mergeRetryResult
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.resolvedSectionDisplayRecordsOrEmpty
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.model.timelineAssetForDate
import com.udnahc.immichgallery.domain.model.timelineDatePickerYearRange
import com.udnahc.immichgallery.domain.model.timelineBucketForDate
import com.udnahc.immichgallery.domain.model.timelineScrollTargetForFraction
import com.udnahc.immichgallery.domain.model.timelineDaySections
import com.udnahc.immichgallery.domain.model.timelineDayDisplayLabel
import com.udnahc.immichgallery.domain.model.toPhotoGridDisplayItems
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineMosaicCacheStatusUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineMosaicSkeletonUseCase
import com.udnahc.immichgallery.runSuspendCatching
import com.udnahc.immichgallery.ui.component.SlideshowCandidateStatus
import com.udnahc.immichgallery.ui.model.ConnectionUiMessage
import com.udnahc.immichgallery.ui.util.MosaicConfigPreparationCoordinator
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import com.udnahc.immichgallery.ui.util.SyncActivityKey
import com.udnahc.immichgallery.ui.util.SyncActivityTracker
import com.udnahc.immichgallery.ui.util.TimelineMosaicDispatcherProvider
import com.udnahc.immichgallery.ui.util.TimelineManualSyncStarter
import com.udnahc.immichgallery.ui.util.TimelineMosaicQueueActor
import com.udnahc.immichgallery.ui.util.TimelineMosaicQueueCommand
import com.udnahc.immichgallery.ui.util.TimelineMosaicQueueEffect
import com.udnahc.immichgallery.ui.util.TimelineMosaicQueueRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDate
import org.lighthousegames.logging.logging
import kotlin.time.TimeSource
import com.udnahc.immichgallery.domain.model.MosaicSectionState as RuntimeMosaicState
import com.udnahc.immichgallery.domain.action.timeline.TimelineMosaicRepairRequiredException
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketSkeleton
import com.udnahc.immichgallery.domain.model.RowItemKind
import com.udnahc.immichgallery.domain.model.timelineMosaicCacheWidth
import com.udnahc.immichgallery.domain.model.timelineMosaicGroupingZoneId
import com.udnahc.immichgallery.domain.model.timelineDaySectionDisplayLabel

@Immutable
data class YearMarker(
    val fraction: Float,
    val year: String
)

@Immutable
data class DayGroup(
    val sectionKey: String,
    val label: AssetGroupDisplayLabel,
    val assets: List<Asset>
)

@Immutable
data class TimelineState(
    val groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    val isLoading: Boolean = false,
    val isSourceReady: Boolean = false,
    val error: String? = null,
    val displayItems: List<TimelineDisplayItem> = emptyList(),
    val displayIndex: TimelineDisplayIndex = TimelineDisplayIndex(),
    val sectionLabelsByKey: Map<String, AssetGroupDisplayLabel> = emptyMap(),
    val yearMarkers: List<YearMarker> = emptyList(),
    val totalItemCount: Int = 0,
    val cumulativeItemCounts: List<Int> = emptyList(),
    val bucketDisplayLabels: List<AssetGroupDisplayLabel> = emptyList(),
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
    val buckets: List<TimelineBucket> = emptyList(),
    val bucketStatuses: Map<String, SlideshowCandidateStatus> = emptyMap(),
    val assetRevisions: Map<String, Int> = emptyMap(),
    val materializationRevisions: Map<String, Int> = emptyMap()
)

enum class TimelineBucketTargetReason {
    VisibleScroll,
    ScrollSettled,
    ScrollbarDrag,
    ScrollbarStop,
    DateNavigation
}

private fun Throwable.timelineMessageOr(
    fallback: ConnectionUiMessage
): ConnectionUiMessage =
    if (this is TimelineApiCompatibilityException ||
        (this is TimelineAssetSyncCompatibilityException &&
            compatibilityCause is TimelineApiCompatibilityException)
    ) {
        ConnectionUiMessage.IncompatibleTimelineApi
    } else {
        fallback
    }

// Temporary local experiment: when true, a completed cold Timeline cache is
// refreshed only by explicit manual sync. Cached launch, visible/target loads,
// retry, and Mosaic settings preparation use Room cache only.
private const val DISABLE_TIMELINE_NON_MANUAL_SERVER_SYNC = true
private const val TIMELINE_MOSAIC_CONFIG_PREP_OWNER_KEY = "timeline"

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

internal fun timelineBucketsChanged(
    current: List<TimelineBucket>,
    next: List<TimelineBucket>
): Boolean =
    current != next

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

internal fun orderedSettledTimelineBucketIndexes(
    targetBucketIndex: Int?,
    visibleBucketIndexes: List<Int>,
    fallbackVisibleBucketIndexes: List<Int>,
    bucketCount: Int
): List<Int> {
    if (bucketCount <= 0) return emptyList()
    return buildList {
        targetBucketIndex?.takeIf { it in 0 until bucketCount }?.let(::add)
        val visible = visibleBucketIndexes.ifEmpty { fallbackVisibleBucketIndexes }
        visible.forEach { index ->
            if (index in 0 until bucketCount && index !in this) add(index)
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
        timeBucket !in materializingBuckets &&
                (timeBucket !in inMemoryBuckets || timeBucket !in loadedBuckets) &&
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
internal data class BucketData(
    val buckets: List<TimelineBucket> = emptyList(),
    val isSourceReady: Boolean = false,
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
    val materializationPublishOwners: Map<String, TimelineMaterializedBucketPublishOwner> = emptyMap(),
    // Content revisions are per bucket on purpose. Launch sync can refresh
    // every bucket successfully while changing none of them; a global revision
    // would make every packed row and Mosaic assignment look stale anyway.
    val assetRevisions: Map<String, Int> = emptyMap()
)

internal data class TimelineMaterializedBucketPublishOwner(
    val bucketGenerationStamp: TimelineRuntimeBucketGenerationStamp,
    val materializationRevision: Int
)

internal data class TimelineMaterializedBucketRollbackState(
    val wasCached: Boolean,
    val wasLoaded: Boolean,
    val wasFailed: Boolean,
    val materializationRevision: Int?,
    val previousPublishOwner: TimelineMaterializedBucketPublishOwner?,
    val publishedOwner: TimelineMaterializedBucketPublishOwner
)

private data class ProgressiveMosaicFlushRequest(
    val sequence: Long = 0L,
    val immediateSequence: Long = 0L
)

private data class TimelineMosaicRuntimeResetRequest(
    val completion: CompletableDeferred<Unit>,
    val mode: TimelineMosaicRuntimeResetMode
)

private data class TimelineMosaicFailurePublish(
    val sessionStamp: SessionOperationStamp,
    val timeBuckets: Set<String>,
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val families: Set<MosaicTemplateFamily>,
    val mosaicEnabled: Boolean,
    val cacheMosaicResults: Boolean,
    val geometryRequest: TimelineMosaicGeometryRequest?,
    val stamp: TimelineMosaicPublishStamp,
    val assetRevisions: Map<String, Int>
)

internal data class TimelineManualMosaicRuntimeOrigin(
    val requestId: Long,
    val generationSnapshot: TimelineRuntimeGenerationSnapshot,
    val assetRevisions: Map<String, Int>
) {
    fun isCurrentFor(
        timeBuckets: Set<String>,
        currentGenerationSnapshot: TimelineRuntimeGenerationSnapshot,
        currentAssetRevisions: Map<String, Int>
    ): Boolean =
        generationSnapshot.globalGeneration == currentGenerationSnapshot.globalGeneration &&
                timeBuckets.all { timeBucket ->
                    generationSnapshot.bucketGenerationStampFor(timeBucket) ==
                            currentGenerationSnapshot.bucketGenerationStampFor(timeBucket) &&
                            (assetRevisions[timeBucket] ?: 0) ==
                            (currentAssetRevisions[timeBucket] ?: 0)
                }
}

private data class TimelineManualMosaicPublicationOrigin(
    val stamp: TimelineMosaicPublishStamp,
    val assetRevisions: Map<String, Int>
)

private data class TimelineManualSyncStart(
    val request: TimelineManualSyncRequest,
    val mosaicRuntimeOrigin: TimelineManualMosaicRuntimeOrigin
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

internal fun BucketData.publishMaterializedBucketsWithStamps(
    publishStamps: Map<String, TimelineRuntimeBucketGenerationStamp>
): Pair<BucketData, Map<String, TimelineMaterializedBucketRollbackState>> {
    if (publishStamps.isEmpty()) return this to emptyMap()
    val timeBuckets = publishStamps.keys
    val nextMaterializationRevisions = materializationRevisions.toMutableMap()
    val publishOwners = publishStamps.mapValues { (timeBucket, stamp) ->
        val nextRevision = (materializationRevisions[timeBucket] ?: 0) + 1
        nextMaterializationRevisions[timeBucket] = nextRevision
        TimelineMaterializedBucketPublishOwner(
            bucketGenerationStamp = stamp,
            materializationRevision = nextRevision
        )
    }
    val rollbackStates = timeBuckets.associateWith { timeBucket ->
        TimelineMaterializedBucketRollbackState(
            wasCached = timeBucket in cachedBuckets,
            wasLoaded = timeBucket in loadedBuckets,
            wasFailed = timeBucket in failedBuckets,
            materializationRevision = materializationRevisions[timeBucket],
            previousPublishOwner = materializationPublishOwners[timeBucket],
            publishedOwner = publishOwners.getValue(timeBucket)
        )
    }
    val next = copy(
        cachedBuckets = cachedBuckets + timeBuckets,
        loadedBuckets = loadedBuckets + timeBuckets,
        failedBuckets = failedBuckets - timeBuckets,
        materializationRevisions = nextMaterializationRevisions,
        materializationPublishOwners = materializationPublishOwners + publishOwners
    )
    return next to rollbackStates
}

internal fun BucketData.rollbackMaterializedBucketsIfStillOwned(
    rollbackStates: Map<String, TimelineMaterializedBucketRollbackState>
): BucketData {
    val ownedStates = rollbackStates.filter { (timeBucket, rollbackState) ->
        materializationPublishOwners[timeBucket] == rollbackState.publishedOwner
    }
    if (ownedStates.isEmpty()) return this
    val nextCachedBuckets = cachedBuckets.toMutableSet()
    val nextLoadedBuckets = loadedBuckets.toMutableSet()
    val nextFailedBuckets = failedBuckets.toMutableSet()
    val nextMaterializationRevisions = materializationRevisions.toMutableMap()
    val nextMaterializationPublishOwners = materializationPublishOwners.toMutableMap()
    ownedStates.forEach { (timeBucket, rollbackState) ->
        if (rollbackState.wasCached) {
            nextCachedBuckets.add(timeBucket)
        } else {
            nextCachedBuckets.remove(timeBucket)
        }
        if (rollbackState.wasLoaded) {
            nextLoadedBuckets.add(timeBucket)
        } else {
            nextLoadedBuckets.remove(timeBucket)
        }
        if (rollbackState.wasFailed) {
            nextFailedBuckets.add(timeBucket)
        } else {
            nextFailedBuckets.remove(timeBucket)
        }
        val previousRevision = rollbackState.materializationRevision
        if (previousRevision == null) {
            nextMaterializationRevisions.remove(timeBucket)
        } else {
            nextMaterializationRevisions[timeBucket] = previousRevision
        }
        val previousOwner = rollbackState.previousPublishOwner
        if (previousOwner == null) {
            nextMaterializationPublishOwners.remove(timeBucket)
        } else {
            nextMaterializationPublishOwners[timeBucket] = previousOwner
        }
    }
    return copy(
        cachedBuckets = nextCachedBuckets,
        loadedBuckets = nextLoadedBuckets,
        failedBuckets = nextFailedBuckets,
        materializationRevisions = nextMaterializationRevisions,
        materializationPublishOwners = nextMaterializationPublishOwners
    )
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
    val mosaicColumnCount: Int = ViewConfig().mosaicColumnCount,
    val viewConfig: ViewConfig = ViewConfig()
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(
    private val getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
    private val getBucketAssetsUseCase: GetBucketAssetsUseCase,
    private val loadBucketAssetsAction: LoadBucketAssetsAction,
    private val syncAllTimelineAssetsAction: SyncAllTimelineAssetsAction,
    private val prepareTimelineMosaicCacheAction: PrepareTimelineMosaicCacheAction,
    private val repairTimelineMosaicCacheAction: RepairTimelineMosaicCacheAction,
    private val getTimelineMosaicCacheStatusUseCase: GetTimelineMosaicCacheStatusUseCase,
    private val getTimelineMosaicSkeletonUseCase: GetTimelineMosaicSkeletonUseCase,
    private val clearTimelineMosaicCacheAction: ClearTimelineMosaicCacheAction,
    private val overlayCoordinator: TimelineOverlayCoordinator,
    private val getTimelineGroupSizeUseCase: GetTimelineGroupSizeUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTimelineGroupSizeAction: SetTimelineGroupSizeAction,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction,
    private val timelineMosaicDispatcherProvider: TimelineMosaicDispatcherProvider,
    private val timelineManualSyncCoordinator: TimelineManualSyncCoordinator,
    private val timelineManualSyncStarter: TimelineManualSyncStarter,
    private val mosaicConfigPreparationCoordinator: MosaicConfigPreparationCoordinator,
    private val syncActivityTracker: SyncActivityTracker,
    private val sessionOperationRunner: SessionOperationRunner = DirectSessionOperationRunner
) : ViewModel() {

    val apiKey: String = overlayCoordinator.apiKey

    val overlayReturnTarget: TimelineOverlayReturnTarget?
        get() = overlayCoordinator.returnTarget

    fun rememberOverlayReturnTarget(
        assetId: String?,
        bucketTimeBucket: String?
    ): TimelineOverlayReturnTarget? =
        overlayCoordinator.rememberReturnTarget(assetId, bucketTimeBucket)

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        overlayCoordinator.getDetail(assetId)

    suspend fun getAssetStack(assetId: String) =
        overlayCoordinator.getStack(assetId)

    suspend fun getAssetFileName(
        assetId: String,
        fallback: String
    ): Result<String> =
        overlayCoordinator.getFileName(assetId, fallback)

    override fun onCleared() {
        super.onCleared()
        mosaicConfigPreparationCoordinator.unregister(TIMELINE_MOSAIC_CONFIG_PREP_OWNER_KEY)
        timelineMosaicQueue.close()
        timelineMosaicDispatcherProvider.close()
        mosaicPublishSignalBuffer.close()
        mosaicRepairSignalBuffer.close()
        deferredTimelineDrainSignal.close()
    }

    private val log = logging("TimelineViewModel")
    private val ownerSessionEpoch = sessionOperationRunner.ownerEpochSnapshot()
    private var syncJob: Job? = null
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope)
    private var visibleBucketIndexes: List<Int> = emptyList()
    private var lastVisibleBucketIndexes: List<Int> = emptyList()
    private var lastTargetBucketIndex: Int? = null
    private var lastLoggedZoomColumnCount: Int? = null
    private var hasSavedTargetRowHeight = false
    private var savedTargetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT
    private val runtimeStateStore = TimelineRuntimeStateStore()
    private val mosaicRenderEngine = MosaicRenderEngine()

    private val _bucketData = MutableStateFlow(BucketData())
    private val _uiConfig: MutableStateFlow<UiConfig>
    private val _mosaicStates =
        MutableStateFlow<Map<MosaicCacheKey, RuntimeMosaicState>>(emptyMap())
    private val _mosaicGeometryStates =
        MutableStateFlow<Map<MosaicCacheKey, TimelineMosaicSectionGeometry>>(emptyMap())
    private val _bucketGeometryStates = MutableStateFlow<Map<MosaicCacheKey, Float>>(emptyMap())
    private val _timelineSkeleton = MutableStateFlow(TimelineSkeletonState())
    private val repairRequiredMosaicKeys = MutableStateFlow<Set<MosaicCacheKey>>(emptySet())
    // Sections already recomputed once; a recompute derives geometry from its own items, so a repeat would loop.
    private val recomputedRepairMosaicKeys = MutableStateFlow<Set<MosaicCacheKey>>(emptySet())
    private val progressiveMosaicBuffer = TimelineProgressiveMosaicBuffer()
    private val progressiveMosaicFlushRequests = MutableStateFlow(ProgressiveMosaicFlushRequest())

    // Exposed directly (not through debounced pipeline) so UI sees it immediately
    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()
    private val _buildError = MutableStateFlow<ConnectionUiMessage?>(null)
    val buildError: StateFlow<ConnectionUiMessage?> = _buildError.asStateFlow()

    // Single owner for `timeBucket -> assets`, shared between the grid's
    // buildDisplayItems pipeline and the overlay's pager. Runtime producers
    // must capture a cache Origin before Room IO and publish only if the
    // runtime bucket generation is still current.
    private val bucketAssetsCache = TimelineBucketAssetsRuntimeCache()

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
    private val timelineRuntimePublicationMutex = Mutex()
    private val timelineMosaicResetMutex = Mutex()
    private val activeTimelineMosaicJobs = mutableMapOf<Long, Job>()
    private var nextTimelineMosaicResetId = 0L
    private val timelineMosaicResetRequests = mutableMapOf<Long, TimelineMosaicRuntimeResetRequest>()
    private val isTimelineScrollActive = MutableStateFlow(false)
    private var isLazyListScrollActive = false
    private var isScrollbarDragActive = false
    private var idleNeighborWarmupJob: Job? = null
    // Coalesces per-bucket publishMosaicUpdates calls so the state combine
    // fires once per drain cycle instead of once per bucket. The keyed signal
    // buffer keeps the newest value per section/repair key and one conflated
    // wakeup instead of one channel entry per publish.
    private val mosaicPublishSignalBuffer = TimelineMosaicPublishSignalBuffer()
    private val mosaicPublishBuffer = TimelineMosaicPublishBuffer()
    private val mosaicRepairSignalBuffer = TimelineMosaicRepairSignalBuffer()
    private val deferredTimelineDrainSignal = Channel<Unit>(Channel.CONFLATED)

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
    // Ready sections whose completed items disagree with their geometry ranges, collected during a build.
    private val pendingRepairRequiredMosaicKeys = mutableSetOf<MosaicCacheKey>()
    private var skeletonLoadJob: Job? = null
    private var skeletonLoadGeneration = 0L
    private var lastBuiltTimelineState: TimelineState? = null

    // Closed (false) until a coordinated bucket+section geometry read completes
    // for the current cached buckets. The mosaic queue's first request waits on
    // this gate so placeholders use exact per-band heights before bands arrive.
    private val timelineGeometryReady = MutableStateFlow(false)
    private var manualRefreshActive = false
    private var activeManualSyncRequestId: Long? = null
    private var activeManualMosaicRuntimeOrigin: TimelineManualMosaicRuntimeOrigin? = null

    val state: StateFlow<TimelineState>
    val overlayState: StateFlow<TimelineOverlayState>

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

        mosaicConfigPreparationCoordinator.register(TIMELINE_MOSAIC_CONFIG_PREP_OWNER_KEY) {
            cancelObsoleteTimelineMosaicWorkForConfigPrepare()
        }

        viewModelScope.launch(Dispatchers.Default) {
            getTimelineGroupSizeUseCase.observe().collect { saved ->
                val size = TimelineGroupSize.entries.find { it.apiValue == saved }
                    ?: TimelineGroupSize.MONTH
                val invalidated = applyTimelineMosaicConfigMutation { config ->
                    config.copy(groupSize = size)
                }
                if (invalidated) {
                    requestPersistedBucketGeometryForCachedBuckets()
                    requestVisibleTimelineMosaicCacheRead()
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            getViewConfigUseCase.observe().collect { saved ->
                val config = saved.normalized
                val invalidated = applyTimelineMosaicConfigMutation { current ->
                    current.copy(
                        viewConfig = config,
                        mosaicColumnCount = config.mosaicColumnCount
                    )
                }
                if (invalidated) {
                    requestPersistedBucketGeometryForCachedBuckets()
                    requestVisibleTimelineMosaicCacheRead()
                }
            }
        }

        // Bucket-load signals must propagate to displayItems IMMEDIATELY so
        // the grid has the dismissed asset's RowItem composed before the
        // shared-element exit animation starts. Layout changes (pinch-to-zoom
        // spamming targetRowHeight updates) stay debounced so we don't rebuild
        // on every intermediate frame. Combining them at the StateFlow level
        // means _bucketData updates bypass the debounce.
        val geometryInputs = combine(
            _mosaicGeometryStates,
            _bucketGeometryStates,
            _timelineSkeleton
        ) { mosaicGeometryStates, bucketGeometryStates, skeleton ->
            TimelineGeometryInputs(mosaicGeometryStates, bucketGeometryStates, skeleton)
        }
        @OptIn(FlowPreview::class)
        state = combine(
            _bucketData,
            _uiConfig.debounce(200),
            _mosaicStates,
            geometryInputs
        ) { data, config, mosaicStates, geometry ->
            buildTimelineState(
                data,
                config,
                mosaicStates,
                geometry
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineState(
                    groupSize = initialSize,
                    targetRowHeight = initialTargetRowHeight,
                    viewConfig = initialViewConfig
                )
            )

        overlayState = combine(state, _bucketData) { timeline, data ->
            TimelineOverlayState(pageIndex = timeline.pageIndex, buckets = timeline.buckets,
                bucketStatuses = timeline.buckets.associate { bucket ->
                    bucket.timeBucket to timelineSlideshowBucketStatus(
                        loading = bucket.timeBucket in data.loadingBuckets,
                        failed = bucket.timeBucket in data.failedBuckets,
                        cached = bucket.timeBucket in data.cachedBuckets || bucket.timeBucket in data.loadedBuckets,
                        cacheOnly = !allowTimelineServerRefresh(TimelineServerRefreshSource.ExplicitBucketLoad),
                        sourceReady = timeline.isSourceReady && !timeline.isSyncing
                    )
                }, assetRevisions = data.assetRevisions, materializationRevisions = data.materializationRevisions)
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineOverlayState())

        viewModelScope.launch(Dispatchers.Default) {
            for (effect in timelineMosaicQueue.effects) {
                handleTimelineMosaicQueueEffect(effect)
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val pending = mosaicPublishSignalBuffer.receiveBatch() ?: break
                pending.groupBy { it.sessionStamp }.values.forEach { sessionPending ->
                    runTimelineMosaicSessionSink(
                        sessionOperationRunner = sessionOperationRunner,
                        sessionStamp = sessionPending.first().sessionStamp
                    ) publication@{
                        mutateTimelineRuntime {
                            val generationSnapshot = runtimeStateStore.generationSnapshot()
                            val currentSessionPending = sessionPending.filter(::isCurrentMosaicPublishOrigin)
                            val publish = mosaicPublishBuffer.coalesceCurrent(
                                pending = currentSessionPending,
                                currentGlobalGeneration = generationSnapshot.globalGeneration,
                                currentBucketGenerations = generationSnapshot.bucketGenerations
                            ) ?: return@mutateTimelineRuntime
                            if (isTimelineScrollActive.value) {
                                runtimeStateStore.requeueMosaicPublishes(currentSessionPending)
                                requestDeferredTimelineDrainIfSettled()
                                return@mutateTimelineRuntime
                            }
                            if (publish.geometryUpdates.isNotEmpty()) {
                                _mosaicGeometryStates.update { states -> states + publish.geometryUpdates }
                            }
                            if (publish.stateUpdates.isNotEmpty()) {
                                _mosaicStates.update { states ->
                                    timelineMergeMosaicStateUpdatesPreservingReady(states, publish.stateUpdates)
                                }
                                progressiveMosaicBuffer.clearKeys(publish.stateUpdates.keys)
                            }
                            if (publish.geometryUpdates.isNotEmpty()) {
                                scheduleProgressiveMosaicFlush(immediate = true)
                            }
                            publish.repairRequests.forEach(mosaicRepairSignalBuffer::offer)
                        }
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val requests = mosaicRepairSignalBuffer.receiveBatch() ?: break
                processTimelineMosaicRepairBatch(requests)
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            while (deferredTimelineDrainSignal.receiveCatching().isSuccess) {
                drainDeferredTimelineUpdatesIfSettled()
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            runProgressiveMosaicFlushOwner()
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
                log.d { "Room emitted <redacted> buckets" }
                if (!timelineBucketsChanged(_bucketData.value.buckets, buckets)) {
                    _bucketData.update { data ->
                        if (data.isSourceReady) data else data.copy(isSourceReady = true)
                    }
                    log.d { "Timeline bucket emission ignored because metadata is unchanged" }
                    return@collect
                }
                val bucketIds = buckets.map { it.timeBucket }.toSet()
                _bucketData.update { data ->
                    data.copy(
                        buckets = buckets,
                        isSourceReady = true,
                        cachedBuckets = data.cachedBuckets.filter { it in bucketIds }.toSet(),
                        geometryReadyBuckets = data.geometryReadyBuckets.filter { it.timeBucket in bucketIds }
                            .toSet(),
                        loadedBuckets = data.loadedBuckets.filter { it in bucketIds }.toSet(),
                        loadingBuckets = data.loadingBuckets.filter { it in bucketIds }.toSet(),
                        failedBuckets = data.failedBuckets.filter { it in bucketIds }.toSet(),
                        materializationRevisions = data.materializationRevisions.filterKeys { it in bucketIds },
                        materializationPublishOwners = data.materializationPublishOwners
                            .filterKeys { it in bucketIds },
                        assetRevisions = data.assetRevisions.filterKeys { it in bucketIds }
                    )
                }
                requestVisibleTimelineMosaicCacheRead()
                requestPersistedBucketGeometryForCachedBuckets()
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            timelineManualSyncCoordinator.events.collect { event ->
                handleManualSyncEvent(event)
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
        val requestId = timelineManualSyncCoordinator.nextRequestId()
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val start = captureManualSyncStart(requestId)
            val request = start.request
            beginManualRefresh(request.requestId, start.mosaicRuntimeOrigin)
            _uiConfig.update {
                it.copy(
                    isSyncing = true,
                    bannerError = null,
                    bannerSuccess = null
                )
            }
            val startedByPlatform = timelineManualSyncStarter.start(request)
            if (!startedByPlatform) {
                timelineManualSyncCoordinator.run(request)
            }
        }
    }

    fun setGroupSize(size: TimelineGroupSize) {
        if (size == _uiConfig.value.groupSize) return
        setTimelineGroupSizeAction(size.apiValue)
        viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            val invalidated = applyTimelineMosaicConfigMutation { config ->
                config.copy(groupSize = size)
            }
            if (invalidated) {
                requestPersistedBucketGeometryForCachedBuckets()
                requestVisibleTimelineMosaicCacheRead()
            }
        }
    }

    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        if (normalized == _uiConfig.value.viewConfig) return
        setViewConfigAction(normalized)
        viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            val invalidated = applyTimelineMosaicConfigMutation { current ->
                current.copy(
                    viewConfig = normalized,
                    mosaicColumnCount = normalized.mosaicColumnCount
                )
            }
            if (invalidated) {
                requestPersistedBucketGeometryForCachedBuckets()
                requestVisibleTimelineMosaicCacheRead()
            }
        }
    }

    suspend fun prepareMosaicViewConfig(config: ViewConfig): Result<Unit> =
        withContext(Dispatchers.Default) {
            syncActivityTracker.track(SyncActivityKey.MosaicPreparation) {
                prepareMosaicViewConfigTracked(config)
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
        viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            val invalidated = applyTimelineMosaicConfigMutation { latest ->
                val bounds = rowHeightBoundsForViewport(viewportHeightDp)
                latest.copy(
                    availableWidth = widthDp,
                    availableHeight = viewportHeightDp,
                    rowHeightBounds = bounds,
                    targetRowHeight = targetRowHeightForConfig(widthDp, bounds),
                    mosaicColumnCount = mosaicColumnCount
                )
            }
            if (invalidated) {
                requestPersistedBucketGeometryForCachedBuckets()
                requestVisibleTimelineMosaicCacheRead()
            }
            log.d {
                "Timeline layout metrics updated widthChanged=<redacted> heightChanged=<redacted> " +
                        "columnChanged=<redacted> mosaicInvalidated=<redacted> elapsed=<redacted>"
            }
        }
    }

    fun setTargetRowHeight(height: Float) {
        val current = _uiConfig.value
        if (current.viewConfig.mosaicEnabled && current.viewConfig.disableZoomWhenMosaicEnabled) return
        val clamped = current.rowHeightBounds.clamp(height)
        if (clamped == current.targetRowHeight) return
        val previousLayoutSpec =
            mosaicLayoutSpecFor(current.availableWidth, current.targetRowHeight)
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
                "Timeline zoom columnCount=<redacted> targetRowHeight=<redacted> " +
                        "availableWidth=<redacted> effectiveMosaicEnabled=<redacted>"
            }
        }
        _uiConfig.update { it.copy(targetRowHeight = clamped) }
        if (previousColumnCount != nextColumnCount || previousUseMosaic != nextUseMosaic) {
            requestVisibleTimelineMosaicCacheRead()
        }
    }

    private fun targetRowHeightForConfig(
        availableWidth: Float,
        bounds: RowHeightBounds
    ): Float {
        val preferred = if (hasSavedTargetRowHeight) {
            savedTargetRowHeight
        } else {
            defaultTargetRowHeightForWidth(availableWidth)
        }
        return bounds.clamp(preferred)
    }

    fun loadBucketAssets(
        timeBucket: String,
        force: Boolean = false
    ) {
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
        log.d { "Timeline visible buckets changed reason=<redacted> buckets=<redacted>" }
        if (isTimelineScrollActive.value) return
        scheduleSettledTimelineWork()
    }

    fun onViewportBucketTargeted(
        bucketIndex: Int,
        reason: TimelineBucketTargetReason = TimelineBucketTargetReason.ScrollbarStop
    ) {
        val snapshot = state.value
        if (bucketIndex !in snapshot.buckets.indices) return
        lastTargetBucketIndex = bucketIndex
        log.d { "Timeline target bucket changed reason=<redacted> bucket=<redacted>" }
        if (isTimelineScrollActive.value) return
        scheduleSettledTimelineWork()
    }

    fun onScrollInProgressChanged(inProgress: Boolean) {
        if (inProgress == isLazyListScrollActive) return
        if (inProgress && !isScrollbarDragActive) {
            lastTargetBucketIndex = null
        }
        isLazyListScrollActive = inProgress
        updateTimelineInteractiveScrollState()
    }

    fun onScrollbarDragInProgressChanged(inProgress: Boolean) {
        if (inProgress == isScrollbarDragActive) return
        isScrollbarDragActive = inProgress
        updateTimelineInteractiveScrollState()
    }

    private fun updateTimelineInteractiveScrollState() {
        viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            var settledPriorityIndexes: List<Int> = emptyList()
            var appliedInProgress: Boolean? = null
            mutateTimelineRuntime {
                val latestInProgress = isLazyListScrollActive || isScrollbarDragActive
                if (latestInProgress == isTimelineScrollActive.value) return@mutateTimelineRuntime
                isTimelineScrollActive.value = latestInProgress
                appliedInProgress = latestInProgress
                if (latestInProgress) {
                    idleNeighborWarmupJob?.cancel()
                    idleNeighborWarmupJob = null
                    timelineMosaicQueue.send(TimelineMosaicQueueCommand.PauseForScroll)
                    log.d { "Timeline Mosaic work paused for active scroll" }
                } else {
                    settledPriorityIndexes = settledTimelineBucketIndexes()
                    val priorityBuckets = timelineBucketsForIndexes(settledPriorityIndexes)
                    deferredTimelineDrainSignal.trySend(Unit)
                    val loadedBuckets = priorityBuckets.filter { it in _bucketData.value.loadedBuckets }
                    timelineMosaicQueue.send(
                        TimelineMosaicQueueCommand.ResumeAfterScroll(
                            priorityBuckets = loadedBuckets,
                            sessionStamp = sessionOperationRunner.stampForOwner(ownerSessionEpoch)
                        )
                    )
                    log.d { "Timeline Mosaic work resumed after scroll priorityBuckets=<redacted>" }
                    scheduleProgressiveMosaicFlush(immediate = true)
                }
            }
            if (appliedInProgress == false && !isTimelineScrollActive.value) {
                scheduleSettledTimelineWork(settledPriorityIndexes)
            }
        }
    }

    private fun scheduleSettledTimelineWork(
        priorityIndexes: List<Int> = settledTimelineBucketIndexes()
    ) {
        if (priorityIndexes.isEmpty() || isTimelineScrollActive.value) return
        val priorityBuckets = timelineBucketsForIndexes(priorityIndexes)
        loadBucketIndexes(
            bucketIndexes = priorityIndexes,
            priority = if (lastTargetBucketIndex != null) {
                TimelineRefreshQueuePriority.ScrollbarStop
            } else {
                TimelineRefreshQueuePriority.Visible
            },
            source = if (lastTargetBucketIndex != null) {
                TimelineServerRefreshSource.Target
            } else {
                TimelineServerRefreshSource.Visible
            }
        )
        val loadedPriorityBuckets = priorityBuckets.filter { it in _bucketData.value.loadedBuckets }
        requestTimelineMosaicCacheReadForBuckets(
            timeBuckets = loadedPriorityBuckets,
            source = TimelineMosaicWorkSource.RenderDemand
        )
        scheduleProgressiveMosaicFlush(immediate = true)
        scheduleIdleNeighborWarmup(priorityIndexes)
    }

    private fun scheduleIdleNeighborWarmup(priorityIndexes: List<Int>) {
        idleNeighborWarmupJob?.cancel()
        idleNeighborWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            delay(TIMELINE_IDLE_NEIGHBOR_WARMUP_DELAY_MS)
            if (isTimelineScrollActive.value) return@launch
            val config = _uiConfig.value
            if (config.viewConfig.mosaicEnabled && !config.viewConfig.cacheMosaicResults) {
                warmCacheOffMosaicWindow(priorityIndexes, config)
                return@launch
            }
            val bucketCount = state.value.buckets.size
            val neighbors = priorityIndexes
                .flatMap(::bucketIndexesNearPriority)
                .filter { it in 0 until bucketCount && it !in priorityIndexes }
                .distinct()
            if (neighbors.isEmpty()) return@launch
            loadBucketIndexes(
                bucketIndexes = neighbors,
                priority = TimelineRefreshQueuePriority.Visible,
                source = TimelineServerRefreshSource.Visible
            )
        }
    }

    // Cache-off has no persisted geometry, so exact layout must be computed before buckets reach the viewport.
    private fun warmCacheOffMosaicWindow(priorityIndexes: List<Int>, config: UiConfig) {
        val snapshot = state.value
        val bucketHeights = timelineBucketDisplayHeights(snapshot.displayItems, snapshot.buckets.size)
        val window = selectCacheOffWarmupBucketIndexes(
            settledIndexes = priorityIndexes,
            bucketHeights = bucketHeights,
            viewportHeight = config.availableHeight,
            screensPerDirection = TIMELINE_CACHE_OFF_WARMUP_SCREENS,
            maxBucketsPerDirection = TIMELINE_CACHE_OFF_WARMUP_MAX_BUCKETS_PER_DIRECTION,
            isComputed = { index ->
                snapshot.buckets.getOrNull(index)?.timeBucket?.let(::isTimelineBucketMosaicComputed) == true
            }
        )
        if (window.isEmpty()) return
        val loadedBuckets = _bucketData.value.loadedBuckets
        val windowBuckets = window.mapNotNull { index -> snapshot.buckets.getOrNull(index)?.timeBucket }
        val (loaded, unloaded) = windowBuckets.partition { it in loadedBuckets && assetsForRender(it) != null }
        if (unloaded.isNotEmpty()) {
            loadBucketIndexes(
                bucketIndexes = window.filter { snapshot.buckets.getOrNull(it)?.timeBucket in unloaded },
                priority = TimelineRefreshQueuePriority.Visible,
                source = TimelineServerRefreshSource.Visible
            )
        }
        // Materialization only enqueues newly published buckets; loaded neighbors need explicit demand.
        requestTimelineMosaicCacheReadForBuckets(
            timeBuckets = loaded,
            source = TimelineMosaicWorkSource.RenderDemand
        )
        log.d { "Timeline cache-off Mosaic warmup window=<redacted> loaded=<redacted> unloaded=<redacted>" }
    }

    private fun isTimelineBucketMosaicComputed(timeBucket: String): Boolean {
        val config = _uiConfig.value
        val assets = assetsForRender(timeBucket) ?: return false
        if (assets.isEmpty()) return true
        val assetRevision = _bucketData.value.assetRevisionFor(timeBucket)
        return runtimeMosaicSections(timeBucket, config.groupSize, assetRevision, assets).all { section ->
            isCurrentRuntimeMosaicSection(
                mosaicCacheKey(
                    timeBucket = timeBucket,
                    sectionKey = section.sectionKey,
                    columnCount = config.mosaicColumnCount,
                    assetRevision = assetRevision,
                    families = config.viewConfig.mosaicFamilies
                )
            )
        }
    }

    private fun isCurrentRuntimeMosaicSection(key: MosaicCacheKey): Boolean =
        _mosaicStates.value[key] is RuntimeMosaicState.Ready &&
                _mosaicGeometryStates.value[key] != null &&
                key !in repairRequiredMosaicKeys.value

    private fun settledTimelineBucketIndexes(): List<Int> =
        orderedSettledTimelineBucketIndexes(
            targetBucketIndex = lastTargetBucketIndex,
            visibleBucketIndexes = visibleBucketIndexes,
            fallbackVisibleBucketIndexes = lastVisibleBucketIndexes,
            bucketCount = state.value.buckets.size
        )

    private fun timelineBucketsForIndexes(indexes: List<Int>): List<String> {
        val buckets = state.value.buckets
        return indexes.mapNotNull { index -> buckets.getOrNull(index)?.timeBucket }.distinct()
    }

    private suspend fun drainDeferredScrollMaterialization(priorityBuckets: Set<String>) {
        val pending = runtimeStateStore.drainDeferredMaterializedBuckets(priorityBuckets)
        pending.entries
            .groupBy { (_, stamp) -> stamp.sessionStamp }
            .forEach { (sessionStamp, entries) ->
                runTimelineMosaicSessionSink(
                    sessionOperationRunner = sessionOperationRunner,
                    sessionStamp = sessionStamp
                ) {
                    publishMaterializedCachedBuckets(
                        timeBuckets = entries.map { it.key }.toSet(),
                        expectedGenerations = entries.associate { it.key to it.value }
                    )
                }
            }
    }

    private fun requestDeferredTimelineDrainIfSettled() {
        if (!isTimelineScrollActive.value) {
            deferredTimelineDrainSignal.trySend(Unit)
        }
    }

    private suspend fun drainDeferredTimelineUpdatesIfSettled() {
        if (isTimelineScrollActive.value) return
        val priorityBuckets = timelineBucketsForIndexes(settledTimelineBucketIndexes()).toSet()
        drainDeferredScrollMaterialization(priorityBuckets)
        publishDeferredScrollMosaicUpdates(priorityBuckets)
        scheduleProgressiveMosaicFlush(immediate = true)
    }

    fun datePickerYearRange(today: LocalDate): IntRange =
        timelineDatePickerYearRange(state.value.buckets, today)

    suspend fun resolveDateTarget(date: LocalDate): Result<TimelineDateTarget?> =
        runSuspendCatching {
            val bucket = withContext(Dispatchers.Default) {
                timelineBucketForDate(state.value.buckets, date)
            } ?: return@runSuspendCatching null
            val assets = withContext(Dispatchers.IO) {
                assetsForRender(bucket.timeBucket) ?: getBucketAssetsUseCase(bucket.timeBucket)
            }
            withContext(Dispatchers.Default) {
                TimelineDateTarget(
                    timeBucket = bucket.timeBucket,
                    assetId = timelineAssetForDate(bucket.timeBucket, assets, date)?.id
                )
            }
        }

    fun scrollTargetForFraction(fraction: Float): TimelineScrollTarget? {
        val snapshot = state.value
        return timelineScrollTargetForFraction(snapshot.pageIndex, snapshot.displayIndex, fraction)
    }

    private fun advanceTimelineMosaicRuntimeGeneration(reason: String) {
        runtimeStateStore.advanceGlobalGeneration()
        log.d { "Timeline Mosaic runtime generation advanced reason=<redacted> generation=<redacted>" }
    }

    private fun advanceTimelineMosaicBucketGeneration(
        timeBucket: String,
        reason: String
    ) {
        runtimeStateStore.invalidateBucket(timeBucket)
        log.d {
            "Timeline Mosaic bucket generation advanced bucket=<redacted> reason=<redacted> " +
                    "generation=<redacted>"
        }
    }

    private suspend fun applyTimelineMosaicConfigMutation(
        transform: (UiConfig) -> UiConfig
    ): Boolean {
        val invalidated = mutateTimelineRuntime {
            var current: UiConfig
            var next: UiConfig
            do {
                current = _uiConfig.value
                next = transform(current)
                if (next == current) return@mutateTimelineRuntime false
            } while (!_uiConfig.compareAndSet(current, next))

            val invalidatesMosaic =
                activeMosaicConfigFor(current) != activeMosaicConfigFor(next) ||
                        timelineMosaicGeometryRequest(
                            current,
                            current.mosaicColumnCount
                        ) != timelineMosaicGeometryRequest(
                            next,
                            next.mosaicColumnCount
                        )
            if (!invalidatesMosaic) return@mutateTimelineRuntime false

            skeletonLoadJob?.cancel()
            skeletonLoadJob = null
            skeletonLoadGeneration++
            repairRequiredMosaicKeys.value = emptySet()
            recomputedRepairMosaicKeys.value = emptySet()
            advanceTimelineMosaicRuntimeGeneration("applied-config")
            progressiveMosaicBuffer.clearAll()
            _mosaicStates.value = emptyMap()
            _mosaicGeometryStates.value = emptyMap()
            _bucketGeometryStates.value = emptyMap()
            _bucketData.update { data ->
                data.copy(geometryReadyBuckets = emptySet())
            }
            timelineGeometryReady.value = timelineSkeletonIdentity(next) == null
            true
        }
        if (invalidated) {
            while (true) {
                try {
                    beginTimelineMosaicRuntimeReset(TimelineMosaicRuntimeResetMode.ClearPublished)
                    break
                } catch (_: StaleSessionOperationException) {
                    // A failed session mutation keeps this owner mounted. Wait
                    // for admission to reopen and retry the reset for the
                    // already-applied config instead of stranding placeholders.
                } catch (_: SessionOperationRejectedException) {
                    // The reset raced closed admission before it registered.
                }
                val admittedStamp = sessionOperationRunner.awaitAdmittedStamp()
                if (admittedStamp.ownerEpoch != ownerSessionEpoch) return false
            }
        }
        return invalidated
    }

    private suspend fun cancelObsoleteTimelineMosaicWorkForConfigPrepare() {
        beginTimelineMosaicRuntimeReset(TimelineMosaicRuntimeResetMode.PreservePublished)
    }

    private suspend fun beginTimelineMosaicRuntimeReset(mode: TimelineMosaicRuntimeResetMode) {
        val completion = CompletableDeferred<Unit>()
        timelineMosaicResetMutex.withLock {
            val next = nextTimelineMosaicResetId + 1L
            nextTimelineMosaicResetId = next
            timelineMosaicResetRequests[next] = TimelineMosaicRuntimeResetRequest(
                completion = completion,
                mode = mode
            )
            timelineMosaicQueue.send(TimelineMosaicQueueCommand.BeginReset(next))
        }
        completion.await()
    }

    private suspend fun completeTimelineMosaicRuntimeReset(resetId: Long) {
        val resetMode = timelineMosaicResetMutex.withLock {
            timelineMosaicResetRequests[resetId]?.mode
        } ?: TimelineMosaicRuntimeResetMode.PreservePublished
        val resetResult = runIsolatedTimelineRuntimeReset {
            sessionOperationRunner.runForOwner(ownerSessionEpoch) {
                mutateTimelineRuntime {
                    advanceTimelineMosaicRuntimeGeneration("progressive-clear")
                    progressiveMosaicBuffer.clearAll()
                    _mosaicStates.update { states ->
                        timelineMosaicStatesAfterReset(states, resetMode)
                    }
                    if (resetMode == TimelineMosaicRuntimeResetMode.ClearPublished) {
                        _mosaicGeometryStates.value = emptyMap()
                        _bucketGeometryStates.value = emptyMap()
                        _bucketData.update { data ->
                            data.copy(geometryReadyBuckets = emptySet())
                        }
                    }
                }
            }
        }
        resetResult.onFailure { error ->
            if (error !is CancellationException) {
                log.e { "Timeline Mosaic runtime reset failed" }
            }
        }
        timelineMosaicQueue.send(TimelineMosaicQueueCommand.EndReset(resetId))
        val (resetRequest, staleRequests) = timelineMosaicResetMutex.withLock {
            val request = timelineMosaicResetRequests.remove(resetId)
            val stale = timelineMosaicResetRequests.keys
                .filter { it < resetId }
                .mapNotNull(timelineMosaicResetRequests::remove)
            request to stale
        }
        staleRequests.forEach { it.completion.complete(Unit) }
        resetRequest?.completion?.let { completion ->
            resetResult.fold(
                onSuccess = { completion.complete(Unit) },
                onFailure = { error -> completion.completeExceptionally(error) }
            )
        }
    }

    private suspend fun <T> mutateTimelineRuntime(block: suspend () -> T): T =
        timelineRuntimePublicationMutex.withLock {
            block()
        }

    private suspend fun <T> commitTimelineRuntime(
        requireSettled: Boolean = true,
        isCurrent: suspend () -> Boolean = { true },
        block: suspend () -> T
    ): T? =
        timelineRuntimePublicationMutex.withLock {
            if (requireSettled && isTimelineScrollActive.value) {
                null
            } else if (!isCurrent()) {
                null
            } else {
                block()
            }
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

    fun retryBucket(timeBucket: String) {
        viewModelScope.launch(Dispatchers.IO) {
            log.d { "Retrying bucket: <redacted>" }
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
    fun getDisplayItemIndexForReturn(
        assetId: String?,
        bucketTimeBucket: String?
    ): Int? {
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
                val assets = assetsForRender(bucket.timeBucket)
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

    private suspend fun captureManualSyncStart(requestId: Long): TimelineManualSyncStart =
        mutateTimelineRuntime {
            val snapshot = _uiConfig.value
            val geometryRequest =
                if (snapshot.viewConfig.mosaicEnabled && snapshot.viewConfig.cacheMosaicResults) {
                    timelineMosaicGeometryRequest(snapshot, snapshot.mosaicColumnCount)
                } else {
                    null
                }
            TimelineManualSyncStart(
                request = TimelineManualSyncRequest(
                    requestId = requestId,
                    sessionStamp = sessionOperationRunner.stampForOwner(ownerSessionEpoch),
                    groupSize = snapshot.groupSize,
                    columnCount = snapshot.mosaicColumnCount,
                    families = snapshot.viewConfig.mosaicFamilies,
                    mosaicEnabled = snapshot.viewConfig.mosaicEnabled,
                    cacheMosaicResults = snapshot.viewConfig.cacheMosaicResults,
                    geometryRequest = geometryRequest,
                    hadBannerError = snapshot.bannerError != null
                ),
                mosaicRuntimeOrigin = TimelineManualMosaicRuntimeOrigin(
                    requestId = requestId,
                    generationSnapshot = runtimeStateStore.generationSnapshot(),
                    assetRevisions = _bucketData.value.assetRevisions
                )
            )
        }

    private suspend fun handleManualSyncEvent(event: TimelineManualSyncEvent) {
        if (!isActiveManualSyncRequest(event)) return
        event.applyIfCurrentToTimelineOwner(
            sessionOperationRunner = sessionOperationRunner,
            ownerEpoch = ownerSessionEpoch
        ) applyEvent@{
            if (!isActiveManualSyncRequest(event)) return@applyEvent
            applyManualSyncEvent(event)
        }
    }

    private suspend fun applyManualSyncEvent(event: TimelineManualSyncEvent) {
        when (event) {
            is TimelineManualSyncEvent.Started -> {
                updateActiveManualSyncRequest(event.requestId) {
                    it.copy(isSyncing = true, bannerError = null, bannerSuccess = null)
                }
            }

            is TimelineManualSyncEvent.Completed -> {
                handleManualSyncCompleted(event.result)
            }

            is TimelineManualSyncEvent.Failed -> {
                log.e { "Manual Timeline sync failed" }
                publishPartialAssetSyncFailure(event.cause)
                val completed = completeActiveManualSyncRequest(event.requestId) {
                    it.copy(
                        isSyncing = false,
                        bannerError = event.cause.timelineMessageOr(ConnectionUiMessage.CannotConnectToServer)
                    )
                }
                if (completed) enqueueCurrentVisibleBucketRefreshes()
            }

            is TimelineManualSyncEvent.SessionCancelled -> {
                val completed = completeActiveManualSyncRequest(event.requestId) {
                    it.copy(isSyncing = false)
                }
                if (completed) enqueueCurrentVisibleBucketRefreshes()
            }
        }
    }

    private suspend fun handleManualSyncCompleted(result: TimelineManualSyncResult) {
        val assetResult = result.assetSyncResult
        val changedSuccessfulBucketIds =
            assetResult.changedBucketIds intersect assetResult.successfulBucketIds
        val mosaicResult = result.mosaicPrecomputeResult
        val mosaicBuckets = mosaicResult?.let { precomputeResult ->
            precomputeResult.successfulBucketIds + precomputeResult.failedBucketIds
        }.orEmpty()
        val manualMosaicRuntimeOrigin =
            if (mosaicBuckets.isEmpty()) null else {
                manualMosaicRuntimeOriginFor(result.request.requestId)
            }
        val bucketResult = result.bucketSyncResult
        rememberBucketMetadataChanges(
            bucketResult.staleBucketIds,
            bucketResult.removedBucketIds
        )
        invalidateRemovedBuckets(bucketResult.removedBucketIds)
        val mosaicPublicationOrigin = publishAssetSyncResult(
            successfulBucketIds = assetResult.successfulBucketIds,
            failedBucketIds = assetResult.failedBucketIds,
            changedBucketIds = assetResult.changedBucketIds,
            manualMosaicRuntimeOrigin = manualMosaicRuntimeOrigin,
            manualMosaicBuckets = mosaicBuckets
        )
        assetResult.successfulBucketIds.forEach { timeBucket ->
            markBucketServerRefreshed(timeBucket)
        }
        if (mosaicResult != null && result.request.geometryRequest != null) {
            if (mosaicPublicationOrigin == null) {
                requestTimelineMosaicCacheReadForBuckets(
                    timeBuckets = changedSuccessfulBucketIds,
                    source = TimelineMosaicWorkSource.RenderDemand,
                    sessionStamp = result.request.sessionStamp
                )
            } else {
                publishBucketGeometrySummaries(
                    summaries = mosaicResult.bucketGeometrySummaries,
                    groupSize = result.request.groupSize,
                    columnCount = result.request.columnCount,
                    families = result.request.families,
                    geometryRequest = result.request.geometryRequest,
                    originStamp = mosaicPublicationOrigin.stamp,
                    originConfig = ActiveMosaicConfig(
                        groupSize = result.request.groupSize,
                        columnCount = result.request.columnCount,
                        families = result.request.families,
                        mosaicEnabled = result.request.mosaicEnabled,
                        cacheMosaicResults = result.request.cacheMosaicResults
                    ),
                    originAssetRevisions = mosaicPublicationOrigin.assetRevisions,
                    sessionStamp = result.request.sessionStamp
                )
                requestTimelineMosaicCacheReadForBuckets(
                    timeBuckets = changedSuccessfulBucketIds,
                    source = TimelineMosaicWorkSource.SyncPrecompute,
                    sessionStamp = result.request.sessionStamp
                )
                failurePublishFor(
                    timeBuckets = mosaicResult.failedBucketIds,
                    sessionStamp = result.request.sessionStamp,
                    groupSize = result.request.groupSize,
                    columnCount = result.request.columnCount,
                    families = result.request.families,
                    mosaicEnabled = result.request.mosaicEnabled,
                    cacheMosaicResults = result.request.cacheMosaicResults,
                    geometryRequest = result.request.geometryRequest,
                    stamp = mosaicPublicationOrigin.stamp,
                    revisions = mosaicPublicationOrigin.assetRevisions
                )?.let { markTimelineMosaicFailedForBuckets(it) }
            }
        }
        val failed = assetResult.failedBucketIds.isNotEmpty()
        val completed = completeActiveManualSyncRequest(result.request.requestId) {
            it.copy(
                isSyncing = false,
                bannerError = if (failed) ConnectionUiMessage.CannotConnectToServer else null,
                bannerSuccess = if (!failed && result.request.hadBannerError) {
                    ConnectionUiMessage.ConnectedToServer
                } else {
                    null
                }
            )
        }
        if (!completed) return
        log.d {
            "Timeline manual refresh finished successful=<redacted> " +
                    "failed=<redacted> changed=<redacted>"
        }
        enqueueCurrentVisibleBucketRefreshes()
    }

    private suspend fun prepareMosaicViewConfigTracked(config: ViewConfig): Result<Unit> =
        sessionOperationRunner.runForOwner(ownerSessionEpoch) {
            prepareMosaicViewConfigTrackedForSession(config)
        }

    private suspend fun prepareMosaicViewConfigTrackedForSession(config: ViewConfig): Result<Unit> {
        val normalized = config.normalized
        if (!normalized.cacheMosaicResults || !normalized.mosaicEnabled) return Result.success(Unit)
        val snapshot = _uiConfig.value
        val data = _bucketData.value
        val buckets = data.buckets
        if (buckets.isEmpty()) return Result.success(Unit)
        mosaicConfigPreparationCoordinator.cancelObsoleteWork()
        val geometryRequest = timelineMosaicGeometryRequest(
            availableWidth = snapshot.availableWidth,
            maxRowHeight = snapshot.rowHeightBounds.max,
            columnCount = normalized.mosaicColumnCount
        )
            ?: return Result.failure(IllegalStateException("Timeline geometry metrics are not ready"))
        log.d {
            "Blocking Timeline Mosaic prepare started buckets=<redacted> " +
                    "group=<redacted> columns=<redacted> " +
                    "families=<redacted>"
        }
        beginManualRefresh()
        try {
            val assetResult =
                if (allowTimelineServerRefresh(TimelineServerRefreshSource.BlockingMosaicPrepare)) {
                    syncAllTimelineAssetsWithRetry(buckets, context = "blocking-mosaic-prepare")
                        .getOrElse { return Result.failure(it) }
                } else {
                    log.d { "Blocking Timeline Mosaic prepare using cached bucket assets; server sync disabled by experiment" }
                    TimelineAssetSyncResult(
                        successfulBucketIds = buckets.map { it.timeBucket }.toSet(),
                        failedBucketIds = emptySet()
                    )
                }
            if (assetResult.failedBucketIds.isNotEmpty()) {
                return Result.failure(
                    IllegalStateException(
                        "Failed to sync Timeline buckets=${assetResult.failedBucketIds.size}"
                    )
                )
            }
            val changedSuccessfulBucketIds =
                assetResult.changedBucketIds intersect assetResult.successfulBucketIds
            val precomputeResult = prepareTimelineMosaicCacheAction(
                timeBuckets = assetResult.successfulBucketIds,
                groupSize = snapshot.groupSize,
                columnCount = normalized.mosaicColumnCount,
                families = normalized.mosaicFamilies,
                geometryRequest = geometryRequest,
                cacheDisplayResults = true
            ).getOrElse { return Result.failure(it) }
            if (precomputeResult.failedBucketIds.isNotEmpty()) {
                return Result.failure(
                    IllegalStateException(
                        "Failed to prepare Timeline Mosaic buckets=${precomputeResult.failedBucketIds.size}"
                    )
                )
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
                "Blocking Timeline Mosaic prepare completed buckets=<redacted>"
            }
            return Result.success(Unit)
        } finally {
            endManualRefresh()
        }
    }

    private fun syncFromServer(isFullRefresh: Boolean = false) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            sessionOperationRunner.runStampedForOwner(
                ownerSessionEpoch
            ) sessionOperation@{ sessionStamp ->
                syncActivityTracker.begin(SyncActivityKey.Timeline)
                try {
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
                    "Timeline sync started mode=<redacted> hasCachedBuckets=<redacted> " +
                            "hasCompletedColdSync=<redacted> isFullRefresh=<redacted>"
                }

                if (isColdSync) {
                    _isBuilding.value = true
                    _buildError.value = null
                    log.d { "Timeline cold sync waiting for full-screen metrics before server sync" }
                    val metrics = _uiConfig
                        .filter { it.availableWidth > 0f }
                        .first()
                    log.d {
                        "Timeline cold sync metrics ready width=<redacted> " +
                                "height=<redacted> targetRowHeight=<redacted> " +
                                "mosaicColumns=<redacted>"
                    }
                } else {
                    _uiConfig.update {
                        it.copy(
                            isSyncing = true,
                            bannerError = null,
                            bannerSuccess = null
                        )
                    }
                }

                val lastSync = getTimelineBucketsUseCase.getLastSyncedAt()
                _uiConfig.update { it.copy(lastSyncedAt = lastSync) }
                log.d { "Timeline sync lastSyncedAt=<redacted> mode=<redacted>" }

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
                    return@sessionOperation
                }

                if (isCachedManualRefresh) {
                    log.d { "Timeline manual refresh waiting for visible refresh queue to pause" }
                    beginManualRefresh()
                }
                try {
                    log.d { "Timeline bucket metadata sync started mode=<redacted>" }
                    getTimelineBucketsUseCase.sync().fold(
                        onSuccess = { syncResult ->
                            val buckets = syncResult.buckets
                            log.d {
                                "Timeline bucket metadata sync completed mode=<redacted> buckets=<redacted> " +
                                        "stale=<redacted> removed=<redacted>"
                            }
                            rememberBucketMetadataChanges(
                                syncResult.staleBucketIds,
                                syncResult.removedBucketIds
                            )
                            invalidateRemovedBuckets(
                                syncResult.removedBucketIds,
                                sessionStamp
                            )
                            clearTimelineMosaicCacheAction.buckets(syncResult.removedBucketIds)

                            if (isColdSync) {
                                _bucketData.update { it.copy(buckets = buckets) }
                                log.d { "Timeline cold asset sync started buckets=<redacted>" }
                                val coldConfig = _uiConfig.value
                                val shouldPrepareMosaic = coldConfig.viewConfig.mosaicEnabled &&
                                        coldConfig.viewConfig.cacheMosaicResults
                                val geometryRequest = if (shouldPrepareMosaic) {
                                    timelineMosaicGeometryRequest(
                                        coldConfig,
                                        coldConfig.mosaicColumnCount
                                    )
                                } else {
                                    null
                                }
                                if (shouldPrepareMosaic && geometryRequest == null) {
                                    log.e { "Timeline cold sync cannot start without Mosaic geometry request" }
                                    _isBuilding.value = false
                                    _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                    _uiConfig.update { it.copy(isSyncing = false) }
                                } else {
                                    syncAllTimelineAssetsWithRetry(
                                        buckets,
                                        context = "cold-sync"
                                    ).fold(
                                        onSuccess = coldAssetSuccess@{ assetResult ->
                                            log.d {
                                                "Timeline cold asset sync completed successful=<redacted> " +
                                                        "failed=<redacted> changed=<redacted>"
                                            }
                                            if (assetResult.failedBucketIds.isNotEmpty()) {
                                                _isBuilding.value = false
                                                _buildError.value =
                                                    ConnectionUiMessage.NoConnectionToServer
                                                _uiConfig.update { it.copy(isSyncing = false) }
                                                log.d {
                                                    "Timeline cold sync finished with failed buckets=<redacted> " +
                                                            "after retry"
                                                }
                                            } else {
                                                if (!shouldPrepareMosaic) {
                                                    publishColdSyncAssetsOnly(assetResult.successfulBucketIds)
                                                    try {
                                                        getTimelineBucketsUseCase.markColdTimelineSyncComplete()
                                                        assetResult.successfulBucketIds.forEach {
                                                            markBucketServerRefreshed(
                                                                it
                                                            )
                                                        }
                                                        _isBuilding.value = false
                                                        _buildError.value = null
                                                        _uiConfig.update { it.copy(isSyncing = false) }
                                                        requestVisibleTimelineMosaicCacheRead(
                                                            sessionStamp
                                                        )
                                                        log.d {
                                                            "Timeline cold sync finished without Mosaic cache prepare buckets=<redacted>"
                                                        }
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        log.e { "Failed to mark Timeline cold sync complete" }
                                                        _isBuilding.value = false
                                                        _buildError.value =
                                                            ConnectionUiMessage.NoConnectionToServer
                                                        _uiConfig.update { it.copy(isSyncing = false) }
                                                    }
                                                    return@coldAssetSuccess
                                                }
                                                val requiredGeometryRequest =
                                                    geometryRequest ?: run {
                                                        log.e { "Timeline cold sync lost Mosaic geometry request" }
                                                        _isBuilding.value = false
                                                        _buildError.value =
                                                            ConnectionUiMessage.NoConnectionToServer
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
                                                        val geometryReadyIds =
                                                            mosaicResult.bucketGeometrySummaries.map { it.timeBucket }
                                                                .toSet()
                                                        val missingGeometryCount =
                                                            mosaicResult.successfulBucketIds.count { it !in geometryReadyIds }
                                                        if (mosaicResult.failedBucketIds.isNotEmpty() || missingGeometryCount > 0) {
                                                            _isBuilding.value = false
                                                            _buildError.value =
                                                                ConnectionUiMessage.NoConnectionToServer
                                                            _uiConfig.update { it.copy(isSyncing = false) }
                                                            log.d {
                                                                "Timeline cold sync finished with mosaicFailed=<redacted> " +
                                                                        "missingGeometry=<redacted>"
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
                                                            assetResult.successfulBucketIds.forEach {
                                                                markBucketServerRefreshed(
                                                                    it
                                                                )
                                                            }
                                                            _isBuilding.value = false
                                                            _buildError.value = null
                                                            _uiConfig.update { it.copy(isSyncing = false) }
                                                            log.d { "Timeline cold sync finished successfully buckets=<redacted>" }
                                                        } catch (e: CancellationException) {
                                                            throw e
                                                        } catch (e: Exception) {
                                                            log.e { "Failed to mark Timeline cold sync complete" }
                                                            _isBuilding.value = false
                                                            _buildError.value =
                                                                ConnectionUiMessage.NoConnectionToServer
                                                            _uiConfig.update { it.copy(isSyncing = false) }
                                                        }
                                                    },
                                                    onFailure = { e ->
                                                        log.e { "Failed cold Timeline Mosaic prepare" }
                                                        _isBuilding.value = false
                                                        _buildError.value =
                                                            ConnectionUiMessage.NoConnectionToServer
                                                        _uiConfig.update { it.copy(isSyncing = false) }
                                                    }
                                                )
                                            }
                                        },
                                        onFailure = { e ->
                                            log.e { "Failed full first timeline asset sync" }
                                            _isBuilding.value = false
                                            _buildError.value = e.timelineMessageOr(
                                                ConnectionUiMessage.NoConnectionToServer
                                            )
                                            _uiConfig.update { it.copy(isSyncing = false) }
                                            log.d { "Timeline cold sync finished with asset-sync failure" }
                                        }
                                    )
                                }
                            } else if (isFullRefresh) {
                                log.d { "Timeline manual refresh asset sync starting buckets=<redacted>" }
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
                            log.e { "Failed to sync buckets from server" }
                            if (isColdSync) {
                                _isBuilding.value = false
                                _buildError.value = e.timelineMessageOr(
                                    ConnectionUiMessage.NoConnectionToServer
                                )
                                log.d { "Timeline cold sync finished with bucket metadata failure" }
                            } else {
                                _uiConfig.update {
                                    it.copy(
                                        isSyncing = false,
                                        bannerError = e.timelineMessageOr(
                                            ConnectionUiMessage.CannotConnectToServer
                                        )
                                    )
                                }
                                log.d { "Timeline cached sync finished with bucket metadata failure mode=<redacted>" }
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
                } finally {
                    syncActivityTracker.end(SyncActivityKey.Timeline)
                }
            }
        }
    }

    private suspend fun beginManualRefresh(
        requestId: Long? = null,
        mosaicRuntimeOrigin: TimelineManualMosaicRuntimeOrigin? = null
    ) {
        refreshQueueMutex.withLock {
            manualRefreshActive = true
            if (requestId != null) {
                activeManualSyncRequestId = requestId
                activeManualMosaicRuntimeOrigin = mosaicRuntimeOrigin
            }
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

    private suspend fun isActiveManualSyncRequest(event: TimelineManualSyncEvent): Boolean =
        refreshQueueMutex.withLock {
            event.matchesActiveRequest(activeManualSyncRequestId)
        }

    private suspend fun manualMosaicRuntimeOriginFor(
        requestId: Long
    ): TimelineManualMosaicRuntimeOrigin? = refreshQueueMutex.withLock {
        activeManualMosaicRuntimeOrigin?.takeIf { it.requestId == requestId }
    }

    private suspend fun updateActiveManualSyncRequest(
        requestId: Long,
        transform: (UiConfig) -> UiConfig
    ): Boolean = refreshQueueMutex.withLock {
        if (activeManualSyncRequestId != requestId) return@withLock false
        _uiConfig.update(transform)
        true
    }

    private suspend fun completeActiveManualSyncRequest(
        requestId: Long,
        transform: (UiConfig) -> UiConfig
    ): Boolean = refreshQueueMutex.withLock {
        if (activeManualSyncRequestId != requestId) return@withLock false
        _uiConfig.update(transform)
        activeManualSyncRequestId = null
        activeManualMosaicRuntimeOrigin = null
        manualRefreshActive = false
        true
    }

    private suspend fun syncAllTimelineAssetsWithRetry(
        buckets: List<TimelineBucket>,
        context: String
    ): Result<TimelineAssetSyncResult> {
        val first = syncAllTimelineAssetsAction(buckets).getOrElse { error ->
            publishPartialAssetSyncFailure(error)
            return Result.failure(error)
        }
        if (first.failedBucketIds.isEmpty()) return Result.success(first)
        val retryBuckets = buckets.filter { it.timeBucket in first.failedBucketIds }
        log.d { "Timeline asset sync retry context=<redacted> buckets=<redacted>" }
        val retry = syncAllTimelineAssetsAction(retryBuckets).getOrElse { error ->
            val compatibilityFailure = error as? TimelineAssetSyncCompatibilityException
            if (compatibilityFailure != null) {
                val combined = first.mergeRetryResult(compatibilityFailure.partialResult)
                publishAssetSyncResult(
                    successfulBucketIds = combined.successfulBucketIds,
                    failedBucketIds = combined.failedBucketIds,
                    changedBucketIds = combined.changedBucketIds
                )
                return Result.failure(compatibilityFailure.withPartialResult(combined))
            }
            return Result.failure(error)
        }
        return Result.success(first.mergeRetryResult(retry))
    }

    private suspend fun publishPartialAssetSyncFailure(error: Throwable) {
        val partialResult =
            (error as? TimelineAssetSyncCompatibilityException)?.partialResult ?: return
        publishAssetSyncResult(
            successfulBucketIds = partialResult.successfulBucketIds,
            failedBucketIds = partialResult.failedBucketIds,
            changedBucketIds = partialResult.changedBucketIds
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
            mutateTimelineRuntime {
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
                        "source=<redacted> buckets=<redacted>"
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
                        "source=<redacted> bucket=<redacted> materializedCachedRows=<redacted>"
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
        if (timeBucket in _bucketData.value.loadedBuckets && hasCurrentBucketAssetsCache(timeBucket)) {
            return true
        }
        return timeBucket in materializeCachedBucketsIfAvailable(listOf(timeBucket))
    }

    fun assetsForRender(timeBucket: String): List<Asset>? =
        bucketAssetsCache.assetsForRender(timeBucket, ::currentBucketAssetGeneration)

    private fun currentBucketAssetGeneration(timeBucket: String): TimelineRuntimeBucketGenerationStamp =
        runtimeStateStore.generationSnapshot().bucketGenerationStampFor(timeBucket)

    private suspend fun removeBucketAssetsCache(timeBucket: String) {
        bucketAssetsCache.invalidate(timeBucket, currentBucketAssetGeneration(timeBucket))
    }

    private suspend fun getOrLoadBucketAssetsCache(
        timeBucket: String,
        expectedGeneration: TimelineRuntimeBucketGenerationStamp? = null
    ): List<Asset>? {
        val generation = expectedGeneration ?: currentBucketAssetGeneration(timeBucket)
        val origin = cacheOriginIfCurrent(timeBucket, generation) ?: return null
        bucketAssetsCache.getCurrent(timeBucket, generation)?.let { return it }
        val fetched = getBucketAssetsUseCase(timeBucket)
        return bucketAssetsCache.putIfCurrent(origin, fetched)?.installedEntry?.assets
            ?: bucketAssetsCache.getCurrent(timeBucket, generation)
    }

    private suspend fun cacheOriginIfCurrent(
        timeBucket: String,
        expectedGeneration: TimelineRuntimeBucketGenerationStamp
    ): TimelineBucketAssetsRuntimeCache.Origin? =
        mutateTimelineRuntime {
            if (!runtimeStateStore.generationSnapshot().matches(expectedGeneration, timeBucket)) {
                return@mutateTimelineRuntime null
            }
            bucketAssetsCache.acceptCurrentGeneration(timeBucket, expectedGeneration)
            bucketAssetsCache.originFor(timeBucket, expectedGeneration)
        }

    private suspend fun hasCurrentBucketAssetsCache(timeBucket: String): Boolean =
        bucketAssetsCache.containsCurrent(timeBucket, ::currentBucketAssetGeneration)

    private suspend fun cachedBucketAssetsIfCurrent(
        timeBucket: String,
        expectedGeneration: TimelineRuntimeBucketGenerationStamp
    ): List<Asset>? =
        bucketAssetsCache.getCurrent(timeBucket, expectedGeneration)

    private suspend fun putMaterializedBucketAssetsCacheIfCurrent(
        origin: TimelineBucketAssetsRuntimeCache.Origin?,
        assets: List<Asset>,
    ): Pair<Boolean, TimelineBucketAssetsRuntimeCache.CacheWrite?> {
        val write = origin?.let { bucketAssetsCache.putIfCurrent(it, assets) }
        return (write != null) to write
    }

    private suspend fun restoreMaterializedBucketAssetsCacheIfStillOwned(
        write: TimelineBucketAssetsRuntimeCache.CacheWrite?
    ) {
        if (write != null) bucketAssetsCache.restoreIfOwned(write)
    }

    private suspend fun materializeCachedBucketsIfAvailable(timeBuckets: List<String>): Set<String> =
        sessionOperationRunner.runStampedForOwner(ownerSessionEpoch) { sessionStamp ->
            materializeCachedBucketsForSession(timeBuckets, sessionStamp)
        }

    private suspend fun materializeCachedBucketsForSession(
        timeBuckets: List<String>,
        sessionStamp: SessionOperationStamp
    ): Set<String> {
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
        val deferredPublishGenerations = linkedMapOf<String, TimelineDeferredMaterializedBucketStamp>()
        chunks.forEachIndexed { chunkIndex, chunk ->
            val claimed = claimCachedMaterializationBuckets(chunk)
            if (claimed.isEmpty()) {
                if (chunkIndex < chunks.lastIndex) yield()
                return@forEachIndexed
            }
            try {
                val materializationGenerationSnapshot = runtimeStateStore.generationSnapshot()
                val loadedAssets = linkedMapOf<String, List<Asset>>()
                val loadedAssetGenerations = linkedMapOf<String, TimelineDeferredMaterializedBucketStamp>()
                val loadedAssetOrigins =
                    linkedMapOf<String, TimelineBucketAssetsRuntimeCache.Origin>()
                for (timeBucket in claimed) {
                    val materializationStamp = TimelineDeferredMaterializedBucketStamp(
                        sessionStamp = sessionStamp,
                        bucketGenerationStamp = materializationGenerationSnapshot
                            .bucketGenerationStampFor(timeBucket)
                    )
                    val materializationOrigin = cacheOriginIfCurrent(
                        timeBucket = timeBucket,
                        expectedGeneration = materializationStamp.bucketGenerationStamp
                    ) ?: continue
                    val cachedAssets = cachedBucketAssetsIfCurrent(
                        timeBucket = timeBucket,
                        expectedGeneration = materializationStamp.bucketGenerationStamp
                    )
                    val assets = if (cachedAssets != null) {
                        cachedAssets
                    } else {
                        roomReadCount++
                        try {
                            getBucketAssetsUseCase(timeBucket)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.e { "Failed to materialize cached Timeline bucket=<redacted>" }
                            emptyList()
                        }
                    }
                    if (assets.isNotEmpty()) {
                        loadedAssets[timeBucket] = assets
                        loadedAssetGenerations[timeBucket] = materializationStamp
                        loadedAssetOrigins[timeBucket] = materializationOrigin
                    }
                }
                if (loadedAssets.isNotEmpty()) {
                    val currentGenerationSnapshot = runtimeStateStore.generationSnapshot()
                    val currentLoadedAssets = loadedAssets.filter { (timeBucket, _) ->
                        loadedAssetGenerations[timeBucket]?.let { expected ->
                            currentGenerationSnapshot.matches(expected.bucketGenerationStamp, timeBucket)
                        } == true
                    }
                    val installedWrites =
                        linkedMapOf<String, TimelineBucketAssetsRuntimeCache.CacheWrite?>()
                    val installedLoadedBuckets = linkedSetOf<String>()
                    currentLoadedAssets.forEach { (timeBucket, assets) ->
                        val (installed, write) = putMaterializedBucketAssetsCacheIfCurrent(
                            origin = loadedAssetOrigins[timeBucket],
                            assets = assets,
                        )
                        if (installed) {
                            installedLoadedBuckets.add(timeBucket)
                            installedWrites[timeBucket] = write
                        }
                    }
                    val postWriteGenerationSnapshot = runtimeStateStore.generationSnapshot()
                    val staleWrittenBuckets = installedLoadedBuckets.filter { timeBucket ->
                        loadedAssetGenerations[timeBucket]?.let { expected ->
                            !postWriteGenerationSnapshot.matches(expected.bucketGenerationStamp, timeBucket)
                        } ?: true
                    }
                    staleWrittenBuckets.forEach { timeBucket ->
                        restoreMaterializedBucketAssetsCacheIfStillOwned(
                            write = installedWrites[timeBucket]
                        )
                    }
                    val postWriteLoadedBuckets = installedLoadedBuckets - staleWrittenBuckets.toSet()
                    val loadedBuckets = postWriteLoadedBuckets
                    if (chunkIndex == 0) {
                        val published = publishMaterializedCachedBuckets(
                            timeBuckets = loadedBuckets,
                            expectedGenerations = loadedAssetGenerations
                        )
                        if (published.isNotEmpty()) {
                            materialized.addAll(published)
                            publishChunkCount++
                            if (_uiConfig.value.viewConfig.mosaicEnabled) {
                                requestTimelineMosaicCacheReadForBuckets(
                                    timeBuckets = published,
                                    sessionStamp = sessionStamp
                                )
                            }
                        }
                    } else {
                        deferredPublishBuckets.addAll(loadedBuckets)
                        loadedBuckets.forEach { timeBucket ->
                            loadedAssetGenerations[timeBucket]?.let { generation ->
                                deferredPublishGenerations[timeBucket] = generation
                            }
                        }
                    }
                }
            } finally {
                releaseCachedMaterializationBuckets(claimed)
            }
            if (chunkIndex < chunks.lastIndex) yield()
        }
        if (deferredPublishBuckets.isNotEmpty()) {
            val deferredGenerations = deferredPublishBuckets
                .mapNotNull { timeBucket ->
                    deferredPublishGenerations[timeBucket]?.let { generation ->
                        timeBucket to generation
                    }
                }
                .toMap()
            val published = publishMaterializedCachedBuckets(
                timeBuckets = deferredPublishBuckets,
                expectedGenerations = deferredGenerations
            )
            if (published.isNotEmpty()) {
                materialized.addAll(published)
                publishChunkCount++
                if (_uiConfig.value.viewConfig.mosaicEnabled) {
                    requestTimelineMosaicCacheReadForBuckets(
                        timeBuckets = published,
                        sessionStamp = sessionStamp
                    )
                }
            }
        }
        if (materialized.isNotEmpty() || roomReadCount > 0) {
            log.d {
                "Timeline cached materialization completed requested=<redacted> " +
                        "materialized=<redacted> roomReads=<redacted> " +
                        "publishChunks=<redacted> elapsed=<redacted>"
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
                inMemoryBuckets = bucketAssetsCache.currentKeys(::currentBucketAssetGeneration)
            ).also { claimed ->
                materializingCachedBucketIds.addAll(claimed)
            }
        }

    private suspend fun releaseCachedMaterializationBuckets(timeBuckets: Collection<String>) {
        refreshQueueMutex.withLock {
            materializingCachedBucketIds.removeAll(timeBuckets.toSet())
        }
    }

    private suspend fun publishMaterializedCachedBuckets(
        timeBuckets: Set<String>,
        expectedGenerations: Map<String, TimelineDeferredMaterializedBucketStamp>? = null
    ): Set<String> {
        if (timeBuckets.isEmpty()) return emptySet()
        val currentBuckets = expectedGenerations?.let { generations ->
            val generationSnapshot = runtimeStateStore.generationSnapshot()
            timeBuckets.filterTo(mutableSetOf()) { timeBucket ->
                generations[timeBucket]?.let { expected ->
                    generationSnapshot.matches(expected.bucketGenerationStamp, timeBucket)
                } == true
            }
        } ?: timeBuckets
        if (currentBuckets.isEmpty()) return emptySet()
        val currentGenerations = expectedGenerations ?: runtimeStateStore
            .generationSnapshot()
            .let { snapshot ->
                currentBuckets.associateWith { timeBucket ->
                    TimelineDeferredMaterializedBucketStamp(
                        sessionStamp = sessionOperationRunner.stampForOwner(ownerSessionEpoch),
                        bucketGenerationStamp = snapshot.bucketGenerationStampFor(timeBucket)
                    )
                }
            }
        return mutateTimelineRuntime {
            // If a materialization run finishes while the user is actively
            // scrolling, the _bucketData.update would otherwise fire combine
            // mid-gesture and cause LazyColumn re-measurement during scroll. Defer
            // the publish to onScrollInProgressChanged(false) where it drains
            // alongside mosaic publishes — same pattern.
            if (isTimelineScrollActive.value) {
                if (runtimeStateStore.deferMaterializedBuckets(currentBuckets, currentGenerations)) {
                    requestDeferredTimelineDrainIfSettled()
                }
                return@mutateTimelineRuntime emptySet()
            }
            var publishedBuckets: Set<String>? = null
            while (publishedBuckets == null) {
                val generationSnapshot = runtimeStateStore.generationSnapshot()
                val publishBuckets = currentBuckets.filterTo(mutableSetOf()) { timeBucket ->
                    currentGenerations[timeBucket]?.let { expected ->
                        generationSnapshot.matches(expected.bucketGenerationStamp, timeBucket)
                    } == true
                }
                if (publishBuckets.isEmpty()) {
                    publishedBuckets = emptySet()
                    break
                }
                val publishStamps = publishBuckets.associateWith { timeBucket ->
                    currentGenerations.getValue(timeBucket).bucketGenerationStamp
                }
                val data = _bucketData.value
                val (next, rollbackStates) = data.publishMaterializedBucketsWithStamps(publishStamps)
                if (_bucketData.compareAndSet(data, next)) {
                    val postPublishGenerationSnapshot = runtimeStateStore.generationSnapshot()
                    val stalePublishedBuckets = publishBuckets.filterTo(mutableSetOf()) { timeBucket ->
                        currentGenerations[timeBucket]?.let { expected ->
                            !postPublishGenerationSnapshot.matches(expected.bucketGenerationStamp, timeBucket)
                        } ?: true
                    }
                    if (stalePublishedBuckets.isEmpty()) {
                        publishedBuckets = publishBuckets
                        continue
                    }
                    _bucketData.update { current ->
                        current.rollbackMaterializedBucketsIfStillOwned(
                            rollbackStates.filterKeys { it in stalePublishedBuckets }
                        )
                    }
                    publishedBuckets = publishBuckets - stalePublishedBuckets
                }
            }
            publishedBuckets.orEmpty()
        }
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
    ): Boolean = sessionOperationRunner.runStampedForOwner(ownerSessionEpoch) { sessionStamp ->
        loadBucketAssetsForSession(
            timeBucket,
            keepCachedRowsOnFailure,
            showRenderLoading,
            sessionStamp
        )
    }

    private suspend fun loadBucketAssetsForSession(
        timeBucket: String,
        keepCachedRowsOnFailure: Boolean,
        showRenderLoading: Boolean,
        sessionStamp: SessionOperationStamp
    ): Boolean {
        val hadCachedRows = timeBucket in _bucketData.value.loadedBuckets
        val hadAssetCache = assetsForRender(timeBucket) != null
        val loadGeneration = currentBucketAssetGeneration(timeBucket)
        log.d { "Syncing assets for bucket: <redacted>" }
        try {
            return loadBucketAssetsAction(timeBucket).fold(
                onSuccess = { syncResult ->
                    log.d { "Synced assets for bucket: <redacted>" }
                    // Sync success and content change are intentionally
                    // separate. Cached background refreshes should not move the
                    // render-facing state unless ordered persisted assets
                    // changed, but a newly visible cached bucket still needs to
                    // be marked materialized once its Room rows are available.
                    var publishedLoadResult = false
                    var shouldPublishLoadResult = false
                    var shouldRequestMosaicRead = false
                    var publishLoadGeneration: TimelineRuntimeBucketGenerationStamp? = null
                    val failedMosaicPublish = when {
                        syncResult.changed -> {
                            invalidateRuntimeMosaicAssignments(timeBucket)
                            val expectedGeneration = currentBucketAssetGeneration(timeBucket)
                            getOrLoadBucketAssetsCache(
                                timeBucket = timeBucket,
                                expectedGeneration = expectedGeneration
                            ) ?: return@fold false
                            if (!publishLoadedBucketResult(
                                    timeBucket = timeBucket,
                                    changed = true,
                                    expectedGeneration = expectedGeneration
                                )
                            ) {
                                return@fold false
                            }
                            publishedLoadResult = true
                            shouldRequestMosaicRead = true
                            val failedPublish = precomputeTimelineMosaicForBuckets(
                                setOf(timeBucket),
                                sessionStamp
                            )
                            failedPublish
                        }

                        assetsForRender(timeBucket) == null -> {
                            val expectedGeneration = currentBucketAssetGeneration(timeBucket)
                            getOrLoadBucketAssetsCache(
                                timeBucket = timeBucket,
                                expectedGeneration = expectedGeneration
                            ) ?: return@fold false
                            publishLoadGeneration = expectedGeneration
                            shouldPublishLoadResult = true
                            shouldRequestMosaicRead = true
                            null
                        }

                        hadCachedRows && hadAssetCache -> {
                            clearBucketLoadingState(timeBucket)
                            log.d { "Timeline bucket unchanged; render state preserved bucket=<redacted>" }
                            null
                        }

                        else -> {
                            publishLoadGeneration = currentBucketAssetGeneration(timeBucket)
                            shouldPublishLoadResult = true
                            shouldRequestMosaicRead = true
                            null
                        }
                    }
                    if (!publishedLoadResult && shouldPublishLoadResult) {
                        val expectedGeneration = publishLoadGeneration
                            ?: currentBucketAssetGeneration(timeBucket)
                        if (!publishLoadedBucketResult(
                                timeBucket = timeBucket,
                                changed = syncResult.changed,
                                expectedGeneration = expectedGeneration
                            )
                        ) {
                            return@fold false
                        }
                    }
                    if (shouldRequestMosaicRead && _uiConfig.value.viewConfig.mosaicEnabled) {
                        requestTimelineMosaicCacheReadForBuckets(
                            timeBuckets = setOf(timeBucket),
                            source = TimelineMosaicWorkSource.SyncPrecompute,
                            sessionStamp = sessionStamp
                        )
                    }
                    failedMosaicPublish?.let { markTimelineMosaicFailedForBuckets(it) }
                    markBucketServerRefreshed(timeBucket)
                    true
                },
                onFailure = { e ->
                    log.e { "Failed to sync assets for bucket: <redacted>" }
                    publishBucketLoadFailure(
                        timeBucket = timeBucket,
                        keepCachedRows = keepCachedRowsOnFailure && hadCachedRows,
                        showRenderLoading = showRenderLoading,
                        expectedGeneration = loadGeneration
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

    private suspend fun publishLoadedBucketResult(
        timeBucket: String,
        changed: Boolean,
        expectedGeneration: TimelineRuntimeBucketGenerationStamp
    ): Boolean =
        commitTimelineRuntime(
            requireSettled = false,
            isCurrent = {
                runtimeStateStore.generationSnapshot().matches(expectedGeneration, timeBucket) &&
                        bucketAssetsCache.containsCurrent(timeBucket, expectedGeneration)
            }
        ) {
            _bucketData.update { data ->
                data.copy(
                    cachedBuckets = data.cachedBuckets + timeBucket,
                    loadingBuckets = data.loadingBuckets - timeBucket,
                    loadedBuckets = data.loadedBuckets + timeBucket,
                    materializationPublishOwners = data.materializationPublishOwners + (
                            timeBucket to TimelineMaterializedBucketPublishOwner(
                                bucketGenerationStamp = expectedGeneration,
                                materializationRevision = data.materializationRevisionFor(timeBucket)
                            )
                            ),
                    assetRevisions = if (changed) {
                        data.assetRevisions.incrementRevision(timeBucket)
                    } else {
                        data.assetRevisions
                    }
                )
            }
            true
        } == true

    private suspend fun publishBucketLoadFailure(
        timeBucket: String,
        keepCachedRows: Boolean,
        showRenderLoading: Boolean,
        expectedGeneration: TimelineRuntimeBucketGenerationStamp
    ) {
        if (!showRenderLoading) return
        commitTimelineRuntime(
            requireSettled = false,
            isCurrent = {
                runtimeStateStore.generationSnapshot().matches(expectedGeneration, timeBucket)
            }
        ) {
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
    }

    private suspend fun invalidateRemovedBuckets(
        timeBuckets: Set<String>,
        sessionStamp: SessionOperationStamp =
            sessionOperationRunner.stampForOwner(ownerSessionEpoch)
    ) {
        if (timeBuckets.isEmpty()) return
        timeBuckets.forEach { timeBucket ->
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
                materializationPublishOwners = data.materializationPublishOwners
                    .filterKeys { it !in timeBuckets },
                // Bucket-count changes alter placeholder height even before
                // assets are loaded, so structural bucket invalidation also
                // bumps the per-bucket content revision.
                assetRevisions = data.assetRevisions.incrementRevisions(timeBuckets)
            )
        }
        requestVisibleTimelineMosaicCacheRead(sessionStamp)
    }

    private fun buildTimelineState(
        data: BucketData,
        config: UiConfig,
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>,
        geometry: TimelineGeometryInputs
    ): TimelineState {
        if (!timelineSkeletonGateOpen(geometry.skeleton, timelineSkeletonIdentity(config))) {
            return heldTimelineState(data, config)
        }
        val mark = TimeSource.Monotonic.markNow()
        val mosaicLayoutSpec = timelineMosaicLayoutSpec(config)
        val skeletonBuckets = geometry.skeleton.buckets.takeIf {
            geometry.skeleton.identity == timelineSkeletonIdentity(config)
        }.orEmpty()
        val displayItems = buildDisplayItems(
            data,
            config.groupSize,
            config.availableWidth,
            config.targetRowHeight,
            config.rowHeightBounds.max,
            mosaicLayoutSpec,
            config.viewConfig,
            mosaicStates,
            geometry.sections,
            geometry.buckets,
            skeletonBuckets
        )
        dispatchRepairRequiredMosaicKeys()
        val pageIndex = pageIndexForState(data)
        val scrollbarData = scrollbarDataForState(data.buckets, pageIndex)
        val displayIndex = displayIndexForItems(
            displayItems,
            data.buckets.map { bucket ->
                "${bucket.timeBucket}#${data.assetRevisionFor(bucket.timeBucket)}#" +
                        "${config.groupSize.apiValue}#${config.mosaicColumnCount}"
            }
        )
        val sectionLabelsByKey = timelineSectionLabels(data, config.groupSize, skeletonBuckets)
        val elapsed = mark.elapsedNow()
        if (elapsed.inWholeMilliseconds >= TIMELINE_REBUILD_LOG_THRESHOLD_MS) {
            log.d {
                "Timeline state rebuilt buckets=<redacted> loaded=<redacted> " +
                        "items=<redacted> rowMode=<redacted> elapsed=<redacted>"
            }
        }

        return TimelineState(
            groupSize = config.groupSize,
            targetRowHeight = config.targetRowHeight,
            rowHeightBounds = config.rowHeightBounds,
            isLoading = config.isLoading,
            isSourceReady = data.isSourceReady,
            error = config.error,
            displayItems = displayItems,
            displayIndex = displayIndex,
            sectionLabelsByKey = sectionLabelsByKey,
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
        ).also { lastBuiltTimelineState = it }
    }

    // Never expose an estimated cached layout: keep the last layout until the exact skeleton loads.
    private fun heldTimelineState(data: BucketData, config: UiConfig): TimelineState =
        lastBuiltTimelineState?.copy(
            isLoading = config.isLoading,
            isSourceReady = data.isSourceReady,
            error = config.error,
            bannerError = config.bannerError,
            bannerSuccess = config.bannerSuccess,
            lastSyncedAt = config.lastSyncedAt,
            isSyncing = config.isSyncing
        ) ?: TimelineState(
            groupSize = config.groupSize,
            targetRowHeight = config.targetRowHeight,
            rowHeightBounds = config.rowHeightBounds,
            isLoading = true,
            isSourceReady = data.isSourceReady,
            error = config.error,
            buckets = data.buckets,
            viewConfig = config.viewConfig,
            bannerError = config.bannerError,
            bannerSuccess = config.bannerSuccess,
            lastSyncedAt = config.lastSyncedAt,
            isSyncing = config.isSyncing
        )

    private fun dispatchRepairRequiredMosaicKeys() {
        if (pendingRepairRequiredMosaicKeys.isEmpty()) return
        val keys = pendingRepairRequiredMosaicKeys.toSet()
        pendingRepairRequiredMosaicKeys.clear()
        val newKeys = keys - repairRequiredMosaicKeys.value
        if (newKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            markTimelineMosaicRepairRequired(newKeys)
        }
    }

    private fun pageIndexForState(data: BucketData): TimelinePageIndex {
        val key = TimelinePageIndexCacheKey(
            bucketKeys = data.buckets.map { bucket ->
                val assets = assetsForRender(bucket.timeBucket)
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
            val assets = assetsForRender(bucket.timeBucket)
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
            bucketTimeBuckets = buckets.map { it.timeBucket },
            pageIndex = pageIndex
        )
        if (key == cachedScrollbarDataKey) return cachedScrollbarData
        return computeScrollbarData(buckets, pageIndex).also { scrollbarData ->
            cachedScrollbarDataKey = key
            cachedScrollbarData = scrollbarData
        }
    }

    private fun displayIndexForItems(
        displayItems: List<TimelineDisplayItem>,
        bucketIdentities: List<String>
    ): TimelineDisplayIndex {
        if (displayItems === cachedDisplayIndexItems) {
            if (cachedDisplayIndex.bucketIdentityByIndex != bucketIdentities) {
                cachedDisplayIndex = cachedDisplayIndex.copy(bucketIdentityByIndex = bucketIdentities)
            }
            return cachedDisplayIndex
        }
        return buildTimelineDisplayIndex(displayItems).copy(bucketIdentityByIndex = bucketIdentities).also { displayIndex ->
            cachedDisplayIndexItems = displayItems
            cachedDisplayIndex = displayIndex
        }
    }

    private fun timelineSectionLabels(
        data: BucketData,
        groupSize: TimelineGroupSize,
        skeletonBuckets: Map<String, TimelineMosaicBucketSkeleton>
    ): Map<String, AssetGroupDisplayLabel> = buildMap {
        data.buckets.forEach { bucket ->
            put(
                monthMosaicSectionKey(bucket.timeBucket),
                bucket.displayLabel
            )
            if (groupSize == TimelineGroupSize.DAY) {
                skeletonBuckets[bucket.timeBucket]?.sections?.forEach { section ->
                    timelineDaySectionDisplayLabel(section.sectionKey)?.let { put(section.sectionKey, it) }
                }
                val assets = assetsForRender(bucket.timeBucket) ?: return@forEach
                dayGroupsForBucket(
                    timeBucket = bucket.timeBucket,
                    assetRevision = data.assetRevisionFor(bucket.timeBucket),
                    assets = assets
                ).forEach { group -> put(group.sectionKey, group.label) }
            }
        }
    }

    /**
     * Runs exclusively on Dispatchers.Default (via flowOn in the combine pipeline).
     * Derived layout cache reads/writes are confined to this thread context;
     * the shared asset cache owns its own cross-dispatcher synchronization.
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
        bucketGeometryStates: Map<MosaicCacheKey, Float>,
        skeletonBuckets: Map<String, TimelineMosaicBucketSkeleton>
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
            availableWidth = mosaicLayoutSpec?.availableWidth ?: availableWidth,
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
            val isRenderReady =
                timeBucket in data.loadedBuckets && assetsForRender(timeBucket) != null
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
            val skeletonKey = skeletonBuckets[timeBucket]?.contentToken ?: "none"
            val cacheKey =
                "${timeBucket}_${stateKey}_${assetRevision}_${materializationRevision}_${layoutKey}_${geometryKey}_$skeletonKey"

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
                    bucketGeometryRequest,
                    skeletonBuckets[timeBucket]
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
            val isRenderReady =
                timeBucket in data.loadedBuckets && assetsForRender(timeBucket) != null
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
            val skeletonKey = skeletonBuckets[timeBucket]?.contentToken ?: "none"
            val cacheKey =
                "${timeBucket}_${stateKey}_${assetRevision}_${materializationRevision}_${layoutKey}_${geometryKey}_$skeletonKey"
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
        val assets = assetsForRender(timeBucket) ?: return "mosaic_days_unloaded"
        val dayGroups = dayGroupsForBucket(timeBucket, assetRevision, assets)
        if (dayGroups.isEmpty()) return "mosaic_empty_days"
        return dayGroups.joinToString(
            separator = ";",
            prefix = "mosaic_days_"
        ) { dayGroup ->
            val key = MosaicCacheKey(
                timeBucket = timeBucket,
                sectionKey = dayGroup.sectionKey,
                columnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                familiesKey = familiesKey
            )
            "${dayGroup.sectionKey}:${mosaicStates[key].cacheToken()}"
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
            return "geometry_${mosaicGeometryStates[key]?.contentToken ?: "missing"}"
        }
        val assets = assetsForRender(timeBucket) ?: return "geometry_days_unloaded"
        val dayGroups = dayGroupsForBucket(timeBucket, assetRevision, assets)
        return dayGroups.joinToString(
            separator = ";",
            prefix = "geometry_days_"
        ) { dayGroup ->
            val key = MosaicCacheKey(
                timeBucket = timeBucket,
                sectionKey = dayGroup.sectionKey,
                columnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                familiesKey = familiesKey
            )
            "${dayGroup.sectionKey}:${mosaicGeometryStates[key]?.contentToken ?: "missing"}"
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
        bucketGeometryRequest: TimelineMosaicGeometryRequest?,
        skeleton: TimelineMosaicBucketSkeleton?
    ): List<TimelineDisplayItem> {
        val timeBucket = bucket.timeBucket
        val useAlignedMosaic = useMosaic && mosaicLayoutSpec != null
        val isFailed = timeBucket in data.failedBuckets
        val isLoaded = timeBucket in data.loadedBuckets && assetsForRender(timeBucket) != null
        val items = mutableListOf<TimelineDisplayItem>()

        items.add(
            HeaderItem(
                gridKey = "h_$timeBucket",
                bucketIndex = index,
                sectionLabel = monthMosaicSectionKey(timeBucket),
                label = timeBucket,
                assetGroupDisplayLabel = bucket.displayLabel
            )
        )

        when {
            isFailed -> {
                items.add(
                    ErrorItem(
                        gridKey = "err_$timeBucket",
                        bucketIndex = index,
                        sectionLabel = monthMosaicSectionKey(timeBucket),
                        timeBucket = timeBucket
                    )
                )
            }

            !isLoaded -> {
                val cachedAssets = assetsForRender(timeBucket)
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
                if (useAlignedMosaic && skeleton != null) {
                    items.addAll(timelineSkeletonBucketItems(timeBucket, index, groupSize, skeleton))
                } else if (useMosaic && bucketGeometryHeight != null) {
                    addGeometryPlaceholderItems(
                        items = items,
                        timeBucket = timeBucket,
                        sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                        bucketIndex = index,
                        sectionLabel = monthMosaicSectionKey(timeBucket),
                        placeholderHeight = bucketGeometryHeight,
                        ordinalCount = bucket.count
                    )
                } else if (useMosaic && bucketGeometryKey != null && bucketGeometryKey in data.geometryReadyBuckets) {
                    // Exact geometry is known to be empty for this bucket.
                } else if (useMosaic && mosaicLayoutSpec != null && !viewConfig.cacheMosaicResults &&
                    !cachedAssets.isNullOrEmpty()
                ) {
                    addProjectedMosaicPlaceholderItems(
                        items = items,
                        timeBucket = timeBucket,
                        sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                        bucketIndex = index,
                        sectionLabel = monthMosaicSectionKey(timeBucket),
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
                        sectionLabel = monthMosaicSectionKey(timeBucket),
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
                val assets = assetsForRender(timeBucket).orEmpty()
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
                                columnCount = mosaicLayoutSpec?.columnCount
                                    ?: DEFAULT_GRID_COLUMN_COUNT,
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
                                    sectionKey = dayGroup.sectionKey,
                                    columnCount = mosaicLayoutSpec?.columnCount
                                        ?: DEFAULT_GRID_COLUMN_COUNT,
                                    assetRevision = assetRevision,
                                    families = viewConfig.mosaicFamilies
                                )
                            ]
                        }
                        val aggregatePlaceholderHeight = timelineAggregateGeometryPlaceholderHeight(
                            aggregateGeometryHeight = aggregateGeometryHeight,
                            sectionStates = daySectionStates
                        )
                        if (useMosaic && mosaicLayoutSpec != null && aggregatePlaceholderHeight != null &&
                            skeleton == null
                        ) {
                            addGeometryPlaceholderItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                                bucketIndex = index,
                                sectionLabel = monthMosaicSectionKey(timeBucket),
                                placeholderHeight = aggregatePlaceholderHeight,
                                ordinalCount = assets.size
                            )
                            return items
                        }
                        var nextDayOrdinalBase = 0
                        for (dayGroup in dayGroupList) {
                            val dayOrdinalBase = nextDayOrdinalBase
                            nextDayOrdinalBase += dayGroup.assets.size
                            val daySectionKey = dayGroup.sectionKey
                            items.add(
                                HeaderItem(
                                    gridKey = "dh_${timeBucket}_$daySectionKey",
                                    bucketIndex = index,
                                    sectionLabel = daySectionKey,
                                    label = daySectionKey,
                                    assetGroupDisplayLabel = dayGroup.label
                                )
                            )
                            val dayMosaicKey = mosaicCacheKey(
                                timeBucket = timeBucket,
                                sectionKey = daySectionKey,
                                columnCount = mosaicLayoutSpec?.columnCount
                                    ?: DEFAULT_GRID_COLUMN_COUNT,
                                assetRevision = assetRevision,
                                families = viewConfig.mosaicFamilies
                            )
                            val dayMosaicState = mosaicStates[dayMosaicKey]
                            if (useAlignedMosaic &&
                                addAlignedMosaicSectionItems(
                                    items = items,
                                    timeBucket = timeBucket,
                                    sectionKey = daySectionKey,
                                    bucketIndex = index,
                                    sectionLabel = daySectionKey,
                                    assets = dayGroup.assets,
                                    state = dayMosaicState,
                                    cacheKey = dayMosaicKey,
                                    stateGeometry = mosaicGeometryStates[dayMosaicKey],
                                    skeletonSection = skeleton.sectionFor(daySectionKey, dayGroup.assets.size),
                                    mosaicLayoutSpec = mosaicLayoutSpec,
                                    maxRowHeight = maxRowHeight,
                                    families = viewConfig.mosaicFamilies
                                )
                            ) {
                                continue
                            }
                            when {
                                useMosaic &&
                                        mosaicLayoutSpec != null &&
                                        (dayMosaicState == null || dayMosaicState == RuntimeMosaicState.Pending) -> {
                                    addStrictMosaicPlaceholderItems(
                                        items = items,
                                        timeBucket = timeBucket,
                                        sectionKey = daySectionKey,
                                        bucketIndex = index,
                                        sectionLabel = daySectionKey,
                                        assets = dayGroup.assets,
                                        mosaicLayoutSpec = mosaicLayoutSpec,
                                        sectionGeometry = mosaicGeometryStates[dayMosaicKey],
                                        ordinalBase = dayOrdinalBase
                                    )
                                }

                                useMosaic && dayMosaicState is RuntimeMosaicState.Partial && mosaicLayoutSpec != null -> {
                                    addPartialMosaicItems(
                                        items = items,
                                        timeBucket = timeBucket,
                                        sectionKey = daySectionKey,
                                        bucketIndex = index,
                                        sectionLabel = daySectionKey,
                                        assets = dayGroup.assets,
                                        partial = dayMosaicState,
                                        mosaicLayoutSpec = mosaicLayoutSpec,
                                        maxRowHeight = maxRowHeight,
                                        sectionGeometry = mosaicGeometryStates[dayMosaicKey]
                                    )
                                }

                                useMosaic &&
                                        dayMosaicState is RuntimeMosaicState.Ready &&
                                        mosaicLayoutSpec != null &&
                                        mosaicGeometryStates[dayMosaicKey] != null -> {
                                    items.addAll(
                                        remapTimelineMosaicBandKeys(
                                            items = readyMosaicDisplayItems(
                                                cacheKey = dayMosaicKey,
                                                assets = dayGroup.assets,
                                                state = dayMosaicState,
                                                bucketIndex = index,
                                                sectionLabel = daySectionKey,
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
                                        sectionLabel = daySectionKey,
                                        assets = dayGroup.assets,
                                        mosaicLayoutSpec = mosaicLayoutSpec,
                                        sectionGeometry = mosaicGeometryStates[dayMosaicKey],
                                        ordinalBase = dayOrdinalBase
                                    )
                                }

                                useMosaic -> {
                                    addPlaceholderItems(
                                        items = items,
                                        timeBucket = timeBucket,
                                        sectionKey = daySectionKey,
                                        bucketIndex = index,
                                        sectionLabel = daySectionKey,
                                        assetCount = dayGroup.assets.size,
                                        groupSize = TimelineGroupSize.MONTH,
                                        availableWidth = availableWidth,
                                        targetRowHeight = mosaicLayoutSpec?.cellHeight ?: targetRowHeight,
                                        ordinalBase = dayOrdinalBase
                                    )
                                }

                                else -> {
                                    items.addAll(
                                        packIntoRows(
                                            dayGroup.assets, index, daySectionKey,
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
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        val monthSectionKey = monthMosaicSectionKey(timeBucket)
                        val monthMosaicKey = mosaicCacheKey(
                            timeBucket = timeBucket,
                            sectionKey = monthSectionKey,
                            columnCount = mosaicLayoutSpec?.columnCount
                                ?: DEFAULT_GRID_COLUMN_COUNT,
                            assetRevision = assetRevision,
                            families = viewConfig.mosaicFamilies
                        )
                        val monthMosaicState = mosaicStates[monthMosaicKey]
                        val aggregatePlaceholderHeight = timelineAggregateGeometryPlaceholderHeight(
                            aggregateGeometryHeight = aggregateGeometryHeight,
                            sectionStates = listOf(monthMosaicState)
                        )
                        if (useMosaic && mosaicLayoutSpec != null && aggregatePlaceholderHeight != null &&
                            skeleton == null
                        ) {
                            addGeometryPlaceholderItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = timelinePlaceholderSectionKey(timeBucket, groupSize),
                                bucketIndex = index,
                                sectionLabel = monthSectionKey,
                                placeholderHeight = aggregatePlaceholderHeight,
                                ordinalCount = assets.size
                            )
                            return items
                        }
                        if (useAlignedMosaic &&
                            addAlignedMosaicSectionItems(
                                items = items,
                                timeBucket = timeBucket,
                                sectionKey = monthSectionKey,
                                bucketIndex = index,
                                sectionLabel = monthSectionKey,
                                assets = assets,
                                state = monthMosaicState,
                                cacheKey = monthMosaicKey,
                                stateGeometry = mosaicGeometryStates[monthMosaicKey],
                                skeletonSection = skeleton.sectionFor(monthSectionKey, assets.size),
                                mosaicLayoutSpec = mosaicLayoutSpec,
                                maxRowHeight = maxRowHeight,
                                families = viewConfig.mosaicFamilies
                            )
                        ) {
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
                                sectionLabel = monthSectionKey,
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
                                sectionLabel = monthSectionKey,
                                assets = assets,
                                partial = monthMosaicState,
                                mosaicLayoutSpec = mosaicLayoutSpec,
                                maxRowHeight = maxRowHeight,
                                sectionGeometry = mosaicGeometryStates[monthMosaicKey]
                            )
                            return items
                        }
                        if (useMosaic &&
                            monthMosaicState is RuntimeMosaicState.Ready &&
                            mosaicLayoutSpec != null &&
                            mosaicGeometryStates[monthMosaicKey] != null
                        ) {
                            items.addAll(
                                remapTimelineMosaicBandKeys(
                                    items = readyMosaicDisplayItems(
                                        cacheKey = monthMosaicKey,
                                        assets = assets,
                                        state = monthMosaicState,
                                        bucketIndex = index,
                                        sectionLabel = monthSectionKey,
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
                                sectionLabel = monthSectionKey,
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
                                sectionLabel = monthSectionKey,
                                assetCount = assets.size,
                                groupSize = TimelineGroupSize.MONTH,
                                availableWidth = availableWidth,
                                targetRowHeight = mosaicLayoutSpec?.cellHeight ?: targetRowHeight
                            )
                        } else {
                            items.addAll(
                                packIntoRows(
                                    assets, index, monthSectionKey,
                                    availableWidth,
                                    targetRowHeight,
                                    GRID_SPACING_DP,
                                    maxRowHeight,
                                    promoteWideImages = true,
                                    minCompleteRowPhotos = 1
                                )
                            )
                        }
                    }
                } else {
                    // Loaded but empty — show header only (no placeholder trap)
                }
            }
        }
        return items
    }

    // Emits exactly one keyed item per geometry range: the completed band/row when it matches, else a placeholder.
    private fun addAlignedMosaicSectionItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        assets: List<Asset>,
        state: RuntimeMosaicState?,
        cacheKey: MosaicCacheKey,
        stateGeometry: TimelineMosaicSectionGeometry?,
        skeletonSection: TimelineMosaicGeometrySummary?,
        mosaicLayoutSpec: MosaicLayoutSpec,
        maxRowHeight: Float,
        families: Set<MosaicTemplateFamily>
    ): Boolean {
        val ranges = stateGeometry?.ranges?.takeIf { it.isNotEmpty() }
            ?: skeletonSection?.ranges?.takeIf { it.isNotEmpty() }
            ?: return false
        val completedItems = when (state) {
            is RuntimeMosaicState.Ready -> readyMosaicDisplayItems(
                cacheKey = cacheKey,
                assets = assets,
                state = state,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                mosaicLayoutSpec = mosaicLayoutSpec,
                maxRowHeight = maxRowHeight,
                families = families
            )

            is RuntimeMosaicState.Partial -> mosaicRenderEngine.projectPartialSectionWithGeometry(
                assets = assets,
                chunks = state.chunks,
                geometryRanges = ranges,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                layoutSpec = mosaicLayoutSpec,
                spacing = GRID_SPACING_DP,
                maxRowHeight = maxRowHeight
            ).orEmpty()

            else -> emptyList()
        }
        val aligned = mosaicRenderEngine.alignToGeometryRanges(completedItems, ranges)
        if (state is RuntimeMosaicState.Ready && !aligned.allMatched) {
            pendingRepairRequiredMosaicKeys.add(cacheKey)
        }
        items.addAll(timelineGeometryAlignedItems(timeBucket, sectionKey, bucketIndex, sectionLabel, aligned))
        return true
    }

    private fun TimelineMosaicBucketSkeleton?.sectionFor(
        sectionKey: String,
        assetCount: Int
    ): TimelineMosaicGeometrySummary? =
        this?.sections
            ?.firstOrNull { it.sectionKey == sectionKey }
            ?.takeIf { section -> section.ranges.sumOf { it.sourceCount } == assetCount }

    private fun addPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        assetCount: Int,
        groupSize: TimelineGroupSize,
        availableWidth: Float,
        targetRowHeight: Float,
        ordinalBase: Int = 0
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
            ).withBucketOrdinals(ordinalBase, assetCount)
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
            val projectedHeight =
                estimatePhotoGridDisplayItemsHeight(projectedItems, GRID_SPACING_DP)
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
                    ).withBucketOrdinals(0, assets.size)
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
        sectionGeometry: TimelineMosaicSectionGeometry?,
        ordinalBase: Int = 0
    ) {
        val ranges = sectionGeometry?.ranges.orEmpty()
        if (ranges.isNotEmpty()) {
            addGeometryRangePlaceholderItems(
                items = items,
                timeBucket = timeBucket,
                sectionKey = sectionKey,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                ranges = ranges
            )
            return
        }
        val height = sectionGeometry?.placeholderHeight
        if (height != null && height > 0f) {
            addGeometryPlaceholderItems(
                items = items,
                timeBucket = timeBucket,
                sectionKey = sectionKey,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                placeholderHeight = height,
                ordinalCount = assets.size,
                ordinalBase = ordinalBase
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
            targetRowHeight = mosaicLayoutSpec.cellHeight,
            ordinalBase = ordinalBase
        )
    }

    private fun addGeometryRangePlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        ranges: List<MosaicSectionGeometryRange>
    ) {
        items.addAll(
            remapTimelinePlaceholderKeys(
                placeholders = buildPhotoGridPlaceholderItemsForGeometryRanges(
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    ranges = ranges,
                    externalSpacing = GRID_SPACING_DP,
                    keyPrefix = "timeline_geom"
                ),
                prefix = "pl",
                timeBucket = timeBucket,
                sectionKey = sectionKey
            )
        )
    }

    private fun addGeometryPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String,
        bucketIndex: Int,
        sectionLabel: String,
        placeholderHeight: Float,
        ordinalCount: Int = 0,
        ordinalBase: Int = 0
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
            ).withBucketOrdinals(ordinalBase, ordinalCount)
        )
    }

    private fun remapTimelinePlaceholderKeys(
        placeholders: List<PlaceholderItem>,
        prefix: String,
        timeBucket: String,
        sectionKey: String
    ): List<PlaceholderItem> =
        placeholders.mapIndexed { chunkIndex, item ->
            item.copy(
                gridKey = "${timelinePlaceholderGridKey(prefix, timeBucket, sectionKey, chunkIndex)}|${item.gridKey}"
            )
        }

    private fun remapTimelineMosaicBandKeys(
        items: List<TimelineDisplayItem>,
        timeBucket: String,
        sectionKey: String
    ): List<TimelineDisplayItem> =
        items.mapIndexed { index, item ->
            when (item) {
                is MosaicBandItem -> item.copy(
                    gridKey = timelineMosaicRangeGridKey(
                        timeBucket,
                        sectionKey,
                        item.sourceStartIndex,
                        item.sourceCount
                    )
                )

                is RowItem -> if (item.kind == RowItemKind.MOSAIC_FALLBACK) {
                    item.copy(
                        gridKey = timelineMosaicRangeGridKey(
                            timeBucket,
                            sectionKey,
                            item.sourceStartIndex,
                            item.sourceCount
                        )
                    )
                } else {
                    item
                }

                is PlaceholderItem -> item.copy(
                    gridKey = timelinePlaceholderGridKey("plp", timeBucket, sectionKey, index)
                )

                else -> item
            }
        }

    private fun timelinePlaceholderSectionKey(
        timeBucket: String,
        groupSize: TimelineGroupSize
    ): String =
        "$timeBucket|placeholder|${groupSize.apiValue}"

    private suspend fun syncCachedBucketsManually(
        buckets: List<TimelineBucket>,
        hadBannerError: Boolean
    ) {
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

    private suspend fun publishColdSyncResult(
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

    private suspend fun publishColdSyncAssetsOnly(successfulBucketIds: Set<String>) {
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
        changedBucketIds: Set<String>,
        sessionStamp: SessionOperationStamp
    ) {
        val changedSuccessfulBucketIds = changedBucketIds intersect successfulBucketIds
        log.d {
            "Timeline publishing asset sync result successful=<redacted> " +
                    "failed=<redacted> changed=<redacted> " +
                    "changedSuccessful=<redacted>"
        }
        publishAssetSyncResult(successfulBucketIds, failedBucketIds, changedBucketIds)
        val failedMosaicPublish = precomputeTimelineMosaicForBuckets(
            changedSuccessfulBucketIds,
            sessionStamp
        )
        requestTimelineMosaicCacheReadForBuckets(
            timeBuckets = changedSuccessfulBucketIds,
            source = TimelineMosaicWorkSource.SyncPrecompute,
            sessionStamp = sessionStamp
        )
        failedMosaicPublish?.let { markTimelineMosaicFailedForBuckets(it) }
        log.d {
            "Timeline asset sync result published mosaicFailed=<redacted>"
        }
    }

    private suspend fun publishBlockingMosaicPrepareResult(
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
            invalidateRuntimeMosaicAssignments(timeBucket)
        }
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets + successfulBucketIds,
                loadingBuckets = data.loadingBuckets - successfulBucketIds,
                failedBuckets = data.failedBuckets - successfulBucketIds,
                materializationPublishOwners = data.materializationPublishOwners -
                        changedSuccessfulBucketIds,
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

    private suspend fun publishAssetSyncResult(
        successfulBucketIds: Set<String>,
        failedBucketIds: Set<String>,
        changedBucketIds: Set<String>,
        manualMosaicRuntimeOrigin: TimelineManualMosaicRuntimeOrigin? = null,
        manualMosaicBuckets: Set<String> = emptySet()
    ): TimelineManualMosaicPublicationOrigin? = mutateTimelineRuntime {
        val changedSuccessfulBucketIds = changedBucketIds intersect successfulBucketIds
        val acceptsManualMosaicResult = manualMosaicRuntimeOrigin?.isCurrentFor(
            timeBuckets = manualMosaicBuckets,
            currentGenerationSnapshot = runtimeStateStore.generationSnapshot(),
            currentAssetRevisions = _bucketData.value.assetRevisions
        ) == true
        // Successful sync means loaded/failed state changed; changed sync means
        // the persisted ordered assets changed and derived layout must be
        // recalculated for that bucket. Keep these separate so no-op launch
        // syncs do not repack the whole timeline.
        changedSuccessfulBucketIds.forEach { timeBucket ->
            invalidateRuntimeMosaicAssignmentsLocked(timeBucket)
        }
        _bucketData.update { data ->
            data.copy(
                cachedBuckets = data.cachedBuckets + successfulBucketIds - failedBucketIds,
                loadedBuckets = data.loadedBuckets + successfulBucketIds - failedBucketIds,
                loadingBuckets = data.loadingBuckets - successfulBucketIds - failedBucketIds,
                failedBuckets = data.failedBuckets + failedBucketIds - successfulBucketIds,
                materializationPublishOwners = data.materializationPublishOwners -
                        changedSuccessfulBucketIds,
                assetRevisions = data.assetRevisions.incrementRevisions(changedSuccessfulBucketIds)
            )
        }
        if (acceptsManualMosaicResult) {
            TimelineManualMosaicPublicationOrigin(
                stamp = runtimeStateStore.publishStampForBuckets(manualMosaicBuckets),
                assetRevisions = manualMosaicBuckets.associateWith { timeBucket ->
                    _bucketData.value.assetRevisionFor(timeBucket)
                }
            )
        } else {
            null
        }
    }

    private suspend fun invalidateRuntimeMosaicAssignments(timeBucket: String) {
        mutateTimelineRuntime {
            invalidateRuntimeMosaicAssignmentsLocked(timeBucket)
        }
    }

    private suspend fun invalidateRuntimeMosaicAssignmentsLocked(timeBucket: String) {
        advanceTimelineMosaicBucketGeneration(timeBucket, "bucket-invalidate")
        removeBucketAssetsCache(timeBucket)
        timelineMosaicQueue.send(TimelineMosaicQueueCommand.InvalidateBucket(timeBucket))
        progressiveMosaicBuffer.clearBuckets(setOf(timeBucket))
        _mosaicStates.update { states ->
            states.filterKeys { it.timeBucket != timeBucket }
        }
        _mosaicGeometryStates.update { states ->
            states.filterKeys { it.timeBucket != timeBucket }
        }
        _bucketGeometryStates.update { states ->
            states.filterKeys { it.timeBucket != timeBucket }
        }
        repairRequiredMosaicKeys.update { keys -> keys.filterNot { it.timeBucket == timeBucket }.toSet() }
        recomputedRepairMosaicKeys.update { keys -> keys.filterNot { it.timeBucket == timeBucket }.toSet() }
        requestTimelineSkeletonBucketReload(setOf(timeBucket))
    }

    private suspend fun markTimelineMosaicRepairRequired(keys: Set<MosaicCacheKey>) {
        val currentKeys = mutateTimelineRuntime {
            val revisions = _bucketData.value.assetRevisions
            val recomputed = recomputedRepairMosaicKeys.value
            keys.filter { it.assetRevision == (revisions[it.timeBucket] ?: 0) && it !in recomputed }.toSet().also { current ->
                if (current.isNotEmpty()) repairRequiredMosaicKeys.update { it + current }
            }
        }
        if (currentKeys.isEmpty()) return
        log.w { "Timeline Mosaic sections disagree with persisted geometry; recomputing sections=<redacted>" }
        requestTimelineMosaicCacheReadForBuckets(
            timeBuckets = currentKeys.map { it.timeBucket }.distinct(),
            source = TimelineMosaicWorkSource.RenderDemand
        )
    }

    private fun requestVisibleTimelineMosaicCacheRead(
        sessionStamp: SessionOperationStamp =
            sessionOperationRunner.stampForOwner(ownerSessionEpoch)
    ) {
        if (!timelineGeometryReady.value) {
            // Defer the queue request until exact per-band geometry is loaded.
            // Once the geometry phase completes, this re-fires and the queue
            // proceeds normally. Subsequent calls (post-init) bypass the gate
            // because timelineGeometryReady remains true.
            viewModelScope.launch(Dispatchers.Default) {
                timelineGeometryReady.first { it }
                requestTimelineMosaicCacheReadForBuckets(
                    timeBuckets = orderedVisibleLoadedTimelineBuckets(),
                    source = TimelineMosaicWorkSource.RenderDemand,
                    sessionStamp = sessionStamp
                )
            }
            return
        }
        requestTimelineMosaicCacheReadForBuckets(
            timeBuckets = orderedVisibleLoadedTimelineBuckets(),
            source = TimelineMosaicWorkSource.RenderDemand,
            sessionStamp = sessionStamp
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

    private fun requestPersistedBucketGeometryForCachedBuckets(
        sessionStamp: SessionOperationStamp =
            sessionOperationRunner.stampForOwner(ownerSessionEpoch)
    ) {
        val config = _uiConfig.value
        val identity = timelineSkeletonIdentity(config)
        if (identity == null) {
            timelineGeometryReady.value = true
            return
        }
        var originEpochs: Map<String, Long> = emptyMap()
        _timelineSkeleton.update { skeleton ->
            skeleton.startingLoad(identity).also { originEpochs = it.bucketEpochs }
        }
        if (_timelineSkeleton.value.status == TimelineSkeletonLoadStatus.Loading) {
            timelineGeometryReady.value = false
        }
        val generation = ++skeletonLoadGeneration
        skeletonLoadJob?.cancel()
        skeletonLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runTimelineMosaicSessionSink(
                    sessionOperationRunner = sessionOperationRunner,
                    sessionStamp = sessionStamp
                ) {
                    val mark = TimeSource.Monotonic.markNow()
                    val result = getTimelineMosaicSkeletonUseCase(
                        groupSize = identity.groupSize,
                        columnCount = identity.columnCount,
                        families = config.viewConfig.mosaicFamilies,
                        geometryRequest = identity.geometryRequest
                    )
                    mutateTimelineRuntime {
                        if (timelineSkeletonIdentity(_uiConfig.value) != identity) return@mutateTimelineRuntime
                        result.onSuccess { skeleton ->
                            _timelineSkeleton.update { current ->
                                current.withLoadedBuckets(identity, skeleton.buckets, null, originEpochs)
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            log.e { "Failed to load Timeline Mosaic skeleton" }
                            failTimelineSkeletonLoad(identity)
                        }
                        timelineGeometryReady.value = true
                    }
                    log.d { "Timeline Mosaic skeleton load completed buckets=<redacted> elapsed=<redacted>" }
                }
            } finally {
                if (generation == skeletonLoadGeneration && !timelineGeometryReady.value &&
                    timelineSkeletonIdentity(_uiConfig.value) == identity
                ) {
                    failTimelineSkeletonLoad(identity)
                    timelineGeometryReady.value = true
                }
            }
        }
    }

    private fun failTimelineSkeletonLoad(identity: TimelineSkeletonIdentity) {
        _timelineSkeleton.update { current ->
            if (current.identity == identity && current.status == TimelineSkeletonLoadStatus.Loading) {
                current.copy(status = TimelineSkeletonLoadStatus.Failed)
            } else {
                current
            }
        }
    }

    private fun requestTimelineSkeletonBucketReload(
        timeBuckets: Set<String>,
        sessionStamp: SessionOperationStamp =
            sessionOperationRunner.stampForOwner(ownerSessionEpoch)
    ) {
        if (timeBuckets.isEmpty()) return
        val config = _uiConfig.value
        val identity = timelineSkeletonIdentity(config) ?: return
        if (_timelineSkeleton.value.identity != identity) return
        var originEpochs: Map<String, Long> = emptyMap()
        _timelineSkeleton.update { skeleton ->
            skeleton.withBumpedEpochs(timeBuckets).also { originEpochs = it.bucketEpochs }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runTimelineMosaicSessionSink(
                sessionOperationRunner = sessionOperationRunner,
                sessionStamp = sessionStamp
            ) {
                val result = getTimelineMosaicSkeletonUseCase(
                    groupSize = identity.groupSize,
                    columnCount = identity.columnCount,
                    families = config.viewConfig.mosaicFamilies,
                    geometryRequest = identity.geometryRequest,
                    timeBuckets = timeBuckets
                )
                result.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.e { "Failed to reload Timeline Mosaic skeleton buckets=<redacted>" }
                }.onSuccess { skeleton ->
                    mutateTimelineRuntime {
                        if (timelineSkeletonIdentity(_uiConfig.value) != identity) return@mutateTimelineRuntime
                        _timelineSkeleton.update { current ->
                            current.withLoadedBuckets(identity, skeleton.buckets, timeBuckets, originEpochs)
                        }
                    }
                }
            }
        }
    }

    private fun timelineSkeletonIdentity(config: UiConfig): TimelineSkeletonIdentity? {
        if (!config.viewConfig.mosaicEnabled || !config.viewConfig.cacheMosaicResults) return null
        val geometryRequest = timelineMosaicGeometryRequest(config, config.mosaicColumnCount) ?: return null
        return TimelineSkeletonIdentity(
            groupSize = config.groupSize,
            columnCount = config.mosaicColumnCount,
            familiesKey = mosaicFamiliesKey(config.viewConfig.mosaicFamilies),
            geometryRequest = geometryRequest,
            groupingZoneId = timelineMosaicGroupingZoneId(config.groupSize)
        )
    }

    private suspend fun publishBucketGeometrySummaries(
        summaries: List<com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest,
        assetRevisions: Map<String, Int> = _bucketData.value.assetRevisions,
        originStamp: TimelineMosaicPublishStamp? = null,
        originConfig: ActiveMosaicConfig? = null,
        originAssetRevisions: Map<String, Int> = assetRevisions,
        sessionStamp: SessionOperationStamp? = null
    ) {
        if (summaries.isEmpty()) return
        val publishBlock: suspend () -> Unit = {
            mutateTimelineRuntime {
                if (originStamp != null) {
                    val buckets = summaries.map { it.timeBucket }.toSet()
                    if (!runtimeStateStore.isCurrent(originStamp)) return@mutateTimelineRuntime
                    if (buckets.any { _bucketData.value.assetRevisions[it] != originAssetRevisions[it] }) {
                        return@mutateTimelineRuntime
                    }
                    val currentConfig = _uiConfig.value
                    val expectedConfig = originConfig ?: return@mutateTimelineRuntime
                    if (activeMosaicConfigFor(currentConfig) != expectedConfig ||
                        timelineMosaicGeometryRequest(currentConfig, columnCount) != geometryRequest
                    ) {
                        return@mutateTimelineRuntime
                    }
                }
                publishBucketGeometrySummariesLocked(
                    summaries = summaries,
                    groupSize = groupSize,
                    columnCount = columnCount,
                    families = families,
                    geometryRequest = geometryRequest,
                    assetRevisions = assetRevisions
                )
            }
        }
        if (sessionStamp == null) {
            publishBlock()
        } else {
            runTimelineMosaicSessionSink(
                sessionOperationRunner = sessionOperationRunner,
                sessionStamp = sessionStamp,
                block = publishBlock
            )
        }
    }

    private fun publishBucketGeometrySummariesLocked(
        summaries: List<com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest,
        assetRevisions: Map<String, Int>
    ) {
        val updates = summaries.associate { geometry ->
            bucketGeometryCacheKey(
                timeBucket = geometry.timeBucket,
                groupSize = groupSize,
                columnCount = columnCount,
                assetRevision = assetRevisions[geometry.timeBucket] ?: 0,
                families = families,
                geometryRequest = geometryRequest
            ) to geometry.placeholderHeight
        }
        _bucketGeometryStates.update { states -> states + updates }
        requestTimelineSkeletonBucketReload(summaries.map { it.timeBucket }.toSet())
        val readyBuckets = updates.keys
        _bucketData.update { data ->
            data.copy(geometryReadyBuckets = data.geometryReadyBuckets + readyBuckets)
        }
        log.d {
            "Timeline bucket geometry published group=<redacted> columns=<redacted> " +
                    "summaries=<redacted>"
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
        source: TimelineMosaicWorkSource = TimelineMosaicWorkSource.RenderDemand,
        sessionStamp: SessionOperationStamp =
            sessionOperationRunner.stampForOwner(ownerSessionEpoch)
    ) {
        if (timeBuckets.isEmpty()) return
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return
        if (!config.viewConfig.cacheMosaicResults && source != TimelineMosaicWorkSource.RenderDemand) {
            log.d { "Timeline Mosaic sync-source request skipped while cache results are off buckets=<redacted>" }
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
                    selection.deferredBuckets.map {
                        TimelineMosaicQueueRequest(
                            timeBucket = it,
                            source = source,
                            sessionStamp = sessionStamp
                        )
                    }
                )
            )
            log.d { "Timeline Mosaic deferred during scroll buckets=<redacted>" }
        }
        val requestedBuckets = selection.runnableBuckets
        if (requestedBuckets.isEmpty()) return
        enqueueTimelineMosaicBucketsForRead(
            timeBuckets = requestedBuckets,
            visiblePriority = requestedBuckets.any { it in visibleOrTargetBuckets.toSet() },
            source = source,
            sessionStamp = sessionStamp
        )
    }

    private fun enqueueTimelineMosaicBucketsForRead(
        timeBuckets: List<String>,
        visiblePriority: Boolean,
        source: TimelineMosaicWorkSource,
        sessionStamp: SessionOperationStamp
    ) {
        val uniqueBuckets = timeBuckets.distinct()
        if (uniqueBuckets.isEmpty()) return
        timelineMosaicQueue.send(
            TimelineMosaicQueueCommand.Enqueue(
                requests = uniqueBuckets.map {
                    TimelineMosaicQueueRequest(
                        timeBucket = it,
                        source = source,
                        sessionStamp = sessionStamp
                    )
                },
                visiblePriority = visiblePriority,
                visibleOrTargetBuckets = orderedVisibleOrTargetLoadedTimelineBuckets().toSet()
            )
        )
    }

    private suspend fun handleTimelineMosaicQueueEffect(effect: TimelineMosaicQueueEffect) {
        when (effect) {
            is TimelineMosaicQueueEffect.StartWork -> startTimelineMosaicWorker(
                effect.request,
                effect.token
            )

            is TimelineMosaicQueueEffect.CancelWork -> cancelTimelineMosaicWorker(
                effect.request,
                effect.token
            )

            is TimelineMosaicQueueEffect.ResetBoundary -> completeTimelineMosaicRuntimeReset(effect.id)
        }
    }

    private suspend fun startTimelineMosaicWorker(
        request: TimelineMosaicQueueRequest,
        token: Long
    ) {
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                sessionOperationRunner.runForStamp(request.sessionStamp) {
                    runTimelineMosaicQueueWork(request, token)
                }
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
        job?.cancelAndJoin()
        log.d { "Timeline Mosaic worker cancelled bucket=<redacted> source=<redacted> token=<redacted>" }
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
                source = request.source,
                sessionStamp = request.sessionStamp
            )
        } else if (request.source == TimelineMosaicWorkSource.RenderDemand) {
            computeRuntimeTimelineMosaicForBuckets(
                timeBuckets = setOf(timeBucket),
                expectedWorkToken = token,
                expectedConfig = config,
                sessionStamp = request.sessionStamp
            )
        } else {
            log.d { "Timeline Mosaic sync-source queue item skipped while cache results are off bucket=<redacted>" }
        }
    }

    // Sync precompute persists cache artifacts; render-demand work is started
    // by TimelineMosaicQueueActor effects so queue state stays single-owner.
    private suspend fun precomputeTimelineMosaicForBuckets(
        timeBuckets: Set<String>,
        sessionStamp: SessionOperationStamp
    ): TimelineMosaicFailurePublish? {
        if (timeBuckets.isEmpty()) return null
        val originPublishStamp = runtimeStateStore.publishStampForBuckets(timeBuckets)
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return null
        if (!config.viewConfig.cacheMosaicResults) {
            log.d { "Timeline Mosaic sync precompute skipped while cache results are off buckets=<redacted>" }
            return null
        }
        val geometryRequest = timelineMosaicGeometryRequest(config)
        log.d {
            "Timeline Mosaic precompute started buckets=<redacted> group=<redacted> " +
                    "columns=<redacted> families=<redacted> " +
                    "geometryWidth=<redacted> geometryMaxRowHeight=<redacted>"
        }
        val expectedRevisions = _bucketData.value.assetRevisions
        return syncActivityTracker.track(SyncActivityKey.MosaicPreparation) {
            prepareTimelineMosaicCacheAction(
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
                        expectedRevisions = expectedRevisions,
                        originPublishStamp = originPublishStamp,
                        sessionStamp = sessionStamp
                    )
                }
            )
        }.fold(
            onSuccess = { result ->
                if (result.failedBucketIds.isNotEmpty()) {
                    log.e { "Failed to precompute Timeline Mosaic buckets=<redacted>" }
                }
                log.d {
                    "Timeline Mosaic precompute completed requested=<redacted> " +
                            "successful=<redacted> failed=<redacted>"
                }
                failurePublishFor(
                    timeBuckets = result.failedBucketIds,
                    sessionStamp = sessionStamp,
                    config = config,
                    geometryRequest = geometryRequest,
                    stamp = originPublishStamp,
                    revisions = expectedRevisions
                )
            },
            onFailure = { e ->
                log.e { "Failed to precompute Timeline Mosaic assignments" }
                failurePublishFor(
                    timeBuckets = timeBuckets,
                    sessionStamp = sessionStamp,
                    config = config,
                    geometryRequest = geometryRequest,
                    stamp = originPublishStamp,
                    revisions = expectedRevisions
                )
            }
        )
    }

    private fun failurePublishFor(
        timeBuckets: Set<String>,
        sessionStamp: SessionOperationStamp,
        config: UiConfig,
        geometryRequest: TimelineMosaicGeometryRequest?,
        stamp: TimelineMosaicPublishStamp,
        revisions: Map<String, Int>
    ): TimelineMosaicFailurePublish? =
        failurePublishFor(
            timeBuckets = timeBuckets,
            sessionStamp = sessionStamp,
            groupSize = config.groupSize,
            columnCount = config.mosaicColumnCount,
            families = config.viewConfig.mosaicFamilies,
            mosaicEnabled = config.viewConfig.mosaicEnabled,
            cacheMosaicResults = config.viewConfig.cacheMosaicResults,
            geometryRequest = geometryRequest,
            stamp = stamp,
            revisions = revisions
        )

    private fun failurePublishFor(
        timeBuckets: Set<String>,
        sessionStamp: SessionOperationStamp,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        mosaicEnabled: Boolean,
        cacheMosaicResults: Boolean,
        geometryRequest: TimelineMosaicGeometryRequest?,
        stamp: TimelineMosaicPublishStamp,
        revisions: Map<String, Int>
    ): TimelineMosaicFailurePublish? =
        if (timeBuckets.isEmpty()) {
            null
        } else {
            TimelineMosaicFailurePublish(
                sessionStamp = sessionStamp,
                timeBuckets = timeBuckets,
                groupSize = groupSize,
                columnCount = columnCount,
                families = families,
                mosaicEnabled = mosaicEnabled,
                cacheMosaicResults = cacheMosaicResults,
                geometryRequest = geometryRequest,
                stamp = TimelineMosaicPublishStamp(
                    globalGeneration = stamp.globalGeneration,
                    bucketGenerations = timeBuckets.associateWith { timeBucket ->
                        stamp.bucketGenerations[timeBucket] ?: 0L
                    }
                ),
                assetRevisions = timeBuckets.associateWith { timeBucket ->
                    revisions[timeBucket] ?: 0
                }
            )
        }

    private suspend fun publishProgressiveMosaicChunk(
        chunk: TimelineMosaicProgressChunk,
        expectedConfig: UiConfig,
        expectedGeometryRequest: TimelineMosaicGeometryRequest?,
        expectedRevisions: Map<String, Int>,
        originPublishStamp: TimelineMosaicPublishStamp,
        sessionStamp: SessionOperationStamp
    ) {
        val bucketGeneration = originPublishStamp.bucketGenerations[chunk.timeBucket] ?: return
        val generationStamp = TimelineRuntimeGenerationStamp(
            globalGeneration = originPublishStamp.globalGeneration,
            bucketGeneration = bucketGeneration
        )
        if (!runtimeStateStore.generationSnapshot().matches(generationStamp, chunk.timeBucket)) return
        val currentConfig = _uiConfig.value
        if (!sameMosaicReadConfig(expectedConfig, currentConfig)) return
        if (expectedGeometryRequest != null &&
            timelineMosaicGeometryRequest(
                currentConfig,
                expectedConfig.mosaicColumnCount
            ) != expectedGeometryRequest
        ) {
            return
        }
        val expectedRevision = expectedRevisions[chunk.timeBucket] ?: 0
        if (_bucketData.value.assetRevisionFor(chunk.timeBucket) != expectedRevision) return
        if (chunk.sourceEndExclusive <= chunk.sourceStartIndex || chunk.assignments.isEmpty()) return
        if (isTimelineScrollActive.value) {
            timelineMosaicQueue.send(
                TimelineMosaicQueueCommand.Defer(
                    listOf(
                        TimelineMosaicQueueRequest(
                            timeBucket = chunk.timeBucket,
                            source = TimelineMosaicWorkSource.SyncPrecompute,
                            sessionStamp = sessionStamp
                        )
                    )
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
        val added = mutateTimelineRuntime {
            if (!runtimeStateStore.generationSnapshot().matches(generationStamp, chunk.timeBucket)) {
                return@mutateTimelineRuntime false
            }
            val gatedConfig = _uiConfig.value
            if (!sameMosaicReadConfig(expectedConfig, gatedConfig)) return@mutateTimelineRuntime false
            if (expectedGeometryRequest != null &&
                timelineMosaicGeometryRequest(
                    gatedConfig,
                    expectedConfig.mosaicColumnCount
                ) != expectedGeometryRequest
            ) {
                return@mutateTimelineRuntime false
            }
            if (_bucketData.value.assetRevisionFor(chunk.timeBucket) != expectedRevision) {
                return@mutateTimelineRuntime false
            }
            if (isTimelineScrollActive.value) {
                timelineMosaicQueue.send(
                    TimelineMosaicQueueCommand.Defer(
                        listOf(
                            TimelineMosaicQueueRequest(
                                timeBucket = chunk.timeBucket,
                                source = TimelineMosaicWorkSource.SyncPrecompute,
                                sessionStamp = sessionStamp
                            )
                        )
                    )
                )
                return@mutateTimelineRuntime false
            }
            progressiveMosaicBuffer.add(key, runtimeChunk, sessionStamp, generationStamp)
            true
        }
        if (added && chunk.timeBucket in visibleLoadedTimelineBuckets()) {
            scheduleProgressiveMosaicFlush(immediate = false)
        }
    }

    private fun scheduleProgressiveMosaicFlush(immediate: Boolean) {
        if (isTimelineScrollActive.value) return
        progressiveMosaicFlushRequests.update { request ->
            val nextSequence = request.sequence + 1L
            request.copy(
                sequence = nextSequence,
                immediateSequence = if (immediate) nextSequence else request.immediateSequence
            )
        }
    }

    private suspend fun runProgressiveMosaicFlushOwner() {
        var processedSequence = 0L
        progressiveMosaicFlushRequests.collect { emitted ->
            var request = emitted
            while (request.sequence > processedSequence) {
                val targetSequence = request.sequence
                val runImmediate = request.immediateSequence > processedSequence
                processedSequence = targetSequence
                if (!runImmediate) {
                    withTimeoutOrNull(PROGRESSIVE_MOSAIC_FLUSH_DELAY_MS) {
                        progressiveMosaicFlushRequests.first { it.immediateSequence > processedSequence }
                    }
                }
                flushProgressiveMosaicBuffer()
                request = progressiveMosaicFlushRequests.value
            }
        }
    }

    private suspend fun flushProgressiveMosaicBuffer() {
        val eligibleBuckets = visibleLoadedTimelineBuckets()
        val eligibleKeys = _mosaicGeometryStates.value
            .filter { (key, geometry) -> key.timeBucket in eligibleBuckets && geometry.ranges.isNotEmpty() }
            .keys
        val pendingPublishes = progressiveMosaicBuffer.drainEligibleKeys(eligibleKeys)
        if (pendingPublishes.isEmpty()) return
        pendingPublishes.forEach { publish ->
            runTimelineMosaicSessionSink(
                sessionOperationRunner = sessionOperationRunner,
                sessionStamp = publish.sessionStamp
            ) {
                mutateTimelineRuntime {
                    if (isTimelineScrollActive.value) {
                        publish.updatesByKey.forEach { (key, chunks) ->
                            val generationStamp = publish.generationStampsByKey[key]
                                ?: return@forEach
                            val clearOrigin = publish.clearOriginsByKey[key]
                                ?: return@forEach
                            chunks.forEach { chunk ->
                                progressiveMosaicBuffer.requeueIfCurrent(
                                    key = key,
                                    chunk = chunk,
                                    sessionStamp = publish.sessionStamp,
                                    generationStamp = generationStamp,
                                    clearOrigin = clearOrigin
                                )
                            }
                        }
                        if (!isTimelineScrollActive.value) {
                            scheduleProgressiveMosaicFlush(immediate = true)
                        }
                        return@mutateTimelineRuntime
                    }
                    val generationSnapshot = runtimeStateStore.generationSnapshot()
                    val currentUpdates = publish.updatesByKey.filterKeys { key ->
                        publish.generationStampsByKey[key]?.let { stamp ->
                            generationSnapshot.matches(stamp, key.timeBucket)
                        } == true &&
                                publish.clearOriginsByKey[key]?.let { origin ->
                                    progressiveMosaicBuffer.isCurrentOrigin(key, origin)
                                } == true
                    }
                    if (currentUpdates.isEmpty()) return@mutateTimelineRuntime
                    _mosaicStates.update { states ->
                        timelineProgressiveMosaicStateUpdates(states, currentUpdates)
                    }
                }
            }
        }
        log.d {
            "Timeline progressive Mosaic chunks flushed buckets=<redacted> " +
                    "sections=<redacted> chunks=<redacted>"
        }
    }

    private suspend fun computeRuntimeTimelineMosaicForBuckets(
        timeBuckets: Set<String>,
        expectedWorkToken: Long? = null,
        expectedConfig: UiConfig? = null,
        requestedSectionKeysByBucket: Map<String, Set<String>> = emptyMap(),
        seedStateUpdates: Map<MosaicCacheKey, RuntimeMosaicState> = emptyMap(),
        seedGeometryUpdates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry> = emptyMap(),
        originPublishStamp: TimelineMosaicPublishStamp? = null,
        sessionStamp: SessionOperationStamp
    ): Set<String> {
        if (timeBuckets.isEmpty()) return emptySet()
        val capturedOriginPublishStamp =
            originPublishStamp ?: runtimeStateStore.publishStampForBuckets(timeBuckets)
        if (!runtimeStateStore.isCurrent(capturedOriginPublishStamp)) return emptySet()
        if (isTimelineScrollActive.value) {
            timelineMosaicQueue.send(
                TimelineMosaicQueueCommand.Defer(
                    timeBuckets.map {
                        TimelineMosaicQueueRequest(
                            timeBucket = it,
                            source = TimelineMosaicWorkSource.RenderDemand,
                            sessionStamp = sessionStamp
                        )
                    }
                )
            )
            return emptySet()
        }
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return emptySet()
        if (expectedConfig != null && !sameMosaicReadConfig(
                expectedConfig,
                config
            )
        ) return emptySet()
        val layoutSpec =
            mosaicLayoutSpecForColumnCount(timelineMosaicLayoutWidth(config), config.mosaicColumnCount)
                ?: return emptySet()
        val columnCount = config.mosaicColumnCount
        val families = config.viewConfig.mosaicFamilies
        val revisions = _bucketData.value.assetRevisions
        val bucketIndexes =
            _bucketData.value.buckets.mapIndexed { index, bucket -> bucket.timeBucket to index }
                .toMap()
        if (!runtimeStateStore.isCurrent(capturedOriginPublishStamp)) return emptySet()
        val stateUpdates = seedStateUpdates.toMutableMap()
        val geometryUpdates = seedGeometryUpdates.toMutableMap()
        val failedBuckets = mutableSetOf<String>()
        for (timeBucket in timeBuckets) {
            if (!isActiveTimelineMosaicWorker(expectedWorkToken)) return failedBuckets
            if (expectedConfig != null && !sameMosaicReadConfig(
                    expectedConfig,
                    _uiConfig.value
                )
            ) return failedBuckets
            val assetRevision = revisions[timeBucket] ?: 0
            val assets = getCachedOrLoadBucketAssets(
                timeBucket = timeBucket,
                expectedGeneration = TimelineRuntimeBucketGenerationStamp(
                    bucketGeneration = capturedOriginPublishStamp.bucketGenerations[timeBucket] ?: 0L
                )
            ) ?: continue
            if (assets.isEmpty()) continue
            val bucketIndex = bucketIndexes[timeBucket] ?: 0
            val sections = runtimeMosaicSections(
                timeBucket = timeBucket,
                groupSize = config.groupSize,
                assetRevision = assetRevision,
                assets = assets
            )
            val requestedSectionKeys = requestedSectionKeysByBucket[timeBucket]
            for (section in sections) {
                if (requestedSectionKeys != null && section.sectionKey !in requestedSectionKeys) continue
                val key = mosaicCacheKey(
                    timeBucket = timeBucket,
                    sectionKey = section.sectionKey,
                    columnCount = columnCount,
                    assetRevision = assetRevision,
                    families = families
                )
                if (isCurrentRuntimeMosaicSection(key)) continue
                val generationStamp = TimelineRuntimeGenerationStamp(
                    globalGeneration = capturedOriginPublishStamp.globalGeneration,
                    bucketGeneration = capturedOriginPublishStamp.bucketGenerations[timeBucket] ?: 0L
                )
                val resumeState = runtimeStateStore.resumeStateFor(
                    key = key,
                    sessionStamp = sessionStamp,
                    expectedStamp = generationStamp
                )
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
                            onProgressChunk = progress@{ chunk ->
                                if (chunk.sourceEndExclusive <= chunk.sourceStartIndex || chunk.assignments.isEmpty()) {
                                    return@progress
                                }
                                val added = mutateTimelineRuntime {
                                    if (!isActiveTimelineMosaicWorker(expectedWorkToken)) {
                                        return@mutateTimelineRuntime false
                                    }
                                    if (!runtimeStateStore.generationSnapshot().matches(generationStamp, timeBucket)) {
                                        return@mutateTimelineRuntime false
                                    }
                                    if (expectedConfig != null &&
                                        !sameMosaicReadConfig(expectedConfig, _uiConfig.value)
                                    ) {
                                        return@mutateTimelineRuntime false
                                    }
                                    if (_bucketData.value.assetRevisionFor(timeBucket) != assetRevision) {
                                        return@mutateTimelineRuntime false
                                    }
                                    if (isTimelineScrollActive.value) return@mutateTimelineRuntime false
                                    progressChunks = (progressChunks + chunk)
                                        .distinctBy { it.sourceStartIndex to it.sourceEndExclusive }
                                        .sortedBy { it.sourceStartIndex }
                                    progressiveMosaicBuffer.add(key, chunk, sessionStamp, generationStamp)
                                    true
                                }
                                if (added && timeBucket in visibleLoadedTimelineBuckets()) {
                                    scheduleProgressiveMosaicFlush(immediate = false)
                                }
                            },
                            shouldContinue = {}
                        )
                    }) {
                        is MosaicSectionResult.Ready -> {
                            runtimeStateStore.removeResumeState(
                                key = key,
                                expectedStamp = generationStamp,
                                sessionStamp = sessionStamp
                            )
                            if (key in repairRequiredMosaicKeys.value) {
                                repairRequiredMosaicKeys.update { it - key }
                                recomputedRepairMosaicKeys.update { it + key }
                            }
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
                            runtimeStateStore.removeResumeState(
                                key = key,
                                expectedStamp = generationStamp,
                                sessionStamp = sessionStamp
                            )
                            stateUpdates[key] = RuntimeMosaicState.Failed
                            failedBuckets.add(timeBucket)
                        }
                    }
                } catch (e: CancellationException) {
                    latestCheckpoint?.let { checkpoint ->
                        runtimeStateStore.saveResumeState(
                            key = key,
                            resumeState = TimelineRuntimeMosaicResumeState(
                                sessionStamp = sessionStamp,
                                checkpoint = checkpoint,
                                chunks = progressChunks
                            ),
                            expectedStamp = generationStamp
                        )
                        timelineMosaicQueue.send(
                            TimelineMosaicQueueCommand.Defer(
                                listOf(
                                    TimelineMosaicQueueRequest(
                                        timeBucket = timeBucket,
                                        source = TimelineMosaicWorkSource.RenderDemand,
                                        sessionStamp = sessionStamp
                                    )
                                )
                            )
                        )
                        log.d { "Timeline runtime Mosaic checkpoint saved bucket=<redacted> section=<redacted>" }
                    }
                    throw e
                }
            }
        }
        if (!isActiveTimelineMosaicWorker(expectedWorkToken)) return failedBuckets
        val repairRequests = if (config.viewConfig.cacheMosaicResults) {
            buildRuntimeMosaicRepairRequests(
                timeBuckets = timeBuckets - failedBuckets,
                config = config,
                geometryRequest = timelineMosaicGeometryRequest(config, columnCount),
                stateUpdates = stateUpdates,
                geometryUpdates = geometryUpdates,
                sessionStamp = sessionStamp,
                publishStamp = capturedOriginPublishStamp
            )
        } else {
            emptyList()
        }
        publishMosaicUpdates(
            sessionStamp = sessionStamp,
            stateUpdates = stateUpdates,
            geometryUpdates = geometryUpdates,
            stamp = capturedOriginPublishStamp,
            originConfig = activeMosaicConfigFor(config),
            originGeometryRequest = timelineMosaicGeometryRequest(config, columnCount),
            originAssetRevisions = revisions.filterKeys { it in timeBuckets },
            repairRequests = repairRequests
        )
        return failedBuckets
    }

    private fun buildRuntimeMosaicRepairRequests(
        timeBuckets: Set<String>,
        config: UiConfig,
        geometryRequest: TimelineMosaicGeometryRequest?,
        stateUpdates: Map<MosaicCacheKey, RuntimeMosaicState>,
        geometryUpdates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry>,
        sessionStamp: SessionOperationStamp,
        publishStamp: TimelineMosaicPublishStamp
    ): List<TimelineMosaicRepairRequest> {
        val request = geometryRequest ?: return emptyList()
        val states = _mosaicStates.value + stateUpdates
        val geometries = _mosaicGeometryStates.value + geometryUpdates
        val revisions = _bucketData.value.assetRevisions
        return timeBuckets.mapNotNull { timeBucket ->
            val assets = assetsForRender(timeBucket) ?: return@mapNotNull null
            val assetRevision = revisions[timeBucket] ?: 0
            val sections = runtimeMosaicSections(
                timeBucket,
                config.groupSize,
                assetRevision,
                assets
            )
            val inputs = sections.mapNotNull { section ->
                val key = mosaicCacheKey(
                    timeBucket,
                    section.sectionKey,
                    config.mosaicColumnCount,
                    assetRevision,
                    config.viewConfig.mosaicFamilies
                )
                val ready = states[key] as? RuntimeMosaicState.Ready ?: return@mapNotNull null
                val geometry = geometries[key] ?: return@mapNotNull null
                section.sectionKey to TimelineMosaicRepairSectionInput(
                    assignments = ready.assignments,
                    displayRecords = ready.displayRecords,
                    geometry = TimelineMosaicGeometrySummary(
                        timeBucket = timeBucket,
                        sectionKey = section.sectionKey,
                        placeholderHeight = geometry.placeholderHeight,
                        displayItemCount = geometry.ranges.size,
                        ranges = geometry.ranges
                    )
                )
            }.toMap()
            if (inputs.size != sections.size) return@mapNotNull null
            TimelineMosaicRepairRequest(
                sessionStamp = sessionStamp,
                timeBucket = timeBucket,
                groupSize = config.groupSize,
                columnCount = config.mosaicColumnCount,
                families = config.viewConfig.mosaicFamilies,
                geometryRequest = request,
                assetRevision = assetRevision,
                globalGeneration = publishStamp.globalGeneration,
                bucketGeneration = publishStamp.bucketGenerations[timeBucket] ?: 0L,
                sectionsByKey = inputs
            )
        }
    }

    private suspend fun processTimelineMosaicRepairBatch(
        firstRequests: List<TimelineMosaicRepairRequest>
    ) {
        val pending = firstRequests.toMutableList()
        pending.addAll(mosaicRepairSignalBuffer.drainAvailable())
        if (isTimelineScrollActive.value) {
            isTimelineScrollActive.first { active -> !active }
            pending.addAll(mosaicRepairSignalBuffer.drainAvailable())
        }
        val requests = coalesceTimelineMosaicRepairRequests(pending)
        requests.forEachIndexed { index, request ->
            if (isTimelineScrollActive.value) {
                mosaicRepairSignalBuffer.requeueAll(requests.drop(index))
                return
            }
            try {
                coroutineScope {
                    async {
                        sessionOperationRunner.runForStamp(
                            request.sessionStamp
                        ) repairOperation@{
                            if (!isCurrentTimelineMosaicRepair(request)) return@repairOperation
                            val prepared = repairTimelineMosaicCacheAction.prepare(
                                timeBucket = request.timeBucket,
                                groupSize = request.groupSize,
                                columnCount = request.columnCount,
                                families = request.families,
                                geometryRequest = request.geometryRequest,
                                sectionsByKey = request.sectionsByKey
                            ).getOrElse { error ->
                                if (error is CancellationException) throw error
                                if (error is TimelineMosaicRepairRequiredException) {
                                    markTimelineMosaicRepairRequired(
                                        error.sectionKeys.map { sectionKey ->
                                            mosaicCacheKey(
                                                timeBucket = request.timeBucket,
                                                sectionKey = sectionKey,
                                                columnCount = request.columnCount,
                                                assetRevision = request.assetRevision,
                                                families = request.families
                                            )
                                        }.toSet()
                                    )
                                    return@repairOperation
                                }
                                log.e {
                                    "Timeline runtime Mosaic repair preparation failed bucket=<redacted>"
                                }
                                return@repairOperation
                            }
                            val commitOutcome = commitTimelineRuntime(
                                requireSettled = true,
                                isCurrent = { isCurrentTimelineMosaicRepair(request) }
                            ) {
                                val commitResult = repairTimelineMosaicCacheAction.commit(prepared)
                                commitResult.onFailure { error ->
                                    if (error is CancellationException) throw error
                                    log.e {
                                        "Timeline runtime Mosaic repair persistence failed bucket=<redacted>"
                                    }
                                }.onSuccess {
                                    log.d {
                                        "Timeline runtime Mosaic repair persisted bucket=<redacted> " +
                                                "elapsed=<redacted>"
                                    }
                                    requestTimelineSkeletonBucketReload(
                                        setOf(request.timeBucket),
                                        request.sessionStamp
                                    )
                                }
                                commitResult.isSuccess
                            }
                            if (commitOutcome == null) {
                                mosaicRepairSignalBuffer.requeueAll(listOf(request))
                            }
                        }
                    }.await()
                }
            } catch (_: StaleSessionOperationException) {
                // Session mutation cancelled this isolated repair child. The
                // long-lived channel consumer remains available if cleanup
                // fails and the current ViewModel stays mounted.
            } catch (_: SessionOperationRejectedException) {
                // Admission closed before the child registered. The stamped
                // request belongs to the generation being replaced.
            }
        }
    }

    private fun isCurrentTimelineMosaicRepair(request: TimelineMosaicRepairRequest): Boolean {
        val config = _uiConfig.value
        val generationSnapshot = runtimeStateStore.generationSnapshot()
        return config.viewConfig.mosaicEnabled &&
                config.viewConfig.cacheMosaicResults &&
                request.belongsToSession(
                    sessionOperationRunner.stampForOwner(ownerSessionEpoch)
                ) &&
                request.globalGeneration == generationSnapshot.globalGeneration &&
                request.bucketGeneration == (generationSnapshot.bucketGenerations[request.timeBucket] ?: 0L) &&
                request.assetRevision == (_bucketData.value.assetRevisions[request.timeBucket] ?: 0) &&
                request.groupSize == config.groupSize &&
                request.columnCount == config.mosaicColumnCount &&
                mosaicFamiliesKey(request.families) == mosaicFamiliesKey(config.viewConfig.mosaicFamilies) &&
                request.geometryRequest == timelineMosaicGeometryRequest(
                    config,
                    config.mosaicColumnCount
                )
    }

    private fun publishMosaicUpdates(
        sessionStamp: SessionOperationStamp,
        stateUpdates: Map<MosaicCacheKey, RuntimeMosaicState>,
        geometryUpdates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry> = emptyMap(),
        stamp: TimelineMosaicPublishStamp,
        originConfig: ActiveMosaicConfig? = activeMosaicConfigFor(_uiConfig.value),
        originGeometryRequest: TimelineMosaicGeometryRequest? =
            timelineMosaicGeometryRequest(_uiConfig.value, _uiConfig.value.mosaicColumnCount),
        originAssetRevisions: Map<String, Int> = _bucketData.value.assetRevisions,
        repairRequests: List<TimelineMosaicRepairRequest> = emptyList()
    ) {
        if (stateUpdates.isEmpty() && geometryUpdates.isEmpty() && repairRequests.isEmpty()) return
        val publishBuckets = stateUpdates.keys.map { it.timeBucket }.toSet() +
                geometryUpdates.keys.map { it.timeBucket }.toSet() +
                repairRequests.map { it.timeBucket }.toSet()
        val resolvedOriginAssetRevisions = publishBuckets.associateWith { timeBucket ->
            originAssetRevisions[timeBucket] ?: 0
        }
        val publish = PendingMosaicPublish(
            sessionStamp = sessionStamp,
            stateUpdates = stateUpdates,
            geometryUpdates = geometryUpdates,
            stamp = stamp,
            originConfig = originConfig,
            originGeometryRequest = originGeometryRequest,
            originAssetRevisions = resolvedOriginAssetRevisions,
            repairRequests = repairRequests
        )
        if (isTimelineScrollActive.value) {
            if (runtimeStateStore.deferMosaicPublish(publish)) {
                requestDeferredTimelineDrainIfSettled()
            }
            log.d {
                "Timeline Mosaic updates deferred during scroll states=<redacted> " +
                        "geometry=<redacted>"
            }
            return
        }
        mosaicPublishSignalBuffer.offer(publish)
    }

    private fun isCurrentMosaicPublishOrigin(publish: PendingMosaicPublish): Boolean {
        val originConfig = publish.originConfig
            ?: return false
        val currentConfig = activeMosaicConfigFor(_uiConfig.value)
        if (currentConfig != originConfig) return false
        val expectedGeometry = timelineMosaicGeometryRequest(_uiConfig.value, originConfig.columnCount)
        if (publish.originGeometryRequest != expectedGeometry) return false
        if (publish.originAssetRevisions.isEmpty()) return false
        val currentRevisions = _bucketData.value.assetRevisions
        return publish.originAssetRevisions.all { (timeBucket, revision) ->
            (currentRevisions[timeBucket] ?: 0) == revision
        }
    }

    private fun activeMosaicConfigFor(config: UiConfig): ActiveMosaicConfig =
        ActiveMosaicConfig(
            groupSize = config.groupSize,
            columnCount = config.mosaicColumnCount,
            families = config.viewConfig.mosaicFamilies,
            mosaicEnabled = config.viewConfig.mosaicEnabled,
            cacheMosaicResults = config.viewConfig.cacheMosaicResults
        )

    private suspend fun publishDeferredScrollMosaicUpdates(priorityBuckets: Set<String>) {
        if (!runtimeStateStore.hasDeferredMosaicWork()) {
            return
        }
        val pending = runtimeStateStore.drainDeferredMosaicPublishes(priorityBuckets)
        mutateTimelineRuntime {
            if (isTimelineScrollActive.value) {
                runtimeStateStore.requeueMosaicPublishes(pending)
                requestDeferredTimelineDrainIfSettled()
                return@mutateTimelineRuntime
            }
            mosaicPublishSignalBuffer.offerAll(pending)
        }
        log.d {
                    "Timeline deferred Mosaic updates filtered and published " +
                    "priorityBuckets=<redacted> states=<redacted> " +
                    "geometry=<redacted> repairs=<redacted>"
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
                    sectionKey = dayGroup.sectionKey,
                    sectionLabel = dayGroup.sectionKey,
                    assets = dayGroup.assets
                )
            }
        } else {
            listOf(
                TimelineRuntimeMosaicSection(
                    sectionKey = monthMosaicSectionKey(timeBucket),
                    sectionLabel = timeBucket,
                    assets = assets
                )
            )
        }

    private suspend fun loadPersistedMosaicAssignmentsForBuckets(
        timeBuckets: Set<String>,
        expectedWorkToken: Long? = null,
        expectedConfig: UiConfig? = null,
        source: TimelineMosaicWorkSource = TimelineMosaicWorkSource.RenderDemand,
        sessionStamp: SessionOperationStamp
    ) {
        if (timeBuckets.isEmpty()) return
        val originPublishStamp = runtimeStateStore.publishStampForBuckets(timeBuckets)
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return
        if (!config.viewConfig.cacheMosaicResults) {
            if (shouldComputeRuntimeTimelineMosaic(config.viewConfig.cacheMosaicResults, source)) {
                computeRuntimeTimelineMosaicForBuckets(
                    timeBuckets = timeBuckets,
                    expectedWorkToken = expectedWorkToken,
                    expectedConfig = expectedConfig,
                    sessionStamp = sessionStamp
                )
            } else {
                log.d { "Timeline persisted Mosaic sync-source read skipped while cache results are off buckets=<redacted>" }
            }
            return
        }
        val columnCount = config.mosaicColumnCount
        val families = config.viewConfig.mosaicFamilies
        val geometryRequest = timelineMosaicGeometryRequest(config, columnCount)
        log.d {
            "Timeline persisted Mosaic read started buckets=<redacted> group=<redacted> " +
                    "columns=<redacted> families=<redacted> geometryWidth=<redacted>"
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
            log.e { "Failed to load persisted Timeline Mosaic assignments" }
        }.onSuccess { status ->
            if (!isActiveTimelineMosaicWorker(expectedWorkToken)) {
                log.d { "Timeline persisted Mosaic read ignored due to inactive worker token=<redacted>" }
                return@onSuccess
            }
            if (expectedConfig != null && !sameMosaicReadConfig(expectedConfig, _uiConfig.value)) {
                log.d { "Timeline persisted Mosaic read ignored due to stale config" }
                return@onSuccess
            }
            if (!runtimeStateStore.isCurrent(originPublishStamp)) {
                log.d { "Timeline persisted Mosaic read ignored due to stale runtime generation" }
                return@onSuccess
            }
            val revisions = _bucketData.value.assetRevisions
            val publishableAssignments = status.assignments
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
            val displayRecordsBySection =
                status.displaySections.associateBy { it.timeBucket to it.sectionKey }
            val cachedUpdates = publishableAssignments.associate { assignment ->
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
            val repairRequiredKeys = repairRequiredMosaicKeys.value.filter { it in cachedUpdates.keys }.toSet()
            val updates = cachedUpdates - repairRequiredKeys
            val missingBucketIds = status.missingBucketIds + repairRequiredKeys.map { it.timeBucket }
            val missingSectionKeysByBucket = missingBucketIds.associateWith { bucket ->
                status.missingSectionKeysByBucket[bucket].orEmpty() +
                        repairRequiredKeys.filter { it.timeBucket == bucket }.map { it.sectionKey }
            }
            if (missingBucketIds.isNotEmpty()) {
                if (source == TimelineMosaicWorkSource.RenderDemand) {
                    log.d {
                        "Timeline persisted Mosaic cache miss using runtime compute buckets=<redacted>"
                    }
                    computeRuntimeTimelineMosaicForBuckets(
                        timeBuckets = missingBucketIds,
                        expectedWorkToken = expectedWorkToken,
                        expectedConfig = expectedConfig ?: config,
                        requestedSectionKeysByBucket = missingSectionKeysByBucket,
                        seedStateUpdates = updates,
                        seedGeometryUpdates = geometryUpdates,
                        originPublishStamp = originPublishStamp,
                        sessionStamp = sessionStamp
                    )
                } else {
                    publishMosaicUpdates(
                        sessionStamp = sessionStamp,
                        stateUpdates = updates,
                        geometryUpdates = geometryUpdates,
                        stamp = originPublishStamp,
                        originConfig = activeMosaicConfigFor(config),
                        originGeometryRequest = geometryRequest,
                        originAssetRevisions = revisions.filterKeys { it in timeBuckets }
                    )
                    log.d {
                        "Timeline persisted Mosaic cache miss left for render demand buckets=<redacted>"
                    }
                }
            } else {
                publishMosaicUpdates(
                    sessionStamp = sessionStamp,
                    stateUpdates = updates,
                    geometryUpdates = geometryUpdates,
                    stamp = originPublishStamp,
                    originConfig = activeMosaicConfigFor(config),
                    originGeometryRequest = geometryRequest,
                    originAssetRevisions = revisions.filterKeys { it in timeBuckets }
                )
            }
            log.d {
                "Timeline persisted Mosaic read completed requested=<redacted> " +
                        "assignments=<redacted> geometry=<redacted> " +
                        "display=<redacted> " +
                        "assignmentGeometryReady=<redacted> " +
                        "displayReady=<redacted> " +
                        "complete=<redacted> missing=<redacted>"
            }
        }
    }

    private fun sameMosaicReadConfig(
        expected: UiConfig,
        actual: UiConfig
    ): Boolean =
        expected.groupSize == actual.groupSize &&
                expected.availableWidth == actual.availableWidth &&
                expected.rowHeightBounds.max == actual.rowHeightBounds.max &&
                expected.mosaicColumnCount == actual.mosaicColumnCount &&
                expected.viewConfig.mosaicEnabled == actual.viewConfig.mosaicEnabled &&
                expected.viewConfig.cacheMosaicResults == actual.viewConfig.cacheMosaicResults &&
                expected.viewConfig.mosaicFamilies.normalizedMosaicFamilies() ==
                actual.viewConfig.mosaicFamilies.normalizedMosaicFamilies()

    private suspend fun markTimelineMosaicFailedForBuckets(failure: TimelineMosaicFailurePublish) {
        if (failure.timeBuckets.isEmpty()) return
        val updates = mutableMapOf<MosaicCacheKey, RuntimeMosaicState>()
        for (timeBucket in failure.timeBuckets) {
            val assetRevision = failure.assetRevisions[timeBucket] ?: 0
            if (failure.groupSize == TimelineGroupSize.DAY) {
                val assets = assetsForRender(timeBucket) ?: getBucketAssetsUseCase(timeBucket)
                dayGroupsForBucket(timeBucket, assetRevision, assets).forEach { dayGroup ->
                    updates[mosaicCacheKey(
                        timeBucket = timeBucket,
                        sectionKey = dayGroup.sectionKey,
                        columnCount = failure.columnCount,
                        assetRevision = assetRevision,
                        families = failure.families
                    )] = RuntimeMosaicState.Failed
                }
            } else {
                updates[mosaicCacheKey(
                    timeBucket = timeBucket,
                    sectionKey = monthMosaicSectionKey(timeBucket),
                    columnCount = failure.columnCount,
                    assetRevision = assetRevision,
                    families = failure.families
                )] = RuntimeMosaicState.Failed
            }
        }
        if (updates.isNotEmpty()) {
            runTimelineMosaicSessionSink(
                sessionOperationRunner = sessionOperationRunner,
                sessionStamp = failure.sessionStamp
            ) {
                mutateTimelineRuntime {
                    val currentConfig = _uiConfig.value
                    if (currentConfig.groupSize != failure.groupSize ||
                        currentConfig.mosaicColumnCount != failure.columnCount ||
                        currentConfig.viewConfig.mosaicFamilies != failure.families ||
                        currentConfig.viewConfig.mosaicEnabled != failure.mosaicEnabled ||
                        currentConfig.viewConfig.cacheMosaicResults != failure.cacheMosaicResults ||
                        timelineMosaicGeometryRequest(currentConfig, failure.columnCount) != failure.geometryRequest
                    ) {
                        return@mutateTimelineRuntime
                    }
                    if (!runtimeStateStore.isCurrent(failure.stamp)) return@mutateTimelineRuntime
                    val currentRevisions = _bucketData.value.assetRevisions
                    if (failure.timeBuckets.any { currentRevisions[it] != failure.assetRevisions[it] }) {
                        return@mutateTimelineRuntime
                    }
                    _mosaicStates.update { states ->
                        timelineMergeMosaicStateUpdatesPreservingReady(states, updates)
                    }
                    progressiveMosaicBuffer.clearKeys(updates.keys)
                }
            }
        }
    }

    private fun timelineMosaicLayoutSpec(config: UiConfig): MosaicLayoutSpec? =
        if (config.viewConfig.mosaicEnabled) {
            mosaicLayoutSpecForColumnCount(
                timelineMosaicLayoutWidth(config),
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

    // Cached Mosaic geometry is keyed on a quantized width, so generation and replay must lay out at it.
    private fun timelineMosaicLayoutWidth(config: UiConfig): Float =
        if (config.viewConfig.mosaicEnabled && config.viewConfig.cacheMosaicResults) {
            timelineMosaicCacheWidth(config.availableWidth)
        } else {
            config.availableWidth
        }

    private fun timelineMosaicGeometryRequest(
        availableWidth: Float,
        maxRowHeight: Float,
        columnCount: Int
    ): TimelineMosaicGeometryRequest? {
        val cacheWidth = timelineMosaicCacheWidth(availableWidth)
        return if (cacheWidth > 0f) {
            TimelineMosaicGeometryRequest(
                availableWidth = cacheWidth,
                maxRowHeight = maxRowHeight,
                spacing = GRID_SPACING_DP
            ).takeIf { mosaicLayoutSpecForColumnCount(cacheWidth, columnCount) != null }
        } else {
            null
        }
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

    private suspend fun getCachedOrLoadBucketAssets(
        timeBucket: String,
        expectedGeneration: TimelineRuntimeBucketGenerationStamp
    ): List<Asset>? =
        getOrLoadBucketAssetsCache(
            timeBucket = timeBucket,
            expectedGeneration = expectedGeneration
        )

    private fun shouldUseMosaicLayout(
        layoutSpec: MosaicLayoutSpec?,
        viewConfig: ViewConfig
    ): Boolean =
        viewConfig.mosaicEnabled && layoutSpec != null

    private fun dayGroupsForBucket(
        timeBucket: String,
        assetRevision: Int,
        assets: List<Asset>
    ): List<DayGroup> {
        val key = DayGroupsCacheKey(timeBucket, assetRevision)
        return runtimeStateStore.dayGroupsFor(key) {
            computeDayGroups(timeBucket, assets)
        }
    }

    private fun computeDayGroups(timeBucket: String, assets: List<Asset>): List<DayGroup> {
        val sections = timelineDaySections(timeBucket = timeBucket, assets = assets)
        val unparseable = sections.firstOrNull { it.date == null }?.assets?.size ?: 0
        if (unparseable > 0) {
            log.w { "<redacted> assets have unparseable createdAt dates" }
        }
        return sections.map { section ->
            DayGroup(
                sectionKey = section.sectionKey,
                label = timelineDayDisplayLabel(section.date),
                assets = section.assets
            )
        }
    }

    companion object {
        private const val PREFETCH_BUCKET_RADIUS = 2
        private const val CACHED_BUCKET_MATERIALIZATION_CHUNK_SIZE = 2
        private const val PROGRESSIVE_MOSAIC_FLUSH_DELAY_MS = 250L
        private const val TIMELINE_IDLE_NEIGHBOR_WARMUP_DELAY_MS = 250L
        private const val TIMELINE_CACHE_OFF_WARMUP_SCREENS = 4f
        private const val TIMELINE_CACHE_OFF_WARMUP_MAX_BUCKETS_PER_DIRECTION = 6
        private const val TIMELINE_REBUILD_LOG_THRESHOLD_MS = 16L
    }
}
