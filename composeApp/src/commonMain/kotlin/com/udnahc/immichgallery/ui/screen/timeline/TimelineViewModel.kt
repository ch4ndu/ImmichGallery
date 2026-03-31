package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.lighthousegames.logging.logging

const val MIN_GRID_COLUMNS = 2
const val MAX_GRID_COLUMNS = 7

@Immutable
data class YearMarker(val fraction: Float, val year: String)

@Immutable
data class DayGroup(val label: String, val assets: List<Asset>)

@Immutable
data class TimelineState(
    val groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    val gridColumns: Int = 3,
    val isLoading: Boolean = false,
    val error: String? = null,
    val displayItems: List<TimelineDisplayItem> = emptyList(),
    // Scrollbar support:
    val yearMarkers: List<YearMarker> = emptyList(),
    val totalItemCount: Int = 0,
    val cumulativeItemCounts: List<Int> = emptyList(),
    val bucketDisplayLabels: List<String> = emptyList(),
    // For TimelinePhotoOverlay (unchanged contract):
    val buckets: List<TimelineBucket> = emptyList(),
    val bucketAssets: Map<String, List<Asset>> = emptyMap(),
    val loadedBuckets: Set<String> = emptySet()
)

// Private bucket management state
private data class BucketData(
    val buckets: List<TimelineBucket> = emptyList(),
    val loadedBuckets: Set<String> = emptySet(),
    val loadingBuckets: Set<String> = emptySet(),
    val failedBuckets: Set<String> = emptySet(),
    val bucketAssets: Map<String, List<Asset>> = emptyMap(),
    val dayGroups: Map<String, List<DayGroup>> = emptyMap()
)

private data class UiConfig(
    val groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    val gridColumns: Int = 3,
    val isLoading: Boolean = false,
    val error: String? = null
)

class TimelineViewModel(
    private val getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
    private val getBucketAssetsUseCase: GetBucketAssetsUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetFileNameUseCase: GetAssetFileNameUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val serverConfigRepository: ServerConfigRepository
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    suspend fun getAssetFileName(assetId: String, fallback: String): Result<String> =
        getAssetFileNameUseCase(assetId, fallback)

    private val log = logging()
    private var loadBucketsJob: Job? = null

    private val _bucketData = MutableStateFlow(BucketData())
    private val _uiConfig: MutableStateFlow<UiConfig>

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
        val saved = serverConfigRepository.getTimelineGroupSize()
        val initialSize = TimelineGroupSize.entries.find { it.apiValue == saved }
            ?: TimelineGroupSize.MONTH
        val initialColumns = serverConfigRepository.getGridColumns()
            .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)

        _uiConfig = MutableStateFlow(UiConfig(groupSize = initialSize, gridColumns = initialColumns))

        @OptIn(FlowPreview::class)
        state = combine(_bucketData, _uiConfig) { data, config ->
            buildTimelineState(data, config)
        }
            .debounce(100)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TimelineState(
                groupSize = initialSize,
                gridColumns = initialColumns
            ))

        loadBuckets()
    }

    fun setGroupSize(size: TimelineGroupSize) {
        if (size == _uiConfig.value.groupSize) return
        serverConfigRepository.setTimelineGroupSize(size.apiValue)
        _bucketData.update { BucketData() }
        _uiConfig.update { it.copy(groupSize = size) }
        loadBuckets()
    }

    fun setGridColumns(columns: Int) {
        val clamped = columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        if (clamped == _uiConfig.value.gridColumns) return
        serverConfigRepository.setGridColumns(clamped)
        _uiConfig.update { it.copy(gridColumns = clamped) }
    }

    fun loadBuckets() {
        loadBucketsJob?.cancel()
        loadBucketsJob = viewModelScope.launch(Dispatchers.IO) {
            log.d { "Loading timeline buckets..." }
            _uiConfig.update { it.copy(isLoading = true, error = null) }
            getTimelineBucketsUseCase().fold(
                onSuccess = { buckets ->
                    log.d { "Loaded ${buckets.size} timeline buckets" }
                    _bucketData.update { it.copy(buckets = buckets) }
                    _uiConfig.update { it.copy(isLoading = false) }
                    // Trigger initial loading — visibility callback may not fire for index 0
                    for (i in 0..minOf(4, buckets.size - 1)) {
                        loadBucketAssets(buckets[i].timeBucket)
                    }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to load timeline buckets" }
                    _uiConfig.update {
                        it.copy(isLoading = false, error = e.message ?: "Failed to load timeline")
                    }
                }
            )
        }
    }

    fun loadBucketAssets(timeBucket: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var shouldLoad = false
            _bucketData.update { current ->
                if (timeBucket in current.loadedBuckets ||
                    timeBucket in current.loadingBuckets ||
                    timeBucket in current.failedBuckets
                ) {
                    shouldLoad = false
                    current
                } else {
                    shouldLoad = true
                    current.copy(loadingBuckets = current.loadingBuckets + timeBucket)
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
        val items = state.value.displayItems
        return items.indexOfFirst {
            it is com.udnahc.immichgallery.domain.model.PhotoItem && it.asset.id == assetId
        }.takeIf { it >= 0 }
    }

    fun getGlobalPhotoIndex(assetId: String): Int? {
        val s = state.value
        var globalIndex = 0
        for (bucket in s.buckets) {
            val assets = s.bucketAssets[bucket.timeBucket]
            if (assets != null) {
                val localIndex = assets.indexOfFirst { it.id == assetId }
                if (localIndex >= 0) return globalIndex + localIndex
            }
            globalIndex += bucket.count
        }
        return null
    }

    // --- Private ---

    private suspend fun loadBucketAssetsInternal(timeBucket: String) {
        log.d { "Loading assets for bucket: $timeBucket" }
        getBucketAssetsUseCase(timeBucket).fold(
            onSuccess = { assets ->
                log.d { "Loaded ${assets.size} assets for bucket: $timeBucket" }
                _bucketData.update { data ->
                    val groupSize = _uiConfig.value.groupSize
                    val newDayGroups = if (groupSize == TimelineGroupSize.DAY) {
                        data.dayGroups + (timeBucket to computeDayGroups(assets))
                    } else {
                        data.dayGroups
                    }
                    data.copy(
                        loadingBuckets = data.loadingBuckets - timeBucket,
                        loadedBuckets = data.loadedBuckets + timeBucket,
                        bucketAssets = data.bucketAssets + (timeBucket to assets),
                        dayGroups = newDayGroups
                    )
                }
            },
            onFailure = { e ->
                log.e(e) { "Failed to load assets for bucket: $timeBucket" }
                _bucketData.update { data ->
                    data.copy(
                        loadingBuckets = data.loadingBuckets - timeBucket,
                        failedBuckets = data.failedBuckets + timeBucket
                    )
                }
            }
        )
    }

    private fun buildTimelineState(data: BucketData, config: UiConfig): TimelineState {
        val displayItems = buildDisplayItems(data, config.groupSize)
        val scrollbarData = computeScrollbarData(data.buckets)

        return TimelineState(
            groupSize = config.groupSize,
            gridColumns = config.gridColumns,
            isLoading = config.isLoading,
            error = config.error,
            displayItems = displayItems,
            yearMarkers = scrollbarData.yearMarkers,
            totalItemCount = scrollbarData.totalItemCount,
            cumulativeItemCounts = scrollbarData.cumulativeItemCounts,
            bucketDisplayLabels = scrollbarData.bucketDisplayLabels,
            buckets = data.buckets,
            bucketAssets = data.bucketAssets,
            loadedBuckets = data.loadedBuckets
        )
    }

    private fun buildDisplayItems(
        data: BucketData,
        groupSize: TimelineGroupSize
    ): List<TimelineDisplayItem> {
        val items = mutableListOf<TimelineDisplayItem>()

        for ((index, bucket) in data.buckets.withIndex()) {
            val timeBucket = bucket.timeBucket
            val isFailed = timeBucket in data.failedBuckets
            val isLoaded = timeBucket in data.loadedBuckets
            val monthLabel = bucket.displayLabel

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
                    repeat(bucket.count) { idx ->
                        items.add(PlaceholderItem(
                            gridKey = "pl_${index}_$idx",
                            bucketIndex = index,
                            sectionLabel = monthLabel
                        ))
                    }
                }
                else -> {
                    val assets = data.bucketAssets[timeBucket]
                    if (assets != null) {
                        val dayGroupList = if (groupSize == TimelineGroupSize.DAY) {
                            data.dayGroups[timeBucket]
                        } else null

                        if (!dayGroupList.isNullOrEmpty()) {
                            for (dayGroup in dayGroupList) {
                                items.add(HeaderItem(
                                    gridKey = "dh_${index}_${dayGroup.label}",
                                    bucketIndex = index,
                                    sectionLabel = dayGroup.label,
                                    label = dayGroup.label
                                ))
                                for (asset in dayGroup.assets) {
                                    items.add(PhotoItem(
                                        gridKey = "p_${asset.id}",
                                        bucketIndex = index,
                                        sectionLabel = dayGroup.label,
                                        asset = asset
                                    ))
                                }
                            }
                        } else {
                            for (asset in assets) {
                                items.add(PhotoItem(
                                    gridKey = "p_${asset.id}",
                                    bucketIndex = index,
                                    sectionLabel = monthLabel,
                                    asset = asset
                                ))
                            }
                        }
                    } else {
                        repeat(bucket.count) { idx ->
                            items.add(PlaceholderItem(
                                gridKey = "pl_${index}_$idx",
                                bucketIndex = index,
                                sectionLabel = monthLabel
                            ))
                        }
                    }
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

    private fun computeScrollbarData(buckets: List<TimelineBucket>): ScrollbarData {
        var cumulative = 0
        val cumulativeCounts = buckets.map { bucket ->
            cumulative += 1 + bucket.count
            cumulative
        }
        val totalGridItems = buckets.sumOf { it.count } + buckets.size

        val markers = mutableListOf<YearMarker>()
        var lastYear = ""
        for (i in buckets.indices) {
            val year = buckets[i].displayLabel.substringAfterLast(" ", "")
            if (year != lastYear && year.isNotEmpty()) {
                val fraction = if (totalGridItems > 0) {
                    (cumulativeCounts.getOrElse(i - 1) { 0 }).toFloat() / totalGridItems
                } else 0f
                markers.add(YearMarker(fraction.coerceIn(0f, 1f), year))
                lastYear = year
            }
        }

        return ScrollbarData(
            yearMarkers = markers,
            totalItemCount = totalGridItems,
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
