package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.RowItem

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun JustifiedPhotoRow(
    row: RowItem,
    spacing: Dp,
    onPhotoClick: (assetId: String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
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
            val onClick = remember(photo.asset.id) { { onPhotoClick(photo.asset.id) } }
            ThumbnailCell(
                asset = photo.asset,
                onClick = onClick,
                modifier = cellModifier,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}
