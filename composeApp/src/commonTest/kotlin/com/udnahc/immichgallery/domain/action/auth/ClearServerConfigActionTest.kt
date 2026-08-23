package com.udnahc.immichgallery.domain.action.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClearServerConfigActionTest {
    @Test
    fun logoutExcludesAndJoinsActiveOperationsBeforeClearing() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val operation = launch {
            sessionStateRepository.withOperation {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    events += "operation-finished"
                }
            }
        }
        started.await()
        val action = ClearServerConfigAction(
            mutate = sessionStateRepository::mutate,
            rotateImageCache = { block -> block() },
            clearServerState = { events += "caches" },
            clearConfiguration = { events += "configuration" }
        )

        assertTrue(action().isSuccess)
        operation.join()

        assertEquals(listOf("operation-finished", "caches", "configuration"), events)
    }

    @Test
    fun serverScopedClearUsesExactOrderedNonImageCacheSet() = runTest {
        val events = mutableListOf<String>()
        val clearer = ServerScopedStateClearer(
            listOf(
                { events += "timeline" },
                { events += "albums" },
                { events += "people" },
                { events += "assets-sync" },
                { events += "detail-mosaic" },
                { events += "timeline-mosaic" },
                { events += "api-runtime" }
            )
        )

        clearer.clear()

        assertEquals(
            listOf(
                "timeline",
                "albums",
                "people",
                "assets-sync",
                "detail-mosaic",
                "timeline-mosaic",
                "api-runtime"
            ),
            events
        )
    }

    @Test
    fun configurationClearsOnlyAfterCaches() = runTest {
        val events = mutableListOf<String>()
        val action = ClearServerConfigAction(
            mutate = { block -> events += "mutation"; block() },
            rotateImageCache = { block ->
                events += "image-stop-clear"
                try {
                    block()
                } finally {
                    events += "image-install"
                }
            },
            clearServerState = { events += "caches" },
            clearConfiguration = { events += "configuration" }
        )

        assertTrue(action().isSuccess)
        assertEquals(
            listOf(
                "mutation",
                "image-stop-clear",
                "caches",
                "configuration",
                "image-install"
            ),
            events
        )
    }

    @Test
    fun cacheFailureKeepsConfiguration() = runTest {
        var configurationCleared = false
        var imageLoaderRestored = false
        val action = ClearServerConfigAction(
            mutate = { block -> block() },
            rotateImageCache = { block ->
                try {
                    block()
                } finally {
                    imageLoaderRestored = true
                }
            },
            clearServerState = { error("cache failure") },
            clearConfiguration = { configurationCleared = true }
        )

        assertTrue(action().isFailure)
        assertEquals(false, configurationCleared)
        assertTrue(imageLoaderRestored)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val action = ClearServerConfigAction(
            mutate = { block -> block() },
            rotateImageCache = { block -> block() },
            clearServerState = { throw CancellationException("cancelled") },
            clearConfiguration = {}
        )

        assertFailsWith<CancellationException> { action() }
    }
}
