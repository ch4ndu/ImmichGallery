package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
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

@Composable
actual fun PlatformVideoPlayer(
    playbackUrl: String,
    originalUrl: String,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier
) {
    val host = remember(playbackUrl) {
        MediaPlayerHost(
            playbackUrl,
            autoPlay = false,
            headers = mapOf(API_KEY_HEADER to apiKey),
            initialVideoFitMode = ScreenResize.FIT
        )
    }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) host.play() else host.pause()
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    VideoPlayerComposable(
        modifier = modifier,
        playerHost = host,
        playerConfig = VideoPlayerConfig(
            isPauseResumeEnabled = true,
            isSeekBarVisible = true,
            enableFullEdgeToEdge = false,
            isScreenLockEnabled = false,
            enablePIPControl = false,
            isScreenResizeEnabled = true,
            isFullScreenEnabled = false,
            controlTopPadding = statusBarTop + Dimens.topBarHeight,
            seekBarBottomPadding = navBarBottom + Dimens.screenPadding,
            topControlSize = 48.dp
        )
    )
}
