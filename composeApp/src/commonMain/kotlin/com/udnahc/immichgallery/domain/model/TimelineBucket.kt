package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class TimelineBucket(
    val displayLabel: String,
    val timeBucket: String,
    val count: Int
)

@Immutable
data class TimelineBucketSyncResult(
    val buckets: List<TimelineBucket>,
    val staleBucketIds: Set<String> = emptySet(),
    val removedBucketIds: Set<String> = emptySet()
)

@Immutable
data class TimelineBucketAssetSyncResult(
    val timeBucket: String,
    val changed: Boolean
)

@Immutable
data class TimelineAssetSyncResult(
    val successfulBucketIds: Set<String>,
    val failedBucketIds: Set<String>,
    val changedBucketIds: Set<String> = emptySet()
)

@Immutable
data class TimelinePageIndex(
    val bucketStartPages: List<Int> = emptyList(),
    val bucketPageCounts: List<Int> = emptyList(),
    val totalPages: Int = 0,
    val loadedAssetPageIndexes: Map<String, Int> = emptyMap()
) {
    fun pageToBucketIndex(pageIndex: Int): Int? {
        if (pageIndex !in 0 until totalPages) return null
        var low = 0
        var high = bucketStartPages.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = bucketStartPages[mid]
            val end = start + bucketPageCounts.getOrElse(mid) { 0 }
            when {
                pageIndex < start -> high = mid - 1
                pageIndex >= end -> low = mid + 1
                else -> return mid
            }
        }
        return null
    }
}
