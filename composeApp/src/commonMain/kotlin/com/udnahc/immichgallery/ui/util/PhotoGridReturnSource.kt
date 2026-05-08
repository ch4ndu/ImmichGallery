package com.udnahc.immichgallery.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

suspend fun LazyListState.ensureReturnSourceVisible(displayIndex: Int) {
    if (displayIndex < 0) return
    val info = layoutInfo
    if (displayIndex >= info.totalItemsCount) return
    val visibleItem = info.visibleItemsInfo.firstOrNull { it.index == displayIndex }
    if (visibleItem != null) return
    scrollToItem(displayIndex)
    withFrameNanos { }
}
