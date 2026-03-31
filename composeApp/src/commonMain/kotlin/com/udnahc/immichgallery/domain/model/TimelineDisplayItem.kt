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
    override val sectionLabel: String
) : TimelineDisplayItem {
    override val isFullSpan: Boolean = false
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
