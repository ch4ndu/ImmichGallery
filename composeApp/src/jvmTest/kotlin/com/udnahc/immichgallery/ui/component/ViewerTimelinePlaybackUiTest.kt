package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.model.SlideshowMotion
import com.udnahc.immichgallery.domain.model.SlideshowOrder
import com.udnahc.immichgallery.domain.model.SlideshowSlot
import com.udnahc.immichgallery.domain.model.SlideshowTransition
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.timelineBucketDisplayLabel
import com.udnahc.immichgallery.ui.screen.timeline.TimelineOverlayState
import com.udnahc.immichgallery.ui.screen.timeline.TimelinePhotoOverlay
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalTestApi::class)
class ViewerTimelinePlaybackUiTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var fixture: ViewerOverlayUiFixture
    private val timeline = MutableStateFlow(TimelineOverlayState())
    private val assetsByBucket = mutableStateMapOf<String, List<Asset>>()
    private val bucketStatuses = mutableMapOf<String, SlideshowCandidateStatus>()
    private val revisions = mutableMapOf<String, Int>()
    private val bucketRequests = CopyOnWriteArrayList<String>()
    private val retryRequests = CopyOnWriteArrayList<String>()
    private val returns = CopyOnWriteArrayList<Pair<String?, String?>>()
    private var bucketCount = 0
    private var assetsPerBucket = 1
    private var visible by mutableStateOf(true)

    @After
    fun cleanup() {
        if (::fixture.isInitialized) fixture.dispose()
    }

    @Test
    fun lazyCurrentAndNextBucketsUseReadyDwellAndPausePreservesRemainingTimeAndHistory() {
        configureBuckets(count = 3)
        show(initialIndex = 1)
        compose.waitUntil { bucketKey(1) in bucketRequests }
        start()
        waitForPendingBucket(1)
        val unresolvedRequest = fixture.state.request
        advance(1_000)
        compose.runOnIdle {
            assertEquals(unresolvedRequest, fixture.state.request)
            assertFalse(fixture.state.ready)
            materializeBucket(1)
        }
        waitVisit(assetId(1))
        ready()
        advance(2_000)
        val pausedRequest = fixture.state.request
        compose.onNodeWithTag("media:${assetId(1)}:selected").performClick() // Startup plus dwell reached the control-hide interval.
        compose.waitUntil { compose.onAllNodesWithText("Pause slideshow").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pause slideshow").performClick()
        compose.waitUntil { fixture.state.paused }
        advance(20_000)
        compose.runOnIdle {
            assertEquals(pausedRequest, fixture.state.request)
            assertEquals(.4f, fixture.state.motionProgress)
            assertFalse(fixture.wakeHeld)
        }
        compose.onNodeWithText("Resume slideshow").performClick()
        compose.waitUntil { !fixture.state.paused }
        advance(2_999)
        compose.runOnIdle { assertEquals(pausedRequest, fixture.state.request) }
        advance(1)
        waitForPendingBucket(2)
        compose.runOnIdle {
            assertTrue(bucketKey(2) in bucketRequests)
            assertNull(assetsByBucket[bucketKey(2)])
            materializeBucket(2)
        }
        waitVisit(assetId(2))
        ready()
        compose.onNodeWithText("Previous slide").performClick()
        waitVisit(assetId(1))
        compose.onNodeWithText("Next slide").performClick()
        waitVisit(assetId(2))
        compose.onNodeWithText("Exit slideshow").performClick()
        compose.waitUntil { fixture.state.config == null }
        compose.onNodeWithText("Asset 3 of 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertEquals(listOf<Pair<String?, String?>>(assetId(2) to bucketKey(2)), returns.toList())
            assertEquals(setOf(bucketKey(1), bucketKey(2)), assetsByBucket.keys)
        }
    }

    @Test
    fun incrementalMaterializationKeepsTheRandomCycleCurrentVisitAndForwardHistory() {
        configureBuckets(count = 6)
        materializeBucket(0)
        show(config = slideshowConfig(order = SlideshowOrder.RANDOM, seconds = 60))
        start()
        waitVisit(assetId(0))
        ready()
        advance(500)
        val visited = mutableListOf(assetId(0))
        compose.runOnIdle { assertTrue(bucketRequests.toSet().size < bucketCount) }

        repeat(bucketCount - 1) {
            val previousRequest = fixture.state.request
            val previousProgress = fixture.state.motionProgress
            compose.runOnIdle {
                bucketRequests.toSet().filterNot(assetsByBucket::containsKey).forEach { key ->
                    materializeBucket((0 until bucketCount).single { bucketKey(it) == key })
                }
            }
            compose.waitUntil { fixture.state.upcomingAsset != null }
            compose.runOnIdle {
                assertEquals(previousRequest, fixture.state.request)
                assertEquals(previousProgress, fixture.state.motionProgress)
                assertTrue(fixture.state.ready)
            }
            val reservedAsset = requireNotNull(fixture.state.upcomingAsset).id
            compose.onNodeWithText("Next slide").performClick()
            waitVisit(reservedAsset)
            visited.add(reservedAsset)
            ready()
            advance(500)
        }

        compose.runOnIdle {
            assertEquals(bucketCount, visited.distinct().size)
            assertEquals((0 until bucketCount).map(::assetId).toSet(), visited.toSet())
        }
        compose.onNodeWithText("Previous slide").performClick()
        waitVisit(visited[visited.lastIndex - 1])
        compose.onNodeWithText("Next slide").performClick()
        waitVisit(visited.last())
        compose.onNodeWithText("Next slide").performClick()
        compose.waitUntil { fixture.state.asset?.id != visited.last() && !fixture.state.transitioning }
        compose.runOnIdle { assertNotEquals(visited.last(), fixture.state.asset?.id) }
    }

    @Test
    fun timelineFilmstripKeepsProjectedCountBoundsLazyDemandAndReturnsSelectedAssetAndBucket() {
        configureBuckets(count = 100, perBucket = 10)
        materializeBucket(0)
        show()
        compose.onNodeWithText("Asset 1 of 1000").assertIsDisplayed()
        compose.onNodeWithTag("media:${assetId(0)}:selected").performClick()
        compose.onNodeWithText("Show thumbnails").assertDoesNotExist()
        compose.onNodeWithText("Asset 1 of 1000").assertDoesNotExist()
        compose.onNodeWithTag("media:${assetId(0)}:selected").performClick()
        compose.onNodeWithText("Show thumbnails").assertIsDisplayed()
        compose.onNodeWithText("Show thumbnails").performClick()
        compose.onNodeWithContentDescription("Asset 1 of 1000").assertIsSelected()

        // Accepted chrome policy: hiding chrome retains the user's expanded state.
        compose.onNodeWithTag("media:${assetId(0)}:selected").performClick()
        compose.onNodeWithText("Hide thumbnails").assertDoesNotExist()
        compose.onNodeWithText("Asset 1 of 1000").assertDoesNotExist()
        compose.onNodeWithTag("media:${assetId(0)}:selected").performClick()
        compose.onNodeWithText("Hide thumbnails").assertIsDisplayed()
        compose.onNodeWithContentDescription("Asset 1 of 1000").assertIsSelected()
        val originalDemand = bucketRequests.toSet()

        compose.onNode(
            hasScrollToIndexAction() and hasAnyDescendant(hasContentDescription("Asset 1 of 1000"))
        ).performScrollToIndex(700)
        compose.waitUntil { bucketKey(70) in bucketRequests }
        compose.onNodeWithContentDescription("Asset 703 of 1000. Preview not loaded.").performClick()
        compose.onNodeWithText("Asset 703 of 1000").assertIsDisplayed()
        compose.runOnIdle { materializeBucket(70) }
        compose.waitUntil { fixture.settledIds.lastOrNull() == assetId(702) }
        compose.onNodeWithTag("media:${assetId(702)}:selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Asset 703 of 1000").assertIsSelected()
        compose.runOnIdle {
            val nearbyBuckets = (69..71).map(::bucketKey).toSet()
            val newDemand = bucketRequests.toSet() - originalDemand
            assertTrue(newDemand.isNotEmpty())
            assertTrue(newDemand.all { it in nearbyBuckets }, "Unexpected lazy demand: $newDemand")
            assertTrue(newDemand.size <= 3)
            assertEquals(20, timeline.value.pageIndex.loadedAssetPageIndexes.size)
            assertEquals(1_000, timeline.value.pageIndex.totalPages)
        }
        compose.onNodeWithText("Hide thumbnails").performClick()
        compose.onNodeWithText("Asset 703 of 1000").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertEquals(listOf<Pair<String?, String?>>(assetId(702) to bucketKey(70)), returns.toList())
        }
    }

    @Test
    fun failedLazyBucketRetryUsesTheExplicitRetryPathAndRecoversTheSameSlot() {
        configureBuckets(count = 3)
        bucketStatuses[bucketKey(1)] = SlideshowCandidateStatus.FAILED
        publishProjection()
        show(initialIndex = 1)
        start()
        compose.waitUntil { fixture.state.failure == SlideshowFailure.BUCKET }
        compose.onNodeWithText("These photos could not be loaded. Retry or choose another slide.")
            .assertIsDisplayed()
        val failedRequest = fixture.state.request
        compose.onNodeWithText("Retry slide").performClick()
        compose.waitUntil { retryRequests.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf(bucketKey(1)), retryRequests)
            bucketStatuses[bucketKey(1)] = SlideshowCandidateStatus.LOADING
            publishProjection()
        }
        compose.waitUntil { fixture.state.request != failedRequest && fixture.state.failure == null }
        compose.runOnIdle { materializeBucket(1) }
        waitVisit(assetId(1))
        ready()
        compose.runOnIdle {
            assertEquals(SlideshowSlot.BucketOffset(bucketKey(1), 0), fixture.state.request?.slot)
            assertNull(fixture.state.failure)
            assertTrue(fixture.state.ready)
            assertEquals(listOf(bucketKey(1)), retryRequests)
        }
        compose.onNodeWithText("Retry slide").assertDoesNotExist()
    }

    private fun show(initialIndex: Int = 0, config: SlideshowConfig = slideshowConfig()) {
        fixture = ViewerOverlayUiFixture(config, fullscreenSupported = false)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            fixture.Content {
                if (visible) {
                    Box(Modifier.width(480.dp).height(640.dp)) {
                        TimelinePhotoOverlay(
                            timelineState = timeline,
                            initialIndex = initialIndex,
                            apiKey = "fixture-key",
                            getAssetFileName = { _, fallback -> Result.success(fallback) },
                            getAssetDetail = { Result.success(viewerFixtureDetail(it)) },
                            getAssetStack = { Result.success(AssetStackLookup.NoStack) },
                            assetProvider = { assetsByBucket[it] },
                            onBucketNeeded = { bucketRequests.add(it) },
                            onRetryBucket = { retryRequests.add(it) },
                            onPersonClick = { _, _ -> },
                            onDismiss = { id, bucket ->
                                returns.add(id to bucket)
                                visible = false
                            },
                            onCurrentAssetChanged = { fixture.settledIds.add(it) }
                        )
                    }
                }
            }
        }
        compose.waitUntil { fixture.bindings.isNotEmpty() }
    }

    private fun start() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Slideshow").performClick()
        compose.onNodeWithText("Start").performClick()
        compose.waitUntil { fixture.state.config != null }
    }

    private fun waitVisit(id: String) {
        compose.waitUntil {
            fixture.state.asset?.id == id && !fixture.state.transitioning &&
                fixture.state.request?.let(fixture.media::containsKey) == true
        }
        compose.onNodeWithTag("media:$id:selected").assertIsDisplayed()
    }

    private fun waitForPendingBucket(index: Int) {
        compose.waitUntil {
            fixture.state.request?.slot == SlideshowSlot.BucketOffset(bucketKey(index), 0) &&
                fixture.state.asset == null && !fixture.state.transitioning
        }
    }

    private fun ready() {
        compose.runOnIdle { fixture.ready() }
        compose.waitUntil { fixture.state.ready }
    }

    private fun advance(millis: Long) {
        compose.runOnIdle { fixture.advance(millis) }
        compose.waitForIdle()
    }

    private fun configureBuckets(count: Int, perBucket: Int = 1) {
        bucketCount = count
        assetsPerBucket = perBucket
        publishProjection()
    }

    private fun materializeBucket(index: Int) {
        val key = bucketKey(index)
        assetsByBucket[key] = List(assetsPerBucket) { offset ->
            viewerFixtureAsset(assetId(index * assetsPerBucket + offset))
        }
        bucketStatuses[key] = SlideshowCandidateStatus.MATERIALIZED
        revisions[key] = (revisions[key] ?: 0) + 1
        publishProjection()
    }

    private fun publishProjection() {
        val loadedIndexes = buildMap {
            repeat(bucketCount) { bucket ->
                assetsByBucket[bucketKey(bucket)]?.forEachIndexed { offset, asset ->
                    put(asset.id, bucket * assetsPerBucket + offset)
                }
            }
        }
        timeline.value = TimelineOverlayState(
            pageIndex = TimelinePageIndex(
                bucketStartPages = List(bucketCount) { it * assetsPerBucket },
                bucketPageCounts = List(bucketCount) { assetsPerBucket },
                totalPages = bucketCount * assetsPerBucket,
                loadedAssetPageIndexes = loadedIndexes
            ),
            buckets = List(bucketCount) { index ->
                val key = bucketKey(index)
                TimelineBucket(timelineBucketDisplayLabel(key), key, assetsPerBucket)
            },
            bucketStatuses = bucketStatuses.toMap(),
            assetRevisions = revisions.toMap(),
            materializationRevisions = revisions.toMap()
        )
    }

    private fun bucketKey(index: Int): String =
        "${2026 - index / 12}-${(12 - index % 12).toString().padStart(2, '0')}"

    private fun assetId(page: Int) = "timeline-$page"

    private fun slideshowConfig(order: SlideshowOrder = SlideshowOrder.SEQUENTIAL, seconds: Int = 5) =
        SlideshowConfig(order = order, durationSeconds = seconds, motion = SlideshowMotion.STILL,
            transition = SlideshowTransition.CUT)
}
