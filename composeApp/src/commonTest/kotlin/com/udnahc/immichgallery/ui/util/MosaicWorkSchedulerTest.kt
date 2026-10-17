package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MosaicWorkSchedulerTest {
    @Test
    fun cancelledWaiterAwaitsRunningExecutionTermination() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val executionStarted = CompletableDeferred<Unit>()
        val releaseExecution = CompletableDeferred<Unit>()
        val executionFinished = CompletableDeferred<Unit>()

        val waiter = async {
            scheduler.run("album", "group_0", 0, MosaicWorkPriority.ForegroundVisible) {
                executionStarted.complete(Unit)
                try {
                    withContext(NonCancellable) {
                        releaseExecution.await()
                    }
                } finally {
                    executionFinished.complete(Unit)
                }
            }
        }
        runCurrent()
        executionStarted.await()

        waiter.cancel()
        runCurrent()

        assertFalse(waiter.isCompleted)
        assertFalse(executionFinished.isCompleted)

        releaseExecution.complete(Unit)
        runCurrent()

        assertTrue(executionFinished.isCompleted)
        assertTrue(waiter.isCompleted)
        assertTrue(waiter.isCancelled)
    }

    @Test
    fun foregroundVisibleRunsBeforeQueuedBackground() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val order = mutableListOf<String>()

        scheduler.setActiveForegroundOwner("album")
        val background = async {
            scheduler.run("timeline", "bg", 0, MosaicWorkPriority.Background) {
                order += "background"
            }
        }
        val foreground = async {
            scheduler.run("album", "visible", 0, MosaicWorkPriority.ForegroundVisible) {
                order += "foreground"
            }
        }

        runCurrent()
        foreground.await()
        background.await()

        assertEquals(listOf("foreground", "background"), order)
    }

    @Test
    fun runningBackgroundIsNotCancelledByForeground() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val order = mutableListOf<String>()

        val background = async {
            scheduler.run("timeline", "bg", 0, MosaicWorkPriority.Background) {
                order += "background_start"
                delay(100)
                order += "background_end"
            }
        }
        runCurrent()
        scheduler.setActiveForegroundOwner("album")
        val foreground = async {
            scheduler.run("album", "visible", 0, MosaicWorkPriority.ForegroundVisible) {
                order += "foreground"
            }
        }

        runCurrent()
        assertEquals(listOf("background_start"), order)
        testScheduler.advanceTimeBy(100)
        runCurrent()
        background.await()
        foreground.await()

        assertEquals(listOf("background_start", "background_end", "foreground"), order)
    }

    @Test
    fun visibleOwnerWorkCancelsRunningOwnerPrefetch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val order = mutableListOf<String>()

        scheduler.setActiveForegroundOwner("album")
        val prefetch = async {
            scheduler.run("album", "group_0", 0, MosaicWorkPriority.ForegroundPrefetch) { token ->
                order += "prefetch_start"
                delay(100)
                token.ensureActive()
                order += "prefetch_end"
            }
        }
        runCurrent()
        val visible = async {
            scheduler.run("album", "group_1", 0, MosaicWorkPriority.ForegroundVisible) {
                order += "visible"
            }
        }

        scheduler.reprioritizePending("album", setOf("group_1"))
        testScheduler.advanceTimeBy(100)
        runCurrent()
        assertFailsWith<MosaicWorkCancelledException> { prefetch.await() }
        visible.await()

        assertEquals(listOf("prefetch_start", "visible"), order)
    }

    @Test
    fun activeForegroundOwnerOutranksOldForegroundOwner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val order = mutableListOf<String>()

        scheduler.setActiveForegroundOwner("person")
        val oldOwner = async {
            scheduler.run("album", "prefetch", 0, MosaicWorkPriority.ForegroundPrefetch) {
                order += "album"
            }
        }
        val activeOwner = async {
            scheduler.run("person", "visible", 0, MosaicWorkPriority.ForegroundVisible) {
                order += "person"
            }
        }

        runCurrent()
        activeOwner.await()
        oldOwner.await()

        assertEquals(listOf("person", "album"), order)
    }

    @Test
    fun duplicatePendingRequestCancelsOlderWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val order = mutableListOf<String>()

        val first = async {
            scheduler.run("album", "group_0", 0, MosaicWorkPriority.ForegroundPrefetch) {
                order += "first"
            }
        }
        val second = async {
            scheduler.run("album", "group_0", 1, MosaicWorkPriority.ForegroundPrefetch) {
                order += "second"
            }
        }

        runCurrent()
        second.await()

        assertFailsWith<Throwable> { first.await() }
        assertEquals(listOf("second"), order)
    }

    @Test
    fun cancelOwnerRemovesPendingWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)

        val blocker = async {
            scheduler.run("timeline", "bg", 0, MosaicWorkPriority.Background) {
                delay(100)
            }
        }
        runCurrent()
        val work = async {
            scheduler.run("album", "group_0", 0, MosaicWorkPriority.ForegroundPrefetch) {
                "done"
            }
        }
        runCurrent()
        scheduler.cancelOwner("album")
        testScheduler.advanceTimeBy(100)
        runCurrent()

        assertFailsWith<Throwable> { work.await() }
        blocker.await()
    }

    @Test
    fun cancelOwnerCancelsRunningWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val order = mutableListOf<String>()

        val work = async {
            scheduler.run("album", "group_0", 0, MosaicWorkPriority.ForegroundVisible) { token ->
                order += "start"
                delay(100)
                token.ensureActive()
                order += "end"
            }
        }
        runCurrent()

        scheduler.cancelOwner("album")
        testScheduler.advanceTimeBy(100)
        runCurrent()

        assertFailsWith<MosaicWorkCancelledException> { work.await() }
        assertEquals(listOf("start"), order)
    }

    @Test
    fun reprioritizePendingMovesVisibleOwnerWorkAhead() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val order = mutableListOf<String>()

        scheduler.setActiveForegroundOwner("album")
        val blocker = async {
            scheduler.run("timeline", "bg", 0, MosaicWorkPriority.Background) {
                delay(100)
            }
        }
        runCurrent()
        val groupZero = async {
            scheduler.run("album", "group_0", 0, MosaicWorkPriority.ForegroundPrefetch) {
                order += "group_0"
            }
        }
        val groupOne = async {
            scheduler.run("album", "group_1", 0, MosaicWorkPriority.ForegroundPrefetch) {
                order += "group_1"
            }
        }
        runCurrent()

        scheduler.reprioritizePending("album", setOf("group_1"))
        testScheduler.advanceTimeBy(100)
        runCurrent()
        blocker.await()
        groupZero.await()
        groupOne.await()

        assertEquals(listOf("group_1", "group_0"), order)
    }

    @Test
    fun staleVisibilityRevisionIsRejectedAndNewSubmissionsUseLatestVisibleSet() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val order = mutableListOf<String>()

        scheduler.setActiveForegroundOwner("album")
        val blocker = async {
            scheduler.run("timeline", "bg", 0, MosaicWorkPriority.Background) {
                delay(100)
            }
        }
        runCurrent()

        val groupZero = async {
            scheduler.run("album", "group_0", 0, MosaicWorkPriority.ForegroundPrefetch) {
                order += "group_0"
            }
        }
        val groupOne = async {
            scheduler.run("album", "group_1", 0, MosaicWorkPriority.ForegroundPrefetch) {
                order += "group_1"
            }
        }
        runCurrent()

        // Newer visibility revision (2) arrives before older revision (1).
        scheduler.reprioritizePending("album", setOf("group_2"), visibilityRevision = 2L)
        scheduler.reprioritizePending("album", setOf("group_0"), visibilityRevision = 1L)

        // group_2 submitted with stale ForegroundPrefetch priority is upgraded to ForegroundVisible
        // because it is in the latest visible set (revision 2).
        val groupTwo = async {
            scheduler.run("album", "group_2", 0, MosaicWorkPriority.ForegroundPrefetch) {
                order += "group_2"
            }
        }
        runCurrent()

        testScheduler.advanceTimeBy(100)
        runCurrent()
        blocker.await()
        groupZero.await()
        groupOne.await()
        groupTwo.await()

        assertEquals(listOf("group_2", "group_0", "group_1"), order)
    }

    @Test
    fun runningPrefetchWorkSurvivesStaleVisibilityRevisionAndCancelsOnNewerNonVisibleRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = MosaicWorkScheduler(dispatcher, this)
        val events = mutableListOf<String>()

        scheduler.setActiveForegroundOwner("album")
        val runningPrefetch = async {
            scheduler.run("album", "group_0", 0, MosaicWorkPriority.ForegroundPrefetch) { token ->
                events += "group_0_start"
                delay(100)
                token.ensureActive()
                events += "group_0_complete"
            }
        }
        runCurrent()
        assertEquals(listOf("group_0_start"), events)

        // Revision 2 keeps group_0 visible; late Revision 1 marks only group_1 visible.
        // Because Revision 1 is stale, running group_0 must NOT be cancelled.
        scheduler.reprioritizePending("album", setOf("group_0"), visibilityRevision = 2L)
        scheduler.reprioritizePending("album", setOf("group_1"), visibilityRevision = 1L)

        testScheduler.advanceTimeBy(100)
        runCurrent()
        runningPrefetch.await()
        assertEquals(listOf("group_0_start", "group_0_complete"), events)

        // Now start another prefetch item and verify a newer revision (3) that excludes it cancels it.
        val cancelledPrefetch = async {
            scheduler.run("album", "group_2", 0, MosaicWorkPriority.ForegroundPrefetch) { token ->
                events += "group_2_start"
                delay(100)
                token.ensureActive()
                events += "group_2_complete"
            }
        }
        runCurrent()
        assertEquals(listOf("group_0_start", "group_0_complete", "group_2_start"), events)

        scheduler.reprioritizePending("album", setOf("group_0"), visibilityRevision = 3L)
        testScheduler.advanceTimeBy(100)
        runCurrent()

        assertFailsWith<MosaicWorkCancelledException> { cancelledPrefetch.await() }
        assertEquals(listOf("group_0_start", "group_0_complete", "group_2_start"), events)
    }
}
