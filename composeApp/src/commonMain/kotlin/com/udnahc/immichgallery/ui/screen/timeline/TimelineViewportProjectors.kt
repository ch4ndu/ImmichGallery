package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.AssetGroupDisplayLabel
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.timelineBucketDisplayLabel

internal data class TimelinePageIndexCacheKey(
    val bucketKeys: List<TimelinePageBucketKey>
)

internal data class TimelinePageBucketKey(
    val timeBucket: String,
    val count: Int,
    val assetRevision: Int,
    val materializationRevision: Int,
    val loaded: Boolean
)

internal data class TimelineScrollbarDataCacheKey(
    val bucketTimeBuckets: List<String>,
    val pageIndex: TimelinePageIndex
)

internal data class ScrollbarData(
    val yearMarkers: List<YearMarker> = emptyList(),
    val totalItemCount: Int = 0,
    val cumulativeItemCounts: List<Int> = emptyList(),
    val bucketDisplayLabels: List<AssetGroupDisplayLabel> = emptyList()
)

internal fun computeScrollbarData(
    buckets: List<TimelineBucket>,
    pageIndex: TimelinePageIndex
): ScrollbarData {
    var cumulative = 0
    val cumulativeCounts = buckets.indices.map { index ->
        cumulative += pageIndex.bucketPageCounts.getOrElse(index) { buckets[index].count }
        cumulative
    }
    val totalItems = pageIndex.totalPages

    val markers = mutableListOf<YearMarker>()
    var lastYear: Int? = null
    for (i in buckets.indices) {
        val year = (timelineBucketDisplayLabel(buckets[i].timeBucket)
                as? AssetGroupDisplayLabel.Month)?.year
        if (year != null && year != lastYear) {
            val fraction = if (totalItems > 0) {
                pageIndex.bucketStartPages.getOrElse(i) { 0 }.toFloat() / totalItems
            } else {
                0f
            }
            markers.add(YearMarker(fraction.coerceIn(0f, 1f), year.toString()))
            lastYear = year
        }
    }

    return ScrollbarData(
        yearMarkers = markers,
        totalItemCount = totalItems,
        cumulativeItemCounts = cumulativeCounts,
        bucketDisplayLabels = buckets.map(TimelineBucket::displayLabel)
    )
}
