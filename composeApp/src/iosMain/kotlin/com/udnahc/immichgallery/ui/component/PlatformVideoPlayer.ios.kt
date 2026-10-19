package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitViewController
import androidx.compose.ui.unit.Dp
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import org.lighthousegames.logging.logging
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.errorLog
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.UIKit.UITapGestureRecognizer
import platform.darwin.NSObject
import kotlinx.cinterop.ObjCAction

private val log = logging()

private enum class IosVideoSource {
    ORIGINAL,
    PLAYBACK,
}

// NSObject subclass so the surface UITapGestureRecognizer has a valid
// target/action pair. Native playback controls are disabled; the shared Compose
// controls are a sibling drawn above the interop view, so their touches are
// handled by Compose and never reach this recognizer.
@OptIn(BetaInteropApi::class)
private class VideoTapHandler(var onTap: () -> Unit) : NSObject() {
    @ObjCAction
    fun handleTap() {
        onTap()
    }
}

@OptIn(ExperimentalForeignApi::class)
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
    // On iOS, try the authenticated original file first. AVPlayer natively decodes
    // mp4/m4v/mov/hevc containers, so the /original endpoint works for the
    // vast majority of user videos. Immich's /video/playback transcode has
    // been observed to produce streams where the audio track decodes but the
    // video track doesn't (black screen + advancing seekbar). Falling back
    // to the transcode covers formats AVPlayer can't open natively (webm, mkv).
    val authenticatedOriginal = remember(originalUrl, apiKey) {
        AuthenticatedMediaUrl.create(originalUrl, apiKey)
    }
    val authenticatedPlayback = remember(playbackUrl, apiKey) {
        AuthenticatedMediaUrl.create(playbackUrl, apiKey)
    }
    // Outlives source fallback so manual pause/mute survive the player swap.
    val normalState = remember(originalUrl, playbackUrl, apiKey) { NormalVideoPlaybackState() }
    var currentSource by remember(originalUrl, playbackUrl, apiKey, slideshowRequest) {
        mutableStateOf(
            if (authenticatedOriginal != null) {
                IosVideoSource.ORIGINAL
            } else {
                IosVideoSource.PLAYBACK
            }
        )
    }
    val currentMediaUrl = when (currentSource) {
        IosVideoSource.ORIGINAL -> authenticatedOriginal
        IosVideoSource.PLAYBACK -> authenticatedPlayback
    }
    if (currentMediaUrl == null) {
        LaunchedEffect(slideshowRequest) { slideshowRequest?.let { onSlideshowEvent(it, SlideshowMediaEvent.Failed) } }
        FailedVideoSurface(slideshowRequest, onTap, modifier)
        return
    }

    log.d { "PlatformVideoPlayer(iOS): loading <redacted>" }

    val player = remember(currentMediaUrl.url, slideshowRequest) {
        NSURL.URLWithString(currentMediaUrl.url)?.let { url ->
            val asset = AVURLAsset(uRL = url, options = null)
            val playerItem = AVPlayerItem(asset = asset)
            AVPlayer(playerItem = playerItem)
        }
    }
    if (player == null) {
        FailedVideoSurface(slideshowRequest, onTap, modifier)
        return
    }

    // Watch for playback failures on the original URL and fall back to the
    // transcoded playback URL if the original format isn't AVPlayer-compatible.
    // isCurrentPage is part of the effect key: leaving the page cancels this
    // bounded window, and returning starts a fresh one without consuming the
    // fallback while the page was inactive.
    LaunchedEffect(isCurrentPage, player, currentSource, authenticatedPlayback) {
        if (
            slideshowRequest != null || !isCurrentPage ||
            currentSource != IosVideoSource.ORIGINAL ||
            authenticatedPlayback == null
        ) {
            return@LaunchedEffect
        }

        repeat(FAILURE_OBSERVATION_POLLS) {
            delay(FAILURE_OBSERVATION_INTERVAL_MILLIS)
            val item = player.currentItem() ?: return@LaunchedEffect
            val errorCount = item.errorLog()?.events?.size ?: 0
            if (errorCount > 0) {
                log.w {
                    "PlatformVideoPlayer(iOS): errorLog has <redacted> entries on original URL; falling back to transcode"
                }
                currentSource = IosVideoSource.PLAYBACK
                return@LaunchedEffect
            }
            when (item.status) {
                AVPlayerItemStatusFailed -> {
                    log.w {
                        "AVPlayerItem failed on original URL: domain=<redacted> " +
                            "code=<redacted> desc=<redacted>"
                    }
                    log.d { "PlatformVideoPlayer(iOS): falling back to transcode <redacted>" }
                    currentSource = IosVideoSource.PLAYBACK
                    return@LaunchedEffect
                }
                AVPlayerItemStatusReadyToPlay -> {
                    // Continue polling so late decode errors still trigger fallback.
                }
                else -> {}
            }
        }
        val item = player.currentItem()
        if (item?.status != AVPlayerItemStatusReadyToPlay) {
            log.w {
                "PlatformVideoPlayer(iOS): original URL stalled (status=<redacted>); falling back to transcode"
            }
            currentSource = IosVideoSource.PLAYBACK
        } else {
            log.d { "PlatformVideoPlayer(iOS): original URL playing cleanly" }
        }
    }

    LaunchedEffect(player, muted, normalState.muted) {
        player.muted = if (slideshowRequest != null) muted else normalState.muted
    }
    // Lifecycle eligibility never rewrites the user's pause intent.
    LaunchedEffect(isCurrentPage, player, normalState.userPaused) {
        if (isCurrentPage && (slideshowRequest != null || !normalState.userPaused)) {
            log.d { "PlatformVideoPlayer(iOS): playing" }
            player.play()
        } else {
            log.d { "PlatformVideoPlayer(iOS): pausing" }
            player.pause()
        }
    }

    val latestPlaying by rememberUpdatedState(isCurrentPage)
    val latestSlideshowEvent by rememberUpdatedState(onSlideshowEvent)
    val fallbackPolicy = remember(slideshowRequest) { SlideshowVideoFallbackPolicy() }
    var ended by remember(player) { mutableStateOf(false) }
    DisposableEffect(player, slideshowRequest) {
        val captured = slideshowRequest
        val capturedSource = currentSource
        val item = player.currentItem()
        var installed = true
        val center = NSNotificationCenter.defaultCenter
        val observer = if (item != null) center.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue
        ) {
            if (installed && currentSource == capturedSource && player.currentItem() == item) {
                ended = true
                // Normal video stops at its end; show Play so the next press replays from the start.
                if (captured != null) latestSlideshowEvent(captured, SlideshowMediaEvent.Completed)
                else normalState.userPaused = true
            }
        } else null
        onDispose { installed = false; observer?.let(center::removeObserver) }
    }
    // Normal controls observe position/duration only while visible on the current page.
    LaunchedEffect(player, isCurrentPage, normalControlsVisible) {
        if (slideshowRequest != null || !isCurrentPage || !normalControlsVisible) return@LaunchedEffect
        while (true) {
            normalState.positionSeconds = CMTimeGetSeconds(player.currentTime()).toFloat()
            normalState.durationSeconds = player.currentItem()?.let { CMTimeGetSeconds(it.duration).toFloat() } ?: 0f
            delay(FAILURE_OBSERVATION_INTERVAL_MILLIS)
        }
    }
    LaunchedEffect(player, slideshowRequest, currentSource) {
        val captured = slideshowRequest ?: return@LaunchedEffect
        var previousPosition = 0L
        while (true) {
            delay(FAILURE_OBSERVATION_INTERVAL_MILLIS)
            if (!latestPlaying || ended) continue
            val item = player.currentItem() ?: continue
            val seconds = CMTimeGetSeconds(player.currentTime())
            val position = if (seconds.isFinite() && seconds > 0) (seconds * 1_000).toLong() else 0L
            val advancing = item.status == AVPlayerItemStatusReadyToPlay && position > previousPosition
            // Error logs include recovered historical failures; progress/stall is authoritative.
            val failed = item.status == AVPlayerItemStatusFailed
            when (fallbackPolicy.observe(FAILURE_OBSERVATION_INTERVAL_MILLIS, advancing, failed,
                canFallback = currentSource == IosVideoSource.ORIGINAL && authenticatedPlayback != null)) {
                SlideshowVideoFallbackDecision.FALLBACK -> {
                    latestSlideshowEvent(captured, SlideshowMediaEvent.Fallback)
                    currentSource = IosVideoSource.PLAYBACK
                    return@LaunchedEffect
                }
                SlideshowVideoFallbackDecision.FAILED -> {
                    latestSlideshowEvent(captured, SlideshowMediaEvent.Failed)
                    return@LaunchedEffect
                }
                SlideshowVideoFallbackDecision.WAIT -> Unit
            }
            latestSlideshowEvent(captured, SlideshowMediaEvent.Buffering(
                player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate))
            if (advancing) {
                previousPosition = position
                latestSlideshowEvent(captured, SlideshowMediaEvent.Progress(position))
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            log.d { "PlatformVideoPlayer(iOS): disposing player" }
            player.pause()
        }
    }

    // Capture the latest onTap through a wrapper so the gesture handler
    // (installed once in factory) always calls the current callback.
    val latestOnTap by rememberUpdatedState(onTap)
    val tapHandler = remember { VideoTapHandler { latestOnTap() } }

    Box(modifier = modifier) {
        UIKitViewController(
            factory = {
                AVPlayerViewController().apply {
                    this.player = player
                    this.showsPlaybackControls = false
                    this.allowsPictureInPicturePlayback = false
                    this.videoGravity = AVLayerVideoGravityResizeAspect
                    val tap = UITapGestureRecognizer(
                        target = tapHandler,
                        action = NSSelectorFromString("handleTap")
                    )
                    tap.cancelsTouchesInView = false
                    view.addGestureRecognizer(tap)
                }
            },
            update = { vc ->
                vc.showsPlaybackControls = false
                if (vc.player !== player) {
                    vc.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (slideshowRequest == null) {
            NormalVideoControls(
                state = normalState,
                visible = normalControlsVisible,
                onPlayPause = {
                    if (normalState.userPaused && ended) {
                        player.seekToTime(CMTimeMakeWithSeconds(0.0, VIDEO_SEEK_TIMESCALE))
                        ended = false
                    }
                    normalState.userPaused = !normalState.userPaused
                },
                onMuteToggle = { normalState.muted = !normalState.muted },
                onSeek = { seconds ->
                    ended = false
                    normalState.positionSeconds = seconds
                    player.seekToTime(CMTimeMakeWithSeconds(seconds.toDouble(), VIDEO_SEEK_TIMESCALE))
                },
                onInteraction = onNormalControlsInteraction,
                onHoldChange = onNormalControlsHoldChange,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomChromePadding)
            )
        }
    }
}

// Unplayable sources keep a full-bounds surface tap so hidden normal chrome can be
// revealed; slideshow pages already route surface taps through their parent.
@Composable
private fun FailedVideoSurface(slideshowRequest: SlideshowMediaRequest?, onTap: () -> Unit, modifier: Modifier) {
    if (slideshowRequest == null) Box(modifier.observeNonConsumingTap(onTap))
}

private const val VIDEO_SEEK_TIMESCALE = 600

private const val FAILURE_OBSERVATION_POLLS = 50
private const val FAILURE_OBSERVATION_INTERVAL_MILLIS = 100L
