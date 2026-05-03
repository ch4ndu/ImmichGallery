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
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.SlideshowAnimations
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.LocalPhotoBoundsTween
import com.udnahc.immichgallery.ui.util.rememberPhotoBoundsTransform
import com.udnahc.immichgallery.ui.util.restoreEdgeToEdge
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.back
import immichgallery.composeapp.generated.resources.detail_slideshow
import immichgallery.composeapp.generated.resources.detail_failed_image
import immichgallery.composeapp.generated.resources.detail_info
import immichgallery.composeapp.generated.resources.ic_back
import immichgallery.composeapp.generated.resources.ic_play
import immichgallery.composeapp.generated.resources.ic_info
import immichgallery.composeapp.generated.resources.ic_more_vert
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
private const val IMAGE_CROSSFADE_MS = 200

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AssetPage(
    asset: Asset,
    apiKey: String,
    isCurrentPage: Boolean,
    isSlideshow: Boolean = false,
    slideshowConfig: SlideshowConfig? = null,
    onTap: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    pageTransform: Modifier = Modifier,
    isDragging: Boolean = false,
    onZoomStateChanged: ((Boolean) -> Unit)? = null,
) {
    // Only treat the shared-element scope as "actively transitioning" during
    // genuine open/dismiss animations (LocalPhotoBoundsTween = true). Pager
    // swipes re-fire a zero-duration sharedElement transition that would
    // otherwise flip isTransitionActive for one frame — causing the layer
    // switch to briefly render the blurry 256 px thumbnail instead of the
    // sharp full image. Gating here keeps the full image mounted across
    // mid-overlay sharedElement no-ops.
    val useTween = LocalPhotoBoundsTween.current
    val isTransitionActive =
        useTween && (sharedTransitionScope?.isTransitionActive ?: false)
    var coverThumbnail by remember { mutableStateOf(false) }

    // - During open/dismiss: reveal thumbnail (sharedElement source).
    // - During drag-before-dismiss: COVER thumbnail immediately — otherwise the
    //   sharedBounds thumbnail and the full-res ImageContent both render under
    //   the page's drag graphicsLayer at slightly different rects, producing a
    //   visible "duplicate image" behind the one following the finger.
    // - Slideshow: cover immediately — KenBurnsImage uses the cached thumbnail
    //   as its own placeholder via placeholderMemoryCacheKey, so the separate
    //   thumbnail layer would only introduce a 1→0 alpha snap at the 500ms
    //   mark and a layout mismatch (padded aspect-fit vs fullscreen scaled).
    // - Steady state (not transitioning, not dragging, not slideshow): cover
    //   after a short delay so the thumbnail acts as a placeholder while the
    //   full-res image loads after pager swipes.
    LaunchedEffect(isTransitionActive, isDragging, isSlideshow, isCurrentPage) {
        when {
            isTransitionActive -> coverThumbnail = false
            isDragging -> coverThumbnail = true
            isSlideshow -> coverThumbnail = true
            else -> {
                delay(THUMBNAIL_HIDE_DELAY_MS)
                // Re-check after suspend: LaunchedEffect auto-cancels on key
                // changes, but this guard makes the invariant explicit — the
                // thumbnail should only hide while the page is settled on a
                // steady-state, non-transitioning, non-dragging view.
                if (isCurrentPage && !isTransitionActive && !isDragging && !isSlideshow) {
                    coverThumbnail = true
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().then(pageTransform),
        contentAlignment = Alignment.Center
    ) {
        // Base layer: thumbnail with shared bounds modifier — always in composition
        // so the exit animation can find it. The sharedBounds container is sized to
        // the asset's aspect ratio (same as the grid cell), so both sides of the
        // transition have identical layout shape and the cross-fade renders no
        // visible difference.
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            val boundsTransform = rememberPhotoBoundsTransform()
            // Same hoist gating as ThumbnailCell: only hoist during real
            // open/dismiss animations so mid-browse AV state changes don't
            // render a ghost thumbnail over the detail image.
            val hoistInOverlay = LocalPhotoBoundsTween.current
            val sharedModifier = with(sharedTransitionScope) {
                // NOTE: DO NOT chain .fillMaxSize() before .aspectRatio() —
                // fillMaxSize locks min=max=parent size, which forces aspectRatio
                // to fall back to the fullscreen size and the sharedBounds ends up
                // being the whole screen rather than the image-aspect rect. That
                // produces a visible second animation where the detail thumbnail
                // scales 0→1 in the center while the grid rect flies to center.
                Modifier
                    .aspectRatio(asset.aspectRatio)
                    .sharedElement(
                        sharedTransitionScope.rememberSharedContentState(key = "thumb_${asset.id}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = boundsTransform,
                        renderInOverlayDuringTransition = hoistInOverlay,
                    )
            }
            // Pad the sharedBounds container with the same status/nav-bar
            // insets as the (non-zoomed) full-image container below, so the
            // thumbnail's aspect-fit rect matches the full image's aspect-fit
            // rect exactly. Without this the thumbnail flies to a full-screen
            // rect and then the full image loads in a smaller padded rect,
            // producing a visible "jump" on load and a flash on pager swipe
            // whenever isTransitionActive briefly toggles.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
            // alpha instead of a black cover Box: keeps the sharedElement
            // modifier in the tree so bounds stay captured for the exit
            // animation, but makes the thumbnail layer invisible during drag
            // and steady-state viewing. The previous black cover drew its own
            // rect and, under the page's drag graphicsLayer, didn't line up
            // perfectly with the full-res ImageContent → visible as a duplicate.
            Box(modifier = sharedModifier.alpha(if (coverThumbnail) 0f else 1f)) {
                // No padding here — the content inside sharedBounds must match the
                // grid-side thumbnail's layout exactly (same Fit, same fillMaxSize,
                // no bar padding). Any layout mismatch makes the sharedBounds
                // cross-fade visible as a "second animation".
                // Explicit memoryCacheKey keeps this thumbnail entry aligned with
                // the grid's ThumbnailCell cache key — the full-image request in
                // ImageContent references it via placeholderMemoryCacheKey.
                val thumbnailContext = LocalPlatformContext.current
                val thumbnailRequest = remember(thumbnailContext, asset.thumbnailUrl) {
                    ImageRequest.Builder(thumbnailContext)
                        .data(asset.thumbnailUrl)
                        .memoryCacheKey(asset.thumbnailUrl)
                        .build()
                }
                AsyncImage(
                    model = thumbnailRequest,
                    contentDescription = asset.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            }
        }

        // Content layer: full-res image or video, layered on top after transition completes
        if (!isTransitionActive) {
            if (isSlideshow) {
                val imageUrl = if (asset.type == AssetType.VIDEO) asset.thumbnailUrl else asset.originalUrl
                val durationMs = (slideshowConfig?.durationSeconds ?: 5) * 1000
                Box(
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { onTap() }
                    }
                ) {
                    if (slideshowConfig?.animations == SlideshowAnimations.OFF) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        KenBurnsImage(
                            url = imageUrl,
                            isActive = isCurrentPage,
                            durationMs = durationMs,
                            thumbnailUrl = asset.thumbnailUrl,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else if (asset.type == AssetType.VIDEO) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center
                ) {
                    VideoContent(
                        playbackUrl = asset.videoPlaybackUrl,
                        originalUrl = asset.originalUrl,
                        apiKey = apiKey,
                        isCurrentPage = isCurrentPage,
                        onTap = onTap
                    )
                }
            } else {
                // Image with zoom — draw under system bars when zoomed/panned
                val zoomState = rememberCoilZoomState()
                val userTransform = zoomState.zoomable.userTransform
                val isZoomed = userTransform.scaleX > 1.01f ||
                    userTransform.offsetX != 0f || userTransform.offsetY != 0f
                LaunchedEffect(isZoomed, isCurrentPage) {
                    if (isCurrentPage) onZoomStateChanged?.invoke(isZoomed)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (!isZoomed) Modifier
                                .statusBarsPadding()
                                .navigationBarsPadding()
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    ImageContent(
                        url = asset.originalUrl,
                        thumbnailUrl = asset.thumbnailUrl,
                        onTap = onTap,
                        zoomState = zoomState
                    )
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
                    .width(Dimens.detailHandleWidth)
                    .height(Dimens.detailHandleHeight)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun ImageContent(
    url: String,
    thumbnailUrl: String,
    onTap: () -> Unit,
    zoomState: com.github.panpf.zoomimage.CoilZoomState
) {
    var hasError by remember { mutableStateOf(false) }

    log.d { "ImageContent: loading $url" }

    // Use the cached thumbnail as a placeholder so a slow network doesn't
    // leave a blank frame between the end of the shared-element transition
    // and the full-resolution image arriving. The short crossfade smooths
    // the thumbnail→original handoff.
    val context = LocalPlatformContext.current
    val imageRequest = remember(context, url, thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(url)
            .placeholderMemoryCacheKey(thumbnailUrl)
            .crossfade(IMAGE_CROSSFADE_MS)
            .build()
    }

    if (hasError) {
        ErrorState(
            message = stringResource(Res.string.detail_failed_image),
            onRetry = { hasError = false }
        )
    } else if (LocalAppActive.current) {
        CoilZoomAsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            zoomState = zoomState,
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
    playbackUrl: String,
    originalUrl: String,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit
) {
    log.d { "VideoContent: loading video from $playbackUrl" }

    DisposableEffect(Unit) {
        onDispose {
            log.d { "VideoContent: disposing, restoring edge-to-edge" }
            restoreEdgeToEdge()
        }
    }

    PlatformVideoPlayer(
        playbackUrl = playbackUrl,
        originalUrl = originalUrl,
        apiKey = apiKey,
        isCurrentPage = isCurrentPage,
        onTap = onTap,
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
