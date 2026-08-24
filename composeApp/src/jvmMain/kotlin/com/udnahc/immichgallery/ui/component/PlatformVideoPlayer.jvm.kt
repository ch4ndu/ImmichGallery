package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chaintech.videoplayer.host.MediaPlayerHost
import chaintech.videoplayer.model.ScreenResize
import chaintech.videoplayer.model.VideoPlayerConfig
import chaintech.videoplayer.ui.video.VideoPlayerComposable
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.JvmVlcBootstrap
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.video_desktop_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun PlatformVideoPlayer(
    playbackUrl: String,
    originalUrl: String,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier
) {
    val authenticatedUrl = remember(playbackUrl, apiKey) {
        AuthenticatedMediaUrl.create(playbackUrl, apiKey)
    }
    val isVlcAvailable = remember { JvmVlcBootstrap.isAvailable() }
    Box(modifier = modifier.observeNonConsumingTap(onTap)) {
        if (!isVlcAvailable || authenticatedUrl == null) {
            DesktopVideoUnavailable(Modifier.fillMaxSize())
            return@Box
        }

        val host = remember(authenticatedUrl) {
            MediaPlayerHost(
                authenticatedUrl.url,
                autoPlay = false,
                initialVideoFitMode = ScreenResize.FIT
            )
        }

        LaunchedEffect(isCurrentPage) {
            if (isCurrentPage) host.play() else host.pause()
        }

        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        VideoPlayerComposable(
            modifier = Modifier.fillMaxSize(),
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
}

@Composable
private fun DesktopVideoUnavailable(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(Dimens.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.video_desktop_unavailable),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}
