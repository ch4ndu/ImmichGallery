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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowItemKind

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun JustifiedPhotoRow(
    row: RowItem,
    spacing: Dp,
    onPhotoClick: (assetId: String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    transitionAssetId: String? = null,
    hiddenAssetId: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(row.rowHeight.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        val contentScale = if (row.kind == RowItemKind.MOSAIC_FALLBACK) {
            ContentScale.Crop
        } else {
            ContentScale.Fit
        }
        for (photo in row.photos) {
            // Mosaic fallback rows are completed Mosaic output and should
            // visually fill the row even when row-packing marked them short.
            val cellModifier = if (row.usesWeightedFullWidthLayout()) {
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
                contentScale = contentScale,
                sharedTransitionScope = sharedTransitionScope,
                transitionAssetId = transitionAssetId,
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

internal fun RowItem.usesWeightedFullWidthLayout(): Boolean =
    isComplete || kind == RowItemKind.MOSAIC_FALLBACK
