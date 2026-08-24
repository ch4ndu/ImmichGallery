package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationWaitsForContendedEndCleanup() = runTest {
        val notifier = RecordingNotifier()
        val mutex = Mutex()
        val tracker = SyncActivityTracker(notifier, mutex)
        val started = CompletableDeferred<Unit>()
        val tracked = launch {
            tracker.track(SyncActivityKey.Timeline) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        mutex.lock()
        tracked.cancel()
        runCurrent()
        mutex.unlock()
        advanceUntilIdle()
        tracked.join()

        assertEquals(listOf(1, 0), notifier.counts)
        assertEquals(0, tracker.activeCount.value)
    }

    @Test
    fun notifierCallbacksAreSerializedWithCountMutation() = runTest {
        val mutex = Mutex()
        val callbackObservedLockedMutex = mutableListOf<Boolean>()
        val notifier = object : PlatformSyncActivityNotifier {
            override fun onActiveSyncCountChanged(activeCount: Int) {
                val acquiredOutsideMutation = mutex.tryLock()
                if (acquiredOutsideMutation) {
                    mutex.unlock()
                }
                callbackObservedLockedMutex += !acquiredOutsideMutation
            }
        }
        val tracker = SyncActivityTracker(notifier, mutex)

        tracker.begin(SyncActivityKey.Timeline)
        tracker.begin(SyncActivityKey.Albums)
        tracker.end(SyncActivityKey.Timeline)
        tracker.end(SyncActivityKey.Albums)

        assertEquals(4, callbackObservedLockedMutex.size)
        assertTrue(callbackObservedLockedMutex.all { it })
        assertEquals(0, tracker.activeCount.value)
    }

    private class RecordingNotifier : PlatformSyncActivityNotifier {
        val counts = mutableListOf<Int>()

        override fun onActiveSyncCountChanged(activeCount: Int) {
            counts += activeCount
        }
    }
}
