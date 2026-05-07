package com.udnahc.immichgallery.ui.util

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import com.udnahc.immichgallery.ui.component.SOURCE_PREP_TIMEOUT_MS
import kotlinx.coroutines.withTimeoutOrNull

suspend fun LazyListState.prepareOverlayDismissSource(
    displayIndex: Int?,
    isSourceReady: () -> Boolean,
    clearSourceReady: () -> Unit,
) {
    val index = displayIndex ?: return
    if (index < 0) return

    val initialVisibleItems = layoutInfo.visibleItemsInfo
    val initialVisibleIndexes = initialVisibleItems.map { it.index }.toSet()
    val firstVisibleIndex = initialVisibleItems.minOfOrNull { it.index }
    val lastVisibleIndex = initialVisibleItems.maxOfOrNull { it.index }

    when {
        initialVisibleItems.isEmpty() -> {
            clearSourceReady()
            scrollToItem(index, 0)
            waitFrames(1)
        }
        index in initialVisibleIndexes -> Unit
        firstVisibleIndex != null && index < firstVisibleIndex -> {
            clearSourceReady()
            scrollToItem(index, 0)
            waitFrames(1)
        }
        lastVisibleIndex != null && index > lastVisibleIndex -> {
            clearSourceReady()
            scrollToItem(index, 0)
            waitFrames(1)
            scrollTargetToViewportEnd(index, clearSourceReady)
        }
        else -> {
            clearSourceReady()
            scrollToItem(index, 0)
            waitFrames(1)
        }
    }

    waitForSourceReady(isSourceReady)
}

private suspend fun LazyListState.scrollTargetToViewportEnd(
    index: Int,
    clearSourceReady: () -> Unit,
) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)
    if (item.size >= viewportHeight) return

    val delta = item.offset + item.size - layoutInfo.viewportEndOffset
    if (delta > 2) {
        clearSourceReady()
        scrollBy(delta.toFloat())
        waitFrames(2)
    }
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
