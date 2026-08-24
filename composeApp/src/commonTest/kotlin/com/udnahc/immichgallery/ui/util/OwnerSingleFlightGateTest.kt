package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OwnerSingleFlightGateTest {
    @Test
    fun rapidActivationStartsOneFakeRequestUntilCompletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = OwnerSingleFlightGate()
        val release = CompletableDeferred<Unit>()
        var requestCount = 0

        val first = gate.launch(this, dispatcher) {
            requestCount++
            release.await()
        }
        val duplicate = gate.launch(this, dispatcher) { requestCount++ }

        assertNotNull(first)
        assertNull(duplicate)
        runCurrent()
        assertEquals(1, requestCount)

        release.complete(Unit)
        advanceUntilIdle()
        assertNotNull(gate.launch(this, dispatcher) { requestCount++ })
        advanceUntilIdle()
        assertEquals(2, requestCount)
    }

    @Test
    fun cancellationReleasesTheOwnerGate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = OwnerSingleFlightGate()
        val started = CompletableDeferred<Unit>()
        val request = gate.launch(this, dispatcher) {
            started.complete(Unit)
            awaitCancellation()
        }

        assertNotNull(request)
        runCurrent()
        started.await()
        request.cancel()
        request.join()

        assertNotNull(gate.launch(this, dispatcher) {})
        advanceUntilIdle()
    }
}
