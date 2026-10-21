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
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.media3.ui.PlayerView
import kotlin.coroutines.EmptyCoroutineContext
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import org.openani.mediamp.exoplayer.compose.ExoPlayerMediampPlayerSurface

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
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val player = rememberMediaMpPlayer(playbackUrl, apiKey, slideshowRequest, applicationContext) {
        ExoPlayerMediampPlayer(applicationContext, EmptyCoroutineContext)
    }
    val normalState = remember(playbackUrl, apiKey) { NormalVideoPlaybackState() }
    // ExoPlayer has no MediaMP audio-level feature; app-owned mute applies volume 0.
    MediaMpVideoBinding(
        player = player,
        url = playbackUrl,
        headers = mapOf(API_KEY_HEADER to apiKey),
        isCurrentPage = isCurrentPage,
        normalState = normalState,
        slideshowRequest = slideshowRequest,
        muted = muted,
        setVolume = { level -> player.impl.volume = level },
        onSlideshowEvent = onSlideshowEvent,
    )

    val zoom = remember(player, slideshowRequest) { slideshowRequest?.let { MediaMpVideoZoom() } }
    val playerView = remember(player) { mutableStateOf<PlayerView?>(null) }
    // The video SurfaceView follows View transforms of its hierarchy, so zoom scales the native
    // PlayerView itself (about its center, like the shared Compose transform).
    LaunchedEffect(playerView, zoom) {
        if (zoom == null) return@LaunchedEffect
        snapshotFlow { Triple(playerView.value, zoom.scale, zoom.offset) }.collect { (view, scale, offset) ->
            view?.apply {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        }
    }

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
            onVolumeChange = { level ->
                normalState.adjustVolume(level, unmute = { normalState.muted = false }, setVolume = {})
            },
            onHoldChange = onNormalControlsHoldChange,
            modifier = Modifier.fillMaxSize()
        ) {
            MediaMpVideoSurface(zoom = zoom, onTap = onTap, modifier = Modifier.fillMaxSize()) {
                // The MediaMP surface disables PlayerView's controller and keeps FIT resizing.
                ExoPlayerMediampPlayerSurface(player, Modifier.fillMaxSize()) { playerView.value = this }
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
