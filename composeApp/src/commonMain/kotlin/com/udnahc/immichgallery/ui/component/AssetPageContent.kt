package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.lighthousegames.logging.logging

internal const val DETAIL_BAR_ALPHA = 0.4f
internal const val API_KEY_HEADER = "x-api-key"

private const val ERROR_TEXT_ALPHA = 0.8f
private const val ERROR_ICON_ALPHA = 0.6f

private val log = logging("AssetPageContent")

private const val THUMBNAIL_HIDE_DELAY_MS = 500L
private const val IMAGE_CROSSFADE_MS = 200
private const val PHOTO_LAYER_HANDOFF_FADE_MS = 100

@Immutable
internal data class PhotoDragTransform(
    val active: Boolean = false,
    val scale: Float = 1f,
    val translation: Offset = Offset.Zero,
    val startPosition: Offset = Offset.Zero,
) {
    companion object {
        val Idle = PhotoDragTransform()
    }
}

private data class AnchoredMediaLayout(
    val widthPx: Float,
    val heightPx: Float,
    val offset: Offset,
)

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
    dragTransform: PhotoDragTransform = PhotoDragTransform.Idle,
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
    val effectiveCoverThumbnail = coverThumbnail && !useTween
    val fullImageAlpha = remember(asset.id) { Animatable(if (useTween) 0f else 1f) }
    var fullImageBecameVisible by remember(asset.id) { mutableStateOf(!useTween) }
    LaunchedEffect(asset.id, useTween) {
        if (useTween) {
            if (fullImageBecameVisible) {
                fullImageAlpha.animateTo(0f, tween(PHOTO_LAYER_HANDOFF_FADE_MS))
            } else {
                fullImageAlpha.snapTo(0f)
            }
        } else {
            fullImageAlpha.animateTo(1f, tween(PHOTO_LAYER_HANDOFF_FADE_MS))
            fullImageBecameVisible = true
        }
    }

    // - During open/dismiss: reveal thumbnail (sharedElement source).
    // - During drag-before-dismiss: COVER thumbnail immediately. It stays
    //   mounted inside the same fitted container as the full-res image so the
    //   release handoff keeps one set of bounds, but only the sharp image is
    //   visible while following the finger.
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
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (!isSlideshow && asset.type != AssetType.VIDEO) {
            // Image with zoom — draw under system bars when zoomed/panned.
            val zoomState = rememberCoilZoomState()
            val userTransform = zoomState.zoomable.userTransform
            val isZoomed = userTransform.scaleX > 1.01f ||
                userTransform.offsetX != 0f || userTransform.offsetY != 0f
            LaunchedEffect(isZoomed, isCurrentPage) {
                if (isCurrentPage) onZoomStateChanged?.invoke(isZoomed)
            }

            if (!dragTransform.active) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    SharedPhotoThumbnailLayer(
                        asset = asset,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        coverThumbnail = effectiveCoverThumbnail,
                        modifier = Modifier.aspectRatio(asset.aspectRatio),
                    )
                }
            }

            if (dragTransform.active) {
                PaddedTransformableMediaLayer(
                    asset = asset,
                    dragTransform = dragTransform,
                    useDragBounds = true,
                ) { mediaModifier ->
                    Box(modifier = mediaModifier) {
                        SharedPhotoThumbnailLayer(
                            asset = asset,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            coverThumbnail = effectiveCoverThumbnail,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ImageContent(
                            url = asset.originalUrl,
                            thumbnailCacheKey = asset.thumbnailCacheKey,
                            onTap = onTap,
                            zoomState = zoomState,
                            modifier = Modifier.alpha(fullImageAlpha.value)
                        )
                    }
                }
            } else if (!isTransitionActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (!isZoomed) {
                                Modifier
                                    .statusBarsPadding()
                                    .navigationBarsPadding()
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    ImageContent(
                        url = asset.originalUrl,
                        thumbnailCacheKey = asset.thumbnailCacheKey,
                        onTap = onTap,
                        zoomState = zoomState,
                        modifier = Modifier.alpha(fullImageAlpha.value)
                    )
                }
            }
        } else {
            if (!dragTransform.active) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    SharedPhotoThumbnailLayer(
                        asset = asset,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        coverThumbnail = effectiveCoverThumbnail,
                        modifier = Modifier.aspectRatio(asset.aspectRatio),
                    )
                }
            }

            // Content layer: full-res image or video, layered on top after transition completes.
            if (!isTransitionActive && isSlideshow) {
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
                            thumbnailCacheKey = asset.thumbnailCacheKey,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else if (asset.type == AssetType.VIDEO && !isTransitionActive) {
                PaddedTransformableMediaLayer(
                    asset = asset,
                    dragTransform = dragTransform,
                    useDragBounds = dragTransform.active,
                ) { mediaModifier ->
                    Box(modifier = mediaModifier) {
                        VideoContent(
                            playbackUrl = asset.videoPlaybackUrl,
                            originalUrl = asset.originalUrl,
                            apiKey = apiKey,
                            isCurrentPage = isCurrentPage,
                            onTap = onTap,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (dragTransform.active) {
                            SharedPhotoThumbnailLayer(
                                asset = asset,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                coverThumbnail = effectiveCoverThumbnail,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaddedTransformableMediaLayer(
    asset: Asset,
    dragTransform: PhotoDragTransform,
    useDragBounds: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        var containerRoot by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    containerRoot = coordinates.positionInRoot()
                },
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val mediaModifier = if (useDragBounds) {
                val layout = computeAnchoredMediaLayout(
                    containerSize = IntSize(constraints.maxWidth, constraints.maxHeight),
                    mediaAspectRatio = asset.aspectRatio,
                    tapInContainer = dragTransform.startPosition - containerRoot,
                    translation = dragTransform.translation,
                    scale = dragTransform.scale,
                )
                Modifier
                    .size(
                        width = with(density) { layout.widthPx.toDp() },
                        height = with(density) { layout.heightPx.toDp() },
                    )
                    .offset {
                        IntOffset(
                            x = layout.offset.x.roundToInt(),
                            y = layout.offset.y.roundToInt(),
                        )
                    }
            } else {
                Modifier.fillMaxSize()
            }
            content(mediaModifier)
        }
    }
}

private fun computeAnchoredMediaLayout(
    containerSize: IntSize,
    mediaAspectRatio: Float,
    tapInContainer: Offset,
    translation: Offset,
    scale: Float,
): AnchoredMediaLayout {
    val containerWidth = containerSize.width.coerceAtLeast(0).toFloat()
    val containerHeight = containerSize.height.coerceAtLeast(0).toFloat()
    if (containerWidth == 0f || containerHeight == 0f) {
        return AnchoredMediaLayout(widthPx = 0f, heightPx = 0f, offset = Offset.Zero)
    }

    val safeAspectRatio = mediaAspectRatio.takeIf { it > 0f } ?: 1f
    val containerAspectRatio = containerWidth / containerHeight
    val fittedWidth = if (containerAspectRatio > safeAspectRatio) {
        containerHeight * safeAspectRatio
    } else {
        containerWidth
    }
    val fittedHeight = if (containerAspectRatio > safeAspectRatio) {
        containerHeight
    } else {
        containerWidth / safeAspectRatio
    }
    val fittedTopLeft = Offset(
        x = (containerWidth - fittedWidth) / 2f,
        y = (containerHeight - fittedHeight) / 2f,
    )
    val clampedTap = Offset(
        x = tapInContainer.x.coerceIn(fittedTopLeft.x, fittedTopLeft.x + fittedWidth),
        y = tapInContainer.y.coerceIn(fittedTopLeft.y, fittedTopLeft.y + fittedHeight),
    )
    // Keep the original touched media point under the finger while the
    // fitted box scales. The returned offset is relative to Box's normal
    // centered placement of the scaled media box.
    val localAnchor = clampedTap - fittedTopLeft
    val fingerNow = clampedTap + translation
    val safeScale = scale.coerceAtLeast(0f)
    val scaledWidth = fittedWidth * safeScale
    val scaledHeight = fittedHeight * safeScale
    val scaledTopLeft = fingerNow - Offset(
        x = localAnchor.x * safeScale,
        y = localAnchor.y * safeScale,
    )
    val centeredScaledTopLeft = Offset(
        x = (containerWidth - scaledWidth) / 2f,
        y = (containerHeight - scaledHeight) / 2f,
    )
    return AnchoredMediaLayout(
        widthPx = scaledWidth,
        heightPx = scaledHeight,
        offset = scaledTopLeft - centeredScaledTopLeft,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedPhotoThumbnailLayer(
    asset: Asset,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    coverThumbnail: Boolean,
    modifier: Modifier,
) {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return
    val boundsTransform = rememberPhotoBoundsTransform()
    val hoistInOverlay = LocalPhotoBoundsTween.current
    val sharedModifier = with(sharedTransitionScope) {
        modifier.sharedElement(
            sharedTransitionScope.rememberSharedContentState(key = "thumb_${asset.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = boundsTransform,
            renderInOverlayDuringTransition = hoistInOverlay,
        )
    }
    ThumbnailImageLayer(
        asset = asset,
        modifier = sharedModifier.alpha(if (coverThumbnail) 0f else 1f)
    )
}

@Composable
private fun ThumbnailImageLayer(
    asset: Asset,
    modifier: Modifier,
) {
    val thumbnailContext = LocalPlatformContext.current
    val thumbnailRequest = remember(thumbnailContext, asset.thumbnailUrl, asset.thumbnailCacheKey) {
        ImageRequest.Builder(thumbnailContext)
            .data(asset.thumbnailUrl)
            .memoryCacheKey(asset.thumbnailCacheKey)
            .diskCacheKey(asset.thumbnailCacheKey)
            .build()
    }
    AsyncImage(
        model = thumbnailRequest,
        contentDescription = asset.fileName,
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxSize()
    )
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
    thumbnailCacheKey: String,
    onTap: () -> Unit,
    zoomState: com.github.panpf.zoomimage.CoilZoomState,
    modifier: Modifier = Modifier
) {
    var hasError by remember { mutableStateOf(false) }

    log.d { "ImageContent: loading $url" }

    // Use the cached thumbnail as a placeholder so a slow network doesn't
    // leave a blank frame between the end of the shared-element transition
    // and the full-resolution image arriving. The short crossfade smooths
    // the thumbnail→original handoff.
    val context = LocalPlatformContext.current
    val imageRequest = remember(context, url, thumbnailCacheKey) {
        ImageRequest.Builder(context)
            .data(url)
            .placeholderMemoryCacheKey(thumbnailCacheKey)
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
            modifier = modifier.fillMaxSize(),
            zoomState = zoomState,
            scrollBar = null,
            onTap = { onTap() }
        )
    } else {
        Box(
            modifier.fillMaxSize()
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
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxSize()
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
