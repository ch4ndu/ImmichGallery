package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val buckets: List<TimelineBucket> = emptyList(),
    val loadedBuckets: Set<String> = emptySet(),
    val loadingBuckets: Set<String> = emptySet(),
    val failedBuckets: Set<String> = emptySet(),
    val bucketAssets: Map<String, List<Asset>> = emptyMap(),
    val dayGroups: Map<String, List<DayGroup>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    // Pre-computed on background dispatcher:
    val allTimeBuckets: List<String> = emptyList(),
    val estimatedGridItemCount: Int = 0,
    val bucketLabelMap: Map<String, String> = emptyMap(),
    // For O(log n) label lookup during scrollbar drag:
    val cumulativeItemCounts: List<Int> = emptyList(),
    val bucketDisplayLabels: List<String> = emptyList(),
    val yearMarkers: List<YearMarker> = emptyList()
)

class TimelineViewModel(
    private val getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
    private val getBucketAssetsUseCase: GetBucketAssetsUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    val getAssetFileNameUseCase: GetAssetFileNameUseCase,
    val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val serverConfigRepository: ServerConfigRepository
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    private val log = logging()
    private var loadBucketsJob: Job? = null
    private val _state: MutableStateFlow<TimelineState>
    val state: StateFlow<TimelineState>

    val labelProvider: (Float) -> String? = { fraction ->
        val currentState = _state.value
        val counts = currentState.cumulativeItemCounts
        if (counts.isNotEmpty()) {
            val targetIndex = (fraction * currentState.estimatedGridItemCount).toInt()
            // Binary search: find first bucket whose cumulative count > targetIndex
            var low = 0
            var high = counts.size - 1
            while (low < high) {
                val mid = (low + high) ushr 1
                if (counts[mid] <= targetIndex) low = mid + 1 else high = mid
            }
            currentState.bucketDisplayLabels.getOrNull(low)
        } else null
    }


    init {
        val saved = serverConfigRepository.getTimelineGroupSize()
        val initialSize = TimelineGroupSize.entries.find { it.apiValue == saved }
            ?: TimelineGroupSize.MONTH
        val initialColumns = serverConfigRepository.getGridColumns().coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        _state = MutableStateFlow(TimelineState(groupSize = initialSize, gridColumns = initialColumns))
        state = _state.asStateFlow()
        loadBuckets()
    }

    fun setGroupSize(size: TimelineGroupSize) {
        if (size == _state.value.groupSize) return
        serverConfigRepository.setTimelineGroupSize(size.apiValue)
        _state.update { TimelineState(groupSize = size, gridColumns = it.gridColumns) }
        loadBuckets()
    }

    fun setGridColumns(columns: Int) {
        val clamped = columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        if (clamped == _state.value.gridColumns) return
        serverConfigRepository.setGridColumns(clamped)
        _state.update { it.copy(gridColumns = clamped) }
    }

    fun loadBuckets() {
        loadBucketsJob?.cancel()
        loadBucketsJob = viewModelScope.launch(Dispatchers.IO) {
            log.d { "Loading timeline buckets..." }
            _state.update { it.copy(isLoading = true, error = null).withDerivedFields() }
            getTimelineBucketsUseCase().fold(
                onSuccess = { buckets ->
                    log.d { "Loaded ${buckets.size} timeline buckets" }
                    _state.update {
                        it.copy(buckets = buckets, isLoading = false).withDerivedFields()
                    }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to load timeline buckets" }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Failed to load timeline"
                        ).withDerivedFields()
                    }
                }
            )
        }
    }

    fun loadBucketAssets(timeBucket: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var shouldLoad = false
            _state.update { current ->
                if (current.loadedBuckets.contains(timeBucket) ||
                    current.loadingBuckets.contains(timeBucket) ||
                    current.failedBuckets.contains(timeBucket)
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

    fun onVisibleBucketsChanged(visibleTimeBuckets: Set<String>) {
        val allBuckets = _state.value.allTimeBuckets
        val indicesToLoad = mutableSetOf<Int>()
        for (tb in visibleTimeBuckets) {
            val idx = allBuckets.indexOf(tb)
            if (idx >= 0) {
                indicesToLoad.add(idx)
                if (idx + 1 < allBuckets.size) indicesToLoad.add(idx + 1)
                if (idx + 2 < allBuckets.size) indicesToLoad.add(idx + 2)
            }
        }
        for (idx in indicesToLoad) {
            loadBucketAssets(allBuckets[idx])
        }
    }

    fun getGlobalPhotoIndex(assetId: String): Int? {
        val currentState = _state.value
        var globalIndex = 0
        for (bucket in currentState.buckets) {
            val bucketAssets = currentState.bucketAssets[bucket.timeBucket]
            if (bucketAssets != null) {
                val localIndex = bucketAssets.indexOfFirst { it.id == assetId }
                if (localIndex >= 0) return globalIndex + localIndex
            }
            globalIndex += bucket.count
        }
        return null
    }

    fun retryBucket(timeBucket: String) {
        viewModelScope.launch(Dispatchers.IO) {
            log.d { "Retrying bucket: $timeBucket" }
            _state.update { state ->
                state.copy(failedBuckets = state.failedBuckets - timeBucket).withDerivedFields()
            }
            loadBucketAssetsInternal(timeBucket)
        }
    }

    private fun TimelineState.withDerivedFields(): TimelineState {
        var cumulative = 0
        val cumulativeCounts = buckets.map { bucket ->
            cumulative += 1 + bucket.count  // header + assets
            cumulative
        }

        val totalGridItems = buckets.sumOf { it.count } + buckets.size
        // Year markers: first bucket of each unique year, with scroll fraction
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

        // Day sub-header labels for StickyHeaderOverlay
        val dayLabelEntries = dayGroups.flatMap { (monthKey, groups) ->
            groups.map { "${monthKey}_day_${it.label}" to it.label }
        }

        return copy(
            allTimeBuckets = buckets.map { it.timeBucket },
            estimatedGridItemCount = totalGridItems,
            bucketLabelMap = buckets.associate { it.timeBucket to it.displayLabel } + dayLabelEntries,
            cumulativeItemCounts = cumulativeCounts,
            bucketDisplayLabels = buckets.map { it.displayLabel },
            yearMarkers = markers
        )
    }

    private suspend fun loadBucketAssetsInternal(timeBucket: String) {
        log.d { "Loading assets for bucket: $timeBucket" }
        getBucketAssetsUseCase(timeBucket).fold(
            onSuccess = { assets ->
                log.d { "Loaded ${assets.size} assets for bucket: $timeBucket" }
                _state.update { state ->
                    val newDayGroups = if (state.groupSize == TimelineGroupSize.DAY) {
                        state.dayGroups + (timeBucket to computeDayGroups(assets))
                    } else {
                        state.dayGroups
                    }
                    state.copy(
                        loadingBuckets = state.loadingBuckets - timeBucket,
                        loadedBuckets = state.loadedBuckets + timeBucket,
                        bucketAssets = state.bucketAssets + (timeBucket to assets),
                        dayGroups = newDayGroups
                    ).withDerivedFields()
                }
            },
            onFailure = { e ->
                log.e(e) { "Failed to load assets for bucket: $timeBucket" }
                _state.update { state ->
                    state.copy(
                        loadingBuckets = state.loadingBuckets - timeBucket,
                        failedBuckets = state.failedBuckets + timeBucket
                    ).withDerivedFields()
                }
            }
        )
    }

    private fun computeDayGroups(assets: List<Asset>): List<DayGroup> {
        val tz = TimeZone.currentSystemDefault()
        return assets
            .groupBy { asset ->
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
            .filterKeys { it != null }
            .entries
            .sortedByDescending { it.key }
            .map { (date, dayAssets) ->
                val d = date!!
                val monthName = d.month.name.lowercase().replaceFirstChar { it.uppercase() }
                DayGroup(
                    label = "${d.dayOfMonth} $monthName ${d.year}",
                    assets = dayAssets.sortedByDescending { it.createdAt }
                )
            }
    }
}
