package com.udnahc.immichgallery.domain.model

enum class SlideshowPlaybackState { RUNNING, PAUSED, STOPPED }
enum class SlideshowMediaKind { IMAGE, VIDEO }

@ConsistentCopyVisibility
data class SlideshowMediaRequest internal constructor(
    val generation: Long,
    val slot: SlideshowSlot,
    val assetId: String?,
    val mediaKey: String
)

/** Supplied times are monotonic milliseconds; no wall clock or coroutine is owned here. */
class SlideshowActiveClock {
    private var accumulated = 0L
    private var startedAt: Long? = null

    fun elapsedMillis(nowMillis: Long): Long =
        accumulated + (startedAt?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0L)

    fun setRunning(running: Boolean, nowMillis: Long) {
        accumulated = elapsedMillis(nowMillis)
        startedAt = nowMillis.takeIf { running }
    }

    fun reset(nowMillis: Long, running: Boolean = false) {
        accumulated = 0
        startedAt = nowMillis.takeIf { running }
    }
}

/** Shared dwell/motion time and observed media events, confined to the playback owner. */
class SlideshowTiming {
    private val presentation = SlideshowActiveClock()
    private val watchdog = SlideshowActiveClock()
    private var generation = 0L
    private var environmentActive = true
    private var transitionComplete = true
    private var buffering = false
    private var kind = SlideshowMediaKind.IMAGE

    var state: SlideshowPlaybackState = SlideshowPlaybackState.STOPPED
        private set
    var request: SlideshowMediaRequest? = null
        private set
    var ready: Boolean = false
        private set
    var completed: Boolean = false
        private set
    var positionMillis: Long = 0
        private set

    fun beginVisit(
        candidate: SlideshowCandidate,
        mediaKey: String,
        kind: SlideshowMediaKind,
        nowMillis: Long
    ): SlideshowMediaRequest {
        generation += 1
        val next = SlideshowMediaRequest(generation, candidate.slot, candidate.assetId, mediaKey)
        request = next
        this.kind = kind
        ready = false
        completed = false
        buffering = false
        positionMillis = 0
        if (state == SlideshowPlaybackState.STOPPED) state = SlideshowPlaybackState.RUNNING
        presentation.reset(nowMillis)
        watchdog.reset(nowMillis)
        updateClocks(nowMillis)
        return next
    }

    fun setState(state: SlideshowPlaybackState, nowMillis: Long) {
        this.state = state
        if (state == SlideshowPlaybackState.STOPPED) request = null
        updateClocks(nowMillis)
    }

    /** Combine application activity, uncovered viewer, and absence of blocking layers. */
    fun setEnvironmentActive(active: Boolean, nowMillis: Long) {
        environmentActive = active
        updateClocks(nowMillis)
    }

    fun setTransitionComplete(complete: Boolean, nowMillis: Long) {
        transitionComplete = complete
        updateClocks(nowMillis)
    }

    fun setBuffering(request: SlideshowMediaRequest, buffering: Boolean, nowMillis: Long): Boolean {
        if (!isCurrent(request)) return false
        this.buffering = buffering
        updateClocks(nowMillis)
        return true
    }

    fun onImageReady(request: SlideshowMediaRequest, nowMillis: Long): Boolean {
        if (!isCurrent(request) || kind != SlideshowMediaKind.IMAGE || ready) return false
        ready = true
        updateClocks(nowMillis)
        return true
    }

    /** Commands, duration, and nonbuffering do not establish video readiness. */
    fun onVideoProgress(request: SlideshowMediaRequest, positionMillis: Long, nowMillis: Long): Boolean {
        if (!isCurrent(request) || kind != SlideshowMediaKind.VIDEO || completed ||
            positionMillis <= this.positionMillis) return false
        this.positionMillis = positionMillis
        ready = true
        watchdog.reset(nowMillis)
        updateClocks(nowMillis)
        return true
    }

    /** A valid end can precede positive time for short media, and is recorded once. */
    fun onVideoCompleted(request: SlideshowMediaRequest, nowMillis: Long): Boolean {
        if (!isCurrent(request) || kind != SlideshowMediaKind.VIDEO || completed) return false
        completed = true
        updateClocks(nowMillis)
        return true
    }

    fun isCurrent(request: SlideshowMediaRequest): Boolean = this.request == request

    fun presentationElapsedMillis(nowMillis: Long): Long = presentation.elapsedMillis(nowMillis)

    fun motionProgress(config: SlideshowConfig, nowMillis: Long): Float =
        (presentationElapsedMillis(nowMillis).toFloat() / (config.normalized.durationSeconds * 1_000L))
            .coerceIn(0f, 1f)

    fun shouldAdvance(config: SlideshowConfig, nowMillis: Long): Boolean {
        if (!active || !transitionComplete) return false
        return if (kind == SlideshowMediaKind.VIDEO) {
            completed || config.normalized.videoLimitSeconds?.let { positionMillis >= it * 1_000L } == true
        } else ready && presentationElapsedMillis(nowMillis) >= config.normalized.durationSeconds * 1_000L
    }

    fun hasTimedOut(nowMillis: Long): Boolean =
        active && !completed && (kind == SlideshowMediaKind.VIDEO || !ready) &&
                watchdog.elapsedMillis(nowMillis) >= SLIDESHOW_MEDIA_WAIT_MILLIS

    private val active: Boolean
        get() = request != null && state == SlideshowPlaybackState.RUNNING && environmentActive

    private fun updateClocks(nowMillis: Long) {
        presentation.setRunning(active && ready && transitionComplete && !buffering && !completed, nowMillis)
        watchdog.setRunning(active && !completed && (kind == SlideshowMediaKind.VIDEO || !ready), nowMillis)
    }
}

const val SLIDESHOW_MEDIA_WAIT_MILLIS = 15_000L
