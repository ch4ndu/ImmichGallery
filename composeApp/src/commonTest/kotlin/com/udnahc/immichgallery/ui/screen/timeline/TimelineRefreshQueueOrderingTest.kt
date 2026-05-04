package com.udnahc.immichgallery.ui.screen.timeline

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineRefreshQueueOrderingTest {
    @Test
    fun newerDragTargetDemotesOlderDragBuckets() {
        val ordering = reorderTimelineRefreshQueue(
            pendingBuckets = listOf("old_drag", "normal"),
            pendingDragBuckets = setOf("old_drag"),
            bucketsToQueue = listOf("new_drag", "new_neighbor"),
            priority = TimelineRefreshQueuePriority.ScrollbarDrag
        )

        assertEquals(listOf("new_drag", "new_neighbor", "normal", "old_drag"), ordering.pendingBuckets)
        assertEquals(setOf("old_drag", "new_drag", "new_neighbor"), ordering.pendingDragBuckets)
    }

    @Test
    fun stoppedBucketAndNeighborsPrecedeOlderDragAndRemainingWork() {
        val ordering = reorderTimelineRefreshQueue(
            pendingBuckets = listOf("old_drag", "normal_a", "normal_b"),
            pendingDragBuckets = setOf("old_drag"),
            bucketsToQueue = listOf("stopped", "near_prev", "near_next"),
            priority = TimelineRefreshQueuePriority.ScrollbarStop
        )

        assertEquals(
            listOf("stopped", "near_prev", "near_next", "old_drag", "normal_a", "normal_b"),
            ordering.pendingBuckets
        )
        assertEquals(setOf("old_drag"), ordering.pendingDragBuckets)
    }

    @Test
    fun visibleWorkRemovesQueuedBucketFromDragSet() {
        val ordering = reorderTimelineRefreshQueue(
            pendingBuckets = listOf("drag_bucket", "normal"),
            pendingDragBuckets = setOf("drag_bucket"),
            bucketsToQueue = listOf("drag_bucket"),
            priority = TimelineRefreshQueuePriority.Visible
        )

        assertEquals(listOf("drag_bucket", "normal"), ordering.pendingBuckets)
        assertEquals(emptySet(), ordering.pendingDragBuckets)
    }
}
