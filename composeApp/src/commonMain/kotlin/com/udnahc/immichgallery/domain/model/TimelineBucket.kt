package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate

@Immutable
data class TimelineBucket(
    val displayLabel: AssetGroupDisplayLabel,
    val timeBucket: String,
    val count: Int
)

internal fun timelineBucketDisplayLabel(timeBucket: String): AssetGroupDisplayLabel {
    if (
        timeBucket.length < 7 ||
        timeBucket[4] != '-' ||
        !timeBucket.substring(0, 4).all(Char::isDigit)
    ) {
        return AssetGroupDisplayLabel.UnknownDate
    }
    val year = timeBucket.substring(0, 4).toIntOrNull()
        ?: return AssetGroupDisplayLabel.UnknownDate
    val monthNumber = timeBucket.substring(5, 7).toIntOrNull()
        ?.takeIf { it in 1..12 }
        ?: return AssetGroupDisplayLabel.UnknownDate
    return AssetGroupDisplayLabel.Month(year = year, monthNumber = monthNumber)
}

internal fun timelineDayDisplayLabel(date: LocalDate?): AssetGroupDisplayLabel =
    date?.let(AssetGroupDisplayLabel::Day) ?: AssetGroupDisplayLabel.UnknownDate

@Immutable
data class TimelineBucketSyncResult(
    val buckets: List<TimelineBucket>,
    val staleBucketIds: Set<String> = emptySet(),
    val removedBucketIds: Set<String> = emptySet(),
    val metadataChanged: Boolean = true
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

class TimelineAssetSyncCompatibilityException(
    val partialResult: TimelineAssetSyncResult,
    val compatibilityCause: Throwable
) : IllegalStateException(
    "Timeline asset sync compatibility failure",
    compatibilityCause
) {
    fun withPartialResult(
        partialResult: TimelineAssetSyncResult
    ): TimelineAssetSyncCompatibilityException =
        TimelineAssetSyncCompatibilityException(partialResult, compatibilityCause)
}

internal fun TimelineAssetSyncResult.mergeRetryResult(
    retry: TimelineAssetSyncResult
): TimelineAssetSyncResult {
    val retrySuccessful = retry.successfulBucketIds
    return TimelineAssetSyncResult(
        successfulBucketIds = successfulBucketIds + retrySuccessful,
        failedBucketIds = (failedBucketIds - retrySuccessful) + retry.failedBucketIds,
        changedBucketIds = changedBucketIds + retry.changedBucketIds
    )
}

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
