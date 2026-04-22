package com.udnahc.immichgallery.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import com.udnahc.immichgallery.ui.screen.timeline.MAX_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.ui.screen.timeline.MIN_TARGET_ROW_HEIGHT

private const val ZOOM_STEP_THRESHOLD = 1.3f
internal const val ZOOM_IN_FACTOR = 1.25f
internal const val ZOOM_OUT_FACTOR = 0.8f

fun Modifier.pinchToZoomRowHeight(
    currentHeight: Float,
    onHeightChanged: (Float) -> Unit
): Modifier = composed {
    var zoomAccumulator by remember { mutableFloatStateOf(1f) }
    pointerInput(currentHeight) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var previousDistance = 0f
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val dist = (pressed[0].position - pressed[1].position).getDistance()
                    if (previousDistance > 0f && dist > 0f) {
                        val zoom = dist / previousDistance
                        zoomAccumulator *= zoom
                        if (zoomAccumulator > ZOOM_STEP_THRESHOLD) {
                            val newHeight = (currentHeight * ZOOM_IN_FACTOR)
                                .coerceIn(MIN_TARGET_ROW_HEIGHT, MAX_TARGET_ROW_HEIGHT)
                            onHeightChanged(newHeight)
                            zoomAccumulator = 1f
                        } else if (zoomAccumulator < 1f / ZOOM_STEP_THRESHOLD) {
                            val newHeight = (currentHeight * ZOOM_OUT_FACTOR)
                                .coerceIn(MIN_TARGET_ROW_HEIGHT, MAX_TARGET_ROW_HEIGHT)
                            onHeightChanged(newHeight)
                            zoomAccumulator = 1f
                        }
                    }
                    previousDistance = dist
                    event.changes.forEach { it.consume() }
                } else {
                    previousDistance = 0f
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
