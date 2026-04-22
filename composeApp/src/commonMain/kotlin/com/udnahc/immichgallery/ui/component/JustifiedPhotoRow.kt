package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.RowItem

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun JustifiedPhotoRow(
    row: RowItem,
    spacing: Dp,
    onPhotoClick: (assetId: String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    hiddenAssetId: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(row.rowHeight.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        for (photo in row.photos) {
            // Complete rows: weight distributes width proportionally, filling the row
            // Incomplete rows: use aspectRatio so items keep natural proportions
            val cellModifier = if (row.isComplete) {
                Modifier.weight(photo.asset.aspectRatio).fillMaxHeight()
            } else {
                Modifier.aspectRatio(photo.asset.aspectRatio).fillMaxHeight()
            }
            val onClick = remember(photo.asset.id, onPhotoClick) {
                { onPhotoClick(photo.asset.id) }
            }
            ThumbnailCell(
                asset = photo.asset,
                onClick = onClick,
                modifier = cellModifier,
                sharedTransitionScope = sharedTransitionScope,
                hiddenAssetId = hiddenAssetId,
            )
        }
    }
}

@Preview
@Composable
private fun JustifiedPhotoRowPreview() {
    fun previewAsset(id: String, aspect: Float) = Asset(
        id = id,
        type = AssetType.IMAGE,
        fileName = "IMG_$id.jpg",
        createdAt = "",
        thumbnailUrl = "",
        originalUrl = "",
        aspectRatio = aspect,
    )
    val row = RowItem(
        gridKey = "preview-row",
        bucketIndex = 0,
        sectionLabel = "",
        photos = listOf(
            PhotoItem(gridKey = "1", bucketIndex = 0, sectionLabel = "", asset = previewAsset("1", 1.5f)),
            PhotoItem(gridKey = "2", bucketIndex = 0, sectionLabel = "", asset = previewAsset("2", 1.0f)),
            PhotoItem(gridKey = "3", bucketIndex = 0, sectionLabel = "", asset = previewAsset("3", 0.75f)),
        ),
        rowHeight = 120f,
    )
    JustifiedPhotoRow(row = row, spacing = 4.dp, onPhotoClick = {})
}
