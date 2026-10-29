package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun repeatedDesktopConstructionFailureKeepsEachAttemptRetryable() {
        val state = NormalVideoPlaybackState().apply {
            userPaused = true
            muted = true
            speed = NormalVideoSpeed.Half
        }
        var interactions = 0
        compose.setContent {
            MaterialTheme {
                DesktopVideoUnavailable(
                    modifier = Modifier.size(400.dp),
                    normalState = state,
                    isCurrentPage = true,
                    onInteraction = { interactions++ }
                )
            }
        }
        compose.onNodeWithText("Desktop video playback is unavailable.", substring = true).assertExists()
        compose.runOnIdle { assertEquals(NormalVideoLoadStatus.Failed, state.loadStatus) }
        repeat(2) { index ->
            compose.onNodeWithText("Retry").performClick()
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(index + 1, state.retryAttempt)
                assertEquals(NormalVideoLoadStatus.Failed, state.loadStatus)
                assertEquals(index + 1, interactions)
                assertTrue(state.userPaused)
                assertTrue(state.muted)
                assertEquals(NormalVideoSpeed.Half, state.speed)
            }
        }
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Desktop video playback is unavailable.", substring = true).assertExists()
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
