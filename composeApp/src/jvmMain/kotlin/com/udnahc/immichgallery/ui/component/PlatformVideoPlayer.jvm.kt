package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.Dp
import chaintech.videoplayer.host.MediaPlayerHost
import chaintech.videoplayer.model.ScreenResize
import chaintech.videoplayer.ui.video.VideoPlayerComposable
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.JvmVlcBootstrap
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.video_desktop_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun PlatformVideoPlayer(
    playbackUrl: String,
    originalUrl: String,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
    bottomChromePadding: Dp,
    normalControlsVisible: Boolean,
    onNormalControlsInteraction: () -> Unit,
    onNormalControlsHoldChange: (ViewerChromeHold, Boolean) -> Unit,
    slideshowRequest: SlideshowMediaRequest?,
    muted: Boolean,
    onSlideshowEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit
) {
    val authenticatedUrl = remember(playbackUrl, apiKey) {
        AuthenticatedMediaUrl.create(playbackUrl, apiKey)
    }
    val isVlcAvailable = remember { JvmVlcBootstrap.isAvailable() }
    Box(modifier = modifier) {
        if (!isVlcAvailable || authenticatedUrl == null) {
            LaunchedEffect(slideshowRequest) { slideshowRequest?.let { onSlideshowEvent(it, SlideshowMediaEvent.Failed) } }
            DesktopVideoUnavailable(Modifier.fillMaxSize().observeNonConsumingTap(onTap))
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
        val normalState = remember(authenticatedUrl) { NormalVideoPlaybackState() }

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
        // Speed only changes the rate; play/pause stays with the effect above.
        LaunchedEffect(host, normalState.speed) {
            if (slideshowRequest == null) host.setSpeed(normalState.speed.playerSpeed)
        }

        NormalVideoGestureSurface(
            enabled = slideshowRequest == null && isCurrentPage,
            brightnessLevel = { normalState.brightness ?: 1f },
            volumeLevel = { normalState.effectiveVolume },
            onBrightnessChange = { level -> normalState.brightness = level },
            onVolumeChange = { level -> normalState.adjustVolume(level, host::unmute, host::setVolume) },
            onHoldChange = onNormalControlsHoldChange,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize().observeNonConsumingTap(onTap)) {
                VideoPlayerComposable(
                    modifier = Modifier.fillMaxSize(),
                    playerHost = host,
                    playerConfig = remember(slideshowRequest == null) {
                        chromelessChaintechConfig(normalPlayback = slideshowRequest == null)
                    }
                )
                // Desktop "brightness" dims only this video layer, never controls or the monitor,
                // and disappears whenever the page is not the current normal player.
                val dimLevel = normalState.brightness?.takeIf { slideshowRequest == null && isCurrentPage }
                if (dimLevel != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = (1f - dimLevel) * DESKTOP_VIDEO_MAX_DIM_ALPHA))
                    )
                }
            }
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

// The minimum desktop level keeps the video faintly visible.
private const val DESKTOP_VIDEO_MAX_DIM_ALPHA = 0.85f

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
