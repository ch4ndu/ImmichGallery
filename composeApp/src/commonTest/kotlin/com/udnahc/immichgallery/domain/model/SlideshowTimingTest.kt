package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SlideshowTimingTest {
    private val candidate = SlideshowCandidate(SlideshowSlot.Asset("a"))
    private val config = SlideshowConfig(durationSeconds = 5)

    @Test
    fun loadingAndTransitionDoNotConsumeDwellOrMotionAndPauseRetainsBoth() {
        val timing = SlideshowTiming()
        val request = timing.beginVisit(candidate, "original", SlideshowMediaKind.IMAGE, 0)
        timing.setTransitionComplete(false, 0)
        timing.onImageReady(request, 2_000)
        timing.setTransitionComplete(true, 3_000)
        assertEquals(0L, timing.presentationElapsedMillis(3_000))
        timing.setState(SlideshowPlaybackState.PAUSED, 5_000)
        assertEquals(2_000L, timing.presentationElapsedMillis(50_000))
        assertEquals(0.4f, timing.motionProgress(config, 50_000))
        assertFalse(timing.shouldAdvance(config, 50_000))
        timing.setState(SlideshowPlaybackState.RUNNING, 50_000)
        assertEquals(request, timing.request)
        assertFalse(timing.shouldAdvance(config, 52_999))
        assertTrue(timing.shouldAdvance(config, 53_000))
        assertEquals(1f, timing.motionProgress(config, 53_000))
    }

    @Test
    fun coveredOrBackgroundViewerFreezesPresentationAndLoadingWatchdog() {
        val timing = SlideshowTiming()
        val request = timing.beginVisit(candidate, "original", SlideshowMediaKind.IMAGE, 0)
        timing.setEnvironmentActive(false, 10_000)
        assertFalse(timing.hasTimedOut(100_000))
        timing.setEnvironmentActive(true, 100_000)
        assertFalse(timing.hasTimedOut(104_999))
        assertTrue(timing.hasTimedOut(105_000))
        timing.onImageReady(request, 105_000)
        assertFalse(timing.hasTimedOut(120_000))
        timing.setEnvironmentActive(false, 106_000)
        assertEquals(1_000L, timing.presentationElapsedMillis(150_000))
        timing.setEnvironmentActive(true, 150_000)
        assertTrue(timing.shouldAdvance(config, 154_000))
    }

    @Test
    fun videoNeedsPositiveProgressAndWatchdogAlsoCatchesStallsAfterStartup() {
        val timing = SlideshowTiming()
        val request = timing.beginVisit(candidate, "video", SlideshowMediaKind.VIDEO, 0)
        timing.setBuffering(request, false, 0)
        assertFalse(timing.ready)
        assertFalse(timing.onImageReady(request, 0))
        assertFalse(timing.onVideoProgress(request, 0, 1_000))
        assertTrue(timing.onVideoProgress(request, 1_000, 2_000))
        assertTrue(timing.ready)
        timing.setBuffering(request, true, 3_000)
        assertEquals(1_000L, timing.presentationElapsedMillis(4_000))
        timing.setState(SlideshowPlaybackState.PAUSED, 4_000)
        assertFalse(timing.hasTimedOut(100_000))
        timing.setState(SlideshowPlaybackState.RUNNING, 100_000)
        assertFalse(timing.hasTimedOut(112_999))
        assertTrue(timing.hasTimedOut(113_000))
        assertTrue(timing.onVideoProgress(request, 2_000, 113_000))
        assertFalse(timing.hasTimedOut(113_000))
    }

    @Test
    fun shortVideoCompletionIsAcceptedOnceAndCannotAdvanceBehindBlockingLayer() {
        val timing = SlideshowTiming()
        val request = timing.beginVisit(candidate, "video", SlideshowMediaKind.VIDEO, 0)
        timing.setEnvironmentActive(false, 50)
        assertTrue(timing.onVideoCompleted(request, 100))
        assertFalse(timing.ready)
        assertFalse(timing.onVideoCompleted(request, 110))
        assertFalse(timing.shouldAdvance(config, 110))
        timing.setEnvironmentActive(true, 200)
        assertTrue(timing.shouldAdvance(config, 200))
        assertFalse(timing.hasTimedOut(20_000))
    }

    @Test
    fun videoLimitUsesPlaybackPositionInsteadOfTimeSpentWaiting() {
        val timing = SlideshowTiming()
        val request = timing.beginVisit(candidate, "video", SlideshowMediaKind.VIDEO, 0)
        val limited = config.copy(videoLimitSeconds = 3)
        timing.onVideoProgress(request, 1_000, 10_000)
        assertFalse(timing.shouldAdvance(limited, 13_000))
        timing.onVideoProgress(request, 3_000, 14_000)
        assertTrue(timing.shouldAdvance(limited, 14_000))
        assertFalse(timing.shouldAdvance(config, 14_000))
    }

    @Test
    fun everyVisitRestartsMediaAndRejectsEventsFromPreviousVisitOrStoppedShow() {
        val timing = SlideshowTiming()
        val first = timing.beginVisit(candidate, "video", SlideshowMediaKind.VIDEO, 0)
        timing.onVideoProgress(first, 3_000, 3_000)
        timing.setState(SlideshowPlaybackState.PAUSED, 4_000)
        val second = timing.beginVisit(candidate, "video", SlideshowMediaKind.VIDEO, 10_000)
        assertNotEquals(first.generation, second.generation)
        assertEquals(SlideshowPlaybackState.PAUSED, timing.state)
        assertEquals(0L, timing.positionMillis)
        assertEquals(0L, timing.presentationElapsedMillis(12_000))
        assertFalse(timing.ready)
        assertFalse(timing.onVideoProgress(first, 5_000, 12_000))
        assertFalse(timing.onVideoCompleted(first, 12_000))
        assertFalse(timing.setBuffering(first, true, 12_000))
        timing.setState(SlideshowPlaybackState.STOPPED, 13_000)
        assertFalse(timing.onVideoCompleted(second, 13_000))
        assertFalse(timing.shouldAdvance(config, 100_000))
    }
}
