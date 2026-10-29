package com.udnahc.immichgallery.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalVideoPlaybackStateTest {
    @Test
    fun failedOpenCanRetryWithoutChangingPlaybackIntent() {
        val state = NormalVideoPlaybackState().apply {
            userPaused = true
            muted = true
            volume = 0.4f
            speed = NormalVideoSpeed.Half
            brightness = 0.6f
            positionSeconds = 20f
            durationSeconds = 60f
        }
        state.failed(0)
        assertEquals(NormalVideoLoadStatus.Failed, state.loadStatus)

        state.retry()
        assertEquals(1, state.retryAttempt)
        assertEquals(NormalVideoLoadStatus.Opening, state.loadStatus)
        assertEquals(0f, state.positionSeconds)
        assertEquals(0f, state.durationSeconds)
        assertTrue(state.userPaused)
        assertTrue(state.muted)
        assertEquals(0.4f, state.volume)
        assertEquals(NormalVideoSpeed.Half, state.speed)
        assertEquals(0.6f, state.brightness)

        state.ready(1)
        assertEquals(NormalVideoLoadStatus.Ready, state.loadStatus)
        assertTrue(state.userPaused)
    }

    @Test
    fun previousAttemptCannotFailOrCompleteTheRetriedVideo() {
        val state = NormalVideoPlaybackState()
        state.failed(0)
        state.retry()
        state.ready(0)
        state.failed(0)
        state.opening(0)
        assertEquals(NormalVideoLoadStatus.Opening, state.loadStatus)
        state.ready(1)
        state.failed(0)
        assertEquals(NormalVideoLoadStatus.Ready, state.loadStatus)
    }

    @Test
    fun lateReadyDoesNotEraseTerminalFailureAndRetryIsSingleFlight() {
        val state = NormalVideoPlaybackState()
        state.failed(0)
        state.ready(0)
        assertEquals(NormalVideoLoadStatus.Failed, state.loadStatus)
        state.retry()
        state.retry()
        assertEquals(1, state.retryAttempt)
        state.ready(1)
        state.retry()
        assertEquals(1, state.retryAttempt)
    }
}
