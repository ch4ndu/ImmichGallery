package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import chaintech.videoplayer.host.MediaPlayerHost
import chaintech.videoplayer.model.ScreenResize
import chaintech.videoplayer.ui.video.VideoPlayerComposable
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap

@Composable
actual fun PlatformVideoPlayer(
    playbackUrl: String,
    originalUrl: String,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
    bottomChromePadding: Dp,
    normalControlsVisible: Boolean,
    onNormalControlsInteraction: () -> Unit,
    onNormalControlsHoldChange: (Boolean) -> Unit,
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
    val normalState = remember(playbackUrl, apiKey) { NormalVideoPlaybackState() }

    val latestEvent by rememberUpdatedState(onSlideshowEvent)
    // One callback owner per host: slideshow requests feed the controller, normal video feeds its controls.
    DisposableEffect(host, slideshowRequest) {
        val captured = slideshowRequest
        host.onEvent = { event ->
            if (captured != null) chaintechSlideshowEvent(event)?.let { latestEvent(captured, it) }
            else normalState.applyChaintechEvent(event)
        }
        host.onError = { if (captured != null) latestEvent(captured, SlideshowMediaEvent.Failed) }
        onDispose { host.onEvent = {}; host.onError = {}; host.pause() }
    }
    LaunchedEffect(host, isCurrentPage, muted, normalState.userPaused) {
        if (slideshowRequest != null) {
            host.setLooping(false)
            if (muted) host.mute() else host.unmute()
        }
        // Lifecycle eligibility never rewrites the user's pause intent.
        if (isCurrentPage && (slideshowRequest != null || !normalState.userPaused)) host.play() else host.pause()
    }

    Box(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize().observeNonConsumingTap(onTap)) {
            VideoPlayerComposable(
                modifier = Modifier.fillMaxSize(),
                playerHost = host,
                playerConfig = remember(slideshowRequest == null) {
                    chromelessChaintechConfig(normalPlayback = slideshowRequest == null)
                }
            )
        }
        if (slideshowRequest == null) {
            NormalVideoControls(
                state = normalState,
                visible = normalControlsVisible,
                onPlayPause = { normalState.userPaused = !normalState.userPaused },
                onMuteToggle = { if (normalState.muted) host.unmute() else host.mute() },
                onSeek = { seconds -> host.seekTo(seconds) },
                onInteraction = onNormalControlsInteraction,
                onHoldChange = onNormalControlsHoldChange,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomChromePadding)
            )
        }
    }
}
