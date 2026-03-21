package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.detail_video
import immichgallery.composeapp.generated.resources.ic_play
import immichgallery.composeapp.generated.resources.ic_stack
import immichgallery.composeapp.generated.resources.stack_count
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val VIDEO_OVERLAY_ALPHA = 0.5f
private const val STACK_BADGE_ALPHA = 0.6f

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
        if (asset.stackCount > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(
                        Color.Black.copy(alpha = STACK_BADGE_ALPHA),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_stack),
                    contentDescription = stringResource(Res.string.stack_count),
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "${asset.stackCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}
