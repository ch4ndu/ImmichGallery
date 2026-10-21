package com.udnahc.immichgallery.ui.component

import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import kotlin.test.*

class SlideshowVideoEventsTest {
    @Test fun onlyObservedPositiveMediaTimeProducesReadiness() {
        for (position in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertNull(mediaMpSlideshowProgress(position))
        }
        assertEquals(SlideshowMediaEvent.Progress(1_250), mediaMpSlideshowProgress(1_250))
        assertNull(mediaMpSlideshowEvent(PlaybackEvent.ExternalPlayWhenReadyChanged(true)))
        assertNull(mediaMpSlideshowEvent(PlaybackEvent.SeekCompleted(120_000)))
        val media = UriMediaData("https://example.invalid/video.mp4", emptyMap(), MediaExtraFiles.EMPTY)
        assertEquals(SlideshowMediaEvent.Completed, mediaMpSlideshowEvent(PlaybackEvent.MediaEnded(media, 1_000, 1_000)))
        assertEquals(
            SlideshowMediaEvent.Failed,
            mediaMpSlideshowEvent(PlaybackEvent.ErrorOccurred(PlaybackException(PlaybackErrorCode.IO, "unreachable")))
        )
    }

    @Test fun originalStallFallsBackOnceAndFallbackHasBoundedStall() {
        val policy = SlideshowVideoFallbackPolicy()
        assertEquals(SlideshowVideoFallbackDecision.WAIT, policy.observe(4_999, false, false, true))
        assertEquals(SlideshowVideoFallbackDecision.FALLBACK, policy.observe(1, false, false, true))
        assertEquals(SlideshowVideoFallbackDecision.WAIT, policy.observe(14_999, false, false, false))
        assertEquals(SlideshowVideoFallbackDecision.FAILED, policy.observe(1, false, false, false))
    }

    @Test fun progressingPlaybackRemainsUsableAndTerminalFailuresStayBounded() {
        val policy = SlideshowVideoFallbackPolicy()
        assertEquals(SlideshowVideoFallbackDecision.FALLBACK, policy.observe(100, false, true, true))
        // Historical/recovered error-log entries are not terminal AVPlayerItem status.
        repeat(200) { assertEquals(SlideshowVideoFallbackDecision.WAIT, policy.observe(100, true, false, false)) }
        assertEquals(SlideshowVideoFallbackDecision.WAIT, policy.observe(14_999, false, false, false))
        assertEquals(SlideshowVideoFallbackDecision.FAILED, policy.observe(1, false, false, false))
        assertEquals(SlideshowVideoFallbackDecision.FAILED, SlideshowVideoFallbackPolicy().observe(100, false, true, false))
    }

    @Test fun progressRestartsTheStallBudget() {
        val policy = SlideshowVideoFallbackPolicy()
        repeat(5) {
            assertEquals(SlideshowVideoFallbackDecision.WAIT, policy.observe(4_900, false, false, true))
            assertEquals(SlideshowVideoFallbackDecision.WAIT, policy.observe(100, true, false, true))
        }
        assertEquals(SlideshowVideoFallbackDecision.FALLBACK, policy.observe(5_000, false, false, true))
    }
}
