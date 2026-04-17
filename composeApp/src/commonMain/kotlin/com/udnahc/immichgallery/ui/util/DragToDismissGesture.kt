package com.udnahc.immichgallery.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange

/**
 * Google-Photos-style drag-to-dismiss for the photo detail overlay.
 *
 * After the user crosses vertical touch slop, if the first committed movement
 * is downward we enter **dismiss mode**: 2D drag tracking driving the
 * [state], releasing past threshold (or a fast flick) triggers [onDismiss].
 * If the first committed movement is upward we fire [onOpenDetailSheet] once
 * the upward threshold is crossed.
 *
 * Horizontal drags are left alone — `awaitVerticalTouchSlopOrCancellation`
 * only commits on vertical-dominant motion, so the inner HorizontalPager
 * still handles horizontal swipes.
 *
 * @param enabled gates the entire gesture (e.g. false when a detail sheet
 *   is open or slideshow is running).
 * @param isZoomed read at gesture start — skip when the image is pinched-in
 *   so the zoom library's own pan gesture owns the events.
 */
fun Modifier.dragToDismiss(
    state: DragToDismissState,
    enabled: Boolean,
    isZoomed: () -> Boolean,
    dismissThresholdPx: Float,
    flickVelocityPx: Float,
    onDismiss: () -> Unit,
    onOpenDetailSheet: () -> Unit,
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (isZoomed()) return@awaitEachGesture

        var slopOverflow = 0f
        val initialDrag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
            slopOverflow = overSlop
            change.consume()
        } ?: return@awaitEachGesture

        if (slopOverflow > 0f) {
            state.onDragStart(down.position, size)
            runDismissLoop(
                initialChange = initialDrag,
                slopOverflow = slopOverflow,
                pointerId = initialDrag.id,
                state = state,
                onDismiss = onDismiss,
            )
        } else {
            runUpwardLoop(
                initialChange = initialDrag,
                slopOverflow = slopOverflow,
                pointerId = initialDrag.id,
                thresholdPx = dismissThresholdPx,
                onOpenDetailSheet = onOpenDetailSheet,
            )
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.runDismissLoop(
    initialChange: androidx.compose.ui.input.pointer.PointerInputChange,
    slopOverflow: Float,
    pointerId: PointerId,
    state: DragToDismissState,
    onDismiss: () -> Unit,
) {
    val velocityTracker = VelocityTracker()
    velocityTracker.addPointerInputChange(initialChange)
    var total = Offset(initialChange.positionChange().x, slopOverflow)
    state.onDrag(total)

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.count { it.pressed }
        if (pressed > 1) {
            state.cancel()
            return
        }
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (change.changedToUp()) {
            velocityTracker.addPointerInputChange(change)
            val velocity = velocityTracker.calculateVelocity()
            val outcome = state.onRelease(velocity.y)
            if (outcome == DismissOutcome.DISMISS) {
                onDismiss()
            }
            change.consume()
            return
        }
        val delta = change.positionChange()
        if (delta != Offset.Zero) {
            total += delta
            velocityTracker.addPointerInputChange(change)
            state.onDrag(total)
            change.consume()
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.runUpwardLoop(
    initialChange: androidx.compose.ui.input.pointer.PointerInputChange,
    slopOverflow: Float,
    pointerId: PointerId,
    thresholdPx: Float,
    onOpenDetailSheet: () -> Unit,
) {
    var total = Offset(initialChange.positionChange().x, slopOverflow)
    var fired = false

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (change.changedToUp()) {
            if (!fired && total.y < -thresholdPx) {
                onOpenDetailSheet()
            }
            change.consume()
            return
        }
        total += change.positionChange()
        change.consume()
        if (!fired && total.y < -thresholdPx) {
            fired = true
            onOpenDetailSheet()
        }
    }
}
