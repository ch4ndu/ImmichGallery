package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NormalVideoRecoveryUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun retryButtonStartsANewAttemptAndPreservesPauseAndMute() {
        val state = NormalVideoPlaybackState().apply {
            userPaused = true
            muted = true
            failed(0)
        }
        var interactions = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp)) {
                    NormalVideoRecovery(state, visible = true, onInteraction = { interactions++ })
                }
            }
        }
        compose.onNodeWithText("Could not play this video.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle {
            assertEquals(1, state.retryAttempt)
            assertEquals(NormalVideoLoadStatus.Opening, state.loadStatus)
            assertEquals(1, interactions)
            assertTrue(state.userPaused)
            assertTrue(state.muted)
            state.ready(1)
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Could not play this video.").assertDoesNotExist()
    }

    @Test
    fun desktopRetryPointerClicksPreserveChromeAndPlainBackgroundTapStillTogglesIt() {
        val state = NormalVideoPlaybackState().apply {
            userPaused = true
            muted = true
            speed = NormalVideoSpeed.Half
        }
        var interactions = 0
        val chrome = ViewerChromeState()
        compose.setContent {
            MaterialTheme {
                DesktopVideoUnavailable(
                    modifier = Modifier.size(400.dp).testTag("unavailable"),
                    normalState = state,
                    isCurrentPage = true,
                    onTap = chrome::toggle,
                    onInteraction = { interactions++; chrome.registerInteraction() }
                )
            }
        }
        compose.onNodeWithText("Desktop video playback is unavailable.", substring = true).assertExists()
        compose.runOnIdle { assertEquals(NormalVideoLoadStatus.Failed, state.loadStatus) }
        repeat(2) { index ->
            compose.onNodeWithText("Retry").performMouseInput { click() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(index + 1, state.retryAttempt)
                assertEquals(NormalVideoLoadStatus.Failed, state.loadStatus)
                assertEquals(index + 1, interactions)
                assertTrue(chrome.visible)
                assertEquals(index + 1, chrome.interactionCount)
                assertTrue(state.userPaused)
                assertTrue(state.muted)
                assertEquals(NormalVideoSpeed.Half, state.speed)
            }
        }
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Desktop video playback is unavailable.", substring = true).assertExists()
        compose.onNodeWithTag("unavailable").performMouseInput { click(Offset(5f, 5f)) }
        compose.runOnIdle {
            assertFalse(chrome.visible)
            assertEquals(2, state.retryAttempt)
            assertEquals(3, chrome.interactionCount)
        }
    }

    @Test
    fun sharedUnavailableSurfaceUsedByIosKeepsHiddenChromeUnchangedOnRetry() {
        val state = NormalVideoPlaybackState().apply { failed(0) }
        val chrome = ViewerChromeState().apply { hide() }
        compose.setContent {
            MaterialTheme {
                VideoUnavailableSurface(
                    normalState = state,
                    isCurrentPage = true,
                    onTap = chrome::toggle,
                    onInteraction = chrome::registerInteraction,
                    modifier = Modifier.size(400.dp).testTag("unavailable"),
                )
            }
        }
        compose.onNodeWithText("Retry").performMouseInput { click() }
        compose.runOnIdle {
            assertEquals(1, state.retryAttempt)
            assertEquals(NormalVideoLoadStatus.Opening, state.loadStatus)
            assertFalse(chrome.visible)
            assertEquals(1, chrome.interactionCount)
        }
        compose.onNodeWithTag("unavailable").performMouseInput { click(Offset(5f, 5f)) }
        compose.runOnIdle {
            assertTrue(chrome.visible)
            assertEquals(2, chrome.interactionCount)
        }
    }

    @Test
    fun pendingDesktopBootstrapShowsOpeningUntilFailureAndPermanentFailureHasGuidanceWithoutRetry() {
        val state = NormalVideoPlaybackState()
        val chrome = ViewerChromeState()
        var preparing by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                DesktopVideoUnavailable(
                    modifier = Modifier.size(400.dp).testTag("unavailable"),
                    normalState = state,
                    isCurrentPage = true,
                    onTap = chrome::toggle,
                    onInteraction = chrome::registerInteraction,
                    preparingRuntime = preparing,
                    retryAllowed = false,
                )
            }
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Desktop video playback is unavailable.", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(NormalVideoLoadStatus.Opening, state.loadStatus)
            preparing = false
        }
        compose.onNodeWithText("Desktop video playback is unavailable.", substring = true).assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle { assertEquals(NormalVideoLoadStatus.Failed, state.loadStatus) }
        compose.onNodeWithTag("unavailable").performMouseInput { click(Offset(5f, 5f)) }
        compose.runOnIdle { assertFalse(chrome.visible) }
    }

    @Test
    fun slideshowUnavailableSurfaceLeavesPlainTapsToItsParent() {
        var normalTaps = 0
        var slideshowTaps = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp).testTag("slideshow").pointerInput(Unit) {
                    detectTapGestures { slideshowTaps++ }
                }) {
                    DesktopVideoUnavailable(
                        modifier = Modifier,
                        normalState = null,
                        isCurrentPage = true,
                        onTap = { normalTaps++ },
                        onInteraction = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithTag("slideshow").performMouseInput { click(Offset(5f, 5f)) }
        compose.runOnIdle {
            assertEquals(1, slideshowTaps)
            assertEquals(0, normalTaps)
        }
    }

    @Test
    fun anOffscreenFailedPageDoesNotExposeRetry() {
        val state = NormalVideoPlaybackState().apply { failed(0) }
        compose.setContent {
            MaterialTheme { NormalVideoRecovery(state, visible = false, onInteraction = {}) }
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Could not play this video.").assertDoesNotExist()
    }
}
