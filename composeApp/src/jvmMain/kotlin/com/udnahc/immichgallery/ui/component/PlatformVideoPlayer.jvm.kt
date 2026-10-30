package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.udnahc.immichgallery.ui.util.JvmMpvAvailability
import com.udnahc.immichgallery.ui.util.JvmMpvBootstrap
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.video_desktop_unavailable
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val normalState = remember(authenticatedUrl) { NormalVideoPlaybackState() }
    val attempt = normalState.retryAttempt
    // Each explicit normal Retry gets one preparation attempt. Recomposition and slideshow
    // request changes reuse it; displaced effects can only write their old remembered state.
    var availability by remember(normalState, attempt) { mutableStateOf<JvmMpvAvailability?>(null) }
    LaunchedEffect(normalState, attempt) {
        availability = if (authenticatedUrl == null) JvmMpvAvailability.Unavailable else {
            withContext(Dispatchers.IO) { JvmMpvBootstrap.initialize(retryPreparation = attempt > 0) }
        }
    }
    val preparingRuntime = authenticatedUrl != null && availability == null
    val player = rememberMediaMpPlayer(authenticatedUrl, slideshowRequest, attempt, availability) {
        if (availability == JvmMpvAvailability.Ready) JvmMpvBootstrap.createPlayer() else null
    }
    Box(modifier = modifier) {
        if (player == null || authenticatedUrl == null) {
            LaunchedEffect(slideshowRequest, preparingRuntime) {
                if (!preparingRuntime) slideshowRequest?.let { onSlideshowEvent(it, SlideshowMediaEvent.Failed) }
            }
            // The slideshow page dispatches its own taps; observing here too would toggle twice.
            DesktopVideoUnavailable(
                modifier = Modifier.fillMaxSize(),
                normalState = normalState.takeIf { slideshowRequest == null },
                isCurrentPage = isCurrentPage,
                onTap = onTap,
                onInteraction = onNormalControlsInteraction,
                preparingRuntime = preparingRuntime,
                retryAllowed = authenticatedUrl != null && availability != JvmMpvAvailability.Unavailable,
            )
            return@Box
        }

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
            NormalVideoRecovery(
                state = normalState,
                visible = isCurrentPage,
                onInteraction = onNormalControlsInteraction,
                modifier = Modifier.align(Alignment.Center)
            )
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
internal fun DesktopVideoUnavailable(
    modifier: Modifier,
    normalState: NormalVideoPlaybackState?,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    onInteraction: () -> Unit,
    preparingRuntime: Boolean = false,
    retryAllowed: Boolean = true,
) {
    val attempt = normalState?.retryAttempt
    // A null construction result belongs to this attempt too. Retrying changes the
    // remembered player key; another null result must become retryable again.
    LaunchedEffect(normalState, attempt, preparingRuntime) {
        if (!preparingRuntime && normalState != null && attempt != null) normalState.failed(attempt)
    }
    VideoUnavailableSurface(
        normalState = normalState.takeIf { preparingRuntime || retryAllowed },
        isCurrentPage = isCurrentPage,
        onTap = onTap.takeIf { normalState != null },
        onInteraction = onInteraction,
        modifier = modifier,
        message = if (preparingRuntime) null else stringResource(Res.string.video_desktop_unavailable),
    )
}
