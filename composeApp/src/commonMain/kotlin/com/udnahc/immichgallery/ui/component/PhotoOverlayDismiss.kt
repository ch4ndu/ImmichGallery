package com.udnahc.immichgallery.ui.component

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

const val SOURCE_PREP_TIMEOUT_MS = 150L

@Immutable
data class PhotoOverlayDismissContext(
    val assetId: String?,
    val bucketKey: String? = null,
    val mode: PhotoOverlayDismissMode,
    val preferredAnchorInRoot: Offset? = null,
    val sourceGeneration: Int = 0,
)

enum class PhotoOverlayDismissMode {
    Back,
    Drag,
    Shortcut
}

@Immutable
data class PhotoOverlaySourcePosition(
    val assetId: String,
    val boundsInRoot: Rect,
    val generation: Int,
)
