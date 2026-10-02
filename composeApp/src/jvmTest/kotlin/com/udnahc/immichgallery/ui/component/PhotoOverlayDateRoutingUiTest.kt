package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.ui.util.PHOTO_TRANSITION_DURATION_MS
import com.udnahc.immichgallery.ui.util.PhotoOverlayReturnTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalSharedTransitionApi::class)
class PhotoOverlayDateRoutingUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var mounted by mutableStateOf(true)
    private var currentAssetId by mutableStateOf("cover")
    private val clickedDate = LocalDate(2020, 4, 17)
    private val preparedIds = mutableListOf<String?>()
    private val dismissedIds = mutableListOf<String?>()
    private val routedDates = mutableListOf<LocalDate>()
    private val events = mutableListOf<String>()
    private var preparation: CompletableDeferred<Unit>? = null

    @Test
    fun clickWaitsForReturnPreparationAndActualExitAndRoutesOnce() {
        preparation = CompletableDeferred()
        show()
        openCover()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithText("Show date in Timeline").performClick()
        compose.onNodeWithText("Show date in Timeline").performClick()
        compose.mainClock.advanceTimeBy(PHOTO_TRANSITION_DURATION_MS * 2L)
        compose.runOnIdle {
            assertEquals(listOf<String?>("cover"), preparedIds)
            assertTrue(dismissedIds.isEmpty())
            assertTrue(routedDates.isEmpty())
        }
        compose.onNodeWithTag(VIEWER_TAG).assertExists()

        compose.runOnIdle { preparation?.complete(Unit) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(PHOTO_TRANSITION_DURATION_MS / 2L)
        compose.runOnIdle {
            assertEquals(listOf<String?>("cover"), dismissedIds)
            assertTrue(routedDates.isEmpty())
        }
        compose.onNodeWithTag(VIEWER_TAG).assertExists()

        finishExit()
        compose.onNodeWithTag(VIEWER_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(clickedDate), routedDates)
            assertEquals(listOf("prepare:cover", "dismiss:cover", "route"), events)
        }
        finishExit()
        compose.runOnIdle { assertEquals(1, routedDates.size) }
    }

    @Test
    fun reopeningBeforeExitSettlesSuppressesPreviousDateRoute() {
        show()
        openCover()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Show date in Timeline").performClick()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Open replacement").performClick()
        finishExit()

        compose.onNodeWithText("Viewing replacement").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf<String?>("cover"), dismissedIds)
            assertTrue(routedDates.isEmpty())
        }
    }

    @Test
    fun disposingHostDuringDeferredPreparationRejectsLateCompletion() {
        preparation = CompletableDeferred()
        show()
        openCover()
        compose.onNodeWithText("Show date in Timeline").performClick()
        compose.runOnIdle {
            assertEquals(1, preparedIds.size)
            mounted = false
        }
        compose.waitForIdle()
        compose.runOnIdle { preparation?.complete(Unit) }
        compose.waitForIdle()

        compose.onNodeWithTag(VIEWER_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(dismissedIds.isEmpty())
            assertTrue(routedDates.isEmpty())
        }
    }

    @Test
    fun disposingHostDuringExitCancelsItsRoutingCompletion() {
        show()
        openCover()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Show date in Timeline").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { mounted = false }

        finishExit()

        compose.runOnIdle {
            assertEquals(listOf<String?>("cover"), dismissedIds)
            assertTrue(routedDates.isEmpty())
        }
    }

    @Test
    fun originReturnKeepsCoverWhileRoutingTheClickedNonCoverDate() {
        show(returnTarget = PhotoOverlayReturnTarget.ORIGIN_ASSET)
        openCover()
        compose.onNodeWithText("View non-cover").performClick()
        compose.onNodeWithText("Viewing non-cover").assertIsDisplayed()
        compose.onNodeWithText("Hidden cover").assertExists()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithText("Show date in Timeline").performClick()
        finishExit()

        compose.runOnIdle {
            assertEquals(listOf<String?>("cover"), preparedIds)
            assertEquals(listOf<String?>("cover"), dismissedIds)
            assertEquals(listOf(clickedDate), routedDates)
        }
        compose.onNodeWithTag(VIEWER_TAG).assertDoesNotExist()
    }

    private fun openCover() {
        compose.onNodeWithText("Open cover").performClick()
        compose.onNodeWithTag(VIEWER_TAG).assertIsDisplayed()
        compose.waitForIdle()
    }

    private fun finishExit() {
        compose.mainClock.advanceTimeBy(PHOTO_TRANSITION_DURATION_MS * 2L)
        compose.waitForIdle()
    }

    private fun show(returnTarget: PhotoOverlayReturnTarget = PhotoOverlayReturnTarget.CURRENT_ASSET) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(480.dp)) {
                    if (mounted) {
                        PhotoOverlayHost(
                            returnTarget = returnTarget,
                            onPrepareOverlayDismiss = { assetId ->
                                preparedIds.add(assetId)
                                events.add("prepare:$assetId")
                                preparation?.await()
                            },
                            onOverlayDismissed = { assetId ->
                                dismissedIds.add(assetId)
                                events.add("dismiss:$assetId")
                            },
                            resolveInitialIndex = { 0 },
                            content = { _, _, hiddenAssetId, onPhotoClick ->
                                Box(Modifier.fillMaxSize()) {
                                    Text("Hidden $hiddenAssetId", Modifier.align(Alignment.Center))
                                    Column(Modifier.align(Alignment.BottomCenter)) {
                                        Button(onClick = {
                                            currentAssetId = "cover"
                                            onPhotoClick("cover")
                                        }) { Text("Open cover") }
                                        Button(onClick = {
                                            currentAssetId = "replacement"
                                            onPhotoClick("replacement")
                                        }) { Text("Open replacement") }
                                    }
                                }
                            },
                            overlay = { _, dismiss, onCurrentAssetChanged, _, _ ->
                                LaunchedEffect(currentAssetId) { onCurrentAssetChanged(currentAssetId) }
                                Column(Modifier.testTag(VIEWER_TAG)) {
                                    Text("Viewing $currentAssetId")
                                    Button(onClick = { currentAssetId = "non-cover" }) {
                                        Text("View non-cover")
                                    }
                                    Button(onClick = {
                                        dismiss.dismiss(currentAssetId) {
                                            events.add("route")
                                            routedDates.add(clickedDate)
                                        }
                                    }) { Text("Show date in Timeline") }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val VIEWER_TAG = "synthetic-date-viewer"
    }
}
