package com.udnahc.immichgallery.ui.component

import org.openani.mediamp.PlaybackEvent

/**
 * MediaMP positions are milliseconds. Only an observed positive position is slideshow progress;
 * Ready, duration and play commands are not.
 */
internal fun mediaMpSlideshowProgress(positionMillis: Long): SlideshowMediaEvent? =
    positionMillis.takeIf { it > 0 }?.let(SlideshowMediaEvent::Progress)

/** Edge events of the request-bound player: natural end completes, a fatal error fails. */
internal fun mediaMpSlideshowEvent(event: PlaybackEvent): SlideshowMediaEvent? = when (event) {
    is PlaybackEvent.MediaEnded -> SlideshowMediaEvent.Completed
    is PlaybackEvent.ErrorOccurred -> SlideshowMediaEvent.Failed
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
