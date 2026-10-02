package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.udnahc.immichgallery.domain.model.*
import com.udnahc.immichgallery.ui.util.PhotoOverlayReturnTarget
import com.udnahc.immichgallery.ui.util.ViewerWindowPlacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalTestApi::class)
class ViewerStaticPlaybackUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ViewerOverlayUiFixture
    private val assets = listOf("a", "b", "c").map { viewerFixtureAsset(it) }
    private val returned = mutableListOf<String?>()
    private var visible by mutableStateOf(true)

    @After fun cleanup() { if (::fixture.isInitialized) fixture.dispose() }

    @Test fun actualControlsDrivePagerHistoryAndRemainingReadyDwell() {
        show()
        start()
        waitVisit("a")
        compose.runOnIdle { fixture.advance(10_000) }
        compose.runOnIdle { assertFalse(fixture.state.ready); assertEquals("a", fixture.state.asset?.id); fixture.ready() }
        compose.waitUntil { fixture.state.ready }
        compose.runOnIdle { fixture.advance(2_000) }
        compose.onNodeWithTag("media:a:selected").performClick() // Reveal controls after the deliberate 10s startup wait.
        waitText("Pause slideshow")
        compose.onNodeWithText("Pause slideshow").performClick()
        compose.waitUntil { fixture.state.paused }
        compose.runOnIdle { fixture.advance(30_000); assertFalse(fixture.wakeHeld) }
        compose.runOnIdle { assertEquals("a", fixture.state.asset?.id); assertEquals(.4f, fixture.state.motionProgress) }
        compose.onNodeWithText("Resume slideshow").performClick()
        compose.waitUntil { !fixture.state.paused }
        compose.runOnIdle { fixture.advance(3_000) }
        waitVisit("b")
        compose.onNodeWithText("Previous slide").performClick()
        waitVisit("a")
        compose.onNodeWithText("Next slide").performClick()
        waitVisit("b")
        compose.onNodeWithText("Exit slideshow").performClick()
        compose.waitUntil { fixture.state.config == null }
        compose.onNodeWithText("Asset 2 of 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf<String?>("b"), returned) }
    }

    @Test fun realOptionsPersistAcrossShowExitAndAnotherOverlayLifetime() {
        var generation by mutableStateOf(0)
        fixture = ViewerOverlayUiFixture()
        compose.setContent { fixture.Content { key(generation) { Overlay() } } }
        openOptions()
        compose.onNode(hasText("Duration (seconds)") and hasSetTextAction()).performTextReplacement("8")
        compose.onNodeWithText("Random").performScrollTo().performClick()
        compose.onNodeWithText("Start").performClick()
        compose.waitUntil { fixture.state.config?.durationSeconds == 8 }
        compose.onNodeWithText("Exit slideshow").performClick()
        compose.runOnIdle { generation += 1 }
        compose.waitForIdle()
        openOptions()
        compose.onNode(hasText("Duration (seconds)") and hasSetTextAction()).assertTextContains("8")
        compose.onNodeWithText("Random").assertIsSelected()
        compose.runOnIdle {
            assertEquals(8, fixture.repository.getSlideshowConfig().durationSeconds)
            assertEquals(SlideshowOrder.RANDOM, fixture.repository.getSlideshowConfig().order)
            assertNull(fixture.state.config)
        }
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun staticFilmstripSelectionUsesMaterializedCountAndCurrentAssetReturn() {
        hostJourney(PhotoOverlayReturnTarget.CURRENT_ASSET, showIndicator = false, expectedReturn = "c")
    }

    @Test fun memoriesFilmstripKeepsItsIndicatorAndOriginCoverReturn() {
        hostJourney(PhotoOverlayReturnTarget.ORIGIN_ASSET, showIndicator = true, expectedReturn = "a")
    }

    @Test fun fullscreenButtonAndFWorkAndEscapeStopsShowThenFullscreenThenViewer() {
        show(SlideshowConfig(transition = SlideshowTransition.CUT, fullscreen = true))
        compose.onNodeWithContentDescription("Enter fullscreen").performClick()
        compose.runOnIdle { assertEquals(ViewerWindowPlacement.Fullscreen, fixture.placement) }
        press(Key.F)
        compose.runOnIdle { assertEquals(ViewerWindowPlacement.Floating, fixture.placement) }
        press(Key.F)
        start()
        waitVisit("a")
        compose.onNodeWithText("Pause slideshow").performClick()
        compose.waitUntil { fixture.state.paused }
        compose.runOnIdle {
            fixture.appActive = false
            fixture.windowVisible = false
            fixture.advance(20_000)
            assertNotNull(fixture.state.config)
            assertEquals(ViewerWindowPlacement.Fullscreen, fixture.placement)
        }
        compose.runOnIdle { fixture.appActive = true; fixture.windowVisible = true }
        press(Key.Escape)
        compose.waitUntil { fixture.state.config == null }
        waitText("Show thumbnails")
        compose.runOnIdle { assertEquals(ViewerWindowPlacement.Fullscreen, fixture.placement); assertTrue(returned.isEmpty()) }
        press(Key.Escape)
        compose.runOnIdle { assertEquals(ViewerWindowPlacement.Floating, fixture.placement); assertTrue(returned.isEmpty()) }
        press(Key.Escape)
        compose.runOnIdle { assertEquals(listOf<String?>("a"), returned) }
    }

    @Test fun showFullscreenLeaseRestoresOnActualShowExit() {
        show(SlideshowConfig(transition = SlideshowTransition.CUT, fullscreen = true))
        start()
        waitVisit("a")
        compose.runOnIdle { assertEquals(ViewerWindowPlacement.Fullscreen, fixture.placement) }
        compose.onNodeWithText("Exit slideshow").performClick()
        compose.waitUntil { fixture.state.config == null && fixture.placement == ViewerWindowPlacement.Floating }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(ViewerWindowPlacement.Floating, fixture.placement) }
    }

    @Test fun unsupportedViewerHidesFullscreenActionsAndFDoesNothing() {
        fixture = ViewerOverlayUiFixture(fullscreenSupported = false)
        compose.setContent { fixture.Content { Overlay() } }
        compose.onNodeWithContentDescription("Enter fullscreen").assertDoesNotExist()
        press(Key.F)
        compose.runOnIdle { assertEquals(ViewerWindowPlacement.Floating, fixture.placement) }
        openOptions()
        compose.onNodeWithText("Enter fullscreen automatically").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun readinessFailuresShowRecoveryAndRetryReplacesTheActualMediaCallback() {
        show()
        start()
        repeat(3) { index ->
            waitVisit(assets[index].id)
            compose.runOnIdle { fixture.ready(SlideshowMediaEvent.Failed) }
            compose.waitUntil { fixture.state.failure != null }
            if (index < 2) compose.runOnIdle { fixture.advance(2_000) }
        }
        compose.waitUntil { fixture.state.paused }
        val failed = fixture.state.request
        compose.onNodeWithText("Retry slide").performClick()
        compose.waitUntil { fixture.state.request != failed && fixture.state.failure == null }
        waitVisit("c")
        compose.runOnIdle {
            fixture.ready()
            val old = requireNotNull(failed)
            requireNotNull(fixture.media[old]).onEvent(old, SlideshowMediaEvent.Failed)
        }
        compose.waitUntil { fixture.state.ready }
        compose.runOnIdle { assertNull(fixture.state.failure) }
        compose.onNodeWithText("Next slide").performClick()
        waitVisit("a")
        compose.onNodeWithText("Exit slideshow").performClick()
        compose.onNodeWithText("Show thumbnails").assertIsDisplayed()
    }

    @Test fun tapsAndSpaceControlActualPlaybackWithoutResettingTheVisit() {
        show()
        press(Key.Spacebar)
        compose.onNodeWithText("Start").performClick()
        waitVisit("a")
        compose.runOnIdle { fixture.ready() }
        compose.waitUntil { fixture.state.ready }
        val request = fixture.state.request
        compose.onNodeWithTag("media:a:selected").performClick()
        compose.waitUntil { !fixture.state.controlsVisible && compose.onAllNodesWithText("Pause slideshow").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Pause slideshow").assertDoesNotExist()
        compose.onNodeWithTag("media:a:selected").performClick()
        waitText("Pause slideshow")
        compose.onNodeWithText("Pause slideshow").assertIsDisplayed()
        press(Key.Spacebar)
        compose.waitUntil { fixture.state.paused }
        compose.runOnIdle { fixture.advance(20_000); assertEquals(request, fixture.state.request) }
        press(Key.Spacebar)
        compose.waitUntil { !fixture.state.paused }
        compose.runOnIdle { assertEquals(request, fixture.state.request) }
    }

    @Test fun captionsClearWhileNewMetadataWaitsAndInfoPausesRemainingPresentation() {
        val pendingB = CompletableDeferred<Result<AssetDetail>>()
        fixture = ViewerOverlayUiFixture(SlideshowConfig(transition = SlideshowTransition.CUT, captions = true))
        compose.setContent { fixture.Content { Overlay(detail = { id -> if (id == "b") pendingB.await() else Result.success(viewerFixtureDetail(id)) }) } }
        start()
        waitVisit("a")
        waitText("a description")
        compose.onNodeWithText("a description").assertIsDisplayed()
        val local = Instant.parse(requireNotNull(viewerFixtureDetail("a").dateTime)).toLocalDateTime(TimeZone.currentSystemDefault())
        val displayedTime = "at ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        compose.onNodeWithText(displayedTime, substring = true).assertIsDisplayed()
        compose.runOnIdle { fixture.ready(); fixture.advance(2_000) }
        compose.onNodeWithText("Info").performClick()
        compose.waitUntil { !fixture.state.playMedia }
        compose.waitUntil { compose.onAllNodesWithText(displayedTime, substring = true).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithText(displayedTime, substring = true).assertCountEquals(2)
        compose.runOnIdle { fixture.advance(30_000); assertEquals("a", fixture.state.asset?.id) }
        compose.onNodeWithContentDescription("Close sheet").performMouseInput { click(topLeft + androidx.compose.ui.geometry.Offset(10f, 10f)) }
        compose.waitUntil { fixture.state.playMedia }
        compose.onNodeWithText("Next slide").performClick()
        waitVisit("b")
        compose.onNodeWithText("a description").assertDoesNotExist()
        compose.runOnIdle { pendingB.complete(Result.success(viewerFixtureDetail("b"))) }
        waitText("b description")
        compose.onNodeWithText("b description").assertIsDisplayed()
    }

    @Test fun nestedStackRetainsBasePageFullscreenAndChildDateUsesBaseReturnIdentity() {
        val dates = mutableListOf<Pair<LocalDate, String?>>()
        val base = listOf(viewerFixtureAsset("a"), viewerFixtureAsset("b", stackCount = 2))
        val child = listOf(base[1], viewerFixtureAsset("child").copy(createdAt = "2026-09-12T12:00:00Z"))
        fixture = ViewerOverlayUiFixture()
        compose.setContent { fixture.Content {
            StaticPhotoOverlay(base, 0, "", ::detail,
                getAssetStack = { Result.success(AssetStackLookup.Available(AssetStack("stack", "b", child))) },
                onPersonClick = { _, _ -> }, onDismiss = { returned += it },
                onCurrentAssetChanged = { fixture.settledIds += it },
                onShowInTimeline = { date, baseId -> dates += date to baseId })
        } }
        compose.onNodeWithText("Show thumbnails").performClick()
        compose.onNodeWithContentDescription("Asset 2 of 2").performClick()
        compose.waitUntil { fixture.settledIds.lastOrNull() == "b" }
        compose.onNodeWithContentDescription("Enter fullscreen").performClick()
        openStack()
        compose.onNodeWithText("Show thumbnails").performClick()
        compose.onNodeWithContentDescription("Asset 2 of 2").performClick()
        compose.onNodeWithTag("media:child:selected").assertIsDisplayed()
        compose.runOnIdle { assertEquals("b", fixture.settledIds.last()); assertEquals(ViewerWindowPlacement.Fullscreen, fixture.placement) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("media:b:selected").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ViewerWindowPlacement.Fullscreen, fixture.placement) }
        openStack()
        compose.onNodeWithText("Show thumbnails").performClick()
        compose.onNodeWithContentDescription("Asset 2 of 2").performClick()
        compose.onNodeWithContentDescription("Show photo information").performClick()
        compose.onNodeWithText("Show in Timeline").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf<Pair<LocalDate, String?>>(LocalDate(2026, 9, 12) to "b"), dates)
            assertTrue(returned.isEmpty())
            assertEquals(ViewerWindowPlacement.Floating, fixture.placement)
        }
    }

    @Test fun mediaTapHidesAndRestoresCollapsedAndExpandedFilmstripChrome() {
        show()
        repeat(2) { expanded ->
            if (expanded == 1) compose.onNodeWithText("Show thumbnails").performClick()
            val label = if (expanded == 1) "Hide thumbnails" else "Show thumbnails"
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithTag("media:a:selected").performClick()
            compose.onNodeWithText(label).assertDoesNotExist()
            compose.onNodeWithText("Asset 1 of 3").assertDoesNotExist()
            compose.onNodeWithTag("media:a:selected").performClick()
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText("Asset 1 of 3").assertIsDisplayed()
        }
    }

    @Test fun cutChangesDirectlyBeforeDwell() = transitionJourney(SlideshowTransition.CUT)
    @Test fun fadeChangesOnlyToItsDistantTargetBeforeDwell() = transitionJourney(SlideshowTransition.FADE)
    @Test fun slideCompletesBeforeDwell() = transitionJourney(SlideshowTransition.SLIDE)

    private fun transitionJourney(transition: SlideshowTransition) {
        val slides = listOf(viewerFixtureAsset("a"), viewerFixtureAsset("video", AssetType.VIDEO), viewerFixtureAsset("c"))
        fixture = ViewerOverlayUiFixture(SlideshowConfig(transition = transition, videoPolicy = SlideshowVideoPolicy.SKIP))
        compose.setContent { fixture.Content {
            StaticPhotoOverlay(slides, 0, "", ::detail, getAssetStack = { Result.success(AssetStackLookup.NoStack) },
                onPersonClick = { _, _ -> }, onDismiss = { returned += it }, onCurrentAssetChanged = { fixture.settledIds += it })
        } }
        start()
        waitVisit("a")
        compose.runOnIdle { fixture.ready(); fixture.settledIds.clear() }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Next slide").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil { fixture.state.asset?.id == "c" }
        compose.mainClock.advanceTimeUntil(5_000) { !fixture.state.transitioning }
        compose.mainClock.autoAdvance = true
        waitVisit("c")
        compose.runOnIdle {
            assertEquals(0f, fixture.state.motionProgress)
            if (transition != SlideshowTransition.SLIDE) assertFalse("video" in fixture.settledIds)
            fixture.advance(5_000)
        }
        compose.runOnIdle { assertEquals("c", fixture.state.asset?.id) } // no ready callback yet
        compose.runOnIdle { fixture.ready() }
        compose.waitUntil { fixture.state.ready }
        compose.runOnIdle { fixture.advance(4_999) }
        compose.runOnIdle { assertEquals("c", fixture.state.asset?.id) }
        compose.runOnIdle { fixture.advance(1) }
        waitVisit("a")
    }

    @Test fun mixedSourceSkipNeverVisitsVideoAndThumbnailUsesImageReadyDwell() {
        val mixed = listOf(viewerFixtureAsset("video", AssetType.VIDEO), viewerFixtureAsset("a"), viewerFixtureAsset("b"))
        var generation by mutableStateOf(0)
        fixture = ViewerOverlayUiFixture(SlideshowConfig(transition = SlideshowTransition.CUT, videoPolicy = SlideshowVideoPolicy.SKIP))
        compose.setContent { fixture.Content { key(generation) {
            StaticPhotoOverlay(mixed, 0, "", ::detail, getAssetStack = { Result.success(AssetStackLookup.NoStack) },
                onPersonClick = { _, _ -> }, onDismiss = {}, onCurrentAssetChanged = { fixture.settledIds += it })
        } } }
        start()
        waitVisit("a")
        compose.onNodeWithText("Next slide").performClick()
        waitVisit("b")
        compose.runOnIdle { assertTrue(fixture.media.keys.none { it.assetId == "video" }) }
        compose.onNodeWithText("Exit slideshow").performClick()
        compose.runOnIdle {
            fixture.preferences.setSlideshowConfig(fixture.repository.getSlideshowConfig().copy(videoPolicy = SlideshowVideoPolicy.THUMBNAIL))
            generation += 1
        }
        compose.waitForIdle()
        start()
        waitVisit("video")
        compose.onNodeWithText("Unmute video").assertDoesNotExist()
        compose.runOnIdle { fixture.ready(SlideshowMediaEvent.Progress(10_000)); fixture.advance(5_000) }
        compose.runOnIdle { assertFalse(fixture.state.ready); assertEquals("video", fixture.state.asset?.id) }
        compose.runOnIdle { fixture.ready() }
        compose.waitUntil { fixture.state.ready }
        compose.runOnIdle { fixture.advance(5_000) }
        waitVisit("a")
    }

    @Test fun videoControlsPersistMutePauseSameVisitAndCapEndRaceAdvancesOnce() {
        showVideos(limit = 2)
        start()
        waitVisit("video")
        compose.runOnIdle { assertTrue(fixture.state.playMedia); assertFalse(fixture.state.ready) }
        compose.onNodeWithText("Unmute video").performClick()
        compose.waitUntil { fixture.state.config?.muted == false && !fixture.repository.getSlideshowConfig().muted }
        val visit = fixture.state.request
        compose.onNodeWithText("Pause slideshow").performClick()
        compose.waitUntil { fixture.state.paused }
        compose.runOnIdle { fixture.advance(30_000); assertEquals(visit, fixture.state.request); assertFalse(fixture.state.playMedia) }
        compose.onNodeWithText("Resume slideshow").performClick()
        compose.waitUntil { fixture.state.playMedia }
        compose.runOnIdle { fixture.ready(SlideshowMediaEvent.Progress(1_000)) }
        compose.waitUntil { fixture.state.ready }
        compose.runOnIdle {
            val current = requireNotNull(fixture.state.request)
            val media = requireNotNull(fixture.media[current])
            media.onEvent(current, SlideshowMediaEvent.Progress(2_000))
            media.onEvent(current, SlideshowMediaEvent.Completed)
        }
        waitVisit("a")
        compose.runOnIdle { assertFalse("b" in fixture.settledIds) }
        compose.onNodeWithText("Exit slideshow").performClick()
        openOptions()
        compose.onNodeWithText("Mute videos").performScrollTo().assertIsOff()
        compose.runOnIdle { assertFalse(fixture.repository.getSlideshowConfig().muted) }
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun shortVideoEndWorksBeforeReadinessAndLaterStallHasVisibleRecovery() {
        showVideos(limit = null)
        start()
        waitVisit("video")
        compose.runOnIdle { fixture.ready(SlideshowMediaEvent.Completed) }
        waitVisit("a")
        compose.onNodeWithText("Previous slide").performClick()
        waitVisit("video")
        compose.runOnIdle { fixture.ready(SlideshowMediaEvent.Progress(1_000)) }
        compose.waitUntil { fixture.state.ready }
        compose.runOnIdle { fixture.advance(15_000) }
        compose.waitUntil { fixture.state.failure == SlideshowFailure.TIMEOUT }
        compose.onNodeWithText("This slide stopped responding.").assertIsDisplayed()
        compose.onNodeWithText("Retry slide").assertIsDisplayed()
        compose.runOnIdle { fixture.advance(2_000) }
        waitVisit("a")
    }

    private fun showVideos(limit: Int?) {
        val mixed = listOf(viewerFixtureAsset("video", AssetType.VIDEO), viewerFixtureAsset("a"), viewerFixtureAsset("b"))
        fixture = ViewerOverlayUiFixture(SlideshowConfig(transition = SlideshowTransition.CUT,
            videoPolicy = SlideshowVideoPolicy.PLAY, videoLimitSeconds = limit, muted = true))
        compose.setContent { fixture.Content {
            StaticPhotoOverlay(mixed, 0, "", ::detail, getAssetStack = { Result.success(AssetStackLookup.NoStack) },
                onPersonClick = { _, _ -> }, onDismiss = {}, onCurrentAssetChanged = { fixture.settledIds += it })
        } }
    }

    private fun show(config: SlideshowConfig = SlideshowConfig(transition = SlideshowTransition.CUT, motion = SlideshowMotion.STILL)) {
        fixture = ViewerOverlayUiFixture(config)
        compose.setContent { fixture.Content { Overlay() } }
    }

    @Composable private fun Overlay(detail: suspend (String) -> Result<AssetDetail> = ::detail) {
        if (visible) StaticPhotoOverlay(assets, 0, "", detail,
            getAssetStack = { Result.success(AssetStackLookup.NoStack) }, onPersonClick = { _, _ -> },
            onDismiss = { returned += it; visible = false }, onCurrentAssetChanged = { fixture.settledIds += it })
    }

    private fun waitText(text: String) { compose.waitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun openOptions() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Slideshow").performClick()
    }
    private fun start() { openOptions(); compose.onNodeWithText("Start").performClick() }
    private fun openStack() { compose.onNodeWithContentDescription("More options").performClick(); compose.onNodeWithText("View stack").performClick() }
    private fun press(key: Key) {
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().lastIndex].performKeyInput { pressKey(key) }
    }
    private fun waitVisit(id: String) {
        compose.waitUntil { fixture.bindings.isNotEmpty() && fixture.state.asset?.id == id && !fixture.state.transitioning && fixture.state.request in fixture.media.keys }
        compose.onNodeWithTag("media:$id:selected").assertIsDisplayed()
    }
    private suspend fun detail(id: String) = Result.success(viewerFixtureDetail(id))

    private fun hostJourney(target: PhotoOverlayReturnTarget, showIndicator: Boolean, expectedReturn: String) {
        val preparation = mutableListOf<String?>()
        fixture = ViewerOverlayUiFixture()
        compose.setContent { fixture.Content {
            PhotoOverlayHost(returnTarget = target, resolveInitialIndex = { id -> assets.indexOfFirst { it.id == id } },
                onPrepareOverlayDismiss = { preparation += it }, onOverlayDismissed = { returned += it },
                content = { _, _, _, open -> Box(Modifier.fillMaxSize()) { Button({ open("a") }) { Text("Open cover") } } },
                overlay = { initial, dismiss, changed, stl, shared ->
                    StaticPhotoOverlay(assets, initial, "", ::detail,
                        getAssetStack = { Result.success(AssetStackLookup.NoStack) }, showPageIndicator = showIndicator,
                        onPersonClick = { _, _ -> }, onDismiss = dismiss, onCurrentAssetChanged = changed,
                        onStlTransitionActiveChanged = stl, sharedTransitionScope = shared, animatedVisibilityScope = this)
                })
        } }
        compose.onNodeWithText("Open cover").performClick()
        compose.onNodeWithText("Show thumbnails").performClick()
        compose.onNodeWithContentDescription("Asset 3 of 3").performClick()
        compose.onNodeWithTag("media:c:selected").assertIsDisplayed()
        compose.onNodeWithText("Hide thumbnails").performClick()
        if (showIndicator) {
            compose.onAllNodesWithText("Asset 3 of 3").assertCountEquals(0)
            compose.onNodeWithContentDescription("Asset 3 of 3").assertExists()
        } else compose.onNodeWithText("Asset 3 of 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil { returned.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf<String?>(expectedReturn), preparation); assertEquals(preparation, returned) }
    }
}
