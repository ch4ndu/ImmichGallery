package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chaintech.videoplayer.host.MediaPlayerHost
import chaintech.videoplayer.model.VideoPlayerConfig
import chaintech.videoplayer.ui.video.VideoPlayerComposable
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.restoreEdgeToEdge
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.lighthousegames.logging.logging
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.back
import immichgallery.composeapp.generated.resources.detail_download
import immichgallery.composeapp.generated.resources.detail_failed_image
import immichgallery.composeapp.generated.resources.detail_info
import immichgallery.composeapp.generated.resources.detail_share
import immichgallery.composeapp.generated.resources.ic_back
import immichgallery.composeapp.generated.resources.ic_download
import immichgallery.composeapp.generated.resources.ic_info
import immichgallery.composeapp.generated.resources.ic_more_vert
import immichgallery.composeapp.generated.resources.ic_share
import immichgallery.composeapp.generated.resources.retry

internal const val DETAIL_BAR_ALPHA = 0.4f
internal const val API_KEY_HEADER = "x-api-key"

private const val ERROR_TEXT_ALPHA = 0.8f
private const val ERROR_ICON_ALPHA = 0.6f

private val log = logging()

@Composable
internal fun AssetPage(asset: Asset, apiKey: String, isCurrentPage: Boolean, onTap: () -> Unit) {
    if (asset.type == AssetType.VIDEO) {
        VideoContent(url = asset.videoPlaybackUrl, apiKey = apiKey, isCurrentPage = isCurrentPage)
    } else {
        ImageContent(url = asset.originalUrl, onTap = onTap)
    }
}

@Composable
internal fun DetailTopBarOverlay(
    showTopBar: Boolean,
    title: String,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit
) {
    AnimatedVisibility(
        visible = showTopBar,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = DETAIL_BAR_ALPHA))
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(Dimens.sectionHeaderHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(Res.drawable.ic_back),
                        contentDescription = stringResource(Res.string.back),
                        tint = Color.White
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            painterResource(Res.drawable.ic_more_vert),
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.detail_download)) },
                            onClick = { menuExpanded = false; onDownload() },
                            leadingIcon = {
                                Icon(painterResource(Res.drawable.ic_download), contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.detail_share)) },
                            onClick = { menuExpanded = false; onShare() },
                            leadingIcon = {
                                Icon(painterResource(Res.drawable.ic_share), contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.detail_info)) },
                            onClick = { menuExpanded = false; onInfo() },
                            leadingIcon = {
                                Icon(painterResource(Res.drawable.ic_info), contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageContent(url: String, onTap: () -> Unit) {
    var hasError by remember { mutableStateOf(false) }

    log.d { "ImageContent: loading $url" }

    if (hasError) {
        ErrorState(
            message = stringResource(Res.string.detail_failed_image),
            onRetry = { hasError = false }
        )
    } else {
        CoilZoomAsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            onTap = { onTap() }
        )
    }
}

@Composable
private fun VideoContent(url: String, apiKey: String, isCurrentPage: Boolean) {
    log.d { "VideoContent: loading video from $url" }
    log.d { "VideoContent: apiKey present=${apiKey.isNotBlank()}" }

    DisposableEffect(Unit) {
        onDispose {
            log.d { "VideoContent: disposing, restoring edge-to-edge" }
            restoreEdgeToEdge()
        }
    }

    val host = remember(url) {
        MediaPlayerHost(url, autoPlay = false, headers = mapOf(API_KEY_HEADER to apiKey))
    }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) host.play() else host.pause()
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    VideoPlayerComposable(
        modifier = Modifier.fillMaxSize(),
        playerHost = host,
        playerConfig = VideoPlayerConfig(
            isPauseResumeEnabled = true,
            isSeekBarVisible = true,
            enableFullEdgeToEdge = false,
            isScreenLockEnabled = false,
            enablePIPControl = false,
            isScreenResizeEnabled = true,
            controlTopPadding = statusBarTop + Dimens.topBarHeight,
            seekBarBottomPadding = navBarBottom
        )
    )
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painterResource(Res.drawable.ic_back),
            contentDescription = null,
            tint = Color.White.copy(alpha = ERROR_ICON_ALPHA),
            modifier = Modifier.size(Dimens.errorIconSize)
        )
        Spacer(modifier = Modifier.height(Dimens.largeSpacing))
        Text(
            text = message,
            color = Color.White.copy(alpha = ERROR_TEXT_ALPHA),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(Dimens.mediumSpacing))
        TextButton(onClick = onRetry) {
            Text(stringResource(Res.string.retry), color = Color.White)
        }
    }
}
