package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import com.udnahc.immichgallery.ui.navigation.TopBarOverlay
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.AssetGroupDisplayLabel
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineDateTarget
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineDateNavigationUiTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var listState: LazyListState
    private var cancelNavigation: () -> Unit = {}
    private var state by mutableStateOf(timelineState(exact = false))
    private val targetedBuckets = mutableListOf<Int>()
    private var navigationMounted by mutableStateOf(true)
    private var dateActions by mutableStateOf<TimelineDateNavigationActions?>(null)
    private val updateActions: (TimelineDateNavigationActions?) -> Unit = { dateActions = it }
    private val externalDate = LocalDate(2024, 2, 17)
    private val externalAcceptances = mutableListOf<Boolean>()

    private fun show(
        externalControls: Boolean = false,
        resolve: suspend (LocalDate) -> Result<TimelineDateTarget?>
    ) {
        compose.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                val reconciler = remember { TimelineScrollAnchorReconciler() }
                if (navigationMounted) {
                    cancelNavigation = TimelineDateNavigation(
                        state, listState, reconciler, updateActions, resolve,
                        { 1900..2100 },
                        { bucket, _ -> targetedBuckets.add(bucket) }
                    )
                }
                Column {
                    TopBarOverlay(
                        title = "Timeline",
                        barColor = MaterialTheme.colorScheme.background,
                        isServerOnline = true,
                        isSyncing = false,
                        showRefresh = false,
                        onRefresh = {},
                        onLogout = {},
                        logoutEnabled = true,
                        dateNavigationActions = dateActions
                    )
                    if (externalControls) {
                        Button(
                            enabled = dateActions != null,
                            onClick = { externalAcceptances.add(dateActions?.showDate(externalDate) == true) }
                        ) { Text("External date request") }
                        Button(onClick = { dateActions?.cancel() }) { Text("Cancel external date") }
                    }
                    LazyColumn(state = listState) {
                        items(100) { index ->
                            Box(Modifier.height(80.dp)) { Text("Photo row $index") }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun calendarCancelDoesNotResolveOrMoveTheTimeline() {
        var requested = false
        show { requested = true; Result.success(TimelineDateTarget("2024-02", "target")) }
        openMenuItem("Jump to date")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertFalse(requested)
            assertEquals(0, listState.firstVisibleItemIndex)
        }
    }

    @Test
    fun confirmingCalendarRevealsBucketThenExactPhotoAfterLayoutPublication() {
        val dates = mutableListOf<LocalDate>()
        show { date ->
            dates.add(date)
            Result.success(TimelineDateTarget("2024-02", "target"))
        }
        openMenuItem("Jump to date")
        val snapshot = compose.onNode(isDialog()).captureToImage().toAwtImage()
        val report = File("build/reports/timeline-date-navigation/calendar.png")
        report.parentFile.mkdirs()
        ImageIO.write(snapshot, "png", report)
        // Calendar day semantics expose a full date rather than the drawn day number.
        compose.onNode(hasText(" 1,", substring = true) and hasClickAction()).performClick()
        compose.onNode(hasText("Jump to date") and hasClickAction()).performClick()
        compose.waitUntil { targetedBuckets.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(1, dates.size)
            assertEquals(1, dates.single().day)
            assertEquals(1, targetedBuckets.last())
            assertTrue(listState.firstVisibleItemIndex >= 49)
            state = timelineState(exact = true)
        }
        compose.waitUntil { listState.layoutInfo.visibleItemsInfo.any { it.index == 75 } }
        compose.runOnIdle {
            assertTrue(listState.firstVisibleItemIndex >= 74)
        }
    }

    @Test
    fun explicitUserInteractionCancelsPendingResolution() {
        val result = CompletableDeferred<Result<TimelineDateTarget?>>()
        var started = false
        show { started = true; result.await() }
        openMenuItem("Back to today")
        compose.waitUntil { started }
        compose.runOnIdle { cancelNavigation() }
        compose.waitForIdle()
        result.complete(Result.success(TimelineDateTarget("2024-02", "target")))
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(targetedBuckets.isEmpty())
            assertEquals(0, listState.firstVisibleItemIndex)
        }
    }

    @Test
    fun cancellingAfterBucketJumpPreventsDelayedLayoutFromPullingUserBack() {
        show { Result.success(TimelineDateTarget("2024-02", "target")) }
        openMenuItem("Back to today")
        compose.waitUntil { targetedBuckets.isNotEmpty() }
        compose.runOnIdle {
            cancelNavigation()
            listState.requestScrollToItem(10)
        }
        compose.waitForIdle()
        compose.runOnIdle { state = timelineState(exact = true) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(10, listState.firstVisibleItemIndex) }
    }

    @Test
    fun repeatedTodaySupersedesThePreviousRequestEvenForTheSameDate() {
        val first = CompletableDeferred<Result<TimelineDateTarget?>>()
        var calls = 0
        show {
            calls++
            if (calls == 1) first.await()
            else Result.success(TimelineDateTarget("2024-02", null))
        }
        openMenuItem("Back to today")
        compose.waitUntil { calls == 1 }
        openMenuItem("Back to today")
        compose.waitUntil { targetedBuckets.isNotEmpty() }
        first.complete(Result.success(TimelineDateTarget("2024-03", "old")))
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, calls)
            assertTrue(targetedBuckets.all { it == 1 })
        }
    }

    @Test
    fun emptyTimelineDoesNotOfferDateNavigation() {
        state = TimelineState()
        show { Result.success(null) }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Jump to date").assertDoesNotExist()
        compose.onNodeWithText("Back to today").assertDoesNotExist()
    }

    @Test
    fun externalDateRevealsBucketThenExactPhotoAfterProjectionArrives() {
        val dates = mutableListOf<LocalDate>()
        show(externalControls = true) { date ->
            dates.add(date)
            Result.success(TimelineDateTarget("2024-02", "target"))
        }

        compose.onNodeWithText("External date request").performClick()
        compose.waitUntil { targetedBuckets.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf(externalDate), dates)
            assertEquals(listOf(true), externalAcceptances)
            assertEquals(1, targetedBuckets.last())
            assertTrue(listState.firstVisibleItemIndex >= 49)
            state = timelineState(exact = true)
        }
        compose.waitUntil { listState.layoutInfo.visibleItemsInfo.any { it.index == 75 } }
        compose.runOnIdle { assertTrue(listState.firstVisibleItemIndex >= 74) }
    }

    @Test
    fun repeatedExternalSameDateSupersedesDeferredFirstResolver() {
        val first = CompletableDeferred<Result<TimelineDateTarget?>>()
        val dates = mutableListOf<LocalDate>()
        show(externalControls = true) { date ->
            dates.add(date)
            if (dates.size == 1) first.await()
            else Result.success(TimelineDateTarget("2024-02", null))
        }

        compose.onNodeWithText("External date request").performClick()
        compose.waitUntil { dates.size == 1 }
        compose.onNodeWithText("External date request").performClick()
        compose.waitUntil { targetedBuckets.isNotEmpty() }
        first.complete(Result.success(TimelineDateTarget("2024-03", "old")))
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(listOf(externalDate, externalDate), dates)
            assertEquals(listOf(true, true), externalAcceptances)
            assertTrue(targetedBuckets.all { it == 1 })
        }
    }

    @Test
    fun loadingThenReadyEmptyRegistersExternalActionAndShowsFailureWithoutDateMenu() {
        state = TimelineState(isLoading = true)
        var resolverCalls = 0
        show(externalControls = true) { resolverCalls++; Result.success(null) }
        compose.onNodeWithText("External date request").assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(dateActions)
            state = TimelineState(isSourceReady = true)
        }
        compose.waitUntil { dateActions != null }
        compose.onNodeWithText("External date request").performClick()
        compose.onNodeWithText(
            "Could not find a cached date to jump to. Refresh the Timeline and try again."
        ).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, resolverCalls)
            assertEquals(listOf(true), externalAcceptances)
            assertFalse(assertNotNull(dateActions).canOpenDateMenu)
            assertTrue(targetedBuckets.isEmpty())
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Jump to date").assertDoesNotExist()
        compose.onNodeWithText("Back to today").assertDoesNotExist()
    }

    @Test
    fun externalCancellationAfterBucketJumpRejectsLaterExactProjection() {
        show(externalControls = true) { Result.success(TimelineDateTarget("2024-02", "target")) }
        compose.onNodeWithText("External date request").performClick()
        compose.waitUntil { targetedBuckets.isNotEmpty() }

        compose.onNodeWithText("Cancel external date").performClick()
        compose.runOnIdle { listState.requestScrollToItem(10) }
        compose.waitForIdle()
        compose.runOnIdle { state = timelineState(exact = true) }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(10, listState.firstVisibleItemIndex) }
    }

    @Test
    fun disposalRejectsCapturedExternalActionsAndDeferredResult() {
        val result = CompletableDeferred<Result<TimelineDateTarget?>>()
        var resolverStarted = false
        show(externalControls = true) { resolverStarted = true; result.await() }
        val oldActions = compose.runOnIdle { assertNotNull(dateActions) }
        compose.onNodeWithText("External date request").performClick()
        compose.waitUntil { resolverStarted }

        compose.runOnIdle { navigationMounted = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(dateActions)
            assertFalse(oldActions.showDate(externalDate))
        }
        result.complete(Result.success(TimelineDateTarget("2024-02", "target")))
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(targetedBuckets.isEmpty())
            assertEquals(0, listState.firstVisibleItemIndex)
        }
    }

    private fun openMenuItem(label: String) {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText(label).performClick()
    }

    private fun timelineState(exact: Boolean) = TimelineState(
        buckets = listOf(
            TimelineBucket(AssetGroupDisplayLabel.Month(2024, 3), "2024-03", 50),
            TimelineBucket(AssetGroupDisplayLabel.Month(2024, 2), "2024-02", 50)
        ),
        pageIndex = TimelinePageIndex(listOf(0, 50), listOf(50, 50), 100),
        displayIndex = TimelineDisplayIndex(
            bucketByDisplayIndex = List(100) { if (it < 50) 0 else 1 },
            displayIndexesByBucket = mapOf(0 to (0..49).toList(), 1 to (50..99).toList()),
            assetDisplayIndexById = if (exact) mapOf("target" to 75) else emptyMap(),
            gridKeyByDisplayIndex = List(100) { "row_$it" }
        )
    )
}
