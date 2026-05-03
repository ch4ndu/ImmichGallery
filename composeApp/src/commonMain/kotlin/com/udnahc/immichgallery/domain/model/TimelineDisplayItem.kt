package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface PhotoGridDisplayItem {
    val gridKey: String
    val bucketIndex: Int
    val isFullSpan: Boolean
    val sectionLabel: String
}

typealias TimelineDisplayItem = PhotoGridDisplayItem

@Immutable
data class HeaderItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val label: String
) : PhotoGridDisplayItem {
    override val isFullSpan: Boolean = true
}

@Immutable
data class PhotoItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val asset: Asset
) : PhotoGridDisplayItem {
    override val isFullSpan: Boolean = false
}

@Immutable
data class PlaceholderItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val estimatedHeight: Float = 150f
) : PhotoGridDisplayItem {
    override val isFullSpan: Boolean = false
}

@Immutable
data class RowItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val photos: List<PhotoItem>,
    val rowHeight: Float,
    val isComplete: Boolean = true
) : PhotoGridDisplayItem {
    override val isFullSpan: Boolean = true
}

@Immutable
data class MosaicTile(
    val photo: PhotoItem,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val visualOrder: Int
)

@Immutable
data class MosaicBandItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val tiles: List<MosaicTile>,
    val bandHeight: Float
) : PhotoGridDisplayItem {
    override val isFullSpan: Boolean = true
}

@Immutable
data class ErrorItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val timeBucket: String
) : PhotoGridDisplayItem {
    override val isFullSpan: Boolean = true
}
