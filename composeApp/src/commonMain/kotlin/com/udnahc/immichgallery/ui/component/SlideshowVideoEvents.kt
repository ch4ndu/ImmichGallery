package com.udnahc.immichgallery.ui.component

import chaintech.videoplayer.host.MediaPlayerEvent

/** The installed Chaintech backend reports seconds, with whole-second JVM resolution. */
internal fun observedVideoPosition(seconds: Float): Long? =
    seconds.takeIf { it.isFinite() && it > 0f }?.let { (it.toDouble() * 1_000).toLong() }

internal fun chaintechSlideshowEvent(event: MediaPlayerEvent): SlideshowMediaEvent? = when (event) {
    is MediaPlayerEvent.CurrentTimeChange -> observedVideoPosition(event.currentTime)?.let(SlideshowMediaEvent::Progress)
    is MediaPlayerEvent.BufferChange -> SlideshowMediaEvent.Buffering(event.isBuffering)
    MediaPlayerEvent.MediaEnd -> SlideshowMediaEvent.Completed
    else -> null
}


internal enum class SlideshowVideoFallbackDecision { WAIT, FALLBACK, FAILED }

/** Called only for active playback observation intervals; one source fallback per visit. */
internal class SlideshowVideoFallbackPolicy {
    private var stalledMillis = 0L
    private var usedFallback = false
    fun observe(activeMillis: Long, advancing: Boolean, failed: Boolean, canFallback: Boolean): SlideshowVideoFallbackDecision {
        stalledMillis = if (advancing) 0 else stalledMillis + activeMillis
        if (!failed && stalledMillis < if (canFallback && !usedFallback) 5_000L else 15_000L) return SlideshowVideoFallbackDecision.WAIT
        return if (canFallback && !usedFallback) {
            usedFallback = true
            stalledMillis = 0
            SlideshowVideoFallbackDecision.FALLBACK
        } else SlideshowVideoFallbackDecision.FAILED
    }
}
