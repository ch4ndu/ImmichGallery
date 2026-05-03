package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
): Modifier {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (enabled) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    return this
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onPrev(); true }
                Key.DirectionRight -> { onNext(); true }
                Key.Escape -> { onDismiss(); true }
                Key.Spacebar -> { onToggleSlideshow(); true }
                Key.I -> { onInfo(); true }
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
