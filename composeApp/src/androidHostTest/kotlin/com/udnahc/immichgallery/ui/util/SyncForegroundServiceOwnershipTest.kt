package com.udnahc.immichgallery.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SyncForegroundServiceOwnershipTest {
    @Test
    fun overlappingManualAndBackgroundWorkStopsOnlyAtTrackerZero() {
        val ownership = SyncForegroundServiceOwnership()
        ownership.onServiceStart(startId = 1)
        ownership.onManualJobStarted(startId = 1)

        ownership.onManualJobFinished(startId = 1)

        assertNull(ownership.stopStartId(trackedActiveCount = 1))
        assertEquals(1, ownership.stopStartId(trackedActiveCount = 0))
    }

    @Test
    fun olderCompletionCannotStopNewerManualStart() {
        val ownership = SyncForegroundServiceOwnership()
        ownership.onServiceStart(startId = 1)
        ownership.onManualJobStarted(startId = 1)
        ownership.onServiceStart(startId = 2)
        ownership.onManualJobStarted(startId = 2)

        ownership.onManualJobFinished(startId = 1)

        assertNull(ownership.stopStartId(trackedActiveCount = 0))
        assertEquals(setOf(2), ownership.activeManualStartIds())

        ownership.onManualJobFinished(startId = 2)

        assertEquals(2, ownership.stopStartId(trackedActiveCount = 0))
    }

    @Test
    fun trackerZeroUsesLatestDeliveredStartId() {
        val ownership = SyncForegroundServiceOwnership()
        ownership.onServiceStart(startId = 7)
        ownership.onServiceStart(startId = 8)

        assertNull(ownership.stopStartId(trackedActiveCount = 2))
        assertEquals(8, ownership.stopStartId(trackedActiveCount = 0))
    }

    @Test
    fun timeoutIsTerminalForAllServiceOwnedJobs() {
        val ownership = SyncForegroundServiceOwnership()
        ownership.onServiceStart(startId = 3)
        ownership.onManualJobStarted(startId = 3)
        ownership.onServiceStart(startId = 4)
        ownership.onManualJobStarted(startId = 4)

        assertEquals(setOf(3, 4), ownership.onTimeout())
        assertEquals(emptySet(), ownership.activeManualStartIds())
        assertNull(ownership.latestStartId())
        assertNull(ownership.stopStartId(trackedActiveCount = 0))
        assertEquals(emptySet(), ownership.onDestroy())
    }

    @Test
    fun completionAfterTerminalTimeoutCannotRestoreOwnership() {
        val ownership = SyncForegroundServiceOwnership()
        ownership.onServiceStart(startId = 4)
        ownership.onManualJobStarted(startId = 4)

        ownership.onTimeout()
        ownership.onManualJobFinished(startId = 4)

        assertEquals(emptySet(), ownership.activeManualStartIds())
        assertNull(ownership.latestStartId())
        assertNull(ownership.stopStartId(trackedActiveCount = 0))
    }

    @Test
    fun startQueuedAfterTerminalTimeoutIsRejectedWithoutRestoringOwnership() {
        val ownership = SyncForegroundServiceOwnership()
        ownership.onServiceStart(startId = 4)
        ownership.onManualJobStarted(startId = 4)
        ownership.onTimeout()

        assertFalse(ownership.onServiceStart(startId = 5))
        assertEquals(emptySet(), ownership.activeManualStartIds())
        assertNull(ownership.latestStartId())
        assertNull(ownership.stopStartId(trackedActiveCount = 1))
    }

    @Test
    fun destructionReturnsJobsToCancelAndResetsOwnership() {
        val ownership = SyncForegroundServiceOwnership()
        ownership.onServiceStart(startId = 10)
        ownership.onManualJobStarted(startId = 10)
        ownership.onServiceStart(startId = 11)
        ownership.onManualJobStarted(startId = 11)

        assertEquals(setOf(10, 11), ownership.onDestroy())
        assertEquals(emptySet(), ownership.activeManualStartIds())
        assertNull(ownership.latestStartId())
        assertNull(ownership.stopStartId(trackedActiveCount = 0))
    }

    @Test
    fun sessionCancelledJobReleasesOnlyItsOwnStart() {
        val ownership = SyncForegroundServiceOwnership()
        ownership.onServiceStart(startId = 20)
        ownership.onManualJobStarted(startId = 20)
        ownership.onServiceStart(startId = 21)
        ownership.onManualJobStarted(startId = 21)

        // Session mutation completes the cancelled coroutine through the same
        // job-finalization path as any other terminal outcome.
        ownership.onManualJobFinished(startId = 20)

        assertEquals(setOf(21), ownership.activeManualStartIds())
        assertNull(ownership.stopStartId(trackedActiveCount = 0))
    }
}
