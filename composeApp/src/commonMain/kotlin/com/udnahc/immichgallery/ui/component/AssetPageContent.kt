package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.restoreEdgeToEdge
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.back
import immichgallery.composeapp.generated.resources.detail_download
import immichgallery.composeapp.generated.resources.detail_slideshow
import immichgallery.composeapp.generated.resources.detail_failed_image
import immichgallery.composeapp.generated.resources.detail_info
import immichgallery.composeapp.generated.resources.detail_share
import immichgallery.composeapp.generated.resources.ic_back
import immichgallery.composeapp.generated.resources.ic_play
import immichgallery.composeapp.generated.resources.ic_download
import immichgallery.composeapp.generated.resources.ic_info
import immichgallery.composeapp.generated.resources.ic_more_vert
import immichgallery.composeapp.generated.resources.ic_share
import immichgallery.composeapp.generated.resources.retry
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.lighthousegames.logging.logging

internal const val DETAIL_BAR_ALPHA = 0.4f
internal const val API_KEY_HEADER = "x-api-key"

private const val ERROR_TEXT_ALPHA = 0.8f
private const val ERROR_ICON_ALPHA = 0.6f

private val log = logging()

private const val THUMBNAIL_HIDE_DELAY_MS = 500L

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AssetPage(
    asset: Asset,
    apiKey: String,
    isCurrentPage: Boolean,
    isSlideshow: Boolean = false,
    onTap: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val isTransitionActive = sharedTransitionScope?.isTransitionActive ?: false
    var coverThumbnail by remember { mutableStateOf(false) }

    // After a delay, cover the thumbnail so it doesn't peek through zoom-out.
    // Reset when transition becomes active again (dismiss).
    LaunchedEffect(isTransitionActive) {
        if (!isTransitionActive) {
            delay(THUMBNAIL_HIDE_DELAY_MS)
            coverThumbnail = true
        } else {
            coverThumbnail = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Base layer: thumbnail with shared bounds modifier — always in composition
        // so the exit animation can find it.
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            val sharedModifier = with(sharedTransitionScope) {
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .sharedBounds(
                        sharedTransitionScope.rememberSharedContentState(key = "thumb_${asset.id}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                            contentScale = ContentScale.Fit
                        )
                    )
            }
            Box(modifier = sharedModifier) {
                AsyncImage(
                    model = asset.thumbnailUrl,
                    contentDescription = asset.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                // Opaque cover to hide thumbnail from zoom-out, while keeping
                // the shared bounds composable fully in the tree for exit animation
                if (coverThumbnail) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }

        // Content layer: full-res image or video, layered on top after transition completes
        if (!isTransitionActive) {
            if (isSlideshow) {
                val imageUrl = if (asset.type == AssetType.VIDEO) asset.thumbnailUrl else asset.originalUrl
                Box(
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { onTap() }
                    }
                ) {
                    KenBurnsImage(url = imageUrl, isActive = isCurrentPage, modifier = Modifier.fillMaxSize())
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center
                ) {
                    if (asset.type == AssetType.VIDEO) {
                        VideoContent(url = asset.videoPlaybackUrl, apiKey = apiKey, isCurrentPage = isCurrentPage)
                    } else {
                        ImageContent(url = asset.originalUrl, onTap = onTap)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailTopBarOverlay(
    showTopBar: Boolean,
    title: String,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onSlideshow: (() -> Unit)? = null
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
                modifier = Modifier.fillMaxWidth().height(Dimens.bottomBarHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                    onClick = onBack
                ) {
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
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                    ) {
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
                        if (onSlideshow != null) {
                            DropdownMenuItem(
                                modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                                text = { Text(stringResource(Res.string.detail_slideshow)) },
                                onClick = { menuExpanded = false; onSlideshow() },
                                leadingIcon = {
                                    Icon(
                                        painterResource(Res.drawable.ic_play),
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                        DropdownMenuItem(
                            modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                            text = { Text(stringResource(Res.string.detail_download)) },
                            onClick = { menuExpanded = false; onDownload() },
                            leadingIcon = {
                                Icon(
                                    painterResource(Res.drawable.ic_download),
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                            text = { Text(stringResource(Res.string.detail_share)) },
                            onClick = { menuExpanded = false; onShare() },
                            leadingIcon = {
                                Icon(
                                    painterResource(Res.drawable.ic_share),
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                            text = { Text(stringResource(Res.string.detail_info)) },
                            onClick = { menuExpanded = false; onInfo() },
                            leadingIcon = {
                                Icon(
                                    painterResource(Res.drawable.ic_info),
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

private const val HANDLE_WIDTH = 32
private const val HANDLE_HEIGHT = 4

@Composable
internal fun DetailBottomHandle(
    visible: Boolean,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(Color.Black.copy(alpha = DETAIL_BAR_ALPHA))
                .navigationBarsPadding()
                .padding(vertical = Dimens.mediumSpacing),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(HANDLE_WIDTH.dp)
                    .height(HANDLE_HEIGHT.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun ImageContent(
    url: String,
    onTap: () -> Unit
) {
    var hasError by remember { mutableStateOf(false) }

    log.d { "ImageContent: loading $url" }

    if (hasError) {
        ErrorState(
            message = stringResource(Res.string.detail_failed_image),
            onRetry = { hasError = false }
        )
    } else if (LocalAppActive.current) {
        CoilZoomAsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            scrollBar = null,
            onTap = { onTap() }
        )
    } else {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
private fun VideoContent(
    url: String,
    apiKey: String,
    isCurrentPage: Boolean
) {
    log.d { "VideoContent: loading video from $url" }

    DisposableEffect(Unit) {
        onDispose {
            log.d { "VideoContent: disposing, restoring edge-to-edge" }
            restoreEdgeToEdge()
        }
    }

    PlatformVideoPlayer(
        url = url,
        apiKey = apiKey,
        isCurrentPage = isCurrentPage,
        modifier = Modifier.fillMaxSize()
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

@Preview
@Composable
private fun DetailTopBarOverlayPreview() {
    DetailTopBarOverlay(
        showTopBar = true,
        title = "IMG_2024.jpg",
        onBack = {},
        onDownload = {},
        onShare = {},
        onInfo = {}
    )
}

@Preview
@Composable
private fun DetailBottomHandlePreview() {
    DetailBottomHandle(
        visible = true,
        onClick = {}
    )
}

@Preview
@Composable
private fun ErrorStatePreview() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        ErrorState(
            message = "Failed to load image",
            onRetry = {}
        )
    }
}
