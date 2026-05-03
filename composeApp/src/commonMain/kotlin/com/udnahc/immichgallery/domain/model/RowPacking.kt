package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

const val DEFAULT_TARGET_ROW_HEIGHT = 150f
const val MIN_TARGET_ROW_HEIGHT_FRACTION = 0.10f
const val MAX_TARGET_ROW_HEIGHT_FRACTION = 0.35f
const val GRID_SPACING_DP = 2f
const val WIDE_FULL_WIDTH_ASPECT_RATIO = 1.5f
const val WIDE_FULL_WIDTH_TARGET_MULTIPLIER = 1.5f

@Immutable
data class RowHeightBounds(
    val min: Float,
    val max: Float
) {
    fun clamp(height: Float): Float = height.coerceIn(min, max)
}

fun rowHeightBoundsForViewport(viewportHeightDp: Float): RowHeightBounds {
    if (viewportHeightDp <= 0f) {
        return RowHeightBounds(0f, Float.MAX_VALUE)
    }
    return RowHeightBounds(
        min = viewportHeightDp * MIN_TARGET_ROW_HEIGHT_FRACTION,
        max = viewportHeightDp * MAX_TARGET_ROW_HEIGHT_FRACTION
    )
}

fun packIntoRows(
    assets: List<Asset>,
    bucketIndex: Int = 0,
    sectionLabel: String = "",
    availableWidth: Float,
    targetRowHeight: Float,
    spacing: Float,
    maxRowHeight: Float = Float.MAX_VALUE,
    promoteWideImages: Boolean = true,
    minCompleteRowPhotos: Int = 1
): List<RowItem> {
    if (availableWidth <= 0f || assets.isEmpty()) return emptyList()
    val rows = mutableListOf<RowItem>()
    var currentRow = mutableListOf<PhotoItem>()
    var currentSumAR = 0f

    for (asset in assets) {
        val photoItem = PhotoItem(
            gridKey = "p_${asset.id}",
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            asset = asset
        )
        if (promoteWideImages &&
            currentRow.isEmpty() &&
            asset.aspectRatio >= WIDE_FULL_WIDTH_ASPECT_RATIO
        ) {
            val fullWidthHeight = availableWidth / asset.aspectRatio
            val maxPromotedHeight = minOf(
                maxRowHeight,
                targetRowHeight * WIDE_FULL_WIDTH_TARGET_MULTIPLIER
            )
            if (fullWidthHeight <= maxPromotedHeight) {
                rows.add(RowItem(
                    gridKey = "row_${photoItem.gridKey}",
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    photos = listOf(photoItem),
                    rowHeight = fullWidthHeight
                ))
                continue
            }
        }
        currentRow.add(photoItem)
        currentSumAR += asset.aspectRatio
        val neededWidth = targetRowHeight * currentSumAR + (currentRow.size - 1) * spacing
        if (neededWidth >= availableWidth && currentRow.size >= minCompleteRowPhotos) {
            val actualHeight = (availableWidth - (currentRow.size - 1) * spacing) / currentSumAR
            rows.add(RowItem(
                gridKey = "row_${currentRow.first().gridKey}",
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                photos = currentRow.toList(),
                rowHeight = actualHeight
            ))
            currentRow = mutableListOf()
            currentSumAR = 0f
        }
    }
    if (currentRow.isNotEmpty()) {
        rows.add(RowItem(
            gridKey = "row_${currentRow.first().gridKey}",
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            photos = currentRow.toList(),
            rowHeight = targetRowHeight,
            isComplete = false
        ))
    }
    return rows
}
