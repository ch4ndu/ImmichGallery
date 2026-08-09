package com.udnahc.immichgallery.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

enum class PhotoOverlayReturnTarget {
    CURRENT_ASSET,
    ORIGIN_ASSET
}

fun resolvePhotoGridReturnAssetId(
    originAssetId: String?,
    currentAssetId: String?,
    returnTarget: PhotoOverlayReturnTarget = PhotoOverlayReturnTarget.CURRENT_ASSET
): String? = when (returnTarget) {
    PhotoOverlayReturnTarget.CURRENT_ASSET -> currentAssetId ?: originAssetId
    PhotoOverlayReturnTarget.ORIGIN_ASSET -> originAssetId ?: currentAssetId
}

suspend fun LazyListState.ensureReturnSourceVisible(displayIndex: Int) {
    if (displayIndex < 0) return
    val info = layoutInfo
    if (displayIndex >= info.totalItemsCount) return
    val visibleItem = info.visibleItemsInfo.firstOrNull { it.index == displayIndex }
    if (visibleItem != null) return
    scrollToItem(displayIndex)
    withFrameNanos { }
}
