package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.domain.model.*
import kotlin.random.Random
import kotlin.test.*

class SlideshowPlaybackControllerTest {
    private class Fixture {
        var now = 0L
        val buckets = mutableListOf<Pair<String, Boolean>>()
        val controller = SlideshowPlaybackController({ now }, { bucket, retry -> buckets += bucket to retry }, Random(7))
        val state get() = controller.state.value
        fun start(items: List<SlideshowSourceItem>, config: SlideshowConfig = SlideshowConfig(), page: Int = 0) {
            controller.updateSource(items)
            controller.start(config, page)
        }
        fun finish() = controller.navigationFinished(state.navigationId)
        fun event(event: SlideshowMediaEvent) = controller.mediaEvent(assertNotNull(state.request), event)
        fun ready() { finish(); event(SlideshowMediaEvent.ImageReady) }
        fun advance(millis: Long) { now += millis; controller.tick() }
    }

    @Test fun durationAndMotionShareOnlyReadyUncoveredUnpausedPresentationTime() {
        val f = Fixture()
        f.start(items("a", "b"), SlideshowConfig(durationSeconds = 5))
        f.advance(3_000)
        assertFalse(f.state.ready)
        assertFalse(f.state.keepAwake)
        f.event(SlideshowMediaEvent.ImageReady)
        f.advance(1_000)
        assertEquals(0f, f.state.motionProgress)
        f.finish()
        f.advance(2_000)
        assertEquals(.4f, f.state.motionProgress)
        assertTrue(f.state.keepAwake)
        val visit = f.state.request
        f.controller.togglePause()
        f.advance(20_000)
        assertEquals(.4f, f.state.motionProgress)
        assertFalse(f.state.playMedia)
        assertFalse(f.state.keepAwake)
        f.controller.togglePause()
        f.controller.setEnvironment(false)
        f.advance(20_000)
        assertEquals(.4f, f.state.motionProgress)
        assertEquals(visit, f.state.request)
        f.controller.setEnvironment(true)
        f.advance(2_999)
        assertEquals("a", f.state.asset?.id)
        f.advance(1)
        assertEquals("b", f.state.asset?.id)
        assertEquals(0f, f.state.motionProgress)
    }

    @Test fun previousSkipsVideoBackwardBeforeCommittingAndForwardReplays() {
        val f = Fixture()
        f.start(items("a", "b") + video("v"), SlideshowConfig(videoPolicy = SlideshowVideoPolicy.SKIP))
        f.controller.previous()
        assertEquals("b", f.state.asset?.id)
        f.controller.next()
        assertEquals("a", f.state.asset?.id)
        f.controller.previous()
        assertEquals("b", f.state.asset?.id)
        f.controller.previous()
        assertEquals("a", f.state.asset?.id)
    }

    @Test fun previousSkipsPreviouslyExcludedAndLazyMaterializedVideoBackward() {
        for (known in listOf(false, true)) {
            val f = Fixture()
            val slot = SlideshowSlot.BucketOffset("v", 0)
            val initial = items("a", "b") + SlideshowSourceItem(slot, if (known) asset("v", AssetType.VIDEO) else null)
            f.start(initial, SlideshowConfig(videoPolicy = SlideshowVideoPolicy.SKIP))
            if (known) { f.controller.next(); f.controller.next() } // upcoming prefetch excludes V
            f.controller.previous()
            if (!known) {
                assertNull(f.state.asset)
                f.controller.updateSource(items("a", "b") + SlideshowSourceItem(slot, asset("v", AssetType.VIDEO)))
            }
            assertEquals("b", f.state.asset?.id)
            f.controller.next()
            assertEquals("a", f.state.asset?.id)
        }
    }

    @Test fun lazyRandomStartNeverInventsHistoryDuringLoadingOrMaterialization() {
        val f = Fixture()
        val slot = SlideshowSlot.BucketOffset("b", 0)
        f.start(items("a") + SlideshowSourceItem(slot), SlideshowConfig(order = SlideshowOrder.RANDOM), page = 1)
        val pending = f.state.request
        f.controller.updateSource(items("a") + SlideshowSourceItem(slot, status = SlideshowCandidateStatus.LOADING))
        assertEquals(pending, f.state.request)
        assertFalse(f.state.canGoPrevious)
        f.controller.updateSource(items("a") + SlideshowSourceItem(slot, asset("b")))
        assertEquals("b", f.state.asset?.id)
        assertFalse(f.state.canGoPrevious)
        f.controller.previous()
        assertEquals("b", f.state.asset?.id)
        f.controller.next()
        assertEquals("a", f.state.asset?.id)
        f.controller.previous()
        assertEquals("b", f.state.asset?.id)
    }

    @Test fun skippedInitialVideoAndLoadingUpdatesNeverEnterRandomHistory() {
        val f = Fixture()
        val slot = SlideshowSlot.BucketOffset("b", 0)
        f.start(listOf(video("v"), SlideshowSourceItem(slot)), SlideshowConfig(order = SlideshowOrder.RANDOM, videoPolicy = SlideshowVideoPolicy.SKIP))
        f.controller.updateSource(listOf(video("v"), SlideshowSourceItem(slot, status = SlideshowCandidateStatus.LOADING)))
        f.controller.updateSource(listOf(video("v"), SlideshowSourceItem(slot, asset("b"))))
        assertFalse(f.state.canGoPrevious)
        assertEquals("b", f.state.asset?.id)
        f.controller.previous()
        assertEquals("b", f.state.asset?.id)
    }

    @Test fun readinessAndCompletionFromOldVisitsCannotAffectCurrentOrStoppedShow() {
        val f = Fixture()
        f.start(listOf(video("v"), image("a")), SlideshowConfig(videoPolicy = SlideshowVideoPolicy.PLAY))
        val old = assertNotNull(f.state.request)
        f.finish()
        assertTrue(f.state.playMedia) // playback begins before readiness
        f.controller.next()
        f.controller.mediaEvent(old, SlideshowMediaEvent.Completed)
        f.controller.mediaEvent(old, SlideshowMediaEvent.Progress(9_000))
        f.controller.mediaEvent(old, SlideshowMediaEvent.ImageReady)
        assertFalse(f.state.ready)
        assertEquals("a", f.state.asset?.id)
        f.controller.previous()
        assertNotEquals(old, f.state.request)
        f.controller.stop()
        f.controller.mediaEvent(old, SlideshowMediaEvent.Failed)
        assertNull(f.state.config)
        assertNull(f.state.failure)
        assertFalse(f.state.keepAwake)
        f.controller.close()
        f.controller.start(SlideshowConfig(), 0)
        assertNull(f.state.config)
    }

    @Test fun shortEndAndCapRaceAdvanceExactlyOnceAndPausedVideoResumesSameVisit() {
        val f = Fixture()
        f.start(listOf(video("v"), image("a"), image("b")), SlideshowConfig(videoPolicy = SlideshowVideoPolicy.PLAY, videoLimitSeconds = 2))
        f.finish()
        val visit = assertNotNull(f.state.request)
        f.event(SlideshowMediaEvent.Progress(1_000))
        f.controller.togglePause()
        f.advance(20_000)
        assertEquals(visit, f.state.request)
        f.controller.togglePause()
        f.event(SlideshowMediaEvent.Progress(2_000))
        f.event(SlideshowMediaEvent.Completed)
        f.controller.tick()
        assertEquals("a", f.state.asset?.id)
        f.controller.mediaEvent(visit, SlideshowMediaEvent.Completed)
        f.controller.tick()
        assertEquals("a", f.state.asset?.id)
        f.controller.previous()
        f.finish()
        f.event(SlideshowMediaEvent.Completed) // accepted even without positive time
        f.controller.tick()
        assertEquals("a", f.state.asset?.id)
    }

    @Test fun startupAndLaterStallsUseActiveTimeoutAndFailuresPauseAfterThree() {
        val f = Fixture()
        f.start(items("a", "b", "c", "d"))
        f.finish()
        f.advance(14_999)
        assertNull(f.state.failure)
        f.controller.setEnvironment(false)
        f.advance(30_000)
        assertNull(f.state.failure)
        f.controller.setEnvironment(true)
        f.advance(1)
        assertEquals(SlideshowFailure.TIMEOUT, f.state.failure)
        f.advance(1_999)
        assertEquals("a", f.state.asset?.id)
        f.advance(1)
        assertEquals("b", f.state.asset?.id)
        f.event(SlideshowMediaEvent.Failed)
        f.advance(2_000)
        assertEquals("c", f.state.asset?.id)
        f.event(SlideshowMediaEvent.Failed)
        assertTrue(f.state.paused)
        assertTrue(f.state.canRetry)
        f.advance(30_000)
        assertEquals("c", f.state.asset?.id)
        val old = f.state.request
        f.controller.retry()
        assertNotEquals(old, f.state.request)
        assertFalse(f.state.paused)
        f.ready()
        f.controller.next()
        f.event(SlideshowMediaEvent.Failed)
        assertFalse(f.state.paused) // actual readiness reset consecutive failure count
    }

    @Test fun videoProgressResetsStallButPauseAndBufferingCommandsDoNotEstablishReadiness() {
        val f = Fixture()
        f.start(listOf(video("v"), image("a")), SlideshowConfig(videoPolicy = SlideshowVideoPolicy.PLAY))
        f.finish()
        f.event(SlideshowMediaEvent.Buffering(false))
        assertFalse(f.state.ready)
        f.advance(14_000)
        f.event(SlideshowMediaEvent.Progress(1_000))
        assertTrue(f.state.ready)
        f.advance(14_999)
        assertNull(f.state.failure)
        f.advance(1)
        assertEquals(SlideshowFailure.TIMEOUT, f.state.failure)
    }

    @Test fun materializingUpcomingKeepsCurrentClockAndProvidesActualNextPrefetch() {
        val f = Fixture()
        val slot = SlideshowSlot.BucketOffset("next", 0)
        val initial = items("a") + SlideshowSourceItem(slot)
        f.start(initial, SlideshowConfig(durationSeconds = 5))
        f.ready()
        val visit = f.state.request
        f.advance(2_000)
        assertEquals(listOf("next" to false), f.buckets)
        f.controller.updateSource(items("a") + SlideshowSourceItem(slot, asset("b")))
        assertEquals(visit, f.state.request)
        assertEquals(.4f, f.state.motionProgress)
        assertEquals("b", f.state.upcomingAsset?.id)
        f.advance(3_000)
        assertEquals("b", f.state.asset?.id)
    }

    @Test fun explicitBucketRetryReplacesGenerationAndRepeatedFailureSnapshotsDoNotCountAgain() {
        val f = Fixture()
        val slot = SlideshowSlot.BucketOffset("missing", 0)
        val failed = listOf(SlideshowSourceItem(slot, status = SlideshowCandidateStatus.FAILED))
        f.start(failed)
        assertEquals(SlideshowFailure.BUCKET, f.state.failure)
        repeat(5) { f.controller.updateSource(failed) }
        assertFalse(f.state.paused)
        val old = f.state.request
        f.controller.retry()
        assertEquals(listOf("missing" to true), f.buckets)
        assertNotEquals(old, f.state.request)
        f.controller.updateSource(listOf(SlideshowSourceItem(slot, status = SlideshowCandidateStatus.LOADING)))
        f.controller.updateSource(listOf(SlideshowSourceItem(slot, asset("a"))))
        f.ready()
        assertEquals("a", f.state.asset?.id)
        assertNull(f.state.failure)
    }

    @Test fun allVideoRequiresSuccessfulKnownPassAndCacheOnlyNeverRequestsNetworkRetry() {
        val f = Fixture()
        val slot = SlideshowSlot.BucketOffset("lazy", 0)
        f.start(listOf(video("v"), SlideshowSourceItem(slot)), SlideshowConfig(videoPolicy = SlideshowVideoPolicy.SKIP))
        assertNull(f.state.failure)
        f.controller.updateSource(listOf(video("v"), SlideshowSourceItem(slot, status = SlideshowCandidateStatus.CACHE_UNAVAILABLE)))
        assertEquals(SlideshowFailure.MANUAL_REFRESH, f.state.failure)
        assertFalse(f.state.canRetry)
        f.controller.retry()
        assertEquals(listOf("lazy" to false), f.buckets)
        f.controller.next()
        assertNotEquals(SlideshowFailure.ALL_VIDEOS, f.state.failure)
        f.controller.stop()
        f.start(listOf(video("v"), SlideshowSourceItem(slot, asset("w", AssetType.VIDEO))), SlideshowConfig(videoPolicy = SlideshowVideoPolicy.SKIP))
        assertEquals(SlideshowFailure.ALL_VIDEOS, f.state.failure)
        assertTrue(f.state.paused)
    }

    @Test fun structuralSourceChangeInvalidatesPendingNavigationAndEmptyCanExit() {
        val f = Fixture()
        f.start(items("a", "b"))
        val oldNav = f.state.navigationId
        f.controller.updateSource(items("b", "a"))
        assertEquals("a", f.state.asset?.id)
        assertEquals(1, f.state.page)
        f.controller.navigationFinished(oldNav)
        assertTrue(f.state.transitioning)
        f.finish()
        assertFalse(f.state.transitioning)
        f.controller.updateSource(emptyList())
        assertTrue(f.state.paused)
        assertEquals(SlideshowFailure.EMPTY, f.state.failure)
        f.controller.stop()
        assertNull(f.state.config)
    }

    @Test fun focusedPausedAndBlockedControlsStayVisibleAndTapDoesNotStopShow() {
        val f = Fixture()
        f.start(items("a"), SlideshowConfig(durationSeconds = 120))
        f.ready()
        f.controller.setFocused(true)
        f.advance(10_000)
        assertTrue(f.state.controlsVisible)
        f.controller.setFocused(false)
        f.advance(3_000)
        assertFalse(f.state.controlsVisible)
        f.controller.toggleControls()
        assertTrue(f.state.controlsVisible)
        assertNotNull(f.state.config)
        f.controller.togglePause()
        f.advance(10_000)
        assertTrue(f.state.controlsVisible)
        f.controller.togglePause()
        f.controller.setEnvironment(false)
        f.advance(10_000)
        assertTrue(f.state.controlsVisible)
    }

    @Test fun backwardLazySkipWithOnlyOneEligibleImageReturnsToThatImage() {
        val f = Fixture()
        val slot = SlideshowSlot.BucketOffset("video", 0)
        f.start(items("a") + SlideshowSourceItem(slot), SlideshowConfig(videoPolicy = SlideshowVideoPolicy.SKIP))
        f.controller.previous()
        f.controller.updateSource(items("a") + SlideshowSourceItem(slot, asset("v", AssetType.VIDEO)))
        assertEquals("a", f.state.asset?.id)
        assertNull(f.state.failure)
        assertFalse(f.state.canGoPrevious)
    }

    @Test fun initiallyCacheOnlyMissingCandidateMakesNoBucketRequest() {
        val f = Fixture()
        f.start(listOf(SlideshowSourceItem(SlideshowSlot.BucketOffset("absent", 0), status = SlideshowCandidateStatus.CACHE_UNAVAILABLE)))
        assertEquals(SlideshowFailure.MANUAL_REFRESH, f.state.failure)
        assertFalse(f.state.canRetry)
        f.controller.retry()
        f.advance(2_000)
        assertTrue(f.buckets.isEmpty())
        assertNotEquals(SlideshowFailure.ALL_VIDEOS, f.state.failure)
    }

    private fun items(vararg ids: String) = ids.map(::image)
    private fun image(id: String) = SlideshowSourceItem(SlideshowSlot.Asset(id), asset(id))
    private fun video(id: String) = SlideshowSourceItem(SlideshowSlot.Asset(id), asset(id, AssetType.VIDEO))
    private fun asset(id: String, type: AssetType = AssetType.IMAGE) = Asset(id, type, "$id.jpg", "2026-10-01T00:00:00Z", "thumb/$id", "original/$id", "video/$id")
}
