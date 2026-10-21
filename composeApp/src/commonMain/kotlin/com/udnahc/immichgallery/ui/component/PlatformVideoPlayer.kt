package com.udnahc.immichgallery.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest

/**
 * The native surface always fills [modifier]. Normal (non-slideshow) players draw
 * the shared [NormalVideoControls] as a sibling above [bottomChromePadding];
 * [normalControlsVisible] alone decides whether those controls are shown. Normal
 * side-drag brightness/volume gestures work on the current page even while the
 * controls are hidden; brightness is owned only by the current normal player and
 * restored when it loses eligibility.
 */
@Composable
internal expect fun PlatformVideoPlayer(
    playbackUrl: String,
    originalUrl: String,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    bottomChromePadding: Dp = 0.dp,
    normalControlsVisible: Boolean = false,
    onNormalControlsInteraction: () -> Unit = {},
    onNormalControlsHoldChange: (ViewerChromeHold, Boolean) -> Unit = { _, _ -> },
    slideshowRequest: SlideshowMediaRequest? = null,
    muted: Boolean = false,
    onSlideshowEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit = { _, _ -> }
)
