package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DEFAULT_GRID_COLUMN_COUNT
import com.udnahc.immichgallery.ui.theme.Dimens

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoRow(
    assets: List<Asset>,
    allAssets: List<Asset>,
    onPhotoClick: (List<Asset>, Int) -> Unit,
    columns: Int = DEFAULT_GRID_COLUMN_COUNT,
    sharedTransitionScope: SharedTransitionScope? = null,
    hiddenAssetId: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gridSpacing)
    ) {
        assets.forEach { asset ->
            val onClick = remember(asset.id, onPhotoClick) {
                {
                    val index = allAssets.indexOf(asset)
                    onPhotoClick(allAssets, index)
                }
            }
            ThumbnailCell(
                asset = asset,
                onClick = onClick,
                modifier = Modifier.weight(1f),
                sharedTransitionScope = sharedTransitionScope,
                hiddenAssetId = hiddenAssetId,
            )
        }
        repeat(columns - assets.size) {
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Preview
@Composable
private fun PhotoRowPreview() {
    val sampleAssets = listOf(
        Asset(
            id = "1", type = AssetType.IMAGE, fileName = "photo1.jpg",
            createdAt = "", thumbnailUrl = "", originalUrl = ""
        ),
        Asset(
            id = "2", type = AssetType.IMAGE, fileName = "photo2.jpg",
            createdAt = "", thumbnailUrl = "", originalUrl = ""
        ),
        Asset(
            id = "3", type = AssetType.VIDEO, fileName = "video1.mp4",
            createdAt = "", thumbnailUrl = "", originalUrl = ""
        )
    )
    PhotoRow(assets = sampleAssets, allAssets = sampleAssets, onPhotoClick = { _, _ -> })
}
