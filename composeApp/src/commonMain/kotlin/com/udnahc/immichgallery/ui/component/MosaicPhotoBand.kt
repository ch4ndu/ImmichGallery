package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
    transitionAssetId: String? = null,
    hiddenAssetId: String? = null,
) {
    // Stabilize the band-width computation so the maxOfOrNull walk doesn't
    // re-run on every recomposition (band is @Immutable; tiles list reference
    // is stable for a given MosaicBandItem identity).
    val bandWidthDp = remember(band) {
        band.tiles.maxOfOrNull { it.x + it.width } ?: 0f
    }
    Box(
        modifier = Modifier.size(
            width = bandWidthDp.dp,
            height = band.bandHeight.dp
        )
    ) {
        for (tile in band.tiles) {
            // Stable child identity per tile so swapping or reordering tiles
            // (rare but possible during partial→ready transitions) doesn't
            // re-use the wrong remembered state across cells.
            key(tile.photo.asset.id) {
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
                    transitionAssetId = transitionAssetId,
                    hiddenAssetId = hiddenAssetId,
                )
            }
        }
    }
}
