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
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.TimelineScrollTarget
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildMosaicAssignments
import com.udnahc.immichgallery.domain.model.buildPhotoGridItemsWithMosaic
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItems
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_GRID_COLUMN_COUNT
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.mosaicFallbackMinRowHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecFor
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.prioritizedTimelineBucketIndexes
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
import com.udnahc.immichgallery.ui.util.MosaicWorkPriority
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import com.udnahc.immichgallery.ui.util.MosaicWorkToken
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
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
    val yearMarkers: List<YearMarker> = emptyList(),
    val totalItemCount: Int = 0,
    val cumulativeItemCounts: List<Int> = emptyList(),
    val bucketDisplayLabels: List<String> = emptyList(),
    val pageIndex: TimelinePageIndex = TimelinePageIndex(),
    val buckets: List<TimelineBucket> = emptyList(),
    val viewConfig: ViewConfig = ViewConfig(),
    val bannerError: TimelineMessage? = null,
    val bannerSuccess: TimelineMessage? = null,
    val lastSyncedAt: Long? = null,
    val isSyncing: Boolean = false
)

@Immutable
enum class TimelineMessage {
    NoConnectionToServer,
    CannotConnectToServer,
    ConnectedToServer
}

enum class TimelineBucketTargetReason {
    VisibleScroll,
    ScrollSettled,
    ScrollbarDrag,
    ScrollbarStop
}

@Immutable
private data class BucketData(
    val buckets: List<TimelineBucket> = emptyList(),
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
    val bannerError: TimelineMessage? = null,
    val bannerSuccess: TimelineMessage? = null,
    val lastSyncedAt: Long? = null,
    val isSyncing: Boolean = false,
    val mosaicAnchorBucketIndex: Int = 0,
    val viewConfig: ViewConfig = ViewConfig()
)

private data class MosaicCacheKey(
    val timeBucket: String,
    val sectionKey: String,
    val columnCount: Int,
    val assetRevision: Int,
    val familiesKey: String
)

private data class MosaicQueueConfigKey(
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val mosaicEnabled: Boolean,
    val familiesKey: String
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

private fun dayMosaicSectionKey(timeBucket: String, dayLabel: String): String =
    "$timeBucket|day|$dayLabel"

private fun dayMosaicLoaderSectionKey(timeBucket: String): String = "$timeBucket|day|loader"

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(
    private val getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
    private val getBucketAssetsUseCase: GetBucketAssetsUseCase,
    private val loadBucketAssetsAction: LoadBucketAssetsAction,
    private val syncAllTimelineAssetsAction: SyncAllTimelineAssetsAction,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetFileNameUseCase: GetAssetFileNameUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTimelineGroupSizeUseCase: GetTimelineGroupSizeUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTimelineGroupSizeAction: SetTimelineGroupSizeAction,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction,
    private val mosaicWorkScheduler: MosaicWorkScheduler
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
    private var mosaicQueueJob: Job? = null
    private var mosaicQueueGeneration: Long = 0
    private var mosaicConfigKey: MosaicQueueConfigKey? = null
    private var mosaicQueueTargetRowHeight: Float? = null
    private val mosaicQueueMutex = Mutex()
    private val mosaicQueueRunner = PhotoGridLayoutRunner(viewModelScope)
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope)
    private val inFlightMosaicKeys = mutableSetOf<MosaicCacheKey>()
    private var backgroundLoadJob: Job? = null
    private var visibleBucketIndexes: List<Int> = emptyList()
    private var lastVisibleBucketIndexes: List<Int> = emptyList()
    private var lastTargetBucketIndex: Int? = null
    private var lastLoggedTopMosaicBucket: String? = null
    private var lastLoggedZoomColumnCount: Int? = null
    private var hasSavedTargetRowHeight = false
    private var savedTargetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT
    private val dayGroupsCache = mutableMapOf<DayGroupsCacheKey, List<DayGroup>>()

    private val _bucketData = MutableStateFlow(BucketData())
    private val _uiConfig: MutableStateFlow<UiConfig>
    private val _mosaicStates = MutableStateFlow<Map<MosaicCacheKey, RuntimeMosaicState>>(emptyMap())

    // Exposed directly (not through debounced pipeline) so UI sees it immediately
    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()
    private val _buildError = MutableStateFlow<TimelineMessage?>(null)
    val buildError: StateFlow<TimelineMessage?> = _buildError.asStateFlow()

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

    val state: StateFlow<TimelineState>

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
                }
            }
        }

        viewModelScope.launch {
            getViewConfigUseCase.observe().collect { saved ->
                val config = saved.normalized
                if (config != _uiConfig.value.viewConfig) {
                    _uiConfig.update { it.copy(viewConfig = config) }
                    scheduleMosaicQueue()
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
        state = combine(_bucketData, _uiConfig.debounce(200), _mosaicStates) { data, config, mosaicStates ->
            buildTimelineState(data, config, mosaicStates)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineState(
                groupSize = initialSize,
                targetRowHeight = initialTargetRowHeight,
                viewConfig = initialViewConfig
            ))

        // Observe Room for buckets (reactive SSOT)
        viewModelScope.launch(Dispatchers.IO) {
            // Pre-populate loadedBuckets from Room so cached assets display immediately
            val alreadyLoaded = getTimelineBucketsUseCase.getLoadedBucketIds()
            if (alreadyLoaded.isNotEmpty()) {
                _bucketData.update { it.copy(loadedBuckets = it.loadedBuckets + alreadyLoaded) }
                scheduleMosaicQueue()
            }

            getTimelineBucketsUseCase.observe().collect { buckets ->
                log.d { "Room emitted ${buckets.size} buckets" }
                val bucketIds = buckets.map { it.timeBucket }.toSet()
                _bucketData.update { data ->
                    data.copy(
                        buckets = buckets,
                        assetRevisions = data.assetRevisions.filterKeys { it in bucketIds }
                    )
                }
                scheduleMosaicQueue()
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
        scheduleMosaicQueue()
    }

    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        if (normalized == _uiConfig.value.viewConfig) return
        setViewConfigAction(normalized)
        _uiConfig.update { it.copy(viewConfig = normalized) }
        scheduleMosaicQueue()
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _uiConfig.value.availableWidth) return
        _uiConfig.update {
            it.copy(
                availableWidth = widthDp,
                targetRowHeight = targetRowHeightForConfig(widthDp, it.rowHeightBounds)
            )
        }
        scheduleMosaicQueue()
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
            scheduleMosaicQueue(debounce = true)
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
            loadBucketAssetsIfNeeded(timeBucket, force)
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
        loadBucketIndexes(validBucketIndexes.flatMap(::bucketIndexesNear).distinct())
        updateMosaicAnchor(validBucketIndexes.first(), reason)
        scheduleMosaicQueue()
        ensureBackgroundBucketLoads()
    }

    fun onViewportBucketTargeted(
        bucketIndex: Int,
        reason: TimelineBucketTargetReason = TimelineBucketTargetReason.ScrollbarStop
    ) {
        val snapshot = state.value
        if (bucketIndex !in snapshot.buckets.indices) return
        if (bucketIndex == lastTargetBucketIndex && reason == TimelineBucketTargetReason.ScrollbarDrag) {
            return
        }
        lastTargetBucketIndex = bucketIndex
        log.d { "Timeline target bucket changed reason=$reason bucket=$bucketIndex" }
        loadBucketIndexes(bucketIndexesNear(bucketIndex))
        updateMosaicAnchor(bucketIndex, reason)
        scheduleMosaicQueue()
        ensureBackgroundBucketLoads()
    }

    fun scrollTargetForFraction(fraction: Float): TimelineScrollTarget? {
        val snapshot = state.value
        return timelineScrollTargetForFraction(snapshot.pageIndex, snapshot.displayItems, fraction)
    }

    private fun updateMosaicAnchor(bucketIndex: Int, reason: TimelineBucketTargetReason) {
        if (bucketIndex != _uiConfig.value.mosaicAnchorBucketIndex) {
            _uiConfig.update { it.copy(mosaicAnchorBucketIndex = bucketIndex) }
            log.d { "Timeline mosaic anchor changed reason=$reason bucket=$bucketIndex" }
        }
    }

    private fun loadBucketIndexes(bucketIndexes: List<Int>) {
        val buckets = state.value.buckets
        bucketIndexes
            .filter { it in buckets.indices }
            .distinct()
            .forEach { index -> loadBucketAssets(buckets[index].timeBucket) }
    }

    private fun bucketIndexesNear(bucketIndex: Int): List<Int> {
        val bucketCount = state.value.buckets.size
        return ((bucketIndex - PREFETCH_BUCKET_RADIUS)..(bucketIndex + PREFETCH_BUCKET_RADIUS))
            .filter { it in 0 until bucketCount }
    }

    fun retryBucket(timeBucket: String) {
        viewModelScope.launch(Dispatchers.IO) {
            log.d { "Retrying bucket: $timeBucket" }
            _bucketData.update { it.copy(failedBuckets = it.failedBuckets - timeBucket) }
            loadBucketAssetsInternal(timeBucket)
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
            for (bucket in s.buckets) {
                val assets = bucketAssetsCache[bucket.timeBucket]
                if (assets != null) {
                    val localIndex = assets.indexOfFirst { it.id == assetId }
                    if (localIndex >= 0) return@withContext globalIndex + localIndex
                }
                globalIndex += s.pageIndex.bucketPageCounts.getOrElse(s.buckets.indexOf(bucket)) { bucket.count }
            }
            null
        }
        if (cached != null) return cached
        // Fallback: query Room for any buckets not in the cache (e.g. user
        // deep-scrolled and we evicted old buckets).
        var globalIndex = 0
        for (bucket in s.buckets) {
            val assets = withContext(Dispatchers.IO) {
                getBucketAssetsUseCase(bucket.timeBucket)
            }
            val localIndex = assets.indexOfFirst { it.id == assetId }
            if (localIndex >= 0) return globalIndex + localIndex
            val bucketIndex = s.buckets.indexOf(bucket)
            globalIndex += s.pageIndex.bucketPageCounts.getOrElse(bucketIndex) { bucket.count }
        }
        return null
    }

    // --- Private ---

    private fun syncFromServer(isFullRefresh: Boolean = false) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val hasCachedBuckets = getTimelineBucketsUseCase.hasCachedBuckets()

            val hadBannerError = _uiConfig.value.bannerError != null

            if (!hasCachedBuckets) {
                _isBuilding.value = true
                _buildError.value = null
            } else {
                _uiConfig.update { it.copy(isSyncing = true, bannerError = null, bannerSuccess = null) }
            }

            val lastSync = getTimelineBucketsUseCase.getLastSyncedAt()
            _uiConfig.update { it.copy(lastSyncedAt = lastSync) }

            getTimelineBucketsUseCase.sync().fold(
                onSuccess = { syncResult ->
                    val buckets = syncResult.buckets
                    log.d { "Synced ${buckets.size} buckets from server" }
                    invalidateBuckets(syncResult.staleBucketIds + syncResult.removedBucketIds)

                    if (!hasCachedBuckets) {
                        _bucketData.update { it.copy(buckets = buckets) }
                        syncAllTimelineAssetsAction(buckets).fold(
                            onSuccess = { assetResult ->
                                publishAssetSyncResult(
                                    assetResult.successfulBucketIds,
                                    assetResult.failedBucketIds,
                                    assetResult.changedBucketIds
                                )
                                _isBuilding.value = false
                                _buildError.value = null
                                _uiConfig.update { it.copy(isSyncing = false) }
                            },
                            onFailure = { e ->
                                log.e(e) { "Failed full first timeline asset sync" }
                                _isBuilding.value = false
                                _buildError.value = TimelineMessage.NoConnectionToServer
                                _uiConfig.update { it.copy(isSyncing = false) }
                            }
                        )
                    } else if (isFullRefresh) {
                        syncCachedBuckets(buckets, hadBannerError)
                    } else {
                        // Subsequent launch with cache: lazy sync
                        syncCachedBuckets(buckets, hadBannerError)
                    }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to sync buckets from server" }
                    if (!hasCachedBuckets) {
                        _isBuilding.value = false
                        _buildError.value = TimelineMessage.NoConnectionToServer
                    } else {
                        _uiConfig.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = TimelineMessage.CannotConnectToServer
                            )
                        }
                    }
                }
            )
        }
    }

    private suspend fun loadBucketAssetsIfNeeded(timeBucket: String, force: Boolean = false) {
        if (claimBucketLoad(timeBucket, force)) {
            loadBucketAssetsInternal(timeBucket)
        }
    }

    private fun claimBucketLoad(timeBucket: String, force: Boolean = false): Boolean {
        var shouldLoad = false
        _bucketData.update { data ->
            if (!force && (timeBucket in data.loadedBuckets ||
                timeBucket in data.loadingBuckets ||
                timeBucket in data.failedBuckets)
            ) {
                shouldLoad = false
                data
            } else {
                shouldLoad = timeBucket !in data.loadingBuckets
                data.copy(
                    loadedBuckets = data.loadedBuckets - timeBucket,
                    loadingBuckets = data.loadingBuckets + timeBucket,
                    failedBuckets = data.failedBuckets - timeBucket
                )
            }
        }
        return shouldLoad
    }

    private suspend fun loadBucketAssetsInternal(timeBucket: String) {
        log.d { "Syncing assets for bucket: $timeBucket" }
        loadBucketAssetsAction(timeBucket).fold(
            onSuccess = { syncResult ->
                log.d { "Synced assets for bucket: $timeBucket" }
                // Publish fresh assets into the unified cache BEFORE signaling
                // loadedBuckets. The overlay pager observes bucketAssetsCache
                // reactively, and buildDisplayItems (triggered by the
                // _bucketData change) will read the same entry — so the grid's
                // RowItem and the overlay's rendered page are materialized
                // from the same data at the same moment. The stateKey
                // transition (placeholder → loaded) causes the derived-item
                // cache to miss this bucket naturally, so no separate
                // invalidation signal is needed.
                // Forced/lazy loads use the same content-change contract as the
                // launch sync. If the server returns the same ordered assets,
                // keep the existing cache and derived rows alive.
                if (syncResult.changed) {
                    invalidateRuntimeMosaicAssignments(timeBucket)
                    val assets = getBucketAssetsUseCase(timeBucket)
                    bucketAssetsCache[timeBucket] = assets
                } else if (bucketAssetsCache[timeBucket] == null) {
                    bucketAssetsCache[timeBucket] = getBucketAssetsUseCase(timeBucket)
                }
                _bucketData.update { data ->
                    data.copy(
                        loadingBuckets = data.loadingBuckets - timeBucket,
                        loadedBuckets = data.loadedBuckets + timeBucket,
                        assetRevisions = if (syncResult.changed) {
                            data.assetRevisions.incrementRevision(timeBucket)
                        } else {
                            data.assetRevisions
                        }
                    )
                }
                scheduleMosaicQueue()
            },
            onFailure = { e ->
                log.e(e) { "Failed to sync assets for bucket: $timeBucket" }
                _bucketData.update { data ->
                    data.copy(
                        loadingBuckets = data.loadingBuckets - timeBucket,
                        failedBuckets = data.failedBuckets + timeBucket
                    )
                }
            }
        )
    }

    private fun invalidateBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        timeBuckets.forEach { timeBucket ->
            bucketAssetsCache.remove(timeBucket)
            invalidateRuntimeMosaicAssignments(timeBucket)
        }
        _bucketData.update { data ->
            data.copy(
                loadedBuckets = data.loadedBuckets - timeBuckets,
                loadingBuckets = data.loadingBuckets - timeBuckets,
                failedBuckets = data.failedBuckets - timeBuckets,
                // Bucket-count changes alter placeholder height even before
                // assets are loaded, so structural bucket invalidation also
                // bumps the per-bucket content revision.
                assetRevisions = data.assetRevisions.incrementRevisions(timeBuckets)
            )
        }
        scheduleMosaicQueue()
    }

    private suspend fun buildTimelineState(
        data: BucketData,
        config: UiConfig,
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>
    ): TimelineState {
        val mosaicLayoutSpec = mosaicLayoutSpecFor(config.availableWidth, config.targetRowHeight)
        val mosaicColumnCount = mosaicLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT
        val displayItems = buildDisplayItems(
            data,
            config.groupSize,
            config.availableWidth,
            config.targetRowHeight,
            config.rowHeightBounds.max,
            mosaicLayoutSpec,
            config.viewConfig,
            mosaicStates
        )
        val pageIndex = computePageIndex(data.buckets)
        val scrollbarData = computeScrollbarData(data.buckets, pageIndex)

        return TimelineState(
            groupSize = config.groupSize,
            targetRowHeight = config.targetRowHeight,
            rowHeightBounds = config.rowHeightBounds,
            isLoading = config.isLoading,
            error = config.error,
            displayItems = displayItems,
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
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>
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

        var anyChanged = false
        val newCache = mutableMapOf<String, List<TimelineDisplayItem>>()

        for ((index, bucket) in data.buckets.withIndex()) {
            val timeBucket = bucket.timeBucket
            val stateKey = when {
                timeBucket in data.failedBuckets -> "failed"
                timeBucket in data.loadedBuckets -> "loaded"
                else -> "placeholder"
            }
            val useMosaic = shouldUseMosaicLayout(mosaicLayoutSpec, viewConfig)
            val assetRevision = data.assetRevisionFor(timeBucket)
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
            val cacheKey = "${timeBucket}_${stateKey}_${assetRevision}_$layoutKey"

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
                    mosaicStates
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
            val stateKey = when {
                timeBucket in data.failedBuckets -> "failed"
                timeBucket in data.loadedBuckets -> "loaded"
                else -> "placeholder"
            }
            val useMosaic = shouldUseMosaicLayout(mosaicLayoutSpec, viewConfig)
            val assetRevision = data.assetRevisionFor(timeBucket)
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
            val cacheKey = "${timeBucket}_${stateKey}_${assetRevision}_$layoutKey"
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
        mosaicStates: Map<MosaicCacheKey, RuntimeMosaicState>
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
                                    addPlaceholderItems(
                                        items = items,
                                        bucketIndex = index,
                                        sectionLabel = dayLabel,
                                        assetCount = dayGroup.assets.size,
                                        groupSize = TimelineGroupSize.MONTH,
                                        availableWidth = availableWidth,
                                        targetRowHeight = mosaicLayoutSpec?.cellHeight ?: targetRowHeight
                                    )
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
                                            mosaicFallbackMinRowHeight(mosaicLayoutSpec)
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
                            addPlaceholderItems(
                                items = items,
                                bucketIndex = index,
                                sectionLabel = monthLabel,
                                assetCount = bucket.count,
                                groupSize = groupSize,
                                availableWidth = availableWidth,
                                targetRowHeight = mosaicLayoutSpec?.cellHeight ?: targetRowHeight
                            )
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
                                mosaicFallbackMinRowHeight(mosaicLayoutSpec)
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

    private suspend fun syncCachedBuckets(buckets: List<TimelineBucket>, hadBannerError: Boolean) {
        syncAllTimelineAssetsAction(buckets).fold(
            onSuccess = { assetResult ->
                publishAssetSyncResult(
                    assetResult.successfulBucketIds,
                    assetResult.failedBucketIds,
                    assetResult.changedBucketIds
                )
                _uiConfig.update {
                    it.copy(
                        isSyncing = false,
                        bannerSuccess = if (hadBannerError) TimelineMessage.ConnectedToServer else null
                    )
                }
            },
            onFailure = { e ->
                log.e(e) { "Failed cached timeline asset sync" }
                _uiConfig.update {
                    it.copy(
                        isSyncing = false,
                        bannerError = TimelineMessage.CannotConnectToServer
                    )
                }
            }
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
                loadedBuckets = data.loadedBuckets + successfulBucketIds - failedBucketIds,
                loadingBuckets = data.loadingBuckets - successfulBucketIds - failedBucketIds,
                failedBuckets = data.failedBuckets + failedBucketIds - successfulBucketIds,
                assetRevisions = data.assetRevisions.incrementRevisions(changedSuccessfulBucketIds)
            )
        }
        scheduleMosaicQueue()
    }

    private fun invalidateRuntimeMosaicAssignments(timeBucket: String) {
        dayGroupsCache.keys.removeAll { it.timeBucket == timeBucket }
        _mosaicStates.update { states ->
            states.filterKeys { it.timeBucket != timeBucket }
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

    private suspend fun getCachedOrLoadBucketAssets(timeBucket: String): List<Asset> =
        bucketAssetsCache[timeBucket] ?: getBucketAssetsUseCase(timeBucket).also { fetched ->
            bucketAssetsCache[timeBucket] = fetched
        }

    private fun scheduleMosaicQueue(debounce: Boolean = false) {
        mosaicQueueRunner.launch(debounce = debounce) {
            scheduleMosaicQueueNow()
        }
    }

    private suspend fun scheduleMosaicQueueNow() {
        val data = _bucketData.value
        val config = _uiConfig.value
        val layoutSpec = mosaicLayoutSpecFor(config.availableWidth, config.targetRowHeight)
        val useMosaic = shouldUseMosaicLayout(layoutSpec, config.viewConfig)
        if (!useMosaic) {
            if (mosaicConfigKey != null || mosaicQueueJob?.isActive == true || backgroundLoadJob?.isActive == true) {
                mosaicQueueGeneration++
                mosaicConfigKey = null
                mosaicQueueTargetRowHeight = null
                mosaicQueueJob?.cancel()
                mosaicQueueJob = null
                backgroundLoadJob?.cancel()
                backgroundLoadJob = null
                clearInFlightMosaicKeys()
            }
            return
        }

        val nextConfigKey = MosaicQueueConfigKey(
            groupSize = config.groupSize,
            columnCount = layoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT,
            mosaicEnabled = config.viewConfig.mosaicEnabled,
            familiesKey = mosaicFamiliesKey(config.viewConfig.mosaicFamilies)
        )
        if (nextConfigKey != mosaicConfigKey) {
            mosaicConfigKey = nextConfigKey
            mosaicQueueTargetRowHeight = config.targetRowHeight
            mosaicQueueGeneration++
            mosaicQueueJob?.cancel()
            mosaicQueueJob = null
            clearInFlightMosaicKeys()
        } else if (mosaicQueueTargetRowHeight != config.targetRowHeight) {
            mosaicQueueTargetRowHeight = config.targetRowHeight
            log.d {
                "Mosaic queue config unchanged; reusing generation=$mosaicQueueGeneration " +
                    "columnCount=${layoutSpec?.columnCount} targetRowHeight=${config.targetRowHeight}"
            }
        }

        val generation = mosaicQueueGeneration
        ensureBackgroundBucketLoads()
        val activeLayoutSpec = layoutSpec ?: return
        if (nextMosaicRequestsAvailable(data, config, activeLayoutSpec).not()) {
            return
        }

        logTopMosaicPriority(data, config, activeLayoutSpec)
        if (mosaicQueueJob?.isActive == true) return

        log.d {
            "Mosaic priority queue start generation=$generation columnCount=${activeLayoutSpec.columnCount} " +
                "anchor=${config.mosaicAnchorBucketIndex} workers=$MOSAIC_WORKER_PARALLELISM"
        }

        mosaicQueueJob = viewModelScope.launch {
            repeat(MOSAIC_WORKER_PARALLELISM) {
                launch {
                    mosaicWorkerLoop(generation)
                }
            }
        }
    }

    private fun ensureBackgroundBucketLoads() {
        val config = _uiConfig.value
        val layoutSpec = mosaicLayoutSpecFor(config.availableWidth, config.targetRowHeight)
        if (!shouldUseMosaicLayout(layoutSpec, config.viewConfig)) return
        if (backgroundLoadJob?.isActive == true) return

        backgroundLoadJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val timeBucket = nextBackgroundBucketToLoad() ?: return@launch
                loadBucketAssetsIfNeeded(timeBucket)
            }
        }
    }

    private fun nextBackgroundBucketToLoad(): String? {
        val data = _bucketData.value
        val config = _uiConfig.value
        if (data.buckets.isEmpty()) return null
        val order = prioritizedTimelineBucketIndexes(
            bucketCount = data.buckets.size,
            targetBucketIndex = config.mosaicAnchorBucketIndex,
            visibleBucketIndexes = visibleBucketIndexes,
            nearbyRadius = PREFETCH_BUCKET_RADIUS
        )
        return order
            .map { data.buckets[it].timeBucket }
            .firstOrNull { timeBucket ->
                timeBucket !in data.loadedBuckets &&
                    timeBucket !in data.loadingBuckets &&
                    timeBucket !in data.failedBuckets
            }
    }

    private suspend fun clearInFlightMosaicKeys() {
        mosaicQueueMutex.withLock {
            inFlightMosaicKeys.clear()
        }
    }

    private suspend fun mosaicWorkerLoop(generation: Long) {
        while (generation == mosaicQueueGeneration) {
            val request = nextMosaicRequest(generation) ?: return
            buildQueuedMosaic(request, generation)
        }
    }

    private suspend fun nextMosaicRequest(generation: Long): MosaicBuildRequest? =
        mosaicQueueMutex.withLock {
            if (generation != mosaicQueueGeneration) return@withLock null
            val data = _bucketData.value
            val config = _uiConfig.value
            val layoutSpec = mosaicLayoutSpecFor(config.availableWidth, config.targetRowHeight)
                ?: return@withLock null
            val request = prioritizedMosaicRequests(data, config, layoutSpec, inFlightMosaicKeys).firstOrNull()
            if (request != null) {
                inFlightMosaicKeys.add(request.key)
            }
            request
        }

    private fun nextMosaicRequestsAvailable(
        data: BucketData,
        config: UiConfig,
        layoutSpec: MosaicLayoutSpec
    ): Boolean =
        prioritizedMosaicRequests(data, config, layoutSpec).isNotEmpty()

    private fun logTopMosaicPriority(
        data: BucketData,
        config: UiConfig,
        layoutSpec: MosaicLayoutSpec
    ) {
        val topBucket = prioritizedMosaicRequests(data, config, layoutSpec)
            .firstOrNull()
            ?.bucket
            ?.timeBucket
        if (topBucket != null && topBucket != lastLoggedTopMosaicBucket) {
            lastLoggedTopMosaicBucket = topBucket
            log.d {
                "Mosaic top priority bucket=$topBucket anchor=${config.mosaicAnchorBucketIndex} " +
                    "visible=$visibleBucketIndexes columnCount=${layoutSpec.columnCount}"
            }
        }
    }

    private fun prioritizedMosaicRequests(
        data: BucketData,
        config: UiConfig,
        layoutSpec: MosaicLayoutSpec,
        inFlightKeys: Set<MosaicCacheKey> = emptySet()
    ): List<MosaicBuildRequest> {
        if (data.buckets.isEmpty()) return emptyList()
        val anchorBucketIndex = config.mosaicAnchorBucketIndex
            .coerceIn(0, (data.buckets.size - 1).coerceAtLeast(0))
        return prioritizedTimelineBucketIndexes(
            bucketCount = data.buckets.size,
            targetBucketIndex = anchorBucketIndex,
            visibleBucketIndexes = visibleBucketIndexes,
            nearbyRadius = PREFETCH_BUCKET_RADIUS
        )
            .flatMap { index ->
                val bucket = data.buckets[index]
                val assetRevision = data.assetRevisionFor(bucket.timeBucket)
                if (bucket.timeBucket !in data.loadedBuckets || bucket.timeBucket in data.failedBuckets) {
                    return@flatMap emptyList()
                }
                if (config.groupSize == TimelineGroupSize.DAY) {
                    val loaderKey = mosaicCacheKey(
                        timeBucket = bucket.timeBucket,
                        sectionKey = dayMosaicLoaderSectionKey(bucket.timeBucket),
                        columnCount = layoutSpec.columnCount,
                        assetRevision = assetRevision,
                        families = config.viewConfig.mosaicFamilies
                    )
                    if (loaderKey in inFlightKeys) {
                        return@flatMap emptyList()
                    }
                    val assets = bucketAssetsCache[bucket.timeBucket]
                    if (assets == null) {
                        return@flatMap when (_mosaicStates.value[loaderKey]) {
                            is RuntimeMosaicState.Ready,
                            RuntimeMosaicState.Failed -> emptyList()
                            else -> if (loaderKey in inFlightKeys) {
                                emptyList()
                            } else {
                                listOf(MosaicBuildRequest(
                                    bucket = bucket,
                                    key = loaderKey,
                                    layoutSpec = layoutSpec,
                                    enabledFamilies = config.viewConfig.mosaicFamilies,
                                    buildAllDaySections = true
                                ))
                            }
                        }
                    }
                    return@flatMap dayGroupsForBucket(bucket.timeBucket, assetRevision, assets).mapNotNull { dayGroup ->
                        val key = mosaicCacheKey(
                            timeBucket = bucket.timeBucket,
                            sectionKey = dayMosaicSectionKey(bucket.timeBucket, dayGroup.label),
                            columnCount = layoutSpec.columnCount,
                            assetRevision = assetRevision,
                            families = config.viewConfig.mosaicFamilies
                        )
                        if (key in inFlightKeys) return@mapNotNull null
                        when (_mosaicStates.value[key]) {
                            is RuntimeMosaicState.Ready,
                            RuntimeMosaicState.Failed -> null
                            else -> MosaicBuildRequest(
                                bucket = bucket,
                                key = key,
                                layoutSpec = layoutSpec,
                                enabledFamilies = config.viewConfig.mosaicFamilies,
                                sectionAssets = dayGroup.assets
                            )
                        }
                    }
                }
                val key = mosaicCacheKey(
                    timeBucket = bucket.timeBucket,
                    sectionKey = monthMosaicSectionKey(bucket.timeBucket),
                    columnCount = layoutSpec.columnCount,
                    assetRevision = assetRevision,
                    families = config.viewConfig.mosaicFamilies
                )
                if (key in inFlightKeys) return@flatMap emptyList()
                when (_mosaicStates.value[key]) {
                    is RuntimeMosaicState.Ready,
                    RuntimeMosaicState.Failed -> emptyList()
                    else -> listOf(MosaicBuildRequest(bucket, key, layoutSpec, config.viewConfig.mosaicFamilies))
                }
            }
    }

    private suspend fun buildQueuedMosaic(request: MosaicBuildRequest, generation: Long) {
        log.d {
            "Mosaic build start generation=$generation bucket=${request.bucket.timeBucket} " +
                "section=${request.key.sectionKey} columnCount=${request.key.columnCount}"
        }
        try {
            mosaicWorkScheduler.run(
                ownerKey = TIMELINE_MOSAIC_OWNER,
                requestKey = request.key.toString(),
                generation = generation,
                priority = MosaicWorkPriority.Background
            ) { token ->
                buildQueuedMosaicInternal(request, generation, token)
            }
        } finally {
            markMosaicRequestComplete(request.key)
        }
    }

    private suspend fun buildQueuedMosaicInternal(
        request: MosaicBuildRequest,
        generation: Long,
        token: MosaicWorkToken
    ) {
        try {
            val assets = request.sectionAssets ?: withContext(Dispatchers.IO) {
                getBucketAssetsUseCase(request.bucket.timeBucket)
            }
            if (request.sectionAssets == null) {
                bucketAssetsCache[request.bucket.timeBucket] = assets
            }
            val mosaicContext = currentCoroutineContext()
            if (request.buildAllDaySections) {
                val updates = mutableMapOf<MosaicCacheKey, RuntimeMosaicState>()
                for (dayGroup in dayGroupsForBucket(request.bucket.timeBucket, request.key.assetRevision, assets)) {
                    token.ensureActive()
                    mosaicContext.ensureActive()
                    val dayKey = mosaicCacheKey(
                        timeBucket = request.bucket.timeBucket,
                        sectionKey = dayMosaicSectionKey(request.bucket.timeBucket, dayGroup.label),
                        columnCount = request.key.columnCount,
                        assetRevision = request.key.assetRevision,
                        families = request.enabledFamilies
                    )
                    val assignments = buildMosaicAssignments(
                        assets = dayGroup.assets,
                        layoutSpec = request.layoutSpec,
                        spacing = GRID_SPACING_DP,
                        enabledFamilies = request.enabledFamilies,
                        shouldContinue = {
                            token.ensureActive()
                            mosaicContext.ensureActive()
                        }
                    )
                    logMosaicAssignmentStats(
                        sectionKey = dayKey.sectionKey,
                        assetCount = dayGroup.assets.size,
                        assignments = assignments
                    )
                    updates[dayKey] = RuntimeMosaicState.Ready(assignments)
                }
                if (!isCurrentMosaicRequest(request.key, generation)) {
                    log.d {
                        "Mosaic build stale generation=$generation bucket=${request.bucket.timeBucket} " +
                            "section=${request.key.sectionKey} columnCount=${request.key.columnCount}"
                    }
                    return
                }
                _mosaicStates.update { it + updates + (request.key to RuntimeMosaicState.Ready(emptyList())) }
                log.d {
                    "Mosaic build finished generation=$generation bucket=${request.bucket.timeBucket} " +
                        "section=${request.key.sectionKey} columnCount=${request.key.columnCount} " +
                        "assets=${assets.size} daySections=${updates.size}"
                }
                return
            }
            val assignments = buildMosaicAssignments(
                assets = assets,
                layoutSpec = request.layoutSpec,
                spacing = GRID_SPACING_DP,
                enabledFamilies = request.enabledFamilies,
                shouldContinue = {
                    token.ensureActive()
                    mosaicContext.ensureActive()
                }
            )
            logMosaicAssignmentStats(
                sectionKey = request.key.sectionKey,
                assetCount = assets.size,
                assignments = assignments
            )
            if (!isCurrentMosaicRequest(request.key, generation)) {
                log.d {
                    "Mosaic build stale generation=$generation bucket=${request.bucket.timeBucket} " +
                        "section=${request.key.sectionKey} columnCount=${request.key.columnCount}"
                }
                return
            }
            _mosaicStates.update { it + (request.key to RuntimeMosaicState.Ready(assignments)) }
            log.d {
                "Mosaic build finished generation=$generation bucket=${request.bucket.timeBucket} " +
                    "section=${request.key.sectionKey} columnCount=${request.key.columnCount} " +
                    "assets=${assets.size} bands=${assignments.size}"
            }
        } catch (e: CancellationException) {
            log.d {
                "Mosaic build cancelled generation=$generation bucket=${request.bucket.timeBucket} " +
                    "section=${request.key.sectionKey} columnCount=${request.key.columnCount}"
            }
            throw e
        } catch (e: Exception) {
            if (isCurrentMosaicRequest(request.key, generation)) {
                _mosaicStates.update { it + (request.key to RuntimeMosaicState.Failed) }
            }
            log.e(e) {
                "Mosaic build failed generation=$generation bucket=${request.bucket.timeBucket} " +
                    "section=${request.key.sectionKey} columnCount=${request.key.columnCount}"
            }
        }
    }

    private suspend fun markMosaicRequestComplete(key: MosaicCacheKey) {
        mosaicQueueMutex.withLock {
            inFlightMosaicKeys.remove(key)
        }
    }

    private fun isCurrentMosaicRequest(key: MosaicCacheKey, generation: Long): Boolean {
        val config = _uiConfig.value
        val currentLayoutSpec = mosaicLayoutSpecFor(config.availableWidth, config.targetRowHeight)
        val currentColumnCount = currentLayoutSpec?.columnCount ?: DEFAULT_GRID_COLUMN_COUNT
        return generation == mosaicQueueGeneration &&
            shouldUseMosaicLayout(currentLayoutSpec, config.viewConfig) &&
            key.assetRevision == _bucketData.value.assetRevisionFor(key.timeBucket) &&
            key.columnCount == currentColumnCount &&
            key.familiesKey == mosaicFamiliesKey(config.viewConfig.mosaicFamilies)
    }

    private fun logMosaicAssignmentStats(
        sectionKey: String,
        assetCount: Int,
        assignments: List<MosaicBandAssignment>
    ) {
        val assignedAssetCount = assignments.sumOf { it.sourceCount }
        log.d {
            "Mosaic assignments section=$sectionKey assets=$assetCount " +
                "bands=${assignments.size} assignedAssets=$assignedAssetCount " +
                "fallbackOrSkippedAssets=${assetCount - assignedAssetCount}"
        }
    }

    private fun shouldUseMosaicLayout(layoutSpec: MosaicLayoutSpec?, viewConfig: ViewConfig): Boolean =
        viewConfig.mosaicEnabled && layoutSpec != null

    private data class MosaicBuildRequest(
        val bucket: TimelineBucket,
        val key: MosaicCacheKey,
        val layoutSpec: MosaicLayoutSpec,
        val enabledFamilies: Set<MosaicTemplateFamily>,
        val sectionAssets: List<Asset>? = null,
        val buildAllDaySections: Boolean = false
    )

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
        private const val MOSAIC_WORKER_PARALLELISM = 1
        private const val TIMELINE_MOSAIC_OWNER = "timeline"
    }
}
