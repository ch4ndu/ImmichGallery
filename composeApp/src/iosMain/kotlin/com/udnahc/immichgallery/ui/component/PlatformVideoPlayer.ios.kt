package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitViewController
import com.udnahc.immichgallery.ui.theme.Dimens
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import org.lighthousegames.logging.logging
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

// NSObject subclass so AVPVC's UITapGestureRecognizer has a valid target/action
// pair. AVPVC also installs its own tap recognizer to toggle its native controls;
// ours fires alongside (cancelsTouchesInView = false) so both the app's top bar
// and AVPVC's chrome respond to the same tap.
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
    modifier: Modifier
) {
    // On iOS, try the raw original file first. AVPlayer natively decodes
    // mp4/m4v/mov/hevc containers, so the /original endpoint works for the
    // vast majority of user videos. Immich's /video/playback transcode has
    // been observed to produce streams where the audio track decodes but the
    // video track doesn't (black screen + advancing seekbar). Falling back
    // to the transcode covers formats AVPlayer can't open natively (webm, mkv).
    var currentUrl by remember(originalUrl, playbackUrl) { mutableStateOf(originalUrl) }
    var triedFallback by remember(originalUrl, playbackUrl) { mutableStateOf(false) }

    log.d { "PlatformVideoPlayer(iOS): loading $currentUrl" }

    val player = remember(currentUrl, apiKey) {
        val nsUrl = NSURL.URLWithString(currentUrl)!!
        val asset = AVURLAsset(
            uRL = nsUrl,
            options = mapOf("AVURLAssetHTTPHeaderFieldsKey" to mapOf("x-api-key" to apiKey))
        )
        val playerItem = AVPlayerItem(asset = asset)
        AVPlayer(playerItem = playerItem)
    }

    // Watch for playback failures on the original URL and fall back to the
    // transcoded playback URL if the original format isn't AVPlayer-compatible.
    LaunchedEffect(player, currentUrl) {
        if (currentUrl != originalUrl || triedFallback) return@LaunchedEffect
        val pollCount = 50 // 5 seconds at 100ms each
        repeat(pollCount) {
            delay(100)
            val item = player.currentItem() ?: return@LaunchedEffect
            val errorCount = item.errorLog()?.events?.size ?: 0
            if (errorCount > 0) {
                log.w {
                    "PlatformVideoPlayer(iOS): errorLog has $errorCount entries on original URL; falling back to transcode"
                }
                triedFallback = true
                currentUrl = playbackUrl
                return@LaunchedEffect
            }
            when (item.status) {
                AVPlayerItemStatusFailed -> {
                    val err = item.error
                    log.w {
                        "AVPlayerItem failed on original URL: domain=${err?.domain} " +
                            "code=${err?.code} desc=${err?.localizedDescription}"
                    }
                    log.d { "PlatformVideoPlayer(iOS): falling back to transcode $playbackUrl" }
                    triedFallback = true
                    currentUrl = playbackUrl
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
                "PlatformVideoPlayer(iOS): original URL stalled (status=${item?.status}); falling back to transcode"
            }
            triedFallback = true
            currentUrl = playbackUrl
        } else {
            log.d { "PlatformVideoPlayer(iOS): original URL playing cleanly" }
        }
    }

    LaunchedEffect(isCurrentPage, player) {
        if (isCurrentPage) {
            log.d { "PlatformVideoPlayer(iOS): playing" }
            player.play()
        } else {
            log.d { "PlatformVideoPlayer(iOS): pausing" }
            player.pause()
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

    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                this.player = player
                this.showsPlaybackControls = true
                this.allowsPictureInPicturePlayback = true
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
            if (vc.player !== player) {
                vc.player = player
            }
        },
        modifier = modifier.padding(
            top = Dimens.topBarHeight,
            bottom = Dimens.largeSpacing
        )
    )
}
