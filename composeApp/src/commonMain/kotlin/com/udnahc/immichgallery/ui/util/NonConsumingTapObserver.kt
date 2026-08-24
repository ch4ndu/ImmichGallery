package com.udnahc.immichgallery.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Observes a single-pointer tap after child gesture handlers without consuming
 * any pointer change. This keeps platform-player controls authoritative while
 * still letting the surrounding viewer react to taps the player consumed.
 */
internal fun Modifier.observeNonConsumingTap(onTap: () -> Unit): Modifier =
    pointerInput(onTap) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = nonConsumingTapPointerEventPass,
            )
            val classifier = NonConsumingTapClassifier(
                initialPosition = down.position,
                touchSlop = viewConfiguration.touchSlop,
            )

            while (true) {
                val event = awaitPointerEvent(nonConsumingTapPointerEventPass)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    classifier.cancel()
                    return@awaitEachGesture
                }
                val outcome = classifier.observe(
                    position = change.position,
                    isPressed = change.pressed,
                    activePointerCount = event.changes.count { it.pressed },
                )
                if (!change.pressed) {
                    if (outcome == NonConsumingTapOutcome.Tap) onTap()
                    return@awaitEachGesture
                }
            }
        }
    }

internal val nonConsumingTapPointerEventPass: PointerEventPass = PointerEventPass.Final

internal enum class NonConsumingTapOutcome {
    Continue,
    Tap,
    Cancelled,
}

internal class NonConsumingTapClassifier(
    private val initialPosition: Offset,
    private val touchSlop: Float,
) {
    private var isCancelled = false

    fun observe(
        position: Offset,
        isPressed: Boolean,
        activePointerCount: Int,
    ): NonConsumingTapOutcome {
        if (
            activePointerCount > 1 ||
            (position - initialPosition).getDistance() > touchSlop
        ) {
            isCancelled = true
        }
        if (isPressed) {
            return if (isCancelled) {
                NonConsumingTapOutcome.Cancelled
            } else {
                NonConsumingTapOutcome.Continue
            }
        }
        return if (isCancelled) {
            NonConsumingTapOutcome.Cancelled
        } else {
            NonConsumingTapOutcome.Tap
        }
    }

    fun cancel() {
        isCancelled = true
    }
}
