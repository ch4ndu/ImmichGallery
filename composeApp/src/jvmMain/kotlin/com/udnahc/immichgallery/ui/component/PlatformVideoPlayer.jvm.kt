package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
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
    modifier: Modifier,
    slideshowRequest: SlideshowMediaRequest?,
    muted: Boolean,
    onSlideshowEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit
) {
    val authenticatedUrl = remember(playbackUrl, apiKey) {
        AuthenticatedMediaUrl.create(playbackUrl, apiKey)
    }
    val isVlcAvailable = remember { JvmVlcBootstrap.isAvailable() }
    Box(modifier = modifier.observeNonConsumingTap(onTap)) {
        if (!isVlcAvailable || authenticatedUrl == null) {
            LaunchedEffect(slideshowRequest) { slideshowRequest?.let { onSlideshowEvent(it, SlideshowMediaEvent.Failed) } }
            DesktopVideoUnavailable(Modifier.fillMaxSize())
            return@Box
        }

        val host = remember(authenticatedUrl, slideshowRequest) {
            MediaPlayerHost(
                authenticatedUrl.url,
                autoPlay = false,
                isMuted = slideshowRequest != null && muted,
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
