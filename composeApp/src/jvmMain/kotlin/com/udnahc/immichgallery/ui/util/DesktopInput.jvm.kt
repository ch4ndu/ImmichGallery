package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.udnahc.immichgallery.domain.model.RowHeightBounds

@Composable
actual fun Modifier.desktopDetailShortcuts(
    enabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    onToggleSlideshow: () -> Unit,
    onInfo: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    slideshowActive: Boolean,
    onSlideshowInteraction: () -> Unit,
): Modifier {
    val focusRequester = remember { FocusRequester() }
    val latestInteraction by rememberUpdatedState(onSlideshowInteraction)
    // Exiting a show removes its focused controls; keep subsequent viewer keys reachable.
    LaunchedEffect(enabled, slideshowActive) {
        if (enabled) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    return this
        .focusRequester(focusRequester)
        .focusable()
        .pointerInput(enabled, slideshowActive) {
            if (!enabled || !slideshowActive) return@pointerInput
            var lastMousePosition: Offset? = null
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse }
                    // Hover changes can report identical current/previous positions. Keep our own
                    // window-level position so media replacement under a stationary mouse is ignored.
                    val moved = event.type == PointerEventType.Move && mouse != null &&
                            lastMousePosition?.let { it != mouse.position } == true
                    if (mouse != null) lastMousePosition = mouse.position
                    val scrolled = event.type == PointerEventType.Scroll && event.changes.any {
                        it.scrollDelta != Offset.Zero
                    }
                    if (moved || scrolled) latestInteraction()
                }
            }
        }
        .onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (slideshowActive) latestInteraction()
            when (event.key) {
                Key.DirectionLeft -> { onPrev(); true }
                Key.DirectionRight -> { onNext(); true }
                Key.Escape -> { onDismiss(); true }
                Key.Spacebar -> { onToggleSlideshow(); true }
                Key.I -> { onInfo(); true }
                Key.F -> onToggleFullscreen?.let { it(); true } ?: false
                else -> false
            }
        }
}

actual fun Modifier.desktopGridZoom(
    currentHeight: Float,
    bounds: RowHeightBounds,
    onHeightChanged: (Float) -> Unit,
): Modifier = this.pointerInput(currentHeight, bounds) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val mods = event.keyboardModifiers
            if (!mods.isCtrlPressed && !mods.isMetaPressed) continue
            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (dy == 0f) continue
            val factor = if (dy < 0f) ZOOM_IN_FACTOR else ZOOM_OUT_FACTOR
            val newHeight = (currentHeight * factor)
                .coerceIn(bounds.min, bounds.max)
            if (newHeight != currentHeight) {
                onHeightChanged(newHeight)
            }
            event.changes.forEach { it.consume() }
        }
    }
}
