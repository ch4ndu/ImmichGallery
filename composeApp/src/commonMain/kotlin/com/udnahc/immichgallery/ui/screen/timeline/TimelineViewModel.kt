package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.Immutable
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
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.packIntoRows
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

const val MIN_TARGET_ROW_HEIGHT = 80f
const val MAX_TARGET_ROW_HEIGHT = 300f
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val displayItems: List<TimelineDisplayItem> = emptyList(),
    val yearMarkers: List<YearMarker> = emptyList(),
    val totalItemCount: Int = 0,
    val cumulativeItemCounts: List<Int> = emptyList(),
    val bucketDisplayLabels: List<String> = emptyList(),
    val buckets: List<TimelineBucket> = emptyList(),
    val bannerError: String? = null,
    val bannerSuccess: String? = null,
    val lastSyncedAt: Long? = null,
    val isSyncing: Boolean = false
)

@Immutable
private data class BucketData(
    val buckets: List<TimelineBucket> = emptyList(),
    val loadedBuckets: Set<String> = emptySet(),
    val loadingBuckets: Set<String> = emptySet(),
    val failedBuckets: Set<String> = emptySet(),
    // Buckets whose caches need invalidation — consumed by buildDisplayItems
    val pendingInvalidations: Set<String> = emptySet()
)

@Immutable
private data class UiConfig(
    val groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val availableWidth: Float = 0f,
    val isLoading: Boolean = false,
    val error: String? = null,
    val bannerError: String? = null,
    val bannerSuccess: String? = null,
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

    var lastViewedAssetId: String? = null

    suspend fun getAssetsForBucket(timeBucket: String): List<Asset> =
        getBucketAssetsUseCase(timeBucket)

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    suspend fun getAssetFileName(assetId: String, fallback: String): Result<String> =
        getAssetFileNameUseCase(assetId, fallback)

    private val log = logging()
    private var syncJob: Job? = null

    private val _bucketData = MutableStateFlow(BucketData())
    private val _uiConfig: MutableStateFlow<UiConfig>

    // Exposed directly (not through debounced pipeline) so UI sees it immediately
    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()
    private val _buildError = MutableStateFlow<String?>(null)
    val buildError: StateFlow<String?> = _buildError.asStateFlow()

    // All cache fields are ONLY read/written from buildDisplayItems on Dispatchers.Default
    // Invalidation signals flow through BucketData.pendingInvalidations (thread-safe via StateFlow)
    private val cachedAssets: MutableMap<String, List<Asset>> = mutableMapOf()
    private var cachedBucketItems: Map<String, List<TimelineDisplayItem>> = emptyMap()
    private var cachedFlatItems: List<TimelineDisplayItem> = emptyList()
    private var cachedGroupSize: TimelineGroupSize? = null
    private var cachedAvailableWidth: Float? = null
    private var cachedTargetRowHeight: Float? = null

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
        val initialTargetRowHeight = getTargetRowHeightUseCase()
            .coerceIn(MIN_TARGET_ROW_HEIGHT, MAX_TARGET_ROW_HEIGHT)

        _uiConfig = MutableStateFlow(UiConfig(groupSize = initialSize, targetRowHeight = initialTargetRowHeight))

        @OptIn(FlowPreview::class)
        state = combine(_bucketData, _uiConfig) { data, config ->
            buildTimelineState(data, config)
        }
            .debounce(200)
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

    fun setTargetRowHeight(height: Float) {
        val clamped = height.coerceIn(MIN_TARGET_ROW_HEIGHT, MAX_TARGET_ROW_HEIGHT)
        if (clamped == _uiConfig.value.targetRowHeight) return
        setTargetRowHeightAction(clamped)
        _uiConfig.update { it.copy(targetRowHeight = clamped) }
    }

    fun loadBucketAssets(timeBucket: String) {
        val current = _bucketData.value
        if (timeBucket in current.loadedBuckets ||
            timeBucket in current.loadingBuckets ||
            timeBucket in current.failedBuckets
        ) return

        viewModelScope.launch(Dispatchers.IO) {
            var shouldLoad = false
            _bucketData.update { data ->
                if (timeBucket in data.loadedBuckets ||
                    timeBucket in data.loadingBuckets ||
                    timeBucket in data.failedBuckets
                ) {
                    shouldLoad = false
                    data
                } else {
                    shouldLoad = true
                    data.copy(loadingBuckets = data.loadingBuckets + timeBucket)
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

    fun getDisplayItemIndex(assetId: String): Int? {
        return state.value.displayItems.indexOfFirst { item ->
            when (item) {
                is PhotoItem -> item.asset.id == assetId
                is RowItem -> item.photos.any { it.asset.id == assetId }
                else -> false
            }
        }.takeIf { it >= 0 }
    }

    suspend fun getGlobalPhotoIndex(assetId: String): Int? {
        val s = state.value
        var globalIndex = 0
        for (bucket in s.buckets) {
            // Always query Room — avoids touching cachedAssets from a different thread context
            val assets = withContext(Dispatchers.IO) {
                getBucketAssetsUseCase(bucket.timeBucket)
            }
            val localIndex = assets.indexOfFirst { it.id == assetId }
            if (localIndex >= 0) return globalIndex + localIndex
            globalIndex += bucket.count
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
                onSuccess = { buckets ->
                    log.d { "Synced ${buckets.size} buckets from server" }

                    if (!hasCachedBuckets) {
                        // First launch: blocking full sync of ALL bucket assets
                        log.d { "First launch — syncing all ${buckets.size} bucket assets..." }
                        for (bucket in buckets) {
                            loadBucketAssetsAction(bucket.timeBucket).fold(
                                onSuccess = {
                                    _bucketData.update { data ->
                                        data.copy(
                                            loadedBuckets = data.loadedBuckets + bucket.timeBucket,
                                            pendingInvalidations = data.pendingInvalidations + bucket.timeBucket
                                        )
                                    }
                                },
                                onFailure = { e ->
                                    log.e(e) { "Failed to sync assets for bucket: ${bucket.timeBucket}" }
                                    _bucketData.update { data ->
                                        data.copy(failedBuckets = data.failedBuckets + bucket.timeBucket)
                                    }
                                }
                            )
                        }
                        _isBuilding.value = false
                    } else if (isFullRefresh) {
                        // Manual refresh: clear tracking so buckets re-sync on scroll
                        _bucketData.update {
                            it.copy(
                                loadedBuckets = emptySet(),
                                loadingBuckets = emptySet(),
                                failedBuckets = emptySet()
                            )
                        }
                        _uiConfig.update {
                            it.copy(
                                isSyncing = false,
                                bannerSuccess = if (hadBannerError) "Connected to server" else null
                            )
                        }
                        for (i in 0..minOf(4, buckets.size - 1)) {
                            loadBucketAssets(buckets[i].timeBucket)
                        }
                    } else {
                        // Subsequent launch with cache: lazy sync
                        _uiConfig.update {
                            it.copy(
                                isSyncing = false,
                                bannerSuccess = if (hadBannerError) "Connected to server" else null
                            )
                        }
                        for (i in 0..minOf(4, buckets.size - 1)) {
                            loadBucketAssets(buckets[i].timeBucket)
                        }
                    }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to sync buckets from server" }
                    if (!hasCachedBuckets) {
                        _isBuilding.value = false
                        _buildError.value = "No connection to server"
                    } else {
                        _uiConfig.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = "Cannot connect to server"
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
                _bucketData.update { data ->
                    data.copy(
                        loadingBuckets = data.loadingBuckets - timeBucket,
                        loadedBuckets = data.loadedBuckets + timeBucket,
                        pendingInvalidations = data.pendingInvalidations + timeBucket
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

    private suspend fun buildTimelineState(data: BucketData, config: UiConfig): TimelineState {
        val displayItems = buildDisplayItems(data, config.groupSize, config.availableWidth, config.targetRowHeight)
        val scrollbarData = computeScrollbarData(data.buckets, displayItems)

        return TimelineState(
            groupSize = config.groupSize,
            targetRowHeight = config.targetRowHeight,
            isLoading = config.isLoading,
            error = config.error,
            displayItems = displayItems,
            yearMarkers = scrollbarData.yearMarkers,
            totalItemCount = scrollbarData.totalItemCount,
            cumulativeItemCounts = scrollbarData.cumulativeItemCounts,
            bucketDisplayLabels = scrollbarData.bucketDisplayLabels,
            buckets = data.buckets,
            bannerError = config.bannerError,
            bannerSuccess = config.bannerSuccess,
            lastSyncedAt = config.lastSyncedAt,
            isSyncing = config.isSyncing
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
        targetRowHeight: Float
    ): List<TimelineDisplayItem> {
        // Full cache invalidation if layout parameters changed
        if (groupSize != cachedGroupSize ||
            availableWidth != cachedAvailableWidth ||
            targetRowHeight != cachedTargetRowHeight
        ) {
            cachedAssets.clear()
            cachedBucketItems = emptyMap()
            cachedFlatItems = emptyList()
            cachedGroupSize = groupSize
            cachedAvailableWidth = availableWidth
            cachedTargetRowHeight = targetRowHeight
        }

        // Process pending bucket invalidations (delivered via BucketData, thread-safe)
        if (data.pendingInvalidations.isNotEmpty()) {
            for (bucket in data.pendingInvalidations) {
                cachedAssets.remove(bucket)
                cachedBucketItems = cachedBucketItems.filterKeys { key ->
                    !key.startsWith("${bucket}_")
                }
            }
            cachedFlatItems = emptyList()
            // Clear consumed invalidations
            _bucketData.update { it.copy(pendingInvalidations = emptySet()) }
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
                    data, index, bucket, groupSize, availableWidth, targetRowHeight
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
        targetRowHeight: Float
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
                val assets = cachedAssets.getOrPut(timeBucket) {
                    getBucketAssetsUseCase(timeBucket)
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
                                availableWidth, targetRowHeight, GRID_SPACING_DP
                            ))
                        }
                    } else {
                        items.addAll(packIntoRows(
                            assets, index, monthLabel,
                            availableWidth, targetRowHeight, GRID_SPACING_DP
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
            .map { (date, dayAssets) ->
                val d = date!!
                val monthName = d.month.name.lowercase()
                DayGroup(
                    label = "${d.dayOfMonth} $monthName ${d.year}",
                    assets = dayAssets.sortedByDescending { it.createdAt }
                )
            }
    }
}
