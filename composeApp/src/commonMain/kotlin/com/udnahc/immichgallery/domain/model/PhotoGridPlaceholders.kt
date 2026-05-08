package com.udnahc.immichgallery.domain.model

import kotlin.math.ceil

const val PHOTO_GRID_MAX_PLACEHOLDER_HEIGHT_DP = 10000f
const val PHOTO_GRID_SECTION_HEADER_HEIGHT_DP = 48f

fun placeholderGridKey(
    bucketIndex: Int,
    sectionLabel: String,
    chunkIndex: Int
): String =
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
    val headerHeight = estimatedHeaderCount * PHOTO_GRID_SECTION_HEADER_HEIGHT_DP
    val totalEstimatedHeight =
        (estimatedRows * (targetRowHeight + GRID_SPACING_DP) - GRID_SPACING_DP + headerHeight)
            .coerceAtLeast(targetRowHeight)
    val chunks = photoGridPlaceholderChunkCount(totalEstimatedHeight)
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

fun buildPhotoGridPlaceholderItemsForHeight(
    bucketIndex: Int,
    sectionLabel: String,
    estimatedHeight: Float,
    externalSpacing: Float = GRID_SPACING_DP
): List<PlaceholderItem> {
    if (estimatedHeight <= 0f) return emptyList()
    val chunks = photoGridPlaceholderChunkCount(estimatedHeight)
    val totalExternalSpacing = externalSpacing * (chunks - 1).coerceAtLeast(0)
    val chunkHeight = ((estimatedHeight - totalExternalSpacing).coerceAtLeast(1f)) / chunks
    return List(chunks) { chunkIndex ->
        PlaceholderItem(
            gridKey = placeholderGridKey(bucketIndex, sectionLabel, chunkIndex),
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            estimatedHeight = chunkHeight
        )
    }
}

fun estimatePhotoGridDisplayItemsHeight(
    items: List<PhotoGridDisplayItem>,
    spacing: Float
): Float {
    if (items.isEmpty()) return 0f
    val itemHeight = items.sumOf { item ->
        when (item) {
            is HeaderItem -> PHOTO_GRID_SECTION_HEADER_HEIGHT_DP.toDouble()
            is RowItem -> item.rowHeight.toDouble()
            is MosaicBandItem -> item.bandHeight.toDouble()
            is PlaceholderItem -> item.estimatedHeight.toDouble()
            else -> 0.0
        }
    }.toFloat()
    return itemHeight + spacing * (items.size - 1).coerceAtLeast(0)
}

fun photoGridPlaceholderChunkCount(estimatedHeight: Float): Int =
    if (estimatedHeight <= 0f) {
        0
    } else {
        ceil(estimatedHeight / PHOTO_GRID_MAX_PLACEHOLDER_HEIGHT_DP).toInt()
            .coerceAtLeast(1)
    }
