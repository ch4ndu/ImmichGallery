package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.JvmMpvBootstrap
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.video_desktop_unavailable
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface

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
    val player = rememberMediaMpPlayer(authenticatedUrl, slideshowRequest) {
        authenticatedUrl?.let { JvmMpvBootstrap.createPlayer() }
    }
    Box(modifier = modifier) {
        if (player == null || authenticatedUrl == null) {
            LaunchedEffect(slideshowRequest) { slideshowRequest?.let { onSlideshowEvent(it, SlideshowMediaEvent.Failed) } }
            // The slideshow page dispatches its own taps; observing here too would toggle twice.
            DesktopVideoUnavailable(
                Modifier.fillMaxSize().then(if (slideshowRequest == null) Modifier.observeNonConsumingTap(onTap) else Modifier)
            )
            return@Box
        }

        val normalState = remember(authenticatedUrl) { NormalVideoPlaybackState() }
        // mpv can amplify up to 2x; the shared contract keeps player volume within 0..1.
        val audio = player.features[AudioLevelController]
        MediaMpVideoBinding(
            player = player,
            url = authenticatedUrl.url,
            headers = emptyMap(),
            isCurrentPage = isCurrentPage,
            normalState = normalState,
            slideshowRequest = slideshowRequest,
            muted = muted,
            setVolume = { level -> audio?.setVolume(level.coerceIn(0f, 1f)) },
            onSlideshowEvent = onSlideshowEvent,
        )

        val zoom = remember(player, slideshowRequest) { slideshowRequest?.let { MediaMpVideoZoom() } }
        NormalVideoGestureSurface(
            enabled = slideshowRequest == null && isCurrentPage,
            brightnessLevel = { normalState.brightness ?: 1f },
            volumeLevel = { normalState.effectiveVolume },
            onBrightnessChange = { level -> normalState.brightness = level },
            onVolumeChange = { level ->
                normalState.adjustVolume(level, unmute = { normalState.muted = false }, setVolume = {})
            },
            onHoldChange = onNormalControlsHoldChange,
            modifier = Modifier.fillMaxSize()
        ) {
            MediaMpVideoSurface(zoom = zoom, onTap = onTap, modifier = Modifier.fillMaxSize()) {
                // Composed with the player so Linux/Windows opens can await its render context;
                // the surface letterboxes frames (FIT). Slideshow zoom transforms only this layer.
                MpvMediampPlayerSurface(player, Modifier.fillMaxSize().mediaMpVideoZoom(zoom))
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
                onMuteToggle = { normalState.muted = !normalState.muted },
                onSeek = { seconds -> player.seekTo((seconds * 1_000).toLong()) },
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
