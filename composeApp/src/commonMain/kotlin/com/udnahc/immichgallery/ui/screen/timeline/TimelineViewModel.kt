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
import com.udnahc.immichgallery.domain.action.timeline.LoadBucketAssetsAction
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.lighthousegames.logging.logging

private const val MAX_PLACEHOLDER_HEIGHT_DP = 10000f
private const val SECTION_HEADER_HEIGHT_DP = 48f

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

@Immutable
private data class BucketData(
    val buckets: List<TimelineBucket> = emptyList(),
    val loadedBuckets: Set<String> = emptySet(),
    val loadingBuckets: Set<String> = emptySet(),
    val failedBuckets: Set<String> = emptySet()
)

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
    val isSyncing: Boolean = false
)

class TimelineViewModel(
    private val getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
    private val getBucketAssetsUseCase: GetBucketAssetsUseCase,
    private val loadBucketAssetsAction: LoadBucketAssetsAction,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetFileNameUseCase: GetAssetFileNameUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTimelineGroupSizeUseCase: GetTimelineGroupSizeUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val setTimelineGroupSizeAction: SetTimelineGroupSizeAction,
    private val setTargetRowHeightAction: SetTargetRowHeightAction
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    var lastViewedAssetId: String? by mutableStateOf(null)
    var lastViewedBucket: String? by mutableStateOf(null)

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    suspend fun getAssetFileName(assetId: String, fallback: String): Result<String> =
        getAssetFileNameUseCase(assetId, fallback)

    private val log = logging()
    private var syncJob: Job? = null
    private var currentVisibleBucketIndex: Int? = null
    private var savedTargetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT

    private val _bucketData = MutableStateFlow(BucketData())
    private val _uiConfig: MutableStateFlow<UiConfig>

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
    // and Dispatchers.Default (lazy getOrPut inside buildBucketItems); reads
    // happen from Default (build pipeline) and the UI thread (overlay pager).
    val bucketAssetsCache: SnapshotStateMap<String, List<Asset>> = mutableStateMapOf()

    // Derived-item caches are layout-dependent, still single-threaded on Default.
    private var cachedBucketItems: Map<String, List<TimelineDisplayItem>> = emptyMap()
    private var cachedFlatItems: List<TimelineDisplayItem> = emptyList()
    private var cachedGroupSize: TimelineGroupSize? = null
    private var cachedAvailableWidth: Float? = null
    private var cachedTargetRowHeight: Float? = null
    private var cachedMaxRowHeight: Float? = null

    val state: StateFlow<TimelineState>

    val labelProvider: (Float) -> String? = { fraction ->
        val s = state.value
        val counts = s.cumulativeItemCounts
        if (counts.isNotEmpty()) {
            val targetIndex = (fraction * s.totalItemCount).toInt()
            var low = 0
            var high = counts.size - 1
            while (low < high) {
                val mid = (low + high) ushr 1
                if (counts[mid] <= targetIndex) low = mid + 1 else high = mid
            }
            s.bucketDisplayLabels.getOrNull(low)
        } else null
    }

    init {
        val saved = getTimelineGroupSizeUseCase()
        val initialSize = TimelineGroupSize.entries.find { it.apiValue == saved }
            ?: TimelineGroupSize.MONTH
        savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.TIMELINE)
        val initialTargetRowHeight = savedTargetRowHeight

        _uiConfig = MutableStateFlow(UiConfig(groupSize = initialSize, targetRowHeight = initialTargetRowHeight))

        viewModelScope.launch {
            getTimelineGroupSizeUseCase.observe().collect { saved ->
                val size = TimelineGroupSize.entries.find { it.apiValue == saved }
                    ?: TimelineGroupSize.MONTH
                if (size != _uiConfig.value.groupSize) {
                    _uiConfig.update { it.copy(groupSize = size) }
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
        state = combine(_bucketData, _uiConfig.debounce(200)) { data, config ->
            buildTimelineState(data, config)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineState(
                groupSize = initialSize,
                targetRowHeight = initialTargetRowHeight
            ))

        // Observe Room for buckets (reactive SSOT)
        viewModelScope.launch(Dispatchers.IO) {
            // Pre-populate loadedBuckets from Room so cached assets display immediately
            val alreadyLoaded = getTimelineBucketsUseCase.getLoadedBucketIds()
            if (alreadyLoaded.isNotEmpty()) {
                _bucketData.update { it.copy(loadedBuckets = it.loadedBuckets + alreadyLoaded) }
            }

            getTimelineBucketsUseCase.observe().collect { buckets ->
                log.d { "Room emitted ${buckets.size} buckets" }
                _bucketData.update { it.copy(buckets = buckets) }
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
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _uiConfig.value.availableWidth) return
        _uiConfig.update { it.copy(availableWidth = widthDp) }
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        if (heightDp == _uiConfig.value.availableHeight) return
        val bounds = rowHeightBoundsForViewport(heightDp)
        _uiConfig.update {
            it.copy(
                availableHeight = heightDp,
                rowHeightBounds = bounds,
                targetRowHeight = bounds.clamp(savedTargetRowHeight)
            )
        }
    }

    fun setTargetRowHeight(height: Float) {
        val clamped = _uiConfig.value.rowHeightBounds.clamp(height)
        if (clamped == _uiConfig.value.targetRowHeight) return
        savedTargetRowHeight = clamped
        setTargetRowHeightAction(RowHeightScope.TIMELINE, clamped)
        _uiConfig.update { it.copy(targetRowHeight = clamped) }
    }

    fun loadBucketAssets(timeBucket: String, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            var shouldLoad = false
            _bucketData.update { data ->
                if (!force && (timeBucket in data.loadedBuckets ||
                    timeBucket in data.loadingBuckets ||
                    timeBucket in data.failedBuckets)
                ) {
                    shouldLoad = false
                    data
                } else {
                    if (force) bucketAssetsCache.remove(timeBucket)
                    shouldLoad = timeBucket !in data.loadingBuckets
                    data.copy(
                        loadedBuckets = data.loadedBuckets - timeBucket,
                        loadingBuckets = data.loadingBuckets + timeBucket,
                        failedBuckets = data.failedBuckets - timeBucket
                    )
                }
            }
            if (shouldLoad) {
                loadBucketAssetsInternal(timeBucket)
            }
        }
    }

    fun onFirstVisibleItemChanged(firstVisibleIndex: Int) {
        val snapshot = state.value
        if (firstVisibleIndex !in snapshot.displayItems.indices) return
        val bucketIndex = snapshot.displayItems[firstVisibleIndex].bucketIndex
        currentVisibleBucketIndex = bucketIndex
        for (offset in -2..2) {
            val idx = bucketIndex + offset
            if (idx in snapshot.buckets.indices) {
                loadBucketAssets(snapshot.buckets[idx].timeBucket)
            }
        }
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
                        _isBuilding.value = false
                        _buildError.value = null
                        _uiConfig.update { it.copy(isSyncing = false) }
                        loadInitialBuckets(buckets, force = false)
                    } else if (isFullRefresh) {
                        _uiConfig.update {
                            it.copy(
                                isSyncing = false,
                                bannerSuccess = if (hadBannerError) TimelineMessage.ConnectedToServer else null
                            )
                        }
                        val forceTargets = syncResult.staleBucketIds + refreshTargetBuckets(buckets)
                        for (timeBucket in forceTargets) {
                            loadBucketAssets(timeBucket, force = true)
                        }
                    } else {
                        // Subsequent launch with cache: lazy sync
                        _uiConfig.update {
                            it.copy(
                                isSyncing = false,
                                bannerSuccess = if (hadBannerError) TimelineMessage.ConnectedToServer else null
                            )
                        }
                        loadInitialBuckets(buckets, force = false)
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

    private suspend fun loadBucketAssetsInternal(timeBucket: String) {
        log.d { "Syncing assets for bucket: $timeBucket" }
        loadBucketAssetsAction(timeBucket).fold(
            onSuccess = {
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
                val assets = getBucketAssetsUseCase(timeBucket)
                bucketAssetsCache[timeBucket] = assets
                _bucketData.update { data ->
                    data.copy(
                        loadingBuckets = data.loadingBuckets - timeBucket,
                        loadedBuckets = data.loadedBuckets + timeBucket
                    )
                }
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
        timeBuckets.forEach { bucketAssetsCache.remove(it) }
        _bucketData.update { data ->
            data.copy(
                loadedBuckets = data.loadedBuckets - timeBuckets,
                loadingBuckets = data.loadingBuckets - timeBuckets,
                failedBuckets = data.failedBuckets - timeBuckets
            )
        }
    }

    private fun loadInitialBuckets(buckets: List<TimelineBucket>, force: Boolean) {
        for (bucket in buckets.take(INITIAL_BUCKET_LOAD_COUNT)) {
            loadBucketAssets(bucket.timeBucket, force = force)
        }
    }

    private fun refreshTargetBuckets(buckets: List<TimelineBucket>): Set<String> {
        val targets = linkedSetOf<String>()
        buckets.take(INITIAL_BUCKET_LOAD_COUNT).forEach { targets.add(it.timeBucket) }
        val visibleIndex = currentVisibleBucketIndex
        if (visibleIndex != null) {
            for (offset in -PREFETCH_BUCKET_RADIUS..PREFETCH_BUCKET_RADIUS) {
                val idx = visibleIndex + offset
                if (idx in buckets.indices) targets.add(buckets[idx].timeBucket)
            }
        }
        return targets
    }

    private suspend fun buildTimelineState(data: BucketData, config: UiConfig): TimelineState {
        val displayItems = buildDisplayItems(
            data,
            config.groupSize,
            config.availableWidth,
            config.targetRowHeight,
            config.rowHeightBounds.max
        )
        val scrollbarData = computeScrollbarData(data.buckets, displayItems)
        val pageIndex = computePageIndex(data.buckets)

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
        maxRowHeight: Float
    ): List<TimelineDisplayItem> {
        // Layout change invalidates only the DERIVED display items (which are
        // sized against width/rowHeight/groupSize). The raw bucket assets in
        // bucketAssetsCache are layout-independent and must survive pinch-to-
        // zoom without forcing a Room re-query for every bucket.
        if (groupSize != cachedGroupSize ||
            availableWidth != cachedAvailableWidth ||
            targetRowHeight != cachedTargetRowHeight ||
            maxRowHeight != cachedMaxRowHeight
        ) {
            cachedBucketItems = emptyMap()
            cachedFlatItems = emptyList()
            cachedGroupSize = groupSize
            cachedAvailableWidth = availableWidth
            cachedTargetRowHeight = targetRowHeight
            cachedMaxRowHeight = maxRowHeight
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
            val cacheKey = "${timeBucket}_${stateKey}"

            val cached = cachedBucketItems[cacheKey]
            if (cached != null) {
                newCache[cacheKey] = cached
            } else {
                anyChanged = true
                val bucketItems = buildBucketItems(
                    data, index, bucket, groupSize, availableWidth, targetRowHeight, maxRowHeight
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
        for (bucket in data.buckets) {
            val timeBucket = bucket.timeBucket
            val stateKey = when {
                timeBucket in data.failedBuckets -> "failed"
                timeBucket in data.loadedBuckets -> "loaded"
                else -> "placeholder"
            }
            val cacheKey = "${timeBucket}_${stateKey}"
            newCache[cacheKey]?.let { items.addAll(it) }
        }

        cachedBucketItems = newCache
        cachedFlatItems = items
        return items
    }

    private suspend fun buildBucketItems(
        data: BucketData,
        index: Int,
        bucket: TimelineBucket,
        groupSize: TimelineGroupSize,
        availableWidth: Float,
        targetRowHeight: Float,
        maxRowHeight: Float
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
                val photosPerRow = if (availableWidth > 0f)
                    (availableWidth / targetRowHeight).coerceAtLeast(1f) else 3f
                val estimatedRows = kotlin.math.ceil(bucket.count.toFloat() / photosPerRow).toInt()
                val estimatedDayHeaders = if (groupSize == TimelineGroupSize.DAY)
                    (bucket.count / 3).coerceAtLeast(1) else 0
                val dayHeaderHeight = estimatedDayHeaders * SECTION_HEADER_HEIGHT_DP
                val totalEstimatedHeight = (estimatedRows * (targetRowHeight + GRID_SPACING_DP) - GRID_SPACING_DP + dayHeaderHeight)
                    .coerceAtLeast(targetRowHeight)

                val chunks = kotlin.math.ceil(totalEstimatedHeight / MAX_PLACEHOLDER_HEIGHT_DP).toInt()
                    .coerceAtLeast(1)
                val chunkHeight = totalEstimatedHeight / chunks
                repeat(chunks) { chunkIdx ->
                    items.add(PlaceholderItem(
                        gridKey = "pl_${index}_$chunkIdx",
                        bucketIndex = index,
                        sectionLabel = monthLabel,
                        estimatedHeight = chunkHeight
                    ))
                }
            }
            else -> {
                // Fast path: overlay or a prior buildBucketItems already
                // populated the unified cache. Slow path: first time this
                // bucket is rendered in the grid, fetch from Room and publish
                // into the cache so the overlay can also read it reactively.
                val assets = bucketAssetsCache[timeBucket] ?: run {
                    val fetched = getBucketAssetsUseCase(timeBucket)
                    bucketAssetsCache[timeBucket] = fetched
                    fetched
                }
                if (assets.isNotEmpty()) {
                    val dayGroupList = if (groupSize == TimelineGroupSize.DAY) {
                        computeDayGroups(assets)
                    } else null

                    if (!dayGroupList.isNullOrEmpty()) {
                        for (dayGroup in dayGroupList) {
                            items.add(HeaderItem(
                                gridKey = "dh_${index}_${dayGroup.label}",
                                bucketIndex = index,
                                sectionLabel = dayGroup.label,
                                label = dayGroup.label
                            ))
                            items.addAll(packIntoRows(
                                dayGroup.assets, index, dayGroup.label,
                                availableWidth, targetRowHeight, GRID_SPACING_DP, maxRowHeight
                            ))
                        }
                    } else {
                        items.addAll(packIntoRows(
                            assets, index, monthLabel,
                            availableWidth, targetRowHeight, GRID_SPACING_DP, maxRowHeight
                        ))
                    }
                } else {
                    // Loaded but empty — show header only (no placeholder trap)
                }
            }
        }
        return items
    }

    private data class ScrollbarData(
        val yearMarkers: List<YearMarker>,
        val totalItemCount: Int,
        val cumulativeItemCounts: List<Int>,
        val bucketDisplayLabels: List<String>
    )

    private fun computeScrollbarData(
        buckets: List<TimelineBucket>,
        displayItems: List<TimelineDisplayItem>
    ): ScrollbarData {
        val bucketItemCounts = IntArray(buckets.size)
        for (item in displayItems) {
            if (item.bucketIndex in bucketItemCounts.indices) {
                bucketItemCounts[item.bucketIndex]++
            }
        }

        var cumulative = 0
        val cumulativeCounts = bucketItemCounts.map { count ->
            cumulative += count
            cumulative
        }
        val totalItems = displayItems.size

        val markers = mutableListOf<YearMarker>()
        var lastYear = ""
        for (i in buckets.indices) {
            val year = buckets[i].displayLabel.substringAfterLast(" ", "")
            if (year != lastYear && year.isNotEmpty()) {
                val fraction = if (totalItems > 0) {
                    (cumulativeCounts.getOrElse(i - 1) { 0 }).toFloat() / totalItems
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
        private const val INITIAL_BUCKET_LOAD_COUNT = 5
        private const val PREFETCH_BUCKET_RADIUS = 2
    }
}
