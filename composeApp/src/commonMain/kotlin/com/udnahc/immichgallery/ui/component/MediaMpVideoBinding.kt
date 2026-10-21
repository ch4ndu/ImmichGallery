package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData

/**
 * One Android/JVM MediaMP player per [keys] (authenticated source plus slideshow request), closed
 * when forgotten or abandoned. [create] runs on the UI thread; null means the backend is unavailable.
 */
@Composable
internal fun <P : MediampPlayer?> rememberMediaMpPlayer(vararg keys: Any?, create: () -> P): P =
    remember(*keys) { MediaMpPlayerHolder(create()) }.player

private class MediaMpPlayerHolder<P : MediampPlayer?>(val player: P) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() { player?.close() }
    override fun onAbandoned() { player?.close() }
}

/**
 * Binds a remembered player to the shared viewer contract. Every command runs in composition
 * effects on the UI thread, which is the player's main dispatcher.
 *
 * The media opens paused; once Ready, the latest page eligibility and user pause intent decide
 * play/pause, so a completed open can never resume a page that left eligibility meanwhile.
 * Normal video loops only while eligible and user-playing (Ended resets the native intent), and
 * Play from Ended rewinds. Slideshow never loops or replays a finished visit; its request-bound
 * player reports observed progress, buffering, completion and failure.
 */
@Composable
internal fun MediaMpVideoBinding(
    player: MediampPlayer,
    url: String,
    headers: Map<String, String>,
    isCurrentPage: Boolean,
    normalState: NormalVideoPlaybackState,
    slideshowRequest: SlideshowMediaRequest?,
    muted: Boolean,
    setVolume: (Float) -> Unit,
    onSlideshowEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit,
) {
    // Lifecycle eligibility never rewrites the user's pause intent.
    val shouldPlay = isCurrentPage && (slideshowRequest != null || !normalState.userPaused)
    val latestShouldPlay by rememberUpdatedState(shouldPlay)
    val latestMuted by rememberUpdatedState(muted)
    val latestSetVolume by rememberUpdatedState(setVolume)
    val latestEvent by rememberUpdatedState(onSlideshowEvent)
    var opened by remember(player) { mutableStateOf(false) }

    LaunchedEffect(player) {
        val request = slideshowRequest
        // Events have no replay, so subscribe before opening.
        launch(start = CoroutineStart.UNDISPATCHED) {
            player.events.collect { event ->
                if (request != null) {
                    mediaMpSlideshowEvent(event)?.let { latestEvent(request, it) }
                } else if (event is PlaybackEvent.MediaEnded && latestShouldPlay) {
                    player.play()
                }
            }
        }
        if (request != null) {
            launch {
                player.currentPositionMillis.collect { position ->
                    mediaMpSlideshowProgress(position)?.let { latestEvent(request, it) }
                }
            }
            launch {
                player.state.map { it.isBuffering }.distinctUntilChanged().collect { buffering ->
                    latestEvent(request, SlideshowMediaEvent.Buffering(buffering))
                }
            }
        } else {
            launch { player.currentPositionMillis.collect { normalState.positionSeconds = it / 1_000f } }
            launch {
                player.mediaProperties.collect { properties ->
                    normalState.durationSeconds = properties?.durationMillis?.let { it / 1_000f } ?: 0f
                }
            }
        }
        try {
            player.setMediaData(UriMediaData(url, headers, MediaExtraFiles.EMPTY), playWhenReady = false)
            opened = true
        } catch (_: PlaybackException) {
            // The same failure is emitted as ErrorOccurred, which reports a slideshow failure.
        }
    }
    LaunchedEffect(player) {
        snapshotFlow {
            if (slideshowRequest != null) {
                if (latestMuted) 0f else 1f
            } else {
                normalState.effectiveVolume
            }
        }.collect { latestSetVolume(it) }
    }
    LaunchedEffect(player, opened, shouldPlay) {
        if (!opened) return@LaunchedEffect
        when {
            !shouldPlay -> player.pause()
            slideshowRequest != null && player.state.value.mediaStatus == MediaStatus.Ended -> Unit
            else -> player.play()
        }
    }
    // Speed only changes the rate; play/pause stays with the effect above.
    LaunchedEffect(player, normalState.speed) {
        if (slideshowRequest == null) player.features[PlaybackSpeed]?.set(normalState.speed.rate)
    }
}

/**
 * Hosts a MediaMP surface. Normal video ([zoom] null) observes surface taps without consuming
 * them, so shared controls and side gestures keep ownership. Slideshow video instead stacks a
 * Compose gesture layer above the surface, which owns touches before any native view: pinch/pan
 * movement is consumed, so it never also counts as a tap, and plain taps stay unconsumed for the
 * slideshow page's own tap detector, which dispatches each tap exactly once.
 */
@Composable
internal fun MediaMpVideoSurface(
    zoom: MediaMpVideoZoom?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (zoom == null) {
        Box(modifier.observeNonConsumingTap(onTap), content = content)
        return
    }
    Box(modifier.clipToBounds()) {
        content()
        Box(
            Modifier.matchParentSize().pointerInput(zoom) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    zoom.transform(size, centroid, pan, gestureZoom)
                }
            }
        )
    }
}

/** Applies slideshow zoom to a Compose-drawn surface; null leaves it untransformed. */
internal fun Modifier.mediaMpVideoZoom(zoom: MediaMpVideoZoom?): Modifier =
    if (zoom == null) {
        this
    } else {
        graphicsLayer {
            scaleX = zoom.scale
            scaleY = zoom.scale
            translationX = zoom.offset.x
            translationY = zoom.offset.y
        }
    }

/**
 * Slideshow-video pinch/pan about the gesture centroid, capped at [MAX_SLIDESHOW_VIDEO_ZOOM] and
 * kept within the surface bounds. The transform origin is the surface center. Remember one per
 * player so every slideshow request starts unzoomed.
 */
@Stable
internal class MediaMpVideoZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun transform(size: IntSize, centroid: Offset, pan: Offset, zoom: Float) {
        val nextScale = (scale * zoom).coerceIn(1f, MAX_SLIDESHOW_VIDEO_ZOOM)
        // Keep the content point under the centroid beneath it while following the pan.
        val anchor = centroid - Offset(size.width / 2f, size.height / 2f)
        val next = anchor + pan - (anchor - offset) * (nextScale / scale)
        val maxX = size.width * (nextScale - 1f) / 2f
        val maxY = size.height * (nextScale - 1f) / 2f
        offset = Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
        scale = nextScale
    }
}

private const val MAX_SLIDESHOW_VIDEO_ZOOM = 3f
