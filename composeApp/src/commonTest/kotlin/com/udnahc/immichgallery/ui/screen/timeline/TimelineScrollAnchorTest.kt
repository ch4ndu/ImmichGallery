package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
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
