package com.udnahc.immichgallery.ui.screen.slideshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.auth.SessionOperationRunner
import com.udnahc.immichgallery.domain.model.*
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetStackUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.slideshow.GetSlideshowCollectionUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.ui.component.SlideshowCandidateStatus
import com.udnahc.immichgallery.ui.screen.timeline.TimelineOverlayState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

sealed interface SlideshowWindowState {
    data object Loading : SlideshowWindowState
    data class Ready(val assets: List<Asset>, val initialIndex: Int) : SlideshowWindowState
    data class Failed(val reason: SlideshowWindowFailure) : SlideshowWindowState
    data object Closed : SlideshowWindowState
}

enum class SlideshowWindowFailure { LOAD, EMPTY, MISSING_ASSET, CACHE_UNAVAILABLE, ALL_VIDEOS }

/** One registered lifetime owns preparation, retry, lazy buckets, and metadata descendants. */
class SlideshowWindowViewModel(
    private val runner: SessionOperationRunner,
    private val collection: GetSlideshowCollectionUseCase,
    private val bucketAssets: GetBucketAssetsUseCase,
    private val apiKeyUseCase: GetApiKeyUseCase,
    private val detail: GetAssetDetailUseCase,
    private val stack: GetAssetStackUseCase,
    private val fileName: GetAssetFileNameUseCase
) : ViewModel() {
    private val mutableState = MutableStateFlow<SlideshowWindowState>(SlideshowWindowState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableTimeline = MutableStateFlow(TimelineOverlayState())
    val timeline = mutableTimeline.asStateFlow()
    private val cachedAssets = MutableStateFlow<Map<String, List<Asset>>>(emptyMap())
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var lifetime: Job? = null
    private var sourceScope: CoroutineScope? = null
    private var request: SlideshowWindowRequest? = null
    var apiKey: String = ""
        private set

    fun start(value: SlideshowWindowRequest, onAdmitted: suspend () -> Unit) {
        if (request != null || state.value == SlideshowWindowState.Closed) return
        request = value
        lifetime = viewModelScope.launch(Dispatchers.Default) {
            try {
                runner.runForStamp(value.stamp) {
                    coroutineScope {
                        sourceScope = this
                        apiKey = apiKeyUseCase()
                        // Admission must precede registry insertion and all source/media work.
                        onAdmitted()
                        launch {
                            prepare(value)
                            for (command in commands) command()
                        }
                        awaitCancellation()
                    }
                }
            } finally {
                sourceScope = null
                mutableState.value = SlideshowWindowState.Closed
            }
        }
    }

    fun close() {
        mutableState.value = SlideshowWindowState.Closed
        lifetime?.cancel()
        commands.close()
    }

    suspend fun join() { lifetime?.join() }

    fun retry() {
        if (state.value is SlideshowWindowState.Failed) {
            commands.trySend {
                if (state.value is SlideshowWindowState.Failed) request?.let { prepare(it) }
            }
        }
    }

    fun assetsForBucket(key: String): List<Asset>? = cachedAssets.value[key]

    fun requestBucket(key: String, retry: Boolean = false) {
        commands.trySend {
            val source = request?.source as? SlideshowWindowSource.Timeline
            if (source != null && source.buckets.any { it.timeBucket == key } &&
                assetsForBucket(key) == null &&
                (retry || mutableTimeline.value.bucketStatuses[key] == SlideshowCandidateStatus.UNRESOLVED)) {
                loadBucket(source, key)
            }
        }
    }

    private suspend fun prepare(value: SlideshowWindowRequest) {
        publish(SlideshowWindowState.Loading)
        try {
            val source = value.source
            val assets: List<Asset>
            val initialIndex: Int
            if (source is SlideshowWindowSource.Timeline) {
                cachedAssets.value = emptyMap()
                mutableTimeline.value = TimelineOverlayState()
                rebuildTimeline(source)
                if (!loadBucket(source, source.openingBucket)) {
                    publish(SlideshowWindowState.Failed(SlideshowWindowFailure.CACHE_UNAVAILABLE))
                    return
                }
                assets = assetsForBucket(source.openingBucket).orEmpty()
                val localIndex = assets.indexOfFirst { it.id == value.initialAssetId }
                if (localIndex < 0) {
                    publish(SlideshowWindowState.Failed(SlideshowWindowFailure.MISSING_ASSET))
                    return
                }
                val bucketIndex = source.buckets.indexOfFirst { it.timeBucket == source.openingBucket }
                initialIndex = mutableTimeline.value.pageIndex.bucketStartPages[bucketIndex] + localIndex
                if (mutableTimeline.value.pageIndex.totalPages == 1 &&
                    value.config.videoPolicy == SlideshowVideoPolicy.SKIP &&
                    assets[localIndex].type == AssetType.VIDEO) {
                    publish(SlideshowWindowState.Failed(SlideshowWindowFailure.ALL_VIDEOS))
                    return
                }
            } else {
                assets = collection(source).getOrThrow()
                if (assets.isEmpty()) {
                    publish(SlideshowWindowState.Failed(SlideshowWindowFailure.EMPTY))
                    return
                }
                initialIndex = assets.indexOfFirst { it.id == value.initialAssetId }
                // Resolve the requested identity before selecting a policy-eligible target.
                if (initialIndex < 0) {
                    publish(SlideshowWindowState.Failed(SlideshowWindowFailure.MISSING_ASSET))
                    return
                }
                if (value.config.videoPolicy == SlideshowVideoPolicy.SKIP) {
                    val photos = assets.filter { it.type != AssetType.VIDEO }
                    if (photos.isEmpty()) {
                        publish(SlideshowWindowState.Failed(SlideshowWindowFailure.ALL_VIDEOS))
                        return
                    }
                    if (photos.size == 1) {
                        publish(SlideshowWindowState.Ready(photos, 0))
                        return
                    }
                }
            }
            publish(when {
                assets.isEmpty() -> SlideshowWindowState.Failed(SlideshowWindowFailure.EMPTY)
                initialIndex < 0 -> SlideshowWindowState.Failed(SlideshowWindowFailure.MISSING_ASSET)
                else -> SlideshowWindowState.Ready(assets, initialIndex)
            })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(SlideshowWindowState.Failed(SlideshowWindowFailure.LOAD))
        }
    }

    private suspend fun publish(value: SlideshowWindowState) {
        currentCoroutineContext().ensureActive()
        mutableState.update { if (it == SlideshowWindowState.Closed) it else value }
    }

    private suspend fun loadBucket(source: SlideshowWindowSource.Timeline, key: String): Boolean {
        val expected = source.buckets.firstOrNull { it.timeBucket == key } ?: return false
        mutableTimeline.update { it.copy(bucketStatuses = it.bucketStatuses + (key to SlideshowCandidateStatus.LOADING)) }
        try {
            val assets = bucketAssets(key).distinctBy(Asset::id)
            currentCoroutineContext().ensureActive()
            // A partial/missing cache is unavailable, never a complete or server-refreshed source.
            if (assets.size != expected.count) {
                mutableTimeline.update { it.copy(bucketStatuses = it.bucketStatuses + (key to SlideshowCandidateStatus.CACHE_UNAVAILABLE)) }
                return false
            }
            cachedAssets.update { it + (key to assets) }
            rebuildTimeline(source)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableTimeline.update { it.copy(bucketStatuses = it.bucketStatuses + (key to SlideshowCandidateStatus.FAILED)) }
            return false
        }
    }

    private fun rebuildTimeline(source: SlideshowWindowSource.Timeline) {
        val starts = mutableListOf<Int>()
        val counts = mutableListOf<Int>()
        val indexes = mutableMapOf<String, Int>()
        var offset = 0
        source.buckets.forEach { bucket ->
            starts += offset
            val assets = assetsForBucket(bucket.timeBucket)
            val count = assets?.size ?: bucket.count
            counts += count
            assets?.forEachIndexed { index, asset -> indexes[asset.id] = offset + index }
            offset += count
        }
        mutableTimeline.update { previous ->
            TimelineOverlayState(
                pageIndex = TimelinePageIndex(starts, counts, offset, indexes),
                buckets = source.buckets,
                bucketStatuses = source.buckets.associate { bucket ->
                    bucket.timeBucket to if (assetsForBucket(bucket.timeBucket) != null)
                        SlideshowCandidateStatus.MATERIALIZED else
                        previous.bucketStatuses[bucket.timeBucket] ?: SlideshowCandidateStatus.UNRESOLVED
                },
                materializationRevisions = cachedAssets.value.keys.associateWith { 1 }
            )
        }
    }

    private suspend fun <T> owned(block: suspend () -> T): T {
        val scope = sourceScope ?: throw CancellationException("Slideshow source is closed")
        val stamp = request?.stamp ?: throw CancellationException("Slideshow source is unavailable")
        val work = scope.async { runner.runForStamp(stamp) { block() } }
        return try { work.await() } finally { work.cancel() }
    }

    suspend fun getDetail(id: String): Result<AssetDetail> = owned { detail(id) }
    suspend fun getStack(id: String): Result<AssetStackLookup> = owned { stack(id) }
    suspend fun getFileName(id: String, fallback: String): Result<String> = owned { fileName(id, fallback) }

    override fun onCleared() { close() }
}
