package com.udnahc.immichgallery.ui.util

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import com.udnahc.immichgallery.ui.component.PhotoOverlayDismissContext
import com.udnahc.immichgallery.ui.component.PhotoOverlayDismissMode
import com.udnahc.immichgallery.ui.component.SOURCE_PREP_TIMEOUT_MS
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

suspend fun LazyListState.prepareOverlayDismissSource(
    displayIndex: Int?,
    context: PhotoOverlayDismissContext,
    listBoundsInRoot: () -> Rect?,
    isSourceReady: () -> Boolean,
    clearSourceReady: () -> Unit,
) {
    val index = displayIndex ?: return
    if (index < 0) return

    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        waitFrames(1)
    }

    val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visibleItem != null) {
        val info = layoutInfo
        val viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
        val desiredTop = desiredItemTopInViewport(
            context = context,
            listBounds = listBoundsInRoot(),
            viewportHeight = viewportHeight,
            itemHeight = visibleItem.size,
        )
        val desiredOffset = info.viewportStartOffset + desiredTop
        val delta = visibleItem.offset - desiredOffset
        if (abs(delta) > 2) {
            clearSourceReady()
            scrollBy(delta.toFloat())
            waitFrames(2)
        }
    }

    waitForSourceReady(isSourceReady)
}

private fun desiredItemTopInViewport(
    context: PhotoOverlayDismissContext,
    listBounds: Rect?,
    viewportHeight: Int,
    itemHeight: Int,
): Int {
    val availableTopRange = (viewportHeight - itemHeight).coerceAtLeast(0)
    val centerTop = availableTopRange / 2f
    val anchorY = if (context.mode == PhotoOverlayDismissMode.Drag) {
        val rootAnchor = context.preferredAnchorInRoot?.y
        val listTop = listBounds?.top
        if (rootAnchor != null && listTop != null) rootAnchor - listTop else null
    } else {
        null
    }
    val targetTop = if (anchorY != null) {
        anchorY - itemHeight / 2f
    } else {
        centerTop
    }
    return targetTop.coerceIn(0f, availableTopRange.toFloat()).roundToInt()
}

private suspend fun waitForSourceReady(isSourceReady: () -> Boolean): Boolean {
    if (isSourceReady()) return true
    return withTimeoutOrNull(SOURCE_PREP_TIMEOUT_MS) {
        while (!isSourceReady()) {
            waitFrames(1)
        }
        true
    } ?: false
}

private suspend fun waitFrames(count: Int) {
    repeat(count.coerceAtLeast(0)) {
        withFrameNanos { }
    }
}
