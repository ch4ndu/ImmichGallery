package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface TimelineDisplayItem {
    val gridKey: String
    val bucketIndex: Int
    val isFullSpan: Boolean
    val sectionLabel: String
}

@Immutable
data class HeaderItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val label: String
) : TimelineDisplayItem {
    override val isFullSpan: Boolean = true
}

@Immutable
data class PhotoItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val asset: Asset
) : TimelineDisplayItem {
    override val isFullSpan: Boolean = false
}

@Immutable
data class PlaceholderItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val estimatedHeight: Float = 150f
) : TimelineDisplayItem {
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
) : TimelineDisplayItem {
    override val isFullSpan: Boolean = true
}

@Immutable
data class ErrorItem(
    override val gridKey: String,
    override val bucketIndex: Int,
    override val sectionLabel: String,
    val timeBucket: String
) : TimelineDisplayItem {
    override val isFullSpan: Boolean = true
}
