package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

internal const val NORMAL_VIDEO_CHROME_TIMEOUT_MS = 3_000L

internal enum class ViewerChromeHold { TopBar, Footer, Menu, VideoControls, VideoGesture, VideoSpeedMenu }

/**
 * Normal (non-slideshow) viewer chrome visibility shared by the top bar, footer
 * and video controls. Holds are keyed by owner so a repeated or late release can
 * never leave a stale count pinning the chrome.
 */
@Stable
internal class ViewerChromeState {
    var visible by mutableStateOf(true)
        private set
    private var interactions by mutableIntStateOf(0)
    private val holds = mutableStateMapOf<ViewerChromeHold, Unit>()

    val interactionCount: Int get() = interactions
    val isHeld: Boolean get() = holds.isNotEmpty()

    fun toggle() {
        visible = !visible
        interactions += 1
    }

    fun show() {
        visible = true
        interactions += 1
    }

    fun hide() {
        visible = false
    }

    /** Restarts the inactivity interval without changing visibility. */
    fun registerInteraction() {
        interactions += 1
    }

    fun setHold(owner: ViewerChromeHold, held: Boolean) {
        if (held) holds[owner] = Unit else holds.remove(owner)
        // A claimed side-drag adjustment reveals hidden chrome for the duration of the drag.
        if (held && owner == ViewerChromeHold.VideoGesture) visible = true
        interactions += 1
    }

    /** Drops press holds; menu holds track real menu state and release on close/disposal. */
    fun clearPointerHolds() {
        ViewerChromeHold.entries
            .filter { it != ViewerChromeHold.Menu && it != ViewerChromeHold.VideoSpeedMenu }
            .forEach { holds.remove(it) }
    }
}

@Composable
internal fun rememberViewerChromeState(): ViewerChromeState = remember { ViewerChromeState() }

/**
 * While [enabled] (settled normal video with no covering layer), each asset entry
 * or regained eligibility reveals the chrome, then hides it after
 * [NORMAL_VIDEO_CHROME_TIMEOUT_MS] without interaction. Photos keep the manual toggle.
 */
@Composable
internal fun NormalVideoChromeTimeout(state: ViewerChromeState, enabled: Boolean, assetKey: Any?) {
    LaunchedEffect(enabled, assetKey) {
        // Holds never survive a page change or regained eligibility.
        state.clearPointerHolds()
        if (enabled) state.show()
    }
    if (enabled && state.visible && !state.isHeld) {
        LaunchedEffect(state.interactionCount) {
            delay(NORMAL_VIDEO_CHROME_TIMEOUT_MS)
            state.hide()
        }
    }
}

/** Non-consuming press observer; cancellation or detachment always reports release. */
internal suspend fun PointerInputScope.observePointerHolds(onHoldChange: (Boolean) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onHoldChange(true)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            onHoldChange(false)
        }
    }
}

/** Press holds plus non-consuming wheel/trackpad scrolls (which never press) restart the interval. */
internal fun Modifier.viewerChromeHold(state: ViewerChromeState, owner: ViewerChromeHold): Modifier =
    pointerInput(state, owner) { observePointerHolds { held -> state.setHold(owner, held) } }
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) state.registerInteraction()
                }
            }
        }
