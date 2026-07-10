package com.udnahc.immichgallery.domain.action.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClearServerConfigActionTest {
    @Test
    fun configurationClearsOnlyAfterCaches() = runTest {
        val events = mutableListOf<String>()
        val action = ClearServerConfigAction(
            clearCaches = { events += "caches" },
            clearConfiguration = { events += "configuration" }
        )

        assertTrue(action().isSuccess)
        assertEquals(listOf("caches", "configuration"), events)
    }

    @Test
    fun cacheFailureKeepsConfiguration() = runTest {
        var configurationCleared = false
        val action = ClearServerConfigAction(
            clearCaches = { error("cache failure") },
            clearConfiguration = { configurationCleared = true }
        )

        assertTrue(action().isFailure)
        assertEquals(false, configurationCleared)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val action = ClearServerConfigAction(
            clearCaches = { throw CancellationException("cancelled") },
            clearConfiguration = {}
        )

        assertFailsWith<CancellationException> { action() }
    }
}
