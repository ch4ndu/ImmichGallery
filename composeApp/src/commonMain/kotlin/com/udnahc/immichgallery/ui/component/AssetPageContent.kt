package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.SlideshowMotion
import com.udnahc.immichgallery.domain.model.SlideshowVideoPolicy
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import androidx.compose.runtime.key
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.ui.model.StackUiMessage
import com.udnahc.immichgallery.ui.model.asText
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.LocalPhotoBoundsTween
import com.udnahc.immichgallery.ui.util.rememberPhotoBoundsTransform
import com.udnahc.immichgallery.ui.util.restoreEdgeToEdge
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.back
import immichgallery.composeapp.generated.resources.checking_stack
import immichgallery.composeapp.generated.resources.detail_failed_image
import immichgallery.composeapp.generated.resources.detail_info
import immichgallery.composeapp.generated.resources.detail_slideshow
import immichgallery.composeapp.generated.resources.ic_back
import immichgallery.composeapp.generated.resources.ic_info
import immichgallery.composeapp.generated.resources.ic_more_vert
import immichgallery.composeapp.generated.resources.ic_play
import immichgallery.composeapp.generated.resources.ic_stack
import immichgallery.composeapp.generated.resources.ic_fullscreen
import immichgallery.composeapp.generated.resources.ic_fullscreen_exit
import immichgallery.composeapp.generated.resources.viewer_enter_fullscreen
import immichgallery.composeapp.generated.resources.viewer_exit_fullscreen
import immichgallery.composeapp.generated.resources.more_options
import immichgallery.composeapp.generated.resources.retry
import immichgallery.composeapp.generated.resources.retry_stack_check
import immichgallery.composeapp.generated.resources.show_photo_information
import immichgallery.composeapp.generated.resources.view_stack
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.lighthousegames.logging.logging
import kotlin.math.roundToInt

internal const val DETAIL_BAR_ALPHA = 0.4f
internal const val API_KEY_HEADER = "x-api-key"

internal sealed interface StackMenuPresentation {
    data object ViewStack : StackMenuPresentation
    data object Checking : StackMenuPresentation
    data class Failed(val message: StackUiMessage) : StackMenuPresentation
    data object Hidden : StackMenuPresentation
}

internal fun stackMenuPresentation(
    stackCount: Int,
    availability: StackAvailability
): StackMenuPresentation = when (availability) {
    StackAvailability.Unknown -> if (stackCount > 0) {
        StackMenuPresentation.ViewStack
    } else {
        StackMenuPresentation.Hidden
    }
    is StackAvailability.Available -> StackMenuPresentation.ViewStack
    is StackAvailability.Checking -> StackMenuPresentation.Checking
    is StackAvailability.Failed -> StackMenuPresentation.Failed(availability.message)
    StackAvailability.NoStack -> StackMenuPresentation.Hidden
}

private const val ERROR_TEXT_ALPHA = 0.8f
private const val ERROR_ICON_ALPHA = 0.6f

private val log = logging("AssetPageContent")

private const val THUMBNAIL_HIDE_DELAY_MS = 500L
private const val IMAGE_CROSSFADE_MS = 200
private const val PHOTO_LAYER_HANDOFF_FADE_MS = 100
private const val THUMBNAIL_DECODE_SIZE = 256

internal data class StillImageLoadState(
    val url: String,
    val retryNonce: Int = 0,
    val hasError: Boolean = false
) {
    val requestIdentity: StillImageRequestIdentity
        get() = StillImageRequestIdentity(url = url, retryNonce = retryNonce)
}

internal data class StillImageRequestIdentity(
    val url: String,
    val retryNonce: Int
)

internal fun stillImageLoadStateForUrl(
    current: StillImageLoadState?,
    url: String
): StillImageLoadState =
    if (current?.url == url) current else StillImageLoadState(url = url)

internal fun StillImageLoadState.markLoadingOrSuccess(
    requestIdentity: StillImageRequestIdentity
): StillImageLoadState =
    if (this.requestIdentity == requestIdentity) copy(hasError = false) else this

internal fun StillImageLoadState.markError(
    requestIdentity: StillImageRequestIdentity
): StillImageLoadState =
    if (this.requestIdentity == requestIdentity) copy(hasError = true) else this

internal fun StillImageLoadState.retry(url: String): StillImageLoadState =
    if (this.url == url) {
        copy(retryNonce = retryNonce + 1, hasError = false)
    } else {
        StillImageLoadState(url = url)
    }

@Immutable
internal data class PhotoDragTransform(
    val active: Boolean = false,
    val scale: Float = 1f,
    val translation: Offset = Offset.Zero,
    val startPosition: Offset = Offset.Zero,
    val dismissCommitted: Boolean = false,
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

private val LocalSharedElementAssetId = compositionLocalOf<String?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AssetPage(
    asset: Asset,
    sharedElementAssetId: String = asset.id,
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
    playback: SlideshowPlaybackState? = null,
    slideshowProgress: () -> Float = { 0f },
    onSlideshowEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit = { _, _ -> },
) {
    LocalViewerPlaybackEnvironment.current?.let { environment ->
        environment.mediaContent(ViewerMediaPresentation(asset, isCurrentPage, isSlideshow, playback,
            slideshowProgress, onTap, onSlideshowEvent))
        return
    }
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

    CompositionLocalProvider(LocalSharedElementAssetId provides sharedElementAssetId) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (!isSlideshow && asset.type != AssetType.VIDEO) {
                StillAssetPage(
                    asset = asset,
                    isCurrentPage = isCurrentPage,
                    onTap = onTap,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    dragTransform = dragTransform,
                    effectiveCoverThumbnail = effectiveCoverThumbnail,
                    fullImageAlpha = fullImageAlpha.value,
                    isTransitionActive = isTransitionActive,
                    onZoomStateChanged = onZoomStateChanged
                )
            } else {
                VideoOrSlideshowAssetPage(
                    asset = asset,
                    apiKey = apiKey,
                    isCurrentPage = isCurrentPage,
                    isSlideshow = isSlideshow,
                    slideshowConfig = slideshowConfig,
                    onTap = onTap,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    dragTransform = dragTransform,
                    effectiveCoverThumbnail = effectiveCoverThumbnail,
                    isTransitionActive = isTransitionActive,
                    playback = playback,
                    slideshowProgress = slideshowProgress,
                    onSlideshowEvent = onSlideshowEvent
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun StillAssetPage(
    asset: Asset,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    dragTransform: PhotoDragTransform,
    effectiveCoverThumbnail: Boolean,
    fullImageAlpha: Float,
    isTransitionActive: Boolean,
    onZoomStateChanged: ((Boolean) -> Unit)?
) {
    val zoomState = rememberCoilZoomState()
    var imageLoadState by remember(asset.originalUrl) {
        mutableStateOf(StillImageLoadState(url = asset.originalUrl))
    }
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
                    loadState = imageLoadState,
                    onLoadStateChanged = { update ->
                        imageLoadState = update(imageLoadState)
                    },
                    onTap = onTap,
                    zoomState = zoomState,
                    modifier = Modifier.alpha(fullImageAlpha)
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
                loadState = imageLoadState,
                onLoadStateChanged = { update ->
                    imageLoadState = update(imageLoadState)
                },
                onTap = onTap,
                zoomState = zoomState,
                modifier = Modifier.alpha(fullImageAlpha)
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun VideoOrSlideshowAssetPage(
    asset: Asset,
    apiKey: String,
    isCurrentPage: Boolean,
    isSlideshow: Boolean,
    slideshowConfig: SlideshowConfig?,
    onTap: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    dragTransform: PhotoDragTransform,
    effectiveCoverThumbnail: Boolean,
    isTransitionActive: Boolean,
    playback: SlideshowPlaybackState?,
    slideshowProgress: () -> Float,
    onSlideshowEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit
) {
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

    if (!isTransitionActive && isSlideshow) {
        SlideshowAssetPage(
            asset = asset,
            isCurrentPage = isCurrentPage,
            slideshowConfig = slideshowConfig,
            onTap = onTap,
            apiKey = apiKey,
            playback = playback,
            progress = slideshowProgress,
            onEvent = onSlideshowEvent
        )
    } else if (asset.type == AssetType.VIDEO && (dragTransform.active || !isTransitionActive)) {
        VideoAssetPage(
            asset = asset,
            apiKey = apiKey,
            isCurrentPage = isCurrentPage,
            onTap = onTap,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            dragTransform = dragTransform
        )
    }
}

@Composable
private fun SlideshowAssetPage(
    asset: Asset,
    isCurrentPage: Boolean,
    slideshowConfig: SlideshowConfig?,
    onTap: () -> Unit,
    apiKey: String,
    playback: SlideshowPlaybackState?,
    progress: () -> Float,
    onEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit
) {
    val request = playback?.request?.takeIf { it.assetId == asset.id }
    val imageUrl = if (asset.type == AssetType.VIDEO) asset.thumbnailUrl else asset.originalUrl
    Box(Modifier.fillMaxSize().pointerInput(onTap) { detectTapGestures { onTap() } }) {
        if (request != null && asset.type == AssetType.VIDEO && slideshowConfig?.videoPolicy == SlideshowVideoPolicy.PLAY) {
            key(request) {
                PlatformVideoPlayer(asset.videoPlaybackUrl, asset.originalUrl, apiKey,
                    isCurrentPage = isCurrentPage && playback.playMedia,
                    onTap = onTap, modifier = Modifier.fillMaxSize(),
                    slideshowRequest = request, muted = slideshowConfig.muted, onSlideshowEvent = onEvent)
            }
        } else if (request != null) {
            key(request) {
                KenBurnsImage(url = imageUrl, progress = progress,
                    motionEnabled = slideshowConfig?.motion == SlideshowMotion.PAN_AND_ZOOM,
                    thumbnailCacheKey = asset.thumbnailCacheKey,
                    onReady = { onEvent(request, SlideshowMediaEvent.ImageReady) },
                    onError = { onEvent(request, SlideshowMediaEvent.Failed) })
            }
        } else {
            AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun VideoAssetPage(
    asset: Asset,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    dragTransform: PhotoDragTransform
) {
    PaddedTransformableMediaLayer(
        asset = asset,
        dragTransform = dragTransform,
        useDragBounds = dragTransform.active,
    ) { mediaModifier ->
        Box(modifier = mediaModifier) {
            VideoDragThumbnailLayer(
                asset = asset,
                dragTransform = dragTransform,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.fillMaxSize(),
            )
            if (!dragTransform.dismissCommitted) {
                VideoContent(
                    playbackUrl = asset.videoPlaybackUrl,
                    originalUrl = asset.originalUrl,
                    apiKey = apiKey,
                    isCurrentPage = isCurrentPage,
                    onTap = onTap,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun VideoDragThumbnailLayer(
    asset: Asset,
    dragTransform: PhotoDragTransform,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier,
) {
    if (dragTransform.active) {
        SharedPhotoThumbnailLayer(
            asset = asset,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            coverThumbnail = false,
            modifier = modifier,
        )
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
            sharedTransitionScope.rememberSharedContentState(
                key = "thumb_${LocalSharedElementAssetId.current ?: asset.id}"
            ),
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
    Box(modifier = modifier) {
        if (LocalAppActive.current) {
            val thumbnailContext = LocalPlatformContext.current
            val thumbnailRequest =
                remember(thumbnailContext, asset.thumbnailUrl, asset.thumbnailCacheKey) {
                    ImageRequest.Builder(thumbnailContext)
                        .data(asset.thumbnailUrl)
                        .size(Size(THUMBNAIL_DECODE_SIZE, THUMBNAIL_DECODE_SIZE))
                        .precision(Precision.EXACT)
                        .memoryCacheKey(asset.thumbnailCacheKey)
                        .diskCacheKey(asset.thumbnailCacheKey)
                        .build()
                }
            val painter = rememberAsyncImagePainter(model = thumbnailRequest)
            Image(
                painter = painter,
                contentDescription = asset.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

@Composable
internal fun DetailTopBarOverlay(
    showTopBar: Boolean,
    title: String,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onSlideshow: (() -> Unit)? = null,
    stackCount: Int = 0,
    stackAvailability: StackAvailability = StackAvailability.Unknown,
    onStackMenuOpened: (() -> Unit)? = null,
    onViewStack: (() -> Unit)? = null,
    onRetryStackCheck: (() -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false
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
                if (onToggleFullscreen != null) IconButton(onClick = onToggleFullscreen) {
                    Icon(painterResource(if (isFullscreen) Res.drawable.ic_fullscreen_exit else Res.drawable.ic_fullscreen),
                        contentDescription = stringResource(if (isFullscreen) Res.string.viewer_exit_fullscreen else Res.string.viewer_enter_fullscreen),
                        tint = Color.White)
                }
                Box {
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = {
                            onStackMenuOpened?.invoke()
                            menuExpanded = true
                        },
                        modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_more_vert),
                            contentDescription = stringResource(Res.string.more_options),
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        when (
                            val stackPresentation = stackMenuPresentation(
                                stackCount,
                                stackAvailability
                            )
                        ) {
                            StackMenuPresentation.ViewStack -> if (onViewStack != null) {
                                DropdownMenuItem(
                                    modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                                    text = { Text(stringResource(Res.string.view_stack)) },
                                    onClick = { menuExpanded = false; onViewStack() },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(Res.drawable.ic_stack),
                                            contentDescription = null
                                        )
                                    }
                                )
                            }

                            StackMenuPresentation.Checking -> DropdownMenuItem(
                                modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                                text = { Text(stringResource(Res.string.checking_stack)) },
                                onClick = {},
                                enabled = false,
                                leadingIcon = {
                                    Icon(
                                        painterResource(Res.drawable.ic_stack),
                                        contentDescription = null
                                    )
                                }
                            )

                            is StackMenuPresentation.Failed -> if (onRetryStackCheck != null) {
                                DropdownMenuItem(
                                    modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                                    text = { Text(stackPresentation.message.asText()) },
                                    onClick = {},
                                    enabled = false,
                                    leadingIcon = {
                                        Icon(
                                            painterResource(Res.drawable.ic_stack),
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.padding(horizontal = Dimens.smallSpacing),
                                    text = { Text(stringResource(Res.string.retry_stack_check)) },
                                    onClick = { menuExpanded = false; onRetryStackCheck() },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(Res.drawable.ic_stack),
                                            contentDescription = null
                                        )
                                    }
                                )
                            }

                            StackMenuPresentation.Hidden -> Unit
                        }
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
    val description = stringResource(Res.string.show_photo_information)
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }
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
    loadState: StillImageLoadState,
    onLoadStateChanged: ((StillImageLoadState) -> StillImageLoadState) -> Unit,
    onTap: () -> Unit,
    zoomState: com.github.panpf.zoomimage.CoilZoomState,
    modifier: Modifier = Modifier
) {
    val requestIdentity = loadState.requestIdentity
    log.d { "ImageContent: loading <redacted>" }

    // Use the cached thumbnail as a placeholder so a slow network doesn't
    // leave a blank frame between the end of the shared-element transition
    // and the full-resolution image arriving. The short crossfade smooths
    // the thumbnail→original handoff.
    val context = LocalPlatformContext.current
    val imageRequest = remember(context, url, thumbnailCacheKey, loadState.retryNonce) {
        ImageRequest.Builder(context)
            .data(url)
            .placeholderMemoryCacheKey(thumbnailCacheKey)
            .memoryCacheKeyExtra("detailRetryNonce", loadState.retryNonce.toString())
            .crossfade(IMAGE_CROSSFADE_MS)
            .build()
    }

    if (loadState.hasError) {
        ErrorState(
            message = stringResource(Res.string.detail_failed_image),
            onRetry = { onLoadStateChanged { current -> current.retry(url) } }
        )
    } else if (LocalAppActive.current) {
        CoilZoomAsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            zoomState = zoomState,
            scrollBar = null,
            onLoading = {
                onLoadStateChanged { current ->
                    current.markLoadingOrSuccess(requestIdentity)
                }
            },
            onSuccess = {
                onLoadStateChanged { current ->
                    current.markLoadingOrSuccess(requestIdentity)
                }
            },
            onError = {
                onLoadStateChanged { current -> current.markError(requestIdentity) }
            },
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
    log.d { "VideoContent: loading video from <redacted>" }

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
