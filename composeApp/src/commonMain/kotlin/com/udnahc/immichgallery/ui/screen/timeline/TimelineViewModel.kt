package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private const val PAGER_PAGE_SIZE = 60
private const val PAGER_PREFETCH_DISTANCE = 30

@Immutable
data class TimelineState(
    val buckets: List<TimelineBucket> = emptyList(),
    val loadedBuckets: Set<String> = emptySet(),
    val loadingBuckets: Set<String> = emptySet(),
    val failedBuckets: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    // Pre-computed on background dispatcher:
    val cumulativeOffsets: List<Int> = emptyList(),
    val allTimeBuckets: List<String> = emptyList(),
    val estimatedGridItemCount: Int = 0,
    val bucketLabelMap: Map<String, String> = emptyMap(),
    // For O(log n) label lookup during scrollbar drag:
    val cumulativeItemCounts: List<Int> = emptyList(),
    val bucketDisplayLabels: List<String> = emptyList()
)

class TimelineViewModel(
    private val getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
    private val getBucketAssetsUseCase: GetBucketAssetsUseCase,
    private val timelineRepository: TimelineRepository,
    private val serverConfigRepository: ServerConfigRepository
) : ViewModel() {

    private val log = logging()
    private val _state = MutableStateFlow(TimelineState())
    val state: StateFlow<TimelineState> = _state.asStateFlow()

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


    val assetsPaging: Flow<PagingData<Asset>> = Pager(
        config = PagingConfig(pageSize = PAGER_PAGE_SIZE, prefetchDistance = PAGER_PREFETCH_DISTANCE)
    ) {
        timelineRepository.getTimelineAssetsPaging()
    }.flow.map { pagingData ->
        val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
        pagingData.map { entity -> entity.toDomain(baseUrl) }
    }.flowOn(Dispatchers.Default)
        .cachedIn(viewModelScope)

    init {
        loadBuckets()
    }

    fun loadBuckets() {
        viewModelScope.launch(Dispatchers.IO) {
            log.d { "Loading timeline buckets..." }
            _state.update { it.copy(isLoading = true, error = null).withDerivedFields() }
            getTimelineBucketsUseCase().fold(
                onSuccess = { buckets ->
                    log.d { "Loaded ${buckets.size} timeline buckets" }
                    _state.update { it.copy(buckets = buckets, isLoading = false).withDerivedFields() }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to load timeline buckets" }
                    _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load timeline").withDerivedFields() }
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
        var loadedSum = 0
        val offsets = buckets.map { bucket ->
            val offset = loadedSum
            if (loadedBuckets.contains(bucket.timeBucket)) {
                loadedSum += bucket.count
            }
            offset
        }

        var cumulative = 0
        val cumulativeCounts = buckets.map { bucket ->
            cumulative += 1 + bucket.count  // header + assets
            cumulative
        }

        return copy(
            cumulativeOffsets = offsets,
            allTimeBuckets = buckets.map { it.timeBucket },
            estimatedGridItemCount = buckets.sumOf { it.count } + buckets.size,
            bucketLabelMap = buckets.associate { it.timeBucket to it.displayLabel },
            cumulativeItemCounts = cumulativeCounts,
            bucketDisplayLabels = buckets.map { it.displayLabel }
        )
    }

    private suspend fun loadBucketAssetsInternal(timeBucket: String) {
        log.d { "Loading assets for bucket: $timeBucket" }
        getBucketAssetsUseCase(timeBucket).fold(
            onSuccess = {
                log.d { "Loaded assets for bucket: $timeBucket" }
                _state.update { state ->
                    state.copy(
                        loadingBuckets = state.loadingBuckets - timeBucket,
                        loadedBuckets = state.loadedBuckets + timeBucket
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
}
