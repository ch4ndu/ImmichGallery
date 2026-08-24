package com.udnahc.immichgallery.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollbarOverlayTest {
    @Test
    fun accessibilityProgressClampsAndUsesDragCallbackOrder() {
        listOf(
            -0.25f to 0f,
            0.4f to 0.4f,
            1.25f to 1f
        ).forEach { (target, expected) ->
            val events = mutableListOf<String>()

            assertTrue(
                dispatchScrollbarProgressAction(
                    target = target,
                    onStarted = { events += "start" },
                    onFraction = { events += "fraction:$it" },
                    onStopped = { events += "stop:$it" }
                )
            )

            assertEquals(
                listOf("start", "fraction:$expected", "stop:$expected"),
                events
            )
        }
    }
}
