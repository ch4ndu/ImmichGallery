package com.udnahc.immichgallery.domain.model

import kotlin.math.ceil

private const val MAX_PLACEHOLDER_HEIGHT_DP = 10000f
private const val SECTION_HEADER_HEIGHT_DP = 48f

fun placeholderGridKey(bucketIndex: Int, sectionLabel: String, chunkIndex: Int): String =
    "pl_${bucketIndex}_${sectionLabel.hashCode()}_$chunkIndex"

fun buildPhotoGridPlaceholderItems(
    bucketIndex: Int,
    sectionLabel: String,
    assetCount: Int,
    availableWidth: Float,
    targetRowHeight: Float,
    estimatedHeaderCount: Int = 0
): List<PlaceholderItem> {
    if (assetCount <= 0 || targetRowHeight <= 0f) return emptyList()
    val photosPerRow = if (availableWidth > 0f) {
        (availableWidth / targetRowHeight).coerceAtLeast(1f)
    } else {
        DEFAULT_GRID_COLUMN_COUNT.toFloat()
    }
    val estimatedRows = ceil(assetCount.toFloat() / photosPerRow).toInt()
    val headerHeight = estimatedHeaderCount * SECTION_HEADER_HEIGHT_DP
    val totalEstimatedHeight =
        (estimatedRows * (targetRowHeight + GRID_SPACING_DP) - GRID_SPACING_DP + headerHeight)
            .coerceAtLeast(targetRowHeight)
    val chunks = ceil(totalEstimatedHeight / MAX_PLACEHOLDER_HEIGHT_DP).toInt()
        .coerceAtLeast(1)
    val chunkHeight = totalEstimatedHeight / chunks
    return List(chunks) { chunkIndex ->
        PlaceholderItem(
            gridKey = placeholderGridKey(bucketIndex, sectionLabel, chunkIndex),
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            estimatedHeight = chunkHeight
        )
    }
}
