package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

enum class SlideshowCandidateStatus { UNRESOLVED, LOADING, FAILED, CACHE_UNAVAILABLE, MATERIALIZED }

internal data class SlideshowSourceItem(
    val slot: SlideshowSlot,
    val asset: Asset? = null,
    val status: SlideshowCandidateStatus = if (asset == null) SlideshowCandidateStatus.UNRESOLVED else SlideshowCandidateStatus.MATERIALIZED
) {
    val candidate: SlideshowCandidate get() = SlideshowCandidate(slot, asset?.id)
}

internal enum class SlideshowFailure { MEDIA, TIMEOUT, BUCKET, MANUAL_REFRESH, ALL_VIDEOS, EMPTY }

sealed interface SlideshowMediaEvent {
    data object ImageReady : SlideshowMediaEvent
    data class Progress(val positionMillis: Long) : SlideshowMediaEvent
    data class Buffering(val active: Boolean) : SlideshowMediaEvent
    data object Completed : SlideshowMediaEvent
    data object Failed : SlideshowMediaEvent
    data object Fallback : SlideshowMediaEvent
}

internal data class SlideshowPlaybackState(
    val config: SlideshowConfig? = null,
    val request: SlideshowMediaRequest? = null,
    val asset: Asset? = null,
    val page: Int = 0,
    val navigationId: Long = 0,
    val transitioning: Boolean = false,
    val paused: Boolean = false,
    val ready: Boolean = false,
    val playMedia: Boolean = false,
    val keepAwake: Boolean = false,
    val motionProgress: Float = 0f,
    val upcomingAsset: Asset? = null,
    val failure: SlideshowFailure? = null,
    val canRetry: Boolean = false,
    val controlsVisible: Boolean = true,
    val canGoPrevious: Boolean = false
)

/** All methods run on the shared binding's one Default-dispatcher command consumer. */
internal class SlideshowPlaybackController(
    private val nowMillis: () -> Long,
    private val requestBucket: (String, Boolean) -> Unit,
    private val random: Random = Random.Default
) {
    private val mutableState = MutableStateFlow(SlideshowPlaybackState())
    val state: StateFlow<SlideshowPlaybackState> = mutableState.asStateFlow()
    private var source = emptyList<SlideshowSourceItem>()
    private var pendingSourceSupplier: (() -> List<SlideshowSourceItem>)? = null
    private var session: SlideshowSession? = null
    private val timing = SlideshowTiming()
    private val failureClock = SlideshowActiveClock()
    private var pendingSlot: SlideshowSlot? = null
    private var pendingTarget: SlideshowTarget? = null
    private var pendingBackward = false
    private var requestedBuckets = mutableSetOf<String>()
    private var consecutiveFailures = 0
    private var environmentActive = true
    private var focused = false
    private var lastInteraction = 0L
    private var navigationId = 0L
    private var fallbackUsed = false
    private var closed = false

    fun updateSource(supplier: () -> List<SlideshowSourceItem>) {
        if (closed) return
        pendingSourceSupplier = supplier
        if (session != null) {
            applySourceItems(supplier())
        }
    }

    fun updateSource(items: List<SlideshowSourceItem>) {
        if (closed) return
        pendingSourceSupplier = null
        applySourceItems(items)
    }

    private fun applySourceItems(items: List<SlideshowSourceItem>) {
        source = items
        val order = session ?: return
        val changed = order.reconcileSource(items.map { it.candidate })
        if (items.isEmpty()) {
            pauseWith(SlideshowFailure.EMPTY)
            return
        }
        if (changed) {
            requestedBuckets.clear()
            pendingTarget = null
            pendingSlot = pendingSlot?.takeIf { slot -> items.any { it.slot == slot } } ?: order.current?.slot ?: items.first().slot
        }
        val currentSlot = pendingSlot ?: order.current?.slot
        val item = source.firstOrNull { it.slot == currentSlot }
        val request = timing.request
        if (state.value.failure != null && item?.asset == null &&
            item?.status in listOf(SlideshowCandidateStatus.FAILED, SlideshowCandidateStatus.CACHE_UNAVAILABLE)) {
            publish()
            return
        }
        if (pendingSlot != null || item?.asset?.id != request?.assetId || item?.asset?.let(::mediaKey) != request?.mediaKey) {
            pendingSlot = currentSlot
            resolvePending()
        } else {
            val page = source.indexOfFirst { it.slot == currentSlot }
            if (changed || page != state.value.page) {
                navigationId += 1
                timing.setTransitionComplete(false, nowMillis())
                mutableState.value = state.value.copy(page = page, navigationId = navigationId, transitioning = true)
            }
            prepareUpcoming()
            publish()
        }
    }

    fun start(config: SlideshowConfig, page: Int) {
        if (closed || !config.isValid) return
        pendingSourceSupplier?.let { source = it() }
        val initial = source.getOrNull(page) ?: source.firstOrNull()
        session = SlideshowSession(source.map { it.candidate }, initial?.slot, config.order, random, recordInitial = false)
        timing.setState(com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.RUNNING, nowMillis())
        consecutiveFailures = 0
        requestedBuckets.clear()
        pendingTarget = null
        pendingBackward = false
        pendingSlot = initial?.slot
        mutableState.value = SlideshowPlaybackState(config = config, page = page)
        interact()
        if (initial == null) pauseWith(SlideshowFailure.EMPTY) else resolvePending()
    }

    fun stop() {
        timing.setState(com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.STOPPED, nowMillis())
        session = null
        source = emptyList()
        pendingSlot = null
        pendingTarget = null
        requestedBuckets.clear()
        navigationId += 1
        mutableState.value = SlideshowPlaybackState(navigationId = navigationId)
    }

    fun close() { stop(); closed = true; pendingSourceSupplier = null; source = emptyList() }

    fun next() {
        val order = session ?: return
        pendingBackward = false
        interact()
        if (state.value.failure != null) pendingSlot?.let(order::exclude)
        pendingTarget = order.upcoming()
        pendingSlot = pendingTarget?.slot
        if (pendingSlot == null) pauseWith(noCandidateFailure()) else resolvePending()
    }

    fun previous() {
        val order = session ?: return
        interact()
        val previous = order.previousTarget() ?: return
        pendingBackward = true
        pendingTarget = previous
        pendingSlot = previous.slot
        resolvePending()
    }

    fun retry() {
        if (!state.value.canRetry) return
        val slot = pendingSlot ?: session?.current?.slot ?: return
        interact()
        mutableState.value = state.value.copy(failure = null, canRetry = false)
        timing.setState(com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.RUNNING, nowMillis())
        if (slot is SlideshowSlot.BucketOffset && source.firstOrNull { it.slot == slot }?.asset == null) {
            requestedBuckets += slot.timeBucket
            requestBucket(slot.timeBucket, true)
            pendingSlot = slot
            beginRequest(source.firstOrNull { it.slot == slot } ?: return)
        } else {
            pendingSlot = slot
            resolvePending(force = true)
        }
    }

    fun togglePause() {
        if (session == null) return
        interact()
        val paused = timing.state == com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.PAUSED
        if (paused && state.value.failure != null) return
        timing.setState(if (paused) com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.RUNNING
        else com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.PAUSED, nowMillis())
        failureClock.setRunning(environmentActive && !pausedState, nowMillis())
        publish()
    }

    fun toggleMute() {
        val config = state.value.config ?: return
        mutableState.value = state.value.copy(config = config.copy(muted = !config.muted))
        interact()
        publish()
    }

    fun setEnvironment(active: Boolean) {
        if (active && !environmentActive) interact()
        environmentActive = active
        timing.setEnvironmentActive(active, nowMillis())
        failureClock.setRunning(active && !pausedState && state.value.failure != null, nowMillis())
        publish()
    }

    fun setFocused(value: Boolean) { focused = value; if (value) interact() }
    fun toggleControls() { mutableState.value = state.value.copy(controlsVisible = !state.value.controlsVisible); lastInteraction = nowMillis() }
    fun interact() { lastInteraction = nowMillis(); mutableState.value = state.value.copy(controlsVisible = true) }

    fun navigationFinished(id: Long) {
        if (id != navigationId || session == null) return
        timing.setTransitionComplete(true, nowMillis())
        mutableState.value = state.value.copy(transitioning = false)
        publish()
    }

    fun mediaEvent(request: SlideshowMediaRequest, event: SlideshowMediaEvent) {
        if (closed || !timing.isCurrent(request) || state.value.failure != null) return
        val now = nowMillis()
        val success = when (event) {
            SlideshowMediaEvent.ImageReady -> timing.onImageReady(request, now)
            is SlideshowMediaEvent.Progress -> timing.onVideoProgress(request, event.positionMillis, now)
            is SlideshowMediaEvent.Buffering -> { timing.setBuffering(request, event.active, now); false }
            SlideshowMediaEvent.Completed -> timing.onVideoCompleted(request, now)
            SlideshowMediaEvent.Failed -> { fail(SlideshowFailure.MEDIA); false }
            SlideshowMediaEvent.Fallback -> {
                if (!fallbackUsed) { timing.restartWatchdog(now); fallbackUsed = true }
                false
            }
        }
        if (success) consecutiveFailures = 0
        publish()
    }

    fun tick() {
        val config = state.value.config ?: return
        val now = nowMillis()
        if (environmentActive && !pausedState) {
            if (state.value.failure != null) {
                if (failureClock.elapsedMillis(now) >= FAILURE_VISIBLE_MILLIS) next()
            } else if (timing.hasTimedOut(now)) fail(SlideshowFailure.TIMEOUT)
            else if (timing.shouldAdvance(config, now)) next()
        }
        if (environmentActive && !pausedState && !state.value.transitioning && timing.ready && state.value.failure == null && !focused && now - lastInteraction >= CONTROLS_VISIBLE_MILLIS) {
            mutableState.value = state.value.copy(controlsVisible = false)
        }
        publish()
    }

    private fun resolvePending(force: Boolean = false) {
        val order = session ?: return
        repeat(source.size.coerceAtLeast(1)) {
            val slot = pendingSlot ?: return
            val item = source.firstOrNull { it.slot == slot } ?: return pauseWith(SlideshowFailure.EMPTY)
            if (state.value.config?.videoPolicy == SlideshowVideoPolicy.SKIP && item.asset?.type == AssetType.VIDEO) {
                order.exclude(slot)
                pendingTarget = if (pendingBackward) order.previousTarget(before = slot) else order.upcoming()
                pendingSlot = pendingTarget?.slot ?: order.current?.slot.takeIf { pendingBackward }
                if (pendingSlot == null) return pauseWith(noCandidateFailure())
            } else {
                if (item.asset != null) {
                    val target = pendingTarget
                    if (target != null) {
                        if (pendingBackward) order.previous(target)
                        else if (order.next(target) == null) return
                    } else order.select(slot)
                    pendingSlot = null
                    pendingTarget = null
                }
                if (force || state.value.request == null || timing.request?.slot != slot || timing.request?.mediaKey != mediaKey(item.asset) || state.value.failure != null || item.asset != null) {
                    beginRequest(item)
                }
                when (item.status) {
                    SlideshowCandidateStatus.FAILED -> fail(SlideshowFailure.BUCKET)
                    SlideshowCandidateStatus.CACHE_UNAVAILABLE -> fail(SlideshowFailure.MANUAL_REFRESH)
                    SlideshowCandidateStatus.UNRESOLVED -> needBucket(item)
                    else -> Unit
                }
                prepareUpcoming()
                publish()
                return
            }
        }
        pauseWith(noCandidateFailure())
    }

    private fun beginRequest(item: SlideshowSourceItem) {
        val now = nowMillis()
        val isVideo = item.asset?.type == AssetType.VIDEO && state.value.config?.videoPolicy == SlideshowVideoPolicy.PLAY
        val request = timing.beginVisit(item.candidate, mediaKey(item.asset), if (isVideo) SlideshowMediaKind.VIDEO else SlideshowMediaKind.IMAGE, now)
        timing.setEnvironmentActive(environmentActive, now)
        timing.setTransitionComplete(false, now)
        fallbackUsed = false
        navigationId += 1
        mutableState.value = state.value.copy(request = request, asset = item.asset,
            page = source.indexOf(item), navigationId = navigationId, transitioning = true,
            failure = null, canRetry = false, ready = false, motionProgress = 0f)
    }

    private fun prepareUpcoming() {
        if (pendingSlot != null) return
        val order = session ?: return
        repeat(source.size) {
            val target = order.upcoming() ?: return
            val item = source.firstOrNull { it.slot == target.slot } ?: return
            if (state.value.config?.videoPolicy == SlideshowVideoPolicy.SKIP && item.asset?.type == AssetType.VIDEO) {
                order.exclude(item.slot)
            } else {
                if (item.status == SlideshowCandidateStatus.UNRESOLVED) needBucket(item)
                mutableState.value = state.value.copy(upcomingAsset = item.asset)
                return
            }
        }
        mutableState.value = state.value.copy(upcomingAsset = null)
    }

    private fun needBucket(item: SlideshowSourceItem) {
        val slot = item.slot as? SlideshowSlot.BucketOffset ?: return
        if (requestedBuckets.add(slot.timeBucket)) requestBucket(slot.timeBucket, false)
    }

    private fun fail(failure: SlideshowFailure) {
        if (state.value.failure != null) return
        consecutiveFailures += 1
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) timing.setState(com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.PAUSED, nowMillis())
        failureClock.reset(nowMillis(), environmentActive && !pausedState)
        mutableState.value = state.value.copy(failure = failure, canRetry = failure != SlideshowFailure.MANUAL_REFRESH, controlsVisible = true)
        publish()
    }

    private fun pauseWith(failure: SlideshowFailure) {
        timing.setState(com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.PAUSED, nowMillis())
        mutableState.value = state.value.copy(failure = failure, canRetry = false, controlsVisible = true, transitioning = false)
        publish()
    }

    private val pausedState get() = timing.state != com.udnahc.immichgallery.domain.model.SlideshowPlaybackState.RUNNING
    private fun noCandidateFailure(): SlideshowFailure = when {
        source.isEmpty() -> SlideshowFailure.EMPTY
        source.all { it.asset?.type == AssetType.VIDEO } -> SlideshowFailure.ALL_VIDEOS
        else -> state.value.failure ?: SlideshowFailure.MEDIA
    }
    private fun mediaKey(asset: Asset?): String = when {
        asset == null -> "unresolved"
        asset.type == AssetType.VIDEO && state.value.config?.videoPolicy == SlideshowVideoPolicy.PLAY -> asset.videoPlaybackUrl
        asset.type == AssetType.VIDEO -> asset.thumbnailUrl
        else -> asset.originalUrl
    }
    private fun publish() {
        val config = state.value.config ?: return
        val active = environmentActive && !pausedState && state.value.failure == null
        mutableState.value = state.value.copy(paused = pausedState, ready = timing.ready,
            playMedia = active && !state.value.transitioning, keepAwake = active && timing.ready && !state.value.transitioning,
            motionProgress = timing.motionProgress(config, nowMillis()), canGoPrevious = session?.canGoPrevious == true,
            controlsVisible = state.value.controlsVisible || pausedState || state.value.failure != null)
    }
}

internal const val FAILURE_VISIBLE_MILLIS = 2_000L
internal const val CONTROLS_VISIBLE_MILLIS = 3_000L
internal const val MAX_CONSECUTIVE_FAILURES = 3
