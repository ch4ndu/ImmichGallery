package com.udnahc.immichgallery.domain.model

import kotlin.math.ceil

const val PHOTO_GRID_MAX_PLACEHOLDER_HEIGHT_DP = 10000f
const val PHOTO_GRID_SECTION_HEADER_HEIGHT_DP = 48f
const val PHOTO_GRID_MAX_EXACT_PLACEHOLDER_PIECES = 32

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

fun buildPhotoGridPlaceholderItemsForGeometryRanges(
    bucketIndex: Int,
    sectionLabel: String,
    ranges: List<MosaicSectionGeometryRange>,
    externalSpacing: Float = GRID_SPACING_DP,
    preferredRangesPerPiece: Int = TIMELINE_MOSAIC_PROGRESS_BAND_BATCH_SIZE,
    maxPieces: Int = PHOTO_GRID_MAX_EXACT_PLACEHOLDER_PIECES,
    keyPrefix: String = "geom"
): List<PlaceholderItem> {
    val sortedRanges = ranges
        .filter { it.sourceCount > 0 && it.height > 0f }
        .sortedBy { it.sourceStartIndex }
    if (sortedRanges.isEmpty()) return emptyList()
    val targetPieces = maxPieces.coerceAtLeast(1)
    val rangesPerPiece = maxOf(
        preferredRangesPerPiece.coerceAtLeast(1),
        ceil(sortedRanges.size.toFloat() / targetPieces).toInt().coerceAtLeast(1)
    )
    return sortedRanges.chunked(rangesPerPiece).flatMapIndexed { pieceIndex, pieceRanges ->
        val sourceStart = pieceRanges.first().sourceStartIndex
        val sourceEnd = pieceRanges.last().sourceStartIndex + pieceRanges.last().sourceCount
        val sourceCount = sourceEnd - sourceStart
        val pieceHeight = pieceRanges.sumOf { it.height.toDouble() }.toFloat() +
                externalSpacing * (pieceRanges.size - 1).coerceAtLeast(0)
        buildPhotoGridPlaceholderItemsForHeight(
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            estimatedHeight = pieceHeight,
            externalSpacing = externalSpacing
        ).mapIndexed { splitIndex, item ->
            item.copy(
                gridKey = exactPlaceholderGridKey(
                    keyPrefix = keyPrefix,
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    sourceStartIndex = sourceStart,
                    sourceCount = sourceCount,
                    pieceIndex = pieceIndex,
                    splitIndex = splitIndex
                )
            )
        }
    }
}

fun exactPlaceholderGridKey(
    keyPrefix: String,
    bucketIndex: Int,
    sectionLabel: String,
    sourceStartIndex: Int,
    sourceCount: Int,
    pieceIndex: Int,
    splitIndex: Int
): String =
    "pl_${keyPrefix}_${bucketIndex}_${sectionLabel.hashCode()}_${sourceStartIndex}_${sourceCount}_${pieceIndex}_$splitIndex"

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
