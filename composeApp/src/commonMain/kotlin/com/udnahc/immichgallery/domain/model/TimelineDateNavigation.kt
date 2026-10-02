package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate
import kotlin.math.abs

@Immutable
data class TimelineDateTarget(val timeBucket: String, val assetId: String?)

/** Month metadata selects one cache read; no library-wide asset scan is needed. */
fun timelineBucketForDate(buckets: List<TimelineBucket>, date: LocalDate): TimelineBucket? {
    val selectedMonth = date.year * 12 + date.month.ordinal
    return buckets.asSequence()
        .filter { it.count > 0 }
        .mapNotNull { bucket ->
            val month = timelineBucketDisplayLabel(bucket.timeBucket)
                as? AssetGroupDisplayLabel.Month ?: return@mapNotNull null
            bucket to (month.year * 12 + month.monthNumber - 1)
        }
        .minWithOrNull(compareBy<Pair<TimelineBucket, Int>> { abs(it.second - selectedMonth) }
            .thenByDescending { it.second })
        ?.first
}

fun timelineAssetForDate(timeBucket: String, assets: List<Asset>, date: LocalDate): Asset? =
    timelineDaySections(timeBucket, assets)
        .filter { it.date != null }
        .minByOrNull { section ->
            section.date?.let { abs(it.toEpochDays() - date.toEpochDays()) } ?: Long.MAX_VALUE
        }
        ?.assets?.firstOrNull()

fun timelineDatePickerYearRange(buckets: List<TimelineBucket>, today: LocalDate): IntRange {
    val years = buckets.mapNotNull {
        (timelineBucketDisplayLabel(it.timeBucket) as? AssetGroupDisplayLabel.Month)?.year
    } + today.year
    return years.min().coerceAtLeast(1)..years.max().coerceAtMost(9999)
}
