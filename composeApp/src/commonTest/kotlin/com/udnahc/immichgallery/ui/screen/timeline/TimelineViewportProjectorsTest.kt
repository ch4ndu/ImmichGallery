package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.model.TimeBucketResponse
import com.udnahc.immichgallery.domain.model.AssetGroupDisplayLabel
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.timelineBucketDisplayLabel
import com.udnahc.immichgallery.domain.model.timelineDayDisplayLabel
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toEntity
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineViewportProjectorsTest {
    @Test
    fun timeBucketProducesTypedMonthOrUnknownLabel() {
        assertEquals(
            AssetGroupDisplayLabel.Month(year = 2026, monthNumber = 3),
            timelineBucketDisplayLabel("2026-03")
        )
        assertEquals(
            AssetGroupDisplayLabel.Month(year = 2025, monthNumber = 12),
            timelineBucketDisplayLabel("2025-12-01T00:00:00.000Z")
        )
        assertEquals(
            AssetGroupDisplayLabel.UnknownDate,
            timelineBucketDisplayLabel("translated label without a bucket date")
        )
        assertEquals(AssetGroupDisplayLabel.UnknownDate, timelineBucketDisplayLabel("2026-00"))
        assertEquals(AssetGroupDisplayLabel.UnknownDate, timelineBucketDisplayLabel("2026-13"))
        assertEquals(AssetGroupDisplayLabel.UnknownDate, timelineBucketDisplayLabel("26-03"))
        assertEquals(AssetGroupDisplayLabel.UnknownDate, timelineBucketDisplayLabel("+026-03"))
        assertEquals(AssetGroupDisplayLabel.UnknownDate, timelineBucketDisplayLabel("20a6-03"))
    }

    @Test
    fun dayProjectionIsTypedAndLegacyEntityLabelRemainsNonPresentational() {
        val date = LocalDate(2026, 8, 23)
        assertEquals(AssetGroupDisplayLabel.Day(date), timelineDayDisplayLabel(date))
        assertEquals(AssetGroupDisplayLabel.UnknownDate, timelineDayDisplayLabel(null))

        val entity = TimeBucketResponse(timeBucket = "2026-08", count = 3).toEntity(sortOrder = 4)
        assertEquals("2026-08", entity.displayLabel)
        assertEquals(
            AssetGroupDisplayLabel.Month(year = 2026, monthNumber = 8),
            entity.toDomain().displayLabel
        )
    }

    @Test
    fun localizedDisplayTextCannotChangeScrollbarLabelsOrYearMarkers() {
        val pageIndex = TimelinePageIndex(
            bucketStartPages = listOf(0, 2, 3),
            bucketPageCounts = listOf(2, 1, 3),
            totalPages = 6
        )
        val original = listOf(
            bucket(displayLabel = "December 2026", timeBucket = "2026-12", count = 2),
            bucket(displayLabel = "November 2026", timeBucket = "2026-11", count = 1),
            bucket(displayLabel = "December 2025", timeBucket = "2025-12", count = 3)
        )
        val localized = listOf(
            bucket(displayLabel = "Diciembre dos mil veintiseis", timeBucket = "2026-12", count = 2),
            bucket(displayLabel = "XI MMXXVI", timeBucket = "2026-11", count = 1),
            bucket(displayLabel = "not a year", timeBucket = "2025-12", count = 3)
        )

        val expected = computeScrollbarData(original, pageIndex)

        assertEquals(expected, computeScrollbarData(localized, pageIndex))
        assertEquals(
            listOf(
                YearMarker(fraction = 0f, year = "2026"),
                YearMarker(fraction = 0.5f, year = "2025")
            ),
            expected.yearMarkers
        )
        assertEquals(
            listOf(
                AssetGroupDisplayLabel.Month(year = 2026, monthNumber = 12),
                AssetGroupDisplayLabel.Month(year = 2026, monthNumber = 11),
                AssetGroupDisplayLabel.Month(year = 2025, monthNumber = 12)
            ),
            expected.bucketDisplayLabels
        )
    }

    @Test
    fun invalidBucketDateHasUnknownLabelAndNoYearMarker() {
        val data = computeScrollbarData(
            buckets = listOf(bucket(displayLabel = "2039", timeBucket = "unknown", count = 1)),
            pageIndex = TimelinePageIndex(
                bucketStartPages = listOf(0),
                bucketPageCounts = listOf(1),
                totalPages = 1
            )
        )

        assertEquals(listOf(AssetGroupDisplayLabel.UnknownDate), data.bucketDisplayLabels)
        assertEquals(emptyList(), data.yearMarkers)
    }

    private fun bucket(
        displayLabel: String,
        timeBucket: String,
        count: Int
    ): TimelineBucket = TimelineBucketEntity(
        timeBucket = timeBucket,
        count = count,
        displayLabel = displayLabel,
        sortOrder = 0
    ).toDomain()
}
