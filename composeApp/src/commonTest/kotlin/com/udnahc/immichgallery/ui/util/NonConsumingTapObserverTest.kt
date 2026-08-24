package com.udnahc.immichgallery.ui.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.test.Test
import kotlin.test.assertEquals

class NonConsumingTapObserverTest {
    @Test
    fun reportsSinglePointerTapWithinTouchSlop() {
        val classifier = NonConsumingTapClassifier(Offset(10f, 12f), touchSlop = 8f)

        assertEquals(
            NonConsumingTapOutcome.Continue,
            classifier.observe(Offset(14f, 15f), isPressed = true, activePointerCount = 1),
        )
        assertEquals(
            NonConsumingTapOutcome.Tap,
            classifier.observe(Offset(14f, 15f), isPressed = false, activePointerCount = 0),
        )
    }

    @Test
    fun dragNeverBecomesTap() {
        val classifier = NonConsumingTapClassifier(Offset.Zero, touchSlop = 8f)

        assertEquals(
            NonConsumingTapOutcome.Cancelled,
            classifier.observe(Offset(9f, 0f), isPressed = true, activePointerCount = 1),
        )
        assertEquals(
            NonConsumingTapOutcome.Cancelled,
            classifier.observe(Offset(9f, 0f), isPressed = false, activePointerCount = 0),
        )
    }

    @Test
    fun multiTouchCancelsGesture() {
        val classifier = NonConsumingTapClassifier(Offset.Zero, touchSlop = 8f)

        assertEquals(
            NonConsumingTapOutcome.Cancelled,
            classifier.observe(Offset.Zero, isPressed = true, activePointerCount = 2),
        )
        assertEquals(
            NonConsumingTapOutcome.Cancelled,
            classifier.observe(Offset.Zero, isPressed = false, activePointerCount = 0),
        )
    }

    @Test
    fun explicitCancellationNeverBecomesTap() {
        val classifier = NonConsumingTapClassifier(Offset.Zero, touchSlop = 8f)

        classifier.cancel()

        assertEquals(
            NonConsumingTapOutcome.Cancelled,
            classifier.observe(Offset.Zero, isPressed = false, activePointerCount = 0),
        )
    }

    @Test
    fun observerUsesFinalPassSoChildConsumptionIsNeverReversed() {
        assertEquals(PointerEventPass.Final, nonConsumingTapPointerEventPass)
    }
}
