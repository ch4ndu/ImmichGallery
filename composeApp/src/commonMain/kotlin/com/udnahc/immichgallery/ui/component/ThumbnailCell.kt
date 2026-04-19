package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.LocalPhotoBoundsTween
import com.udnahc.immichgallery.ui.util.photoTransitionFadeIn
import com.udnahc.immichgallery.ui.util.photoTransitionFadeOut
import com.udnahc.immichgallery.ui.util.rememberPhotoBoundsTransform
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.detail_video
import immichgallery.composeapp.generated.resources.ic_play
import immichgallery.composeapp.generated.resources.ic_stack
import immichgallery.composeapp.generated.resources.stack_count
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val THUMBNAIL_DECODE_SIZE = 256
private const val VIDEO_OVERLAY_ALPHA = 0.5f
private const val STACK_BADGE_ALPHA = 0.6f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ThumbnailCell(
    asset: Asset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    hiddenAssetId: String? = null,
) {
    // Per-cell AnimatedVisibility: only the viewed asset's cell transitions.
    // Its scope drives the sharedElement bounds animation while the rest of
    // the grid stays composed and visible behind the detail overlay.
    AnimatedVisibility(
        visible = hiddenAssetId != asset.id,
        enter = photoTransitionFadeIn,
        exit = photoTransitionFadeOut,
        modifier = modifier,
    ) {
        // sharedElement is attached to the Box (with fillMaxSize) rather than
        // the Image (with matchParentSize). The matchParentSize + sharedElement
        // combination caused the grid-side source bounds to be miscaptured at
        // 2×cell_Y in screens that re-enter the nav composable (Album), which
        // made the open transition fly in from off-screen below.
        val boundsTransform = rememberPhotoBoundsTransform()
        // Only hoist the shared element to the overlay layer during genuine
        // open/dismiss animations; otherwise (steady-state / pager swipe)
        // keep it in-place so a mid-browse AV transition can't render a
        // "ghost" thumbnail above the detail image.
        val hoistInOverlay = LocalPhotoBoundsTween.current
        val boxModifier = if (sharedTransitionScope != null) {
            with(sharedTransitionScope) {
                Modifier.fillMaxSize().sharedElement(
                    sharedTransitionScope.rememberSharedContentState(key = "thumb_${asset.id}"),
                    animatedVisibilityScope = this@AnimatedVisibility,
                    boundsTransform = boundsTransform,
                    renderInOverlayDuringTransition = hoistInOverlay,
                ).clickable(onClick = onClick)
            }
        } else {
            Modifier.fillMaxSize().clickable(onClick = onClick)
        }
        Box(modifier = boxModifier) {
            if (LocalAppActive.current) {
                val imageModifier = Modifier.matchParentSize()
                val painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(asset.thumbnailUrl)
                        .size(Size(THUMBNAIL_DECODE_SIZE, THUMBNAIL_DECODE_SIZE))
                        .precision(Precision.EXACT)
                        // Stable key so the full-image request can reference this
                        // cached thumbnail as its placeholder (see AssetPage).
                        .memoryCacheKey(asset.thumbnailUrl)
                        .build()
                )
                Image(
                    painter = painter,
                    contentDescription = asset.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier
                )
            } else {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            if (asset.type == AssetType.VIDEO) {
                Icon(
                    painter = painterResource(Res.drawable.ic_play),
                    contentDescription = stringResource(Res.string.detail_video),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(Dimens.playIconSize)
                        .background(Color.Black.copy(alpha = VIDEO_OVERLAY_ALPHA), CircleShape)
                        .padding(Dimens.playIconPadding)
                )
            }
            if (asset.stackCount > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.smallSpacing)
                        .background(
                            Color.Black.copy(alpha = STACK_BADGE_ALPHA),
                            RoundedCornerShape(Dimens.smallSpacing)
                        )
                        .padding(horizontal = Dimens.smallSpacing, vertical = Dimens.stackBadgePaddingVertical),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_stack),
                        contentDescription = stringResource(Res.string.stack_count),
                        tint = Color.White,
                        modifier = Modifier.size(Dimens.stackBadgeIconSize)
                    )
                    Text(
                        text = "${asset.stackCount}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = Dimens.stackBadgePaddingVertical)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun ThumbnailCellPreview() {
    ThumbnailCell(
        asset = Asset(
            id = "1",
            type = AssetType.IMAGE,
            fileName = "photo.jpg",
            createdAt = "",
            thumbnailUrl = "",
            originalUrl = ""
        ),
        onClick = {},
        modifier = Modifier.aspectRatio(1f)
    )
}

@Preview
@Composable
private fun ThumbnailCellVideoPreview() {
    ThumbnailCell(
        asset = Asset(
            id = "2",
            type = AssetType.VIDEO,
            fileName = "video.mp4",
            createdAt = "",
            thumbnailUrl = "",
            originalUrl = "",
            stackCount = 3
        ),
        onClick = {},
        modifier = Modifier.aspectRatio(1f)
    )
}
