package com.udnahc.immichgallery.ui.component

import chaintech.videoplayer.host.MediaPlayerEvent
import kotlin.test.*

class SlideshowVideoEventsTest {
    @Test fun onlyObservedPositiveMediaTimeProducesReadiness() {
        for (time in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(chaintechSlideshowEvent(MediaPlayerEvent.CurrentTimeChange(time)))
        }
        assertEquals(SlideshowMediaEvent.Progress(1_250), chaintechSlideshowEvent(MediaPlayerEvent.CurrentTimeChange(1.25f)))
        assertNull(chaintechSlideshowEvent(MediaPlayerEvent.PauseChange(false)))
        assertNull(chaintechSlideshowEvent(MediaPlayerEvent.TotalTimeChange(120f)))
        assertEquals(SlideshowMediaEvent.Buffering(false), chaintechSlideshowEvent(MediaPlayerEvent.BufferChange(false)))
        assertEquals(SlideshowMediaEvent.Completed, chaintechSlideshowEvent(MediaPlayerEvent.MediaEnd))
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
