package com.udnahc.immichgallery.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.udnahc.immichgallery.domain.model.AssetGroupDisplayLabel
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineDateTarget
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.ui.screen.timeline.TimelineDateNavigation
import com.udnahc.immichgallery.ui.screen.timeline.TimelineDateNavigationActions
import com.udnahc.immichgallery.ui.screen.timeline.TimelineScrollAnchorReconciler
import com.udnahc.immichgallery.ui.screen.timeline.TimelineState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerTimelineDateRoutingUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val requests = ViewerTimelineDateRequests(initiallyActive = true)
    private val date = LocalDate(2024, 2, 17)
    private lateinit var root: NavHostController
    private lateinit var tabs: NavHostController
    private var detailRoute: Any = AlbumDetailRoute("fixture-album")
    private var actionsReady by mutableStateOf(true)
    private var viewerActive by mutableStateOf(false)
    private var loggingOut by mutableStateOf(false)
    private var actions by mutableStateOf<TimelineDateNavigationActions?>(null)
    private val updateActions: (TimelineDateNavigationActions?) -> Unit = { actions = it }
    private val resolvedDates = mutableListOf<LocalDate>()
    private val handledIds = mutableListOf<Long>()
    private val targetedBuckets = mutableListOf<Int>()
    private var capturedDetailDateAction: () -> Unit = {}
    private var resolver: suspend (LocalDate) -> Result<TimelineDateTarget?> = {
        Result.success(TimelineDateTarget("2024-02", null))
    }

    @Test
    fun allDetailRoutesReturnToExistingMainAndTimelineAndPreserveExploreState() {
        show()
        val originalMainId = compose.runOnIdle { root.getBackStackEntry(MainRoute).id }
        val originalTimelineId = compose.runOnIdle { tabs.getBackStackEntry(TimelineRoute).id }
        compose.onNodeWithText("Explore tab").performClick()
        compose.onNodeWithText("Increment Explore").performClick()
        compose.onNodeWithText("Explore count 1").assertIsDisplayed()

        val routes = listOf(
            AlbumDetailRoute("fixture-album"),
            PersonDetailRoute("fixture-person", "Fixture"),
            FavoritesRoute,
            MemoriesRoute
        )
        routes.forEachIndexed { index, route ->
            compose.runOnIdle { detailRoute = route }
            compose.onNodeWithText("Open detail").performClick()
            compose.onNodeWithText("Show detail date in Timeline").performClick()
            compose.waitUntil { resolvedDates.size == index + 1 }
            compose.runOnIdle {
                assertEquals(originalMainId, root.getBackStackEntry(MainRoute).id)
                assertEquals(originalTimelineId, tabs.getBackStackEntry(TimelineRoute).id)
                assertTrue(root.currentDestination?.hasRoute(MainRoute::class) == true)
                assertTrue(tabs.currentDestination?.hasRoute(TimelineRoute::class) == true)
                assertNull(root.previousBackStackEntry)
                assertNull(tabs.previousBackStackEntry)
                assertNull(requests.pending)
            }
            compose.onNodeWithText("Explore tab").performClick()
            compose.onNodeWithText("Explore count 1").assertIsDisplayed()
        }
    }

    @Test
    fun pendingDateWaitsForLateActionsAndIsNotRedeliveredAfterReregistration() {
        actionsReady = false
        show()
        compose.onNodeWithText("Explore tab").performClick()
        compose.onNodeWithText("Submit date").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(tabs.currentDestination?.hasRoute(TimelineRoute::class) == true)
            assertNotNull(requests.pending)
            assertTrue(resolvedDates.isEmpty())
            actionsReady = true
        }
        compose.waitUntil { resolvedDates.size == 1 }
        compose.runOnIdle {
            assertNull(requests.pending)
            assertEquals(1, handledIds.size)
            actionsReady = false
        }
        compose.waitForIdle()
        compose.runOnIdle { actionsReady = true; viewerActive = true }
        compose.waitForIdle()
        compose.runOnIdle { viewerActive = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(date), resolvedDates)
            assertEquals(1, handledIds.size)
        }
    }

    @Test
    fun sameDateReplacementKeepsLatestIdAndLaterSameDateWorksAgain() {
        actionsReady = false
        show()
        compose.onNodeWithText("Submit date").performClick()
        val firstId = compose.runOnIdle { assertNotNull(requests.pending).id }
        compose.onNodeWithText("Submit date").performClick()
        val secondId = compose.runOnIdle { assertNotNull(requests.pending).id }
        compose.runOnIdle {
            assertNotEquals(firstId, secondId)
            requests.acknowledge(firstId)
            assertEquals(secondId, assertNotNull(requests.pending).id)
            actionsReady = true
        }
        compose.waitUntil { resolvedDates.size == 1 }
        compose.onNodeWithText("Submit date").performClick()
        compose.waitUntil { resolvedDates.size == 2 }

        compose.runOnIdle {
            assertEquals(listOf(date, date), resolvedDates)
            assertEquals(secondId, handledIds.first())
            assertNotEquals(handledIds.first(), handledIds.last())
            assertNull(requests.pending)
        }
    }

    @Test
    fun sessionEndBeforeRegistrationClearsPendingAndRejectsCapturedDetailCallback() {
        actionsReady = false
        show()
        compose.onNodeWithText("Open detail").performClick()
        val oldDateAction = compose.runOnIdle { capturedDetailDateAction }
        compose.onNodeWithText("Show detail date in Timeline").performClick()
        compose.runOnIdle { assertNotNull(requests.pending) }

        compose.onNodeWithText("End session").performClick()
        compose.runOnIdle { actionsReady = true; oldDateAction() }
        compose.waitForIdle()

        compose.runOnIdle {
            assertNull(requests.pending)
            assertTrue(resolvedDates.isEmpty())
            assertTrue(handledIds.isEmpty())
            assertTrue(root.currentDestination?.hasRoute(MainRoute::class) == true)
        }
    }

    @Test
    fun sessionEndAfterAcceptanceCancelsDeferredTimelineResolution() {
        val result = CompletableDeferred<Result<TimelineDateTarget?>>()
        resolver = { result.await() }
        show()
        compose.onNodeWithText("Submit date").performClick()
        compose.waitUntil { resolvedDates.size == 1 }
        compose.runOnIdle {
            assertNull(requests.pending)
            assertEquals(1, handledIds.size)
        }

        compose.onNodeWithText("End session").performClick()
        result.complete(Result.success(TimelineDateTarget("2024-02", null)))
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(targetedBuckets.isEmpty())
            assertFalse(requests.isCurrent(handledIds.single()))
        }
    }

    private fun show() {
        compose.setContent {
            val windowOwner = remember { FixtureWindowOwner() }
            DisposableEffect(windowOwner) {
                onDispose {
                    windowOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
                    windowOwner.viewModelStore.clear()
                }
            }
            CompositionLocalProvider(
                LocalLifecycleOwner provides windowOwner,
                LocalViewModelStoreOwner provides windowOwner
            ) {
                MaterialTheme {
                    val rootController = rememberNavController()
                    SideEffect { root = rootController }
                    NavHost(
                        navController = rootController,
                        startDestination = MainRoute,
                        modifier = Modifier.size(640.dp),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        composable<MainRoute> { MainFixture(rootController) }
                        composable<AlbumDetailRoute> { DetailFixture(rootController) }
                        composable<PersonDetailRoute> { DetailFixture(rootController) }
                        composable<FavoritesRoute> { DetailFixture(rootController) }
                        composable<MemoriesRoute> { DetailFixture(rootController) }
                    }
                }
            }
        }
    }

    /** The root host has a window lifetime; nested hosts still inherit their parent entry. */
    private class FixtureWindowOwner : LifecycleOwner, ViewModelStoreOwner {
        override val lifecycle = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val viewModelStore = ViewModelStore()
    }

    @Composable
    private fun MainFixture(rootController: NavHostController) {
        val tabController = rememberNavController()
        val tabEntry by tabController.currentBackStackEntryAsState()
        SideEffect { tabs = tabController }
        ViewerTimelineDateRequestEffects(
            navController = tabController,
            request = requests.pending,
            timelineSelected = tabEntry?.destination?.hasRoute(TimelineRoute::class) == true,
            viewerActive = viewerActive,
            loggingOut = loggingOut,
            actions = actions,
            isCurrent = requests::isCurrent,
            onHandled = { id -> handledIds.add(id); requests.acknowledge(id) }
        )
        val epoch = requests.sessionEpoch
        // Desktop test pointer injection uses the test thread; real window callbacks use the UI thread.
        Column {
            Row {
                Button(onClick = {
                    compose.runOnUiThread { tabController.selectMainTab(ExploreRoute) }
                }) { Text("Explore tab") }
                Button(onClick = {
                    compose.runOnUiThread { rootController.navigate(detailRoute) }
                }) { Text("Open detail") }
            }
            Row {
                Button(onClick = {
                    compose.runOnUiThread {
                        showViewerDateInTimeline(rootController, requests, epoch, date)
                    }
                }) {
                    Text("Submit date")
                }
                Button(onClick = {
                    compose.runOnUiThread {
                        actions?.cancel()
                        requests.endSession()
                        loggingOut = true
                    }
                }) { Text("End session") }
            }
            NavHost(
                navController = tabController,
                startDestination = TimelineRoute,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable<TimelineRoute> { TimelineFixture() }
                composable<ExploreRoute> {
                    var count by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Text("Explore count $count")
                        Button(onClick = { count++ }) { Text("Increment Explore") }
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailFixture(rootController: NavHostController) {
        val epoch = requests.sessionEpoch
        val showDate = {
            compose.runOnUiThread {
                showViewerDateInTimeline(rootController, requests, epoch, date)
            }
        }
        SideEffect { capturedDetailDateAction = showDate }
        Button(onClick = showDate) { Text("Show detail date in Timeline") }
    }

    @Composable
    private fun TimelineFixture() {
        val listState = rememberLazyListState()
        val reconciler = remember { TimelineScrollAnchorReconciler() }
        val timelineState = remember {
            TimelineState(
                isSourceReady = true,
                buckets = listOf(
                    TimelineBucket(AssetGroupDisplayLabel.Month(2024, 3), "2024-03", 20),
                    TimelineBucket(AssetGroupDisplayLabel.Month(2024, 2), "2024-02", 20)
                ),
                pageIndex = TimelinePageIndex(listOf(0, 20), listOf(20, 20), 40),
                displayIndex = TimelineDisplayIndex(
                    bucketByDisplayIndex = List(40) { if (it < 20) 0 else 1 },
                    displayIndexesByBucket = mapOf(0 to (0..19).toList(), 1 to (20..39).toList()),
                    gridKeyByDisplayIndex = List(40) { "date-row-$it" }
                )
            )
        }
        if (actionsReady) {
            TimelineDateNavigation(
                state = timelineState,
                listState = listState,
                scrollAnchorReconciler = reconciler,
                onActionsChanged = updateActions,
                resolveTarget = { requested -> resolvedDates.add(requested); resolver(requested) },
                yearRange = { 1900..2100 },
                onBucketTargeted = { bucket, _ -> targetedBuckets.add(bucket) }
            )
        }
        LazyColumn(state = listState) {
            items(40) { index ->
                Box(Modifier.height(48.dp)) { Text("Timeline row $index") }
            }
        }
    }
}
