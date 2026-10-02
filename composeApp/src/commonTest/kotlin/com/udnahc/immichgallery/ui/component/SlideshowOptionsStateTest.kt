package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.domain.model.*
import kotlin.test.*

class SlideshowOptionsStateTest {
    @Test fun durationRequiresVisibleIntegerWithinAcceptedRange() {
        for (text in listOf("", "0", "121", "hello", "1.5", "99999999999")) {
            assertNull(SlideshowOptionsState(SlideshowConfig(), durationText = text).validated)
        }
        for (seconds in listOf(1, 5, 120)) {
            assertEquals(seconds, SlideshowOptionsState(SlideshowConfig(), durationText = "$seconds").validated?.durationSeconds)
        }
    }

    @Test fun playCapAcceptsBlankOrPositiveAndInactiveInvalidTextCannotTrapStart() {
        val config = SlideshowConfig(videoPolicy = SlideshowVideoPolicy.PLAY, videoLimitSeconds = 30)
        assertNull(SlideshowOptionsState(config, limitText = "").validated?.videoLimitSeconds)
        assertEquals(1, SlideshowOptionsState(config, limitText = "1").validated?.videoLimitSeconds)
        for (text in listOf("0", "-1", "1.5", "bad")) {
            val state = SlideshowOptionsState(config, limitText = text)
            assertNull(state.validated)
            for (policy in listOf(SlideshowVideoPolicy.SKIP, SlideshowVideoPolicy.THUMBNAIL)) {
                val inactive = assertNotNull(state.copy(config = config.copy(videoPolicy = policy)).validated)
                assertTrue(inactive.isValid)
                assertEquals(policy, inactive.videoPolicy)
                assertEquals(30, inactive.videoLimitSeconds)
            }
        }
    }
}
