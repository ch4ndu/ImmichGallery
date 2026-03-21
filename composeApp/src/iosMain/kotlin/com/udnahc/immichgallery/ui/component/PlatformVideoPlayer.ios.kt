package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitViewController
import com.udnahc.immichgallery.ui.theme.Dimens
import kotlinx.cinterop.ExperimentalForeignApi
import org.lighthousegames.logging.logging
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

private val log = logging()

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformVideoPlayer(
    url: String,
    apiKey: String,
    isCurrentPage: Boolean,
    modifier: Modifier
) {
    log.d { "PlatformVideoPlayer(iOS): loading $url" }

    val player = remember(url) {
        val nsUrl = NSURL.URLWithString(url)!!
        val asset = AVURLAsset(
            uRL = nsUrl,
            options = mapOf("AVURLAssetHTTPHeaderFieldsKey" to mapOf("x-api-key" to apiKey))
        )
        val playerItem = AVPlayerItem(asset = asset)
        AVPlayer(playerItem = playerItem)
    }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            log.d { "PlatformVideoPlayer(iOS): playing" }
            player.play()
        } else {
            log.d { "PlatformVideoPlayer(iOS): pausing" }
            player.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            log.d { "PlatformVideoPlayer(iOS): disposing player" }
            player.pause()
        }
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = statusBarTop + Dimens.topBarHeight

    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                this.player = player
                this.showsPlaybackControls = true
                this.allowsPictureInPicturePlayback = true
            }
        },
        modifier = modifier.padding(top = topPadding)
    )
}
