package com.udnahc.immichgallery.domain.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineDateNavigationTest {
    @Test
    fun exactMonthAndMissingMonthsUseMetadataWithoutDependingOnListOrder() {
        val buckets = listOf(bucket("2020-01"), bucket("2020-05"), bucket("2020-03"))
        assertEquals("2020-03", timelineBucketForDate(buckets, LocalDate(2020, 3, 15))?.timeBucket)
        // Equal month distances prefer the newer month.
        assertEquals("2020-05", timelineBucketForDate(buckets, LocalDate(2020, 4, 1))?.timeBucket)
        assertEquals("2020-01", timelineBucketForDate(buckets, LocalDate(1900, 1, 1))?.timeBucket)
        assertEquals("2020-05", timelineBucketForDate(buckets, LocalDate(2030, 1, 1))?.timeBucket)
    }

    @Test
    fun emptyAndUndatedBucketsAreNotNavigationTargets() {
        assertNull(timelineBucketForDate(emptyList(), LocalDate(2020, 1, 1)))
        assertNull(timelineBucketForDate(
            listOf(bucket("unknown"), bucket("2020-13"), bucket("2020-01", 0)),
            LocalDate(2020, 1, 1)
        ))
    }

    @Test
    fun dateTargetsNewestPhotoOnExactDayAndNearestDayWhenMissing() {
        // Date-only values intentionally exercise the same fallback used by Timeline grouping.
        val assets = listOf(asset("older", "2024-02-20"), asset("leap", "2024-02-29"),
            asset("newer", "2024-02-24"), asset("invalid", "not-a-date"))
        assertEquals("leap", timelineAssetForDate("2024-02", assets, LocalDate(2024, 2, 29))?.id)
        assertEquals("newer", timelineAssetForDate("2024-02", assets, LocalDate(2024, 2, 22))?.id)
        assertEquals("older", timelineAssetForDate("2024-02", assets, LocalDate(2024, 2, 1))?.id)
        assertEquals("leap", timelineAssetForDate("2024-02", assets, LocalDate(2025, 1, 1))?.id)
    }

    @Test
    fun emptyOrUnparseableAssetDatesKeepTheBucketFallback() {
        assertNull(timelineAssetForDate("2020-01", emptyList(), LocalDate(2020, 1, 1)))
        assertNull(timelineAssetForDate("2020-01", listOf(asset("bad", "bad")), LocalDate(2020, 1, 1)))
    }

    @Test
    fun pickerIncludesOldLibraryYearsAndToday() {
        assertEquals(1850..2026, timelineDatePickerYearRange(
            listOf(bucket("1850-01"), bucket("2000-01")), LocalDate(2026, 10, 2)
        ))
        assertEquals(2026..2026, timelineDatePickerYearRange(emptyList(), LocalDate(2026, 10, 2)))
    }

    private fun bucket(month: String, count: Int = 3) =
        TimelineBucket(timelineBucketDisplayLabel(month), month, count)

    private fun asset(id: String, date: String) =
        Asset(id, AssetType.IMAGE, id, date, "", "")
}
