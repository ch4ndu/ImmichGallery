package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.theme.Dimens
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.detail_video
import immichgallery.composeapp.generated.resources.ic_play

private const val VIDEO_OVERLAY_ALPHA = 0.5f

@Composable
fun ThumbnailCell(
    asset: Asset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        if (LocalAppActive.current) {
            AsyncImage(
                model = asset.thumbnailUrl,
                contentDescription = asset.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
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
    }
}
