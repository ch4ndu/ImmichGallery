package com.udnahc.immichgallery.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import com.udnahc.immichgallery.ui.util.ScreenWakeLock

/** Narrow preview/test boundary around native media, wall clock and wake side effects. */
internal class ViewerPlaybackEnvironment(
    val createBinding: ((String, Boolean) -> Unit) -> SlideshowBinding,
    val wakeLock: ScreenWakeLock,
    val mediaContent: @Composable (ViewerMediaPresentation) -> Unit
)

internal data class ViewerMediaPresentation(
    val asset: Asset,
    val isCurrentPage: Boolean,
    val isSlideshow: Boolean,
    val playback: SlideshowPlaybackState?,
    val progress: () -> Float,
    val onTap: () -> Unit,
    val onEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit,
    val bottomChromePadding: Dp = 0.dp
)

internal val LocalViewerPlaybackEnvironment = staticCompositionLocalOf<ViewerPlaybackEnvironment?> { null }
