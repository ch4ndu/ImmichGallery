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
    val failedBuckets: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null
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

    private val loadedBuckets = mutableSetOf<String>()

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
            _state.update { it.copy(isLoading = true, error = null) }
            getTimelineBucketsUseCase().fold(
                onSuccess = { buckets ->
                    log.d { "Loaded ${buckets.size} timeline buckets" }
                    _state.update { it.copy(buckets = buckets, isLoading = false) }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to load timeline buckets" }
                    _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load timeline") }
                }
            )
        }
    }

    fun loadBucketAssets(timeBucket: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (loadedBuckets.contains(timeBucket)) return@launch
            if (_state.value.failedBuckets.contains(timeBucket)) return@launch
            loadBucketAssetsInternal(timeBucket)
        }
    }

    fun retryBucket(timeBucket: String) {
        viewModelScope.launch(Dispatchers.IO) {
            log.d { "Retrying bucket: $timeBucket" }
            loadedBuckets.remove(timeBucket)
            _state.update { state ->
                state.copy(failedBuckets = state.failedBuckets - timeBucket)
            }
            loadBucketAssetsInternal(timeBucket)
        }
    }

    private suspend fun loadBucketAssetsInternal(timeBucket: String) {
        loadedBuckets.add(timeBucket)
        log.d { "Loading assets for bucket: $timeBucket" }
        getBucketAssetsUseCase(timeBucket).fold(
            onSuccess = {
                log.d { "Loaded assets for bucket: $timeBucket" }
                _state.update { state ->
                    state.copy(failedBuckets = state.failedBuckets - timeBucket)
                }
            },
            onFailure = { e ->
                log.e(e) { "Failed to load assets for bucket: $timeBucket" }
                loadedBuckets.remove(timeBucket)
                _state.update { state ->
                    state.copy(failedBuckets = state.failedBuckets + timeBucket)
                }
            }
        )
    }
}
