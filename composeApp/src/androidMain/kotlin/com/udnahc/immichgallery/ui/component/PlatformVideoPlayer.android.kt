package com.udnahc.immichgallery.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import chaintech.videoplayer.host.MediaPlayerHost
import chaintech.videoplayer.model.ScreenResize
import chaintech.videoplayer.ui.video.VideoPlayerComposable
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap

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
    // Speed only changes the rate; play/pause stays with the effect above.
    LaunchedEffect(host, normalState.speed) {
        if (slideshowRequest == null) host.setSpeed(normalState.speed.playerSpeed)
    }

    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    val ownsBrightness = slideshowRequest == null && isCurrentPage && window != null
    // Only the current normal player overrides window brightness; leaving eligibility restores it,
    // and regaining it reapplies the user's level after capturing a fresh pre-override value.
    DisposableEffect(normalState, window, ownsBrightness) {
        if (!ownsBrightness || window == null) return@DisposableEffect onDispose {}
        VideoWindowBrightness.begin(normalState, window)
        normalState.brightness?.let { VideoWindowBrightness.apply(normalState, it) }
        onDispose { VideoWindowBrightness.end(normalState) }
    }

    Box(modifier = modifier) {
        NormalVideoGestureSurface(
            enabled = slideshowRequest == null && isCurrentPage,
            brightnessLevel = { normalState.brightness ?: window?.currentBrightness(context) ?: 1f },
            volumeLevel = { normalState.effectiveVolume },
            onBrightnessChange = { level ->
                normalState.brightness = level
                VideoWindowBrightness.apply(normalState, level)
            },
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Window.currentBrightness(context: Context): Float {
    val override = attributes.screenBrightness
    if (override >= 0f) return override
    // Following the system: start the gesture from the current screen setting (no write permission needed).
    return runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / SYSTEM_BRIGHTNESS_MAX
    }.getOrDefault(1f).coerceIn(0f, 1f)
}

/**
 * One window-brightness override at a time, owned by the current normal player's state. A newly
 * active owner takes over the original pre-override value, so a late disposal from the previous
 * page cannot restore over it, and only the owner that applied a level restores it.
 */
private object VideoWindowBrightness {
    private var owner: Any? = null
    private var window: Window? = null
    private var prior = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var overridden = false

    fun begin(token: Any, target: Window) {
        val current = window
        if (owner != null && current != null) {
            if (overridden) current.setBrightness(prior)
            if (current !== target) prior = target.attributes.screenBrightness
        } else {
            prior = target.attributes.screenBrightness
        }
        owner = token
        window = target
        overridden = false
    }

    fun apply(token: Any, level: Float) {
        val target = window ?: return
        if (owner !== token) return
        target.setBrightness(level.coerceIn(0f, 1f))
        overridden = true
    }

    fun end(token: Any) {
        if (owner !== token) return
        if (overridden) window?.setBrightness(prior)
        owner = null
        window = null
        overridden = false
    }

    private fun Window.setBrightness(level: Float) {
        attributes = attributes.apply { screenBrightness = level }
    }
}

private const val SYSTEM_BRIGHTNESS_MAX = 255f
