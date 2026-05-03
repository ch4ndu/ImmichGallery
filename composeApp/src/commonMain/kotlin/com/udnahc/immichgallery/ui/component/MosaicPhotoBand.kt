package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.MosaicBandItem

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MosaicPhotoBand(
    band: MosaicBandItem,
    onPhotoClick: (assetId: String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    hiddenAssetId: String? = null,
) {
    Box(
        modifier = Modifier.size(
            width = band.tiles.maxOfOrNull { it.x + it.width }?.dp ?: 0.dp,
            height = band.bandHeight.dp
        )
    ) {
        for (tile in band.tiles) {
            val onClick = remember(tile.photo.asset.id, onPhotoClick) {
                { onPhotoClick(tile.photo.asset.id) }
            }
            ThumbnailCell(
                asset = tile.photo.asset,
                onClick = onClick,
                modifier = Modifier
                    .offset(x = tile.x.dp, y = tile.y.dp)
                    .size(width = tile.width.dp, height = tile.height.dp),
                sharedTransitionScope = sharedTransitionScope,
                hiddenAssetId = hiddenAssetId,
            )
        }
    }
}
