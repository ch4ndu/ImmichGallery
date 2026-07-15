package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailSyncGateTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun secondSyncWaitsForTheActiveSyncToFinish() = runTest {
        val gate = DetailSyncGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = async {
            gate.run {
                events += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "first-end"
            }
        }
        firstEntered.await()
        val second = async {
            gate.run { events += "second" }
        }

        runCurrent()
        assertEquals(listOf("first-start"), events)

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("first-start", "first-end", "second"), events)
    }
}
