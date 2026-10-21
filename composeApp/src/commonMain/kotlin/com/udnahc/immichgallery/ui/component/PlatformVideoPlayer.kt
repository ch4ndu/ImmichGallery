package com.udnahc.immichgallery.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chaintech.videoplayer.model.VideoPlayerConfig
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

/**
 * Chaintech hosts render only the surface; app-owned controls and gestures replace its chrome/timers.
 * Pinch zoom is disabled only for [normalPlayback]; slideshow keeps the library default.
 */
internal fun chromelessChaintechConfig(normalPlayback: Boolean): VideoPlayerConfig = VideoPlayerConfig(
    showControls = false,
    showControlsOverride = false,
    isPauseResumeEnabled = false,
    isSeekBarVisible = false,
    isDurationVisible = false,
    isAutoHideControlEnabled = false,
    isFastForwardBackwardEnabled = false,
    isMuteControlEnabled = false,
    isSpeedControlEnabled = false,
    isFullScreenEnabled = false,
    isScreenLockEnabled = false,
    isScreenResizeEnabled = false,
    isZoomEnabled = !normalPlayback,
    isGestureVolumeControlEnabled = false,
    showVideoQualityOptions = false,
    showAudioTracksOptions = false,
    showSubTitlesOptions = false,
    enableFullEdgeToEdge = false,
    enablePIPControl = false,
    enableLongPressFastForward = false,
)
