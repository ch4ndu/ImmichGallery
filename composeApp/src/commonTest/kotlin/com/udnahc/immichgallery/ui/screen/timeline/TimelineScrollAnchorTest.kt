package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.buildTimelineDisplayIndex
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineScrollAnchorTest {
    @Test
    fun requestedDateAssetCannotBeOverwrittenByOldRowsInTheSameBucket() {
        val before = projection(
            row(bucket = 0, key = "old-row", assetId = "old"),
            row(bucket = 0, key = "target-row", assetId = "target")
        )
        val after = projection(
            row(bucket = 0, key = "inserted", assetId = "inserted"),
            row(bucket = 0, key = "old-row", assetId = "old"),
            row(bucket = 0, key = "target-row", assetId = "target")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.requestAssetAnchor(before, "target", 0, -32)
        assertNull(reconciler.observe(before, visible(index = 0, offset = 0), false))
        assertEquals(
            TimelineScrollRestore(displayIndex = 2, scrollOffset = -32),
            reconciler.observe(after, visible(index = 0, offset = 0), false)
        )
    }

    @Test
    fun cancellingPendingDateAnchorAllowsUserViewportToWin() {
        val before = projection(
            row(bucket = 0, key = "user-row", assetId = "user"),
            row(bucket = 1, key = "target-row", assetId = "target")
        )
        val after = projection(
            row(bucket = 0, key = "inserted", assetId = "inserted"),
            row(bucket = 0, key = "user-row", assetId = "user"),
            row(bucket = 1, key = "target-row", assetId = "target")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.requestBucketAnchor(before, 1, "target-row")
        reconciler.cancelRequestedAnchor()
        assertNull(reconciler.observe(before, visible(index = 0, offset = -8), false))
        assertEquals(
            TimelineScrollRestore(displayIndex = 1, scrollOffset = 8),
            reconciler.observe(after, visible(index = 0, offset = 0), false)
        )
    }

    @Test
    fun insertionBeforeVisibleAssetRestoresExactAssetAndOffset() {
        val before = projection(
            row(bucket = 0, key = "a-row", assetId = "a"),
            row(bucket = 0, key = "b-row", assetId = "b")
        )
        val after = projection(
            row(bucket = 0, key = "inserted", assetId = "x"),
            row(bucket = 0, key = "a-row", assetId = "a"),
            row(bucket = 0, key = "b-row", assetId = "b")
        )
        val reconciler = TimelineScrollAnchorReconciler()

        assertNull(reconciler.observe(before, visible(index = 0, offset = -37), false))

        assertEquals(
            TimelineScrollRestore(displayIndex = 1, scrollOffset = 37),
            reconciler.observe(after, visible(index = 0, offset = 0), false)
        )
    }

    @Test
    fun removalBeforeVisibleAssetRestoresExactAsset() {
        val before = projection(
            row(bucket = 0, key = "a-row", assetId = "a"),
            row(bucket = 0, key = "b-row", assetId = "b")
        )
        val after = projection(row(bucket = 0, key = "b-row", assetId = "b"))
        val reconciler = TimelineScrollAnchorReconciler()

        reconciler.observe(before, visible(index = 1, offset = -11), false)

        assertEquals(
            TimelineScrollRestore(displayIndex = 0, scrollOffset = 11),
            reconciler.observe(after, visible(index = 0, offset = 0), false)
        )
    }

    @Test
    fun placeholderReplacementFallsBackToFirstItemInTargetBucket() {
        val placeholder = projection(row(bucket = 2, key = "placeholder", assetId = null))
        val materialized = projection(
            row(bucket = 0, key = "other", assetId = "x"),
            row(bucket = 2, key = "materialized", assetId = "a")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.requestBucketAnchor(
            projection = placeholder,
            bucketIndex = 2,
            itemKey = "placeholder",
            scrollOffset = 9
        )

        assertEquals(
            TimelineScrollRestore(displayIndex = 1, scrollOffset = 9),
            reconciler.observe(materialized, visible(index = 0, offset = 0), false)
        )
    }

    @Test
    fun removedAssetFallsBackToItsPriorBucket() {
        val before = projection(
            row(bucket = 0, key = "h_other", assetId = null),
            row(bucket = 1, key = "h_target", assetId = null),
            row(bucket = 1, key = "old-row", assetId = "a")
        )
        val after = projection(
            row(bucket = 0, key = "h_inserted", assetId = null),
            row(bucket = 1, key = "h_other", assetId = null),
            row(bucket = 2, key = "h_target", assetId = null),
            row(bucket = 2, key = "replacement", assetId = "b")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, visible(index = 2, offset = -23), false)

        assertEquals(
            TimelineScrollRestore(displayIndex = 2, scrollOffset = 23),
            reconciler.observe(after, visible(index = 0, offset = 0), false)
        )
    }

    @Test
    fun activeScrollDefersProjectionRestore() {
        val before = projection(row(bucket = 0, key = "a", assetId = "a"))
        val after = projection(
            row(bucket = 0, key = "x", assetId = "x"),
            row(bucket = 0, key = "a", assetId = "a")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, visible(index = 0, offset = -4), false)

        assertNull(reconciler.observe(after, visible(index = 0, offset = -2), true))
        assertEquals("a", reconciler.anchoredAssetIdForTests())
        assertFalse(reconciler.isRestoringForTests())
        assertEquals(
            TimelineScrollRestore(displayIndex = 1, scrollOffset = 4),
            reconciler.observe(after, visible(index = 0, offset = -2), false)
        )
    }

    @Test
    fun visibleStateCannotOverwriteAnchorUntilRestoreCommits() {
        val before = projection(row(bucket = 0, key = "a", assetId = "a"))
        val after = projection(
            row(bucket = 0, key = "b", assetId = "b"),
            row(bucket = 0, key = "a", assetId = "a")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, visible(index = 0, offset = -7), false)

        assertEquals(
            TimelineScrollRestore(displayIndex = 1, scrollOffset = 7),
            reconciler.observe(after, visible(index = 0, offset = 0), false)
        )
        assertTrue(reconciler.isRestoringForTests())
        assertNull(reconciler.observe(after, visible(index = 0, offset = 0), false))
        assertEquals("a", reconciler.anchoredAssetIdForTests())

        reconciler.commitRestore(after, visible(index = 1, offset = -7))

        assertFalse(reconciler.isRestoringForTests())
        assertEquals("a", reconciler.anchoredAssetIdForTests())
    }

    @Test
    fun supersededProjectionKeepsStableAnchorUntilLatestRestoreCommits() {
        val before = projection(row(bucket = 0, key = "a", assetId = "a"))
        val firstReplacement = projection(
            row(bucket = 0, key = "x", assetId = "x"),
            row(bucket = 0, key = "a", assetId = "a")
        )
        val latestReplacement = projection(
            row(bucket = 0, key = "y", assetId = "y"),
            row(bucket = 0, key = "z", assetId = "z"),
            row(bucket = 0, key = "a", assetId = "a")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, visible(index = 0, offset = -5), false)

        assertEquals(
            TimelineScrollRestore(displayIndex = 1, scrollOffset = 5),
            reconciler.observe(firstReplacement, visible(index = 0, offset = 0), false)
        )
        assertEquals(
            TimelineScrollRestore(displayIndex = 2, scrollOffset = 5),
            reconciler.observe(latestReplacement, visible(index = 0, offset = 0), false)
        )

        reconciler.commitRestore(firstReplacement, visible(index = 1, offset = -5))
        assertEquals("a", reconciler.anchoredAssetIdForTests())
        assertTrue(reconciler.isRestoringForTests())

        reconciler.commitRestore(latestReplacement, visible(index = 2, offset = -5))
        assertFalse(reconciler.isRestoringForTests())
        assertEquals("a", reconciler.anchoredAssetIdForTests())
    }

    @Test
    fun placeholderOnlyViewportRestoresByKeyInsteadOfStaleAssetAbove() {
        val top = projection(
            row(bucket = 0, key = "a-row", assetId = "a"),
            row(bucket = 1, key = "mr_1_0_4", assetId = null),
            row(bucket = 1, key = "mr_1_4_4", assetId = null)
        )
        val ready = projection(
            row(bucket = 0, key = "a-row", assetId = "a"),
            row(bucket = 1, key = "mr_1_0_4", assetId = "b"),
            row(bucket = 1, key = "mr_1_4_4", assetId = "c")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(top, visible(index = 0, offset = 0), false)
        reconciler.observe(top, listOf(TimelineVisibleScrollItem(2, -40, key = "mr_1_4_4")), false)

        assertNull(reconciler.anchoredAssetIdForTests())
        assertEquals(
            TimelineScrollRestore(displayIndex = 2, scrollOffset = 40),
            reconciler.observe(ready, visible(index = 0, offset = 0), false)
        )
    }

    @Test
    fun measuredLazyKeyWinsOverLaggingProjectionIndex() {
        val before = projection(
            row(bucket = 0, key = "x", assetId = "x"),
            row(bucket = 0, key = "y", assetId = "y")
        )
        val after = projection(
            row(bucket = 0, key = "inserted", assetId = "i"),
            row(bucket = 0, key = "x", assetId = "x"),
            row(bucket = 0, key = "y", assetId = "y")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, listOf(TimelineVisibleScrollItem(0, -6, key = "y")), false)

        assertEquals(
            TimelineScrollRestore(displayIndex = 2, scrollOffset = 6),
            reconciler.observe(after, visible(index = 0, offset = 0), false)
        )
    }

    @Test
    fun staleMeasuredLayoutCannotCaptureWrongPhotoAndCoherentProjectionRecovers() {
        val before = projection(
            row(bucket = 0, key = "a-row", assetId = "a"),
            row(bucket = 0, key = "b-row", assetId = "b")
        )
        val after = projection(
            row(bucket = 0, key = "inserted", assetId = "x"),
            row(bucket = 0, key = "a-row", assetId = "a"),
            row(bucket = 0, key = "b-row", assetId = "b")
        )
        val latest = projection(
            row(bucket = 0, key = "y-row", assetId = "y"),
            row(bucket = 0, key = "inserted", assetId = "x"),
            row(bucket = 0, key = "a-row", assetId = "a"),
            row(bucket = 0, key = "b-row", assetId = "b")
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, listOf(TimelineVisibleScrollItem(1, -10, key = "b-row")), false)
        assertEquals(
            TimelineScrollRestore(displayIndex = 2, scrollOffset = 10),
            reconciler.observe(after, listOf(TimelineVisibleScrollItem(1, -10, key = "b-row")), false)
        )

        // The lazy layout still measures the older provider: old indices plus a key `after` lacks.
        reconciler.commitRestore(
            after,
            listOf(
                TimelineVisibleScrollItem(0, -400, key = "stale-only"),
                TimelineVisibleScrollItem(1, -10, key = "b-row")
            )
        )
        assertEquals("b", reconciler.anchoredAssetIdForTests())
        reconciler.observe(after, listOf(TimelineVisibleScrollItem(0, 0, key = "stale-only")), false)
        assertEquals("b", reconciler.anchoredAssetIdForTests())

        assertEquals(
            TimelineScrollRestore(displayIndex = 3, scrollOffset = 10),
            reconciler.observe(latest, listOf(TimelineVisibleScrollItem(2, -10, key = "b-row")), false)
        )
    }

    @Test
    fun clippedEstimateAboveVisibleBandPinsTheVisibleBand() {
        val before = timelineProjection(header("h_b"), estimate("est", 500f, 0, 10), rangeBand("mr_b_0_4", 0, 4, 300f))
        val after = timelineProjection(header("h_b"), estimate("est2", 120f, 0, 10), rangeBand("mr_b_0_4", 0, 4, 300f))
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(
            before,
            listOf(TimelineVisibleScrollItem(1, -100, "est"), TimelineVisibleScrollItem(2, 400, "mr_b_0_4")),
            false
        )

        assertEquals(TimelineScrollRestore(displayIndex = 2, scrollOffset = -400), reconciler.observe(after, visible(0, 0), false))
    }

    @Test
    fun estimateOnlyViewportKeepsPixelDistanceFromPrecedingHeader() {
        val metrics = TimelineAnchorMetrics(heightPx = { (it * 2.75f).roundToInt() }, spacingPx = 6, viewportPx = 2000)
        val before = timelineProjection(header("h_b"), estimate("e1", 1000f, 0, 10), estimate("e2", 1000f, 10, 10))
        val after = timelineProjection(header("h_b"), rangeBand("mr_b_0_20", 0, 20, 700f))
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, listOf(TimelineVisibleScrollItem(2, -50, "e2")), false, metrics)

        val expectedDistance = 50 + 2750 + 6 + 132 + 6
        assertEquals(
            TimelineScrollRestore(displayIndex = 0, scrollOffset = expectedDistance),
            reconciler.observe(after, visible(0, 0), false, metrics)
        )
    }

    @Test
    fun farFromAnyHeaderEstimatedOrdinalMapsIntoSecondDayAfterMaterialization() {
        val metrics = TimelineAnchorMetrics(heightPx = { it.roundToInt() }, spacingPx = 0, viewportPx = 1000)
        val before = timelineProjection(
            header("h_b"),
            estimate("estA", 5000f, 0, 50),
            estimate("estB", 5000f, 50, 50),
            identity = "b#1#DAY#4"
        )
        val after = timelineProjection(
            header("h_b"),
            header("dh_1", section = "b|day|1"),
            rangeBand("mr_d1_0", 0, 30, 600f, section = "b|day|1"),
            rangeBand("mr_d1_30", 30, 30, 600f, section = "b|day|1"),
            header("dh_2", section = "b|day|2"),
            rangeBand("mr_d2_0", 0, 20, 400f, section = "b|day|2"),
            rangeBand("mr_d2_20", 20, 20, 400f, section = "b|day|2"),
            identity = "b#1#DAY#4"
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, listOf(TimelineVisibleScrollItem(2, -2500, "estB")), false, metrics)

        assertEquals(
            TimelineScrollRestore(displayIndex = 5, scrollOffset = 300),
            reconciler.observe(after, visible(0, 0), false, metrics)
        )
    }

    @Test
    fun estimatedOrdinalIsRejectedAfterBucketContentRevisionChanges() {
        val metrics = TimelineAnchorMetrics(heightPx = { it.roundToInt() }, spacingPx = 0, viewportPx = 1000)
        val before = timelineProjection(
            header("h_b"),
            estimate("estA", 5000f, 0, 50),
            estimate("estB", 5000f, 50, 50),
            identity = "b#1#DAY#4"
        )
        val after = timelineProjection(
            header("h_b"),
            rangeBand("mr_b_0", 0, 100, 4000f),
            identity = "b#2#DAY#4"
        )
        val reconciler = TimelineScrollAnchorReconciler()
        reconciler.observe(before, listOf(TimelineVisibleScrollItem(2, -2500, "estB")), false, metrics)

        assertNull(reconciler.observe(after, visible(0, 0), false, metrics)?.displayIndex)
    }

    private fun header(key: String, section: String = "b|month") = HeaderItem(key, 0, section, key)

    private fun estimate(key: String, height: Float, start: Int, count: Int) = PlaceholderItem(
        gridKey = key,
        bucketIndex = 0,
        sectionLabel = "b|month",
        estimatedHeight = height,
        sourceStartIndex = start,
        sourceCount = count,
        sourceIsBucketOrdinal = true
    )

    private fun rangeBand(key: String, start: Int, count: Int, height: Float, section: String = "b|month") =
        MosaicBandItem(
            gridKey = key,
            bucketIndex = 0,
            sectionLabel = section,
            tiles = emptyList(),
            bandHeight = height,
            sourceStartIndex = start,
            sourceCount = count
        )

    private fun timelineProjection(vararg items: PhotoGridDisplayItem, identity: String = "b#1#MONTH#4") =
        buildTimelineDisplayIndex(items.toList()).copy(bucketIdentityByIndex = listOf(identity))

    private data class Row(
        val bucket: Int,
        val key: String,
        val assetId: String?
    )

    private fun row(bucket: Int, key: String, assetId: String?): Row = Row(bucket, key, assetId)

    private fun visible(index: Int, offset: Int): List<TimelineVisibleScrollItem> =
        listOf(TimelineVisibleScrollItem(displayIndex = index, itemOffset = offset))

    private fun projection(vararg rows: Row): TimelineDisplayIndex = TimelineDisplayIndex(
        bucketByDisplayIndex = rows.map(Row::bucket),
        displayIndexesByBucket = rows.indices.groupBy { rows[it].bucket },
        assetDisplayIndexById = rows.mapIndexedNotNull { index, row ->
            row.assetId?.let { it to index }
        }.toMap(),
        sectionLabelByDisplayIndex = rows.map { "section-${it.bucket}" },
        firstAssetIdByDisplayIndex = rows.map(Row::assetId),
        gridKeyByDisplayIndex = rows.map(Row::key),
        displayIndexByGridKey = rows.mapIndexed { index, row -> row.key to index }.toMap()
    )
}
