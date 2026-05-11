package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncActivityTrackerTest {
    @Test
    fun overlappingManualSyncAndMosaicPreparationUpdateActiveCount() = runTest {
        val notifier = RecordingNotifier()
        val tracker = SyncActivityTracker(notifier)

        tracker.begin(SyncActivityKey.TimelineManualSync)
        tracker.begin(SyncActivityKey.MosaicPreparation)
        tracker.end(SyncActivityKey.TimelineManualSync)
        tracker.end(SyncActivityKey.MosaicPreparation)

        assertEquals(listOf(1, 2, 1, 0), notifier.counts)
        assertEquals(0, tracker.activeCount.value)
    }

    @Test
    fun nestedSameKeyWorkKeepsNotificationUntilAllEnd() = runTest {
        val notifier = RecordingNotifier()
        val tracker = SyncActivityTracker(notifier)
        val first = async {
            tracker.track(SyncActivityKey.MosaicPreparation) {
                tracker.track(SyncActivityKey.MosaicPreparation) {
                    "done"
                }
            }
        }

        assertEquals("done", first.await())
        assertEquals(listOf(1, 2, 1, 0), notifier.counts)
    }

    private class RecordingNotifier : PlatformSyncActivityNotifier {
        val counts = mutableListOf<Int>()

        override fun onActiveSyncCountChanged(activeCount: Int) {
            counts += activeCount
        }
    }
}
