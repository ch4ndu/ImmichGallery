package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chaintech.videoplayer.host.MediaPlayerHost
import chaintech.videoplayer.model.ScreenResize
import chaintech.videoplayer.model.VideoPlayerConfig
import chaintech.videoplayer.ui.video.VideoPlayerComposable
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap

@Composable
actual fun PlatformVideoPlayer(
    playbackUrl: String,
    originalUrl: String,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
    slideshowRequest: SlideshowMediaRequest?,
    muted: Boolean,
    onSlideshowEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit
) {
    val host = remember(playbackUrl, apiKey, slideshowRequest) {
        MediaPlayerHost(
            playbackUrl,
            autoPlay = false,
            isMuted = slideshowRequest != null && muted,
            headers = mapOf(API_KEY_HEADER to apiKey),
            initialVideoFitMode = ScreenResize.FIT
        ).also { if (slideshowRequest != null) it.setLooping(false) }
    }

    val latestEvent by rememberUpdatedState(onSlideshowEvent)
    DisposableEffect(host, slideshowRequest) {
        val captured = slideshowRequest
        if (captured != null) {
            host.onEvent = { event -> chaintechSlideshowEvent(event)?.let { latestEvent(captured, it) } }
            host.onError = { latestEvent(captured, SlideshowMediaEvent.Failed) }
        }
        onDispose { host.onEvent = {}; host.onError = {}; host.pause() }
    }
    LaunchedEffect(host, isCurrentPage, muted) {
        if (slideshowRequest != null) {
            host.setLooping(false)
            if (muted) host.mute() else host.unmute()
        }
        if (isCurrentPage) host.play() else host.pause()
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = modifier.observeNonConsumingTap(onTap)) {
        VideoPlayerComposable(
            modifier = Modifier.fillMaxSize(),
            playerHost = host,
            playerConfig = VideoPlayerConfig(
                showControlsOverride = if (slideshowRequest == null) null else false,
                isPauseResumeEnabled = slideshowRequest == null,
                isSeekBarVisible = slideshowRequest == null,
                enableFullEdgeToEdge = false,
                isScreenLockEnabled = false,
                enablePIPControl = false,
                isScreenResizeEnabled = slideshowRequest == null,
                isFastForwardBackwardEnabled = slideshowRequest == null,
                isGestureVolumeControlEnabled = slideshowRequest == null,
                enableLongPressFastForward = slideshowRequest == null,
                isFullScreenEnabled = false,
                controlTopPadding = statusBarTop + Dimens.topBarHeight,
                seekBarBottomPadding = navBarBottom + Dimens.screenPadding,
                topControlSize = 36.dp
            )
        )
    }
}
