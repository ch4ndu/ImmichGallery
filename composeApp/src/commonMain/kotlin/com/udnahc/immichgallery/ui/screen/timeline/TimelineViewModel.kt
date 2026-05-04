package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.action.timeline.LoadBucketAssetsAction
import com.udnahc.immichgallery.domain.action.timeline.PrecomputeTimelineMosaicAction
import com.udnahc.immichgallery.domain.action.timeline.SyncAllTimelineAssetsAction
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandAssignment
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
import com.udnahc.immichgallery.domain.model.MosaicLayoutSpec
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TIMELINE_MOSAIC_COMPACT_COLUMN_COUNT
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.TimelineScrollTarget
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildTimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.buildPhotoGridItemsWithMosaic
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
import com.udnahc.immichgallery.domain.model.timelineScrollTargetForFraction
import com.udnahc.immichgallery.domain.model.bucketIndexForTimelineFraction
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketGeometryUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineMosaicAssignmentsUseCase
import com.udnahc.immichgallery.ui.model.ConnectionUiMessage
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
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

internal data class TimelineRefreshQueueOrdering(
    val pendingBuckets: List<String>,
    val pendingDragBuckets: Set<String>
)

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
    // Content revisions are per bucket on purpose. Launch sync can refresh
    // every bucket successfully while changing none of them; a global revision
    // would make every packed row and Mosaic assignment look stale anyway.
    val assetRevisions: Map<String, Int> = emptyMap()
)

private fun BucketData.assetRevisionFor(timeBucket: String): Int =
    assetRevisions[timeBucket] ?: 0

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
    val mosaicColumnCount: Int = TIMELINE_MOSAIC_COMPACT_COLUMN_COUNT,
    val viewConfig: ViewConfig = ViewConfig(),
    val activeMosaicConfig: ActiveMosaicConfig? = null
)

private data class MosaicCacheKey(
    val timeBucket: String,
    val sectionKey: String,
    val columnCount: Int,
    val assetRevision: Int,
    val familiesKey: String
)

private data class ActiveMosaicConfig(
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val families: Set<MosaicTemplateFamily>
)

private data class DayGroupsCacheKey(
    val timeBucket: String,
    val assetRevision: Int
)

private sealed interface RuntimeMosaicState {
    data object Pending : RuntimeMosaicState
    data class Ready(val assignments: List<MosaicBandAssignment>) : RuntimeMosaicState
    data object Failed : RuntimeMosaicState
}

private fun RuntimeMosaicState?.cacheToken(): String =
    when (this) {
        null -> "missing"
        RuntimeMosaicState.Pending -> "pending"
        is RuntimeMosaicState.Ready -> "ready_${assignments.size}"
        RuntimeMosaicState.Failed -> "failed"
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

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(
    private val getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
    private val getBucketAssetsUseCase: GetBucketAssetsUseCase,
    private val loadBucketAssetsAction: LoadBucketAssetsAction,
    private val syncAllTimelineAssetsAction: SyncAllTimelineAssetsAction,
    private val precomputeTimelineMosaicAction: PrecomputeTimelineMosaicAction,
    private val getTimelineMosaicAssignmentsUseCase: GetTimelineMosaicAssignmentsUseCase,
    private val getTimelineBucketGeometryUseCase: GetTimelineBucketGeometryUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetFileNameUseCase: GetAssetFileNameUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTimelineGroupSizeUseCase: GetTimelineGroupSizeUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTimelineGroupSizeAction: SetTimelineGroupSizeAction,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    var lastViewedAssetId: String? by mutableStateOf(null)
    var lastViewedBucket: String? by mutableStateOf(null)

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    suspend fun getAssetFileName(assetId: String, fallback: String): Result<String> =
        getAssetFileNameUseCase(assetId, fallback)

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

    private val _bucketData = MutableStateFlow(BucketData())
    private val _uiConfig: MutableStateFlow<UiConfig>
    private val _mosaicStates = MutableStateFlow<Map<MosaicCacheKey, RuntimeMosaicState>>(emptyMap())
    private val _mosaicGeometryStates = MutableStateFlow<Map<MosaicCacheKey, Float>>(emptyMap())
    private val _bucketGeometryStates = MutableStateFlow<Map<MosaicCacheKey, Float>>(emptyMap())

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
    private val refreshQueueMutex = Mutex()
    private val pendingVisibleRefreshBuckets = mutableListOf<String>()
    private val pendingScrollbarDragRefreshBuckets = mutableSetOf<String>()
    private val serverRefreshedBucketIds = mutableSetOf<String>()
    private val staleBucketIds = mutableSetOf<String>()
    private val refreshingBucketIds = mutableSetOf<String>()
    private var visibleRefreshJob: Job? = null
    private var mosaicCacheJob: Job? = null
    private var mosaicCacheGeneration = 0L
    private var bucketGeometryCacheJob: Job? = null
    private var bucketGeometryCacheGeneration = 0L
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
                viewConfig = initialViewConfig
            )
        )

        viewModelScope.launch {
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

        viewModelScope.launch {
            getViewConfigUseCase.observe().collect { saved ->
                val config = saved.normalized
                if (config != _uiConfig.value.viewConfig) {
                    _uiConfig.update { it.copy(viewConfig = config) }
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
        requestVisibleTimelineMosaicCacheRead()
    }

    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        if (normalized == _uiConfig.value.viewConfig) return
        setViewConfigAction(normalized)
        _uiConfig.update { it.copy(viewConfig = normalized) }
        requestVisibleTimelineMosaicCacheRead()
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _uiConfig.value.availableWidth) return
        _uiConfig.update {
            it.copy(
                availableWidth = widthDp,
                targetRowHeight = targetRowHeightForConfig(widthDp, it.rowHeightBounds)
            )
        }
        requestVisibleTimelineMosaicCacheRead()
        requestPersistedBucketGeometryForCachedBuckets()
    }

    fun setMosaicColumnCount(columnCount: Int) {
        if (columnCount == _uiConfig.value.mosaicColumnCount) return
        _uiConfig.update { it.copy(mosaicColumnCount = columnCount) }
        requestVisibleTimelineMosaicCacheRead()
        requestPersistedBucketGeometryForCachedBuckets()
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        if (heightDp == _uiConfig.value.availableHeight) return
        val bounds = rowHeightBoundsForViewport(heightDp)
        _uiConfig.update {
            it.copy(
                availableHeight = heightDp,
                rowHeightBounds = bounds,
                targetRowHeight = targetRowHeightForConfig(it.availableWidth, bounds)
            )
        }
    }

    fun setTargetRowHeight(height: Float) {
        val current = _uiConfig.value
        if (current.viewConfig.mosaicEnabled) return
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
            enqueueVisibleBucketRefreshes(listOf(timeBucket), force)
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
            priority = TimelineRefreshQueuePriority.Visible
        )
        requestVisibleTimelineMosaicCacheRead()
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
            priority = priority
        )
        requestVisibleTimelineMosaicCacheRead()
        updateMosaicAnchor(bucketIndex, reason)
    }

    fun scrollTargetForFraction(fraction: Float): TimelineScrollTarget? {
        val snapshot = state.value
        return timelineScrollTargetForFraction(snapshot.pageIndex, snapshot.displayIndex, fraction)
    }

    private fun updateMosaicAnchor(bucketIndex: Int, reason: TimelineBucketTargetReason) {
        if (bucketIndex != _uiConfig.value.mosaicAnchorBucketIndex) {
            _uiConfig.update { it.copy(mosaicAnchorBucketIndex = bucketIndex) }
            log.d { "Timeline mosaic anchor changed reason=$reason bucket=$bucketIndex" }
        }
    }

    private fun loadBucketIndexes(
        bucketIndexes: List<Int>,
        priority: TimelineRefreshQueuePriority = TimelineRefreshQueuePriority.Normal
    ) {
        val buckets = state.value.buckets
        val timeBuckets = bucketIndexes
            .filter { it in buckets.indices }
            .distinct()
            .map { index -> buckets[index].timeBucket }
        viewModelScope.launch(Dispatchers.IO) {
            enqueueVisibleBucketRefreshes(timeBuckets, force = false, priority = priority)
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
            loadBucketAssetsIfNeeded(timeBucket, force = true, keepCachedRowsOnFailure = false)
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

            if (isCachedManualRefresh) {
                log.d { "Timeline manual refresh waiting for visible refresh queue to pause" }
                beginManualRefresh()
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

                        if (isColdSync) {
                            _bucketData.update { it.copy(buckets = buckets) }
                            log.d { "Timeline cold asset sync started buckets=${buckets.size}" }
                            val coldConfig = _uiConfig.value
                            val geometryRequest = timelineMosaicGeometryRequest(coldConfig, coldConfig.mosaicColumnCount)
                            if (geometryRequest == null) {
                                log.e { "Timeline cold sync cannot start without Mosaic geometry request" }
                                _isBuilding.value = false
                                _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                _uiConfig.update { it.copy(isSyncing = false) }
                            } else {
                                syncAllTimelineAssetsAction(
                                    buckets = buckets,
                                    groupSize = coldConfig.groupSize,
                                    columnCount = coldConfig.mosaicColumnCount,
                                    families = coldConfig.viewConfig.mosaicFamilies,
                                    geometryRequest = geometryRequest
                                ).fold(
                                    onSuccess = { assetResult ->
                                        log.d {
                                            "Timeline cold asset sync completed successful=${assetResult.successfulBucketIds.size} " +
                                                "failed=${assetResult.failedBucketIds.size} changed=${assetResult.changedBucketIds.size}"
                                        }
                                        val geometryReadyIds = assetResult.bucketGeometrySummaries.map { it.timeBucket }.toSet()
                                        val missingGeometryCount = assetResult.successfulBucketIds.count { it !in geometryReadyIds }
                                        if (assetResult.failedBucketIds.isNotEmpty() || missingGeometryCount > 0) {
                                            _isBuilding.value = false
                                            _buildError.value = ConnectionUiMessage.NoConnectionToServer
                                            _uiConfig.update { it.copy(isSyncing = false) }
                                            log.d {
                                                "Timeline cold sync finished with failed buckets=${assetResult.failedBucketIds.size} " +
                                                    "missingGeometry=$missingGeometryCount"
                                            }
                                        } else {
                                            publishColdSyncResult(
                                                successfulBucketIds = assetResult.successfulBucketIds,
                                                bucketGeometrySummaries = assetResult.bucketGeometrySummaries,
                                                groupSize = coldConfig.groupSize,
                                                columnCount = coldConfig.mosaicColumnCount,
                                                families = coldConfig.viewConfig.mosaicFamilies,
                                                geometryRequest = geometryRequest
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
            priority = TimelineRefreshQueuePriority.Visible
        )
    }

    private suspend fun enqueueVisibleBucketRefreshes(
        timeBuckets: List<String>,
        force: Boolean,
        priority: TimelineRefreshQueuePriority = TimelineRefreshQueuePriority.Normal
    ) {
        if (timeBuckets.isEmpty()) return
        val requestedBuckets = timeBuckets.distinct()
        requestedBuckets.forEach { timeBucket ->
            materializeCachedBucketIfAvailable(timeBucket)
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
                keepCachedRowsOnFailure = true
            )
        }
    }

    private suspend fun loadBucketAssetsIfNeeded(
        timeBucket: String,
        force: Boolean = false,
        keepCachedRowsOnFailure: Boolean = false
    ): Boolean {
        val materializedCachedRows = materializeCachedBucketIfAvailable(timeBucket)
        val showRenderLoading = timeBucket !in _bucketData.value.loadedBuckets
        if (!claimBucketRefresh(timeBucket, force, showRenderLoading)) return false
        return loadBucketAssetsInternal(
            timeBucket = timeBucket,
            keepCachedRowsOnFailure = keepCachedRowsOnFailure || materializedCachedRows,
            showRenderLoading = showRenderLoading
        )
    }

    private suspend fun materializeCachedBucketIfAvailable(timeBucket: String): Boolean {
        val data = _bucketData.value
        if (timeBucket in data.loadedBuckets) return true
        if (timeBucket !in data.cachedBuckets && bucketAssetsCache[timeBucket] == null) return false
        val assets = bucketAssetsCache[timeBucket] ?: getBucketAssetsUseCase(timeBucket)
        if (assets.isEmpty()) return false
        bucketAssetsCache[timeBucket] = assets
        loadPersistedMosaicAssignmentsForBuckets(setOf(timeBucket))
        loadPersistedActiveMosaicAssignmentsForBuckets(setOf(timeBucket))
        _bucketData.update {
            it.copy(
                cachedBuckets = it.cachedBuckets + timeBucket,
                loadedBuckets = it.loadedBuckets + timeBucket,
                failedBuckets = it.failedBuckets - timeBucket
            )
        }
        requestTimelineMosaicCacheReadForBuckets(setOf(timeBucket))
        return true
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
                    val failedMosaicBuckets = when {
                        syncResult.changed -> {
                            val failedBuckets = precomputeTimelineMosaicForBuckets(setOf(timeBucket))
                            val failedActiveBuckets = precomputeActiveTimelineMosaicForBuckets(setOf(timeBucket))
                            invalidateRuntimeMosaicAssignments(timeBucket)
                            val assets = getBucketAssetsUseCase(timeBucket)
                            bucketAssetsCache[timeBucket] = assets
                            markActiveTimelineMosaicFailedForBuckets(failedActiveBuckets)
                            failedBuckets
                        }
                        bucketAssetsCache[timeBucket] == null -> {
                            bucketAssetsCache[timeBucket] = getBucketAssetsUseCase(timeBucket)
                            emptySet()
                        }
                        else -> emptySet()
                    }
                    publishLoadedBucketResult(timeBucket, syncResult.changed)
                    requestTimelineMosaicCacheReadForBuckets(setOf(timeBucket))
                    loadPersistedActiveMosaicAssignmentsForBuckets(setOf(timeBucket))
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
                // Bucket-count changes alter placeholder height even before
                // assets are loaded, so structural bucket invalidation also
                // bumps the per-bucket content revision.
                assetRevisions = data.assetRevisions.incrementRevisions(timeBuckets)
            )
        }
        requestVisibleTimelineMosaicCacheRead()
    }

    private suspend fun buildTimelineState(
        data: BucketData,
        config: UiConfig,
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>,
        mosaicGeometryStates: Map<MosaicCacheKey, Float>,
        bucketGeometryStates: Map<MosaicCacheKey, Float>
    ): TimelineState {
        val mosaicLayoutSpec = timelineMosaicLayoutSpec(config)
        val activeMosaicConfig = config.activeMosaicConfig
        val effectiveGroupSize = activeMosaicConfig?.groupSize ?: config.groupSize
        val effectiveViewConfig = activeMosaicConfig?.let {
            config.viewConfig.copy(mosaicFamilies = it.families)
        } ?: config.viewConfig
        val displayItems = buildDisplayItems(
            data,
            effectiveGroupSize,
            config.availableWidth,
            config.targetRowHeight,
            config.rowHeightBounds.max,
            mosaicLayoutSpec,
            effectiveViewConfig,
            mosaicStates,
            mosaicGeometryStates,
            bucketGeometryStates
        )
        val pageIndex = computePageIndex(data.buckets)
        val scrollbarData = computeScrollbarData(data.buckets, pageIndex)
        val displayIndex = buildTimelineDisplayIndex(displayItems)

        return TimelineState(
            groupSize = effectiveGroupSize,
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

    /**
     * Runs exclusively on Dispatchers.Default (via flowOn in the combine pipeline).
     * All cache reads/writes are confined to this single thread context.
     */
    private suspend fun buildDisplayItems(
        data: BucketData,
        groupSize: TimelineGroupSize,
        availableWidth: Float,
        targetRowHeight: Float,
        maxRowHeight: Float,
        mosaicLayoutSpec: MosaicLayoutSpec?,
        viewConfig: ViewConfig,
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>,
        mosaicGeometryStates: Map<MosaicCacheKey, Float>,
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
            val stateKey = when {
                timeBucket in data.failedBuckets -> "failed"
                timeBucket in data.loadedBuckets -> "loaded"
                useMosaic && bucketGeometryKey != null && bucketGeometryKey in data.geometryReadyBuckets -> "geometry_ready"
                else -> "placeholder"
            }
            val layoutKey = mosaicLayoutKeyForBucket(
                timeBucket = timeBucket,
                isLoaded = timeBucket in data.loadedBuckets,
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
                isLoaded = timeBucket in data.loadedBuckets,
                groupSize = groupSize,
                mosaicColumnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                viewConfig = viewConfig,
                useMosaic = useMosaic,
                mosaicGeometryStates = mosaicGeometryStates,
                bucketGeometryStates = bucketGeometryStates,
                bucketGeometryRequest = bucketGeometryRequest
            )
            val cacheKey = "${timeBucket}_${stateKey}_${assetRevision}_${layoutKey}_$geometryKey"

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
            val stateKey = when {
                timeBucket in data.failedBuckets -> "failed"
                timeBucket in data.loadedBuckets -> "loaded"
                useMosaic && bucketGeometryKey != null && bucketGeometryKey in data.geometryReadyBuckets -> "geometry_ready"
                else -> "placeholder"
            }
            val layoutKey = mosaicLayoutKeyForBucket(
                timeBucket = timeBucket,
                isLoaded = timeBucket in data.loadedBuckets,
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
                isLoaded = timeBucket in data.loadedBuckets,
                groupSize = groupSize,
                mosaicColumnCount = mosaicColumnCount,
                assetRevision = assetRevision,
                viewConfig = viewConfig,
                useMosaic = useMosaic,
                mosaicGeometryStates = mosaicGeometryStates,
                bucketGeometryStates = bucketGeometryStates,
                bucketGeometryRequest = bucketGeometryRequest
            )
            val cacheKey = "${timeBucket}_${stateKey}_${assetRevision}_${layoutKey}_$geometryKey"
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
        mosaicGeometryStates: Map<MosaicCacheKey, Float>,
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
            return "geometry_${mosaicGeometryStates[key] ?: "missing"}"
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
            "${dayGroup.label}:${mosaicGeometryStates[key] ?: "missing"}"
        }
    }

    private suspend fun buildBucketItems(
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
        mosaicGeometryStates: Map<MosaicCacheKey, Float>,
        bucketGeometryStates: Map<MosaicCacheKey, Float>,
        bucketGeometryRequest: TimelineMosaicGeometryRequest?
    ): List<TimelineDisplayItem> {
        val timeBucket = bucket.timeBucket
        val isFailed = timeBucket in data.failedBuckets
        val isLoaded = timeBucket in data.loadedBuckets
        val monthLabel = bucket.displayLabel
        val items = mutableListOf<TimelineDisplayItem>()

        items.add(HeaderItem(
            gridKey = "h_$index",
            bucketIndex = index,
            sectionLabel = monthLabel,
            label = monthLabel
        ))

        when {
            isFailed -> {
                items.add(ErrorItem(
                    gridKey = "err_$index",
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
                    addGeometryPlaceholderItems(items, index, monthLabel, bucketGeometryHeight)
                } else if (useMosaic && bucketGeometryKey != null && bucketGeometryKey in data.geometryReadyBuckets) {
                    // Exact geometry is known to be empty for this bucket.
                } else if (useMosaic && mosaicLayoutSpec != null && !cachedAssets.isNullOrEmpty()) {
                    addProjectedMosaicPlaceholderItems(
                        items = items,
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
                // Fast path: overlay or a prior buildBucketItems already
                // populated the unified cache. Slow path: first time this
                // bucket is rendered in the grid, fetch from Room and publish
                // into the cache so the overlay can also read it reactively.
                val assets = getCachedOrLoadBucketAssets(timeBucket)
                if (assets.isNotEmpty()) {
                    val assetRevision = data.assetRevisionFor(timeBucket)
                    val dayGroupList = if (groupSize == TimelineGroupSize.DAY) {
                        dayGroupsForBucket(timeBucket, assetRevision, assets)
                    } else null

                    if (!dayGroupList.isNullOrEmpty()) {
                        for (dayGroup in dayGroupList) {
                            val dayLabel = dayGroup.label
                            items.add(HeaderItem(
                                gridKey = "dh_${index}_$dayLabel",
                                bucketIndex = index,
                                sectionLabel = dayLabel,
                                label = dayLabel
                            ))
                            val dayMosaicState = mosaicStates[mosaicCacheKey(
                                timeBucket = timeBucket,
                                sectionKey = dayMosaicSectionKey(timeBucket, dayLabel),
                                columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                                assetRevision = assetRevision,
                                families = viewConfig.mosaicFamilies
                            )]
                            when {
                                useMosaic && (dayMosaicState == null || dayMosaicState == RuntimeMosaicState.Pending) -> {
                                    val geometryHeight = mosaicGeometryStates[mosaicCacheKey(
                                        timeBucket = timeBucket,
                                        sectionKey = dayMosaicSectionKey(timeBucket, dayLabel),
                                        columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                                        assetRevision = assetRevision,
                                        families = viewConfig.mosaicFamilies
                                    )]
                                    if (geometryHeight != null) {
                                        addGeometryPlaceholderItems(items, index, dayLabel, geometryHeight)
                                    } else {
                                        addProjectedMosaicPlaceholderItems(
                                            items = items,
                                            bucketIndex = index,
                                            sectionLabel = dayLabel,
                                            assets = dayGroup.assets,
                                            assignments = null,
                                            mosaicLayoutSpec = mosaicLayoutSpec,
                                            maxRowHeight = maxRowHeight
                                        )
                                    }
                                }
                                useMosaic && dayMosaicState is RuntimeMosaicState.Ready && mosaicLayoutSpec != null -> {
                                    items.addAll(
                                        buildPhotoGridItemsWithMosaic(
                                            assets = dayGroup.assets,
                                            assignments = dayMosaicState.assignments,
                                            bucketIndex = index,
                                            sectionLabel = dayLabel,
                                            layoutSpec = mosaicLayoutSpec,
                                            spacing = GRID_SPACING_DP,
                                            maxRowHeight = maxRowHeight,
                                            promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
                                            minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                                        )
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
                        val monthMosaicState = mosaicStates[mosaicCacheKey(
                            timeBucket = timeBucket,
                            sectionKey = monthMosaicSectionKey(timeBucket),
                            columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                            assetRevision = assetRevision,
                            families = viewConfig.mosaicFamilies
                        )]
                        if (useMosaic && (monthMosaicState == null || monthMosaicState == RuntimeMosaicState.Pending)) {
                            val geometryHeight = mosaicGeometryStates[mosaicCacheKey(
                                timeBucket = timeBucket,
                                sectionKey = monthMosaicSectionKey(timeBucket),
                                columnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
                                assetRevision = assetRevision,
                                families = viewConfig.mosaicFamilies
                            )]
                            if (geometryHeight != null) {
                                addGeometryPlaceholderItems(items, index, monthLabel, geometryHeight)
                            } else {
                                addProjectedMosaicPlaceholderItems(
                                    items = items,
                                    bucketIndex = index,
                                    sectionLabel = monthLabel,
                                    assets = assets,
                                    assignments = null,
                                    mosaicLayoutSpec = mosaicLayoutSpec,
                                    maxRowHeight = maxRowHeight
                                )
                            }
                            return items
                        }
                        if (useMosaic && monthMosaicState is RuntimeMosaicState.Ready && mosaicLayoutSpec != null) {
                        items.addAll(
                            buildPhotoGridItemsWithMosaic(
                                assets = assets,
                                assignments = monthMosaicState.assignments,
                                bucketIndex = index,
                                sectionLabel = monthLabel,
                                layoutSpec = mosaicLayoutSpec,
                                spacing = GRID_SPACING_DP,
                                maxRowHeight = maxRowHeight,
                                promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
                                minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                            )
                        )
                        } else {
                        items.addAll(packIntoRows(
                            assets, index, monthLabel,
                            availableWidth,
                            if (useMosaic && mosaicLayoutSpec != null) {
                                mosaicFallbackRowHeight(
                                    layoutSpec = mosaicLayoutSpec,
                                    assetCount = assets.size,
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
                } else {
                    // Loaded but empty — show header only (no placeholder trap)
                }
            }
        }
        return items
    }

    private fun addPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
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
            buildPhotoGridPlaceholderItems(
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                assetCount = assetCount,
                availableWidth = availableWidth,
                targetRowHeight = targetRowHeight,
                estimatedHeaderCount = estimatedDayHeaders
            )
        )
    }

    private fun addProjectedMosaicPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        bucketIndex: Int,
        sectionLabel: String,
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>?,
        mosaicLayoutSpec: MosaicLayoutSpec?,
        maxRowHeight: Float
    ) {
        if (assets.isNotEmpty() && mosaicLayoutSpec != null) {
            val projectedItems = if (assignments != null) {
                buildPhotoGridItemsWithMosaic(
                    assets = assets,
                    assignments = assignments,
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    layoutSpec = mosaicLayoutSpec,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = maxRowHeight,
                    promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
                    minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                )
            } else {
                packIntoRows(
                    assets = assets,
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    availableWidth = mosaicLayoutSpec.availableWidth,
                    targetRowHeight = mosaicFallbackRowHeight(
                        layoutSpec = mosaicLayoutSpec,
                        assetCount = assets.size,
                        maxRowHeight = maxRowHeight
                    ),
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = maxRowHeight,
                    promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
                    minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                )
            }
            val projectedHeight = estimatePhotoGridDisplayItemsHeight(projectedItems, GRID_SPACING_DP)
            if (projectedHeight > 0f) {
                items.addAll(
                    buildPhotoGridPlaceholderItemsForHeight(
                        bucketIndex = bucketIndex,
                        sectionLabel = sectionLabel,
                        estimatedHeight = projectedHeight
                    )
                )
                return
            }
        }
        addPlaceholderItems(
            items = items,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            assetCount = assets.size,
            groupSize = TimelineGroupSize.MONTH,
            availableWidth = mosaicLayoutSpec?.availableWidth ?: 0f,
            targetRowHeight = mosaicLayoutSpec?.cellHeight ?: DEFAULT_TARGET_ROW_HEIGHT
        )
    }

    private fun addGeometryPlaceholderItems(
        items: MutableList<TimelineDisplayItem>,
        bucketIndex: Int,
        sectionLabel: String,
        placeholderHeight: Float
    ) {
        items.addAll(
            buildPhotoGridPlaceholderItemsForHeight(
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                estimatedHeight = placeholderHeight,
                externalSpacing = GRID_SPACING_DP
            )
        )
    }

    private suspend fun syncCachedBucketsManually(buckets: List<TimelineBucket>, hadBannerError: Boolean) {
        var failed = false
        for (bucket in buckets) {
            val success = loadBucketAssetsIfNeeded(
                timeBucket = bucket.timeBucket,
                force = true,
                keepCachedRowsOnFailure = true
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
        val failedMosaicBuckets = precomputeTimelineMosaicForBuckets(changedSuccessfulBucketIds)
        val failedActiveMosaicBuckets = precomputeActiveTimelineMosaicForBuckets(changedSuccessfulBucketIds)
        publishAssetSyncResult(successfulBucketIds, failedBucketIds, changedBucketIds)
        loadPersistedMosaicAssignmentsForBuckets(changedSuccessfulBucketIds)
        loadPersistedActiveMosaicAssignmentsForBuckets(changedSuccessfulBucketIds)
        markTimelineMosaicFailedForBuckets(failedMosaicBuckets)
        markActiveTimelineMosaicFailedForBuckets(failedActiveMosaicBuckets)
        log.d {
            "Timeline asset sync result published mosaicFailed=${failedMosaicBuckets.size} " +
                "activeMosaicFailed=${failedActiveMosaicBuckets.size}"
        }
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
        dayGroupsCache.keys.removeAll { it.timeBucket == timeBucket }
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
        requestTimelineMosaicCacheReadForBuckets(visibleLoadedTimelineBuckets())
    }

    private fun requestPersistedBucketGeometryForCachedBuckets() {
        val data = _bucketData.value
        val buckets = data.cachedBuckets
        if (buckets.isEmpty()) return
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return
        val geometryRequest = timelineMosaicGeometryRequest(config, config.mosaicColumnCount) ?: return
        val generation = ++bucketGeometryCacheGeneration
        bucketGeometryCacheJob?.cancel()
        bucketGeometryCacheJob = viewModelScope.launch(Dispatchers.IO) {
            getTimelineBucketGeometryUseCase(
                timeBuckets = buckets,
                groupSize = config.groupSize,
                columnCount = config.mosaicColumnCount,
                families = config.viewConfig.mosaicFamilies,
                geometryRequest = geometryRequest
            ).onFailure { e ->
                if (e is CancellationException) throw e
                log.e(e) { "Failed to load persisted Timeline bucket geometry" }
            }.onSuccess { summaries ->
                if (generation != bucketGeometryCacheGeneration) {
                    log.d {
                        "Timeline bucket geometry read ignored due to stale generation " +
                            "expected=$generation actual=$bucketGeometryCacheGeneration"
                    }
                    return@onSuccess
                }
                if (!sameBucketGeometryReadConfig(config, geometryRequest, _uiConfig.value)) {
                    log.d { "Timeline bucket geometry read ignored due to stale config" }
                    return@onSuccess
                }
                publishBucketGeometrySummaries(
                    summaries = summaries,
                    groupSize = config.groupSize,
                    columnCount = config.mosaicColumnCount,
                    families = config.viewConfig.mosaicFamilies,
                    geometryRequest = geometryRequest
                )
            }
        }
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
        val snapshot = state.value
        val loadedBuckets = _bucketData.value.loadedBuckets
        val seeds = if (visibleBucketIndexes.isNotEmpty() || lastTargetBucketIndex != null) {
            buildList {
                visibleBucketIndexes.forEach { add(it) }
                lastTargetBucketIndex?.let { target ->
                    if (target !in this) add(target)
                }
            }
        } else {
            lastVisibleBucketIndexes
        }
        return seeds
            .flatMap(::bucketIndexesNear)
            .distinct()
            .mapNotNull { index -> snapshot.buckets.getOrNull(index)?.timeBucket }
            .filter { timeBucket -> timeBucket in loadedBuckets }
            .toSet()
    }

    private fun requestTimelineMosaicCacheReadForBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        val loadedBuckets = _bucketData.value.loadedBuckets
        val requestedBuckets = timeBuckets.filter { it in loadedBuckets }.toSet()
        if (requestedBuckets.isEmpty()) return
        val generation = ++mosaicCacheGeneration
        val config = _uiConfig.value
        mosaicCacheJob?.cancel()
        mosaicCacheJob = viewModelScope.launch(Dispatchers.IO) {
            loadPersistedMosaicAssignmentsForBuckets(
                timeBuckets = requestedBuckets,
                expectedGeneration = generation,
                expectedConfig = config
            )
        }
    }

    // Timeline Mosaic deliberately does not run a continuous ViewModel worker.
    // Server sync decides which buckets changed, the repository persists those
    // assignments, and this ViewModel only loads the cached rows into display
    // state. Config and width changes are cache reads only; missing rows stay
    // as placeholders until a changed-bucket sync recomputes them.
    private suspend fun precomputeTimelineMosaicForBuckets(timeBuckets: Set<String>): Set<String> {
        if (timeBuckets.isEmpty()) return emptySet()
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return emptySet()
        val geometryRequest = timelineMosaicGeometryRequest(config)
        log.d {
            "Timeline Mosaic precompute started buckets=${timeBuckets.size} group=${config.groupSize} " +
                "columns=${config.mosaicColumnCount} families=${config.viewConfig.mosaicFamilies} " +
                "geometryWidth=${geometryRequest?.availableWidth} geometryMaxRowHeight=${geometryRequest?.maxRowHeight}"
        }
        return precomputeTimelineMosaicAction(
            timeBuckets = timeBuckets,
            groupSize = config.groupSize,
            columnCount = config.mosaicColumnCount,
            families = config.viewConfig.mosaicFamilies,
            geometryRequest = geometryRequest
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

    private suspend fun precomputeActiveTimelineMosaicForBuckets(timeBuckets: Set<String>): Set<String> {
        if (timeBuckets.isEmpty()) return emptySet()
        val config = _uiConfig.value
        val active = config.activeMosaicConfig ?: return emptySet()
        if (!config.viewConfig.mosaicEnabled) return emptySet()
        if (active.groupSize == config.groupSize &&
            active.columnCount == config.mosaicColumnCount &&
            active.families == config.viewConfig.mosaicFamilies.normalizedMosaicFamilies()
        ) {
            return emptySet()
        }
        val geometryRequest = timelineMosaicGeometryRequest(config, active.columnCount)
        log.d {
            "Timeline active Mosaic precompute started buckets=${timeBuckets.size} group=${active.groupSize} " +
                "columns=${active.columnCount} families=${active.families} " +
                "geometryWidth=${geometryRequest?.availableWidth} geometryMaxRowHeight=${geometryRequest?.maxRowHeight}"
        }
        return precomputeTimelineMosaicAction(
            timeBuckets = timeBuckets,
            groupSize = active.groupSize,
            columnCount = active.columnCount,
            families = active.families,
            geometryRequest = geometryRequest
        ).fold(
            onSuccess = { result ->
                if (result.failedBucketIds.isNotEmpty()) {
                    log.e { "Failed to precompute active Timeline Mosaic buckets=${result.failedBucketIds}" }
                }
                log.d {
                    "Timeline active Mosaic precompute completed requested=${timeBuckets.size} " +
                        "successful=${result.successfulBucketIds.size} failed=${result.failedBucketIds.size}"
                }
                result.failedBucketIds
            },
            onFailure = { e ->
                log.e(e) { "Failed to precompute active Timeline Mosaic assignments" }
                timeBuckets
            }
        )
    }

    private suspend fun loadPersistedMosaicAssignmentsForBuckets(
        timeBuckets: Set<String>,
        expectedGeneration: Long? = null,
        expectedConfig: UiConfig? = null
    ) {
        if (timeBuckets.isEmpty()) return
        val config = _uiConfig.value
        if (!config.viewConfig.mosaicEnabled) return
        val columnCount = config.mosaicColumnCount
        val families = config.viewConfig.mosaicFamilies
        val geometryRequest = timelineMosaicGeometryRequest(config, columnCount)
        log.d {
            "Timeline persisted Mosaic read started buckets=${timeBuckets.size} group=${config.groupSize} " +
                "columns=$columnCount families=$families geometryWidth=${geometryRequest?.availableWidth}"
        }
        val result = getTimelineMosaicAssignmentsUseCase(
            timeBuckets = timeBuckets,
            groupSize = config.groupSize,
            columnCount = columnCount,
            families = families,
            geometryRequest = geometryRequest
        )
        result.onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "Failed to load persisted Timeline Mosaic assignments" }
        }.onSuccess { status ->
            if (expectedGeneration != null && expectedGeneration != mosaicCacheGeneration) {
                log.d {
                    "Timeline persisted Mosaic read ignored due to stale generation " +
                        "expected=$expectedGeneration actual=$mosaicCacheGeneration"
                }
                return@onSuccess
            }
            if (expectedConfig != null && !sameMosaicReadConfig(expectedConfig, _uiConfig.value)) {
                log.d { "Timeline persisted Mosaic read ignored due to stale config" }
                return@onSuccess
            }
            val revisions = _bucketData.value.assetRevisions
            val geometryUpdates = status.geometrySummaries.associate { geometry ->
                mosaicCacheKey(
                    timeBucket = geometry.timeBucket,
                    sectionKey = geometry.sectionKey,
                    columnCount = columnCount,
                    assetRevision = revisions[geometry.timeBucket] ?: 0,
                    families = families
                ) to geometry.placeholderHeight
            }
            if (geometryUpdates.isNotEmpty()) {
                _mosaicGeometryStates.update { states -> states + geometryUpdates }
            }
            val updates = status.assignments.associate { assignment ->
                mosaicCacheKey(
                    timeBucket = assignment.timeBucket,
                    sectionKey = assignment.sectionKey,
                    columnCount = columnCount,
                    assetRevision = revisions[assignment.timeBucket] ?: 0,
                    families = families
                ) to RuntimeMosaicState.Ready(assignment.assignments)
            }
            if (updates.isNotEmpty()) {
                _mosaicStates.update { states -> states + updates }
            }
            if (timeBuckets.isNotEmpty() && status.completeBucketIds.containsAll(timeBuckets)) {
                _uiConfig.update {
                    it.copy(
                        activeMosaicConfig = ActiveMosaicConfig(
                            groupSize = config.groupSize,
                            columnCount = columnCount,
                            families = families.normalizedMosaicFamilies()
                        )
                    )
                }
            }
            log.d {
                "Timeline persisted Mosaic read completed requested=${timeBuckets.size} " +
                    "assignments=${status.assignments.size} geometry=${status.geometrySummaries.size} " +
                    "complete=${status.completeBucketIds.size} missing=${status.missingBucketIds.size}"
            }
        }
    }

    private fun sameMosaicReadConfig(expected: UiConfig, actual: UiConfig): Boolean =
        expected.groupSize == actual.groupSize &&
            expected.mosaicColumnCount == actual.mosaicColumnCount &&
            expected.viewConfig.mosaicEnabled == actual.viewConfig.mosaicEnabled &&
            expected.viewConfig.mosaicFamilies.normalizedMosaicFamilies() ==
            actual.viewConfig.mosaicFamilies.normalizedMosaicFamilies()

    private suspend fun loadPersistedActiveMosaicAssignmentsForBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        val config = _uiConfig.value
        val active = config.activeMosaicConfig ?: return
        if (!config.viewConfig.mosaicEnabled) return
        val requestedFamilies = config.viewConfig.mosaicFamilies.normalizedMosaicFamilies()
        if (active.groupSize == config.groupSize &&
            active.columnCount == config.mosaicColumnCount &&
            active.families == requestedFamilies
        ) {
            return
        }
        val geometryRequest = timelineMosaicGeometryRequest(config, active.columnCount)
        log.d {
            "Timeline active persisted Mosaic read started buckets=${timeBuckets.size} group=${active.groupSize} " +
                "columns=${active.columnCount} families=${active.families} geometryWidth=${geometryRequest?.availableWidth}"
        }
        getTimelineMosaicAssignmentsUseCase(
            timeBuckets = timeBuckets,
            groupSize = active.groupSize,
            columnCount = active.columnCount,
            families = active.families,
            geometryRequest = geometryRequest
        ).onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "Failed to load active persisted Timeline Mosaic assignments" }
        }.onSuccess { status ->
            val revisions = _bucketData.value.assetRevisions
            val geometryUpdates = status.geometrySummaries.associate { geometry ->
                mosaicCacheKey(
                    timeBucket = geometry.timeBucket,
                    sectionKey = geometry.sectionKey,
                    columnCount = active.columnCount,
                    assetRevision = revisions[geometry.timeBucket] ?: 0,
                    families = active.families
                ) to geometry.placeholderHeight
            }
            if (geometryUpdates.isNotEmpty()) {
                _mosaicGeometryStates.update { states -> states + geometryUpdates }
            }
            val updates = status.assignments.associate { assignment ->
                mosaicCacheKey(
                    timeBucket = assignment.timeBucket,
                    sectionKey = assignment.sectionKey,
                    columnCount = active.columnCount,
                    assetRevision = revisions[assignment.timeBucket] ?: 0,
                    families = active.families
                ) to RuntimeMosaicState.Ready(assignment.assignments)
            }
            if (updates.isNotEmpty()) {
                _mosaicStates.update { states -> states + updates }
            }
            log.d {
                "Timeline active persisted Mosaic read completed requested=${timeBuckets.size} " +
                    "assignments=${status.assignments.size} geometry=${status.geometrySummaries.size} " +
                    "complete=${status.completeBucketIds.size} missing=${status.missingBucketIds.size}"
            }
        }
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
        }
    }

    private suspend fun markActiveTimelineMosaicFailedForBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        val config = _uiConfig.value
        val active = config.activeMosaicConfig ?: return
        if (!config.viewConfig.mosaicEnabled) return
        markTimelineMosaicFailedForConfig(
            timeBuckets = timeBuckets,
            groupSize = active.groupSize,
            columnCount = active.columnCount,
            families = active.families
        )
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
        }
    }

    private fun timelineMosaicLayoutSpec(config: UiConfig): MosaicLayoutSpec? =
        if (config.viewConfig.mosaicEnabled) {
            mosaicLayoutSpecForColumnCount(
                config.availableWidth,
                config.activeMosaicConfig?.columnCount ?: config.mosaicColumnCount
            )
        } else {
            mosaicLayoutSpecFor(config.availableWidth, config.targetRowHeight)
        }

    private fun timelineMosaicGeometryRequest(
        config: UiConfig,
        columnCount: Int = config.activeMosaicConfig?.columnCount ?: config.mosaicColumnCount
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

    private data class ScrollbarData(
        val yearMarkers: List<YearMarker>,
        val totalItemCount: Int,
        val cumulativeItemCounts: List<Int>,
        val bucketDisplayLabels: List<String>
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
    }
}
