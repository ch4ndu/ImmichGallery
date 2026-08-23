package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGridLayoutRunnerTest {
    @Test
    fun debouncedWorkPublishesOnlyLatestGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = PhotoGridLayoutRunner(this, dispatcher, debounceMillis = 100)
        val published = mutableListOf<String>()

        runner.launch(debounce = true) { generation ->
            if (runner.isCurrent(generation)) published += "first"
        }
        advanceTimeBy(50)
        runner.launch(debounce = true) { generation ->
            if (runner.isCurrent(generation)) published += "second"
        }

        advanceTimeBy(99)
        runCurrent()
        assertTrue(published.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("second"), published)
    }

    @Test
    fun cancelPreventsPendingWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = PhotoGridLayoutRunner(this, dispatcher, debounceMillis = 100)
        val published = mutableListOf<String>()

        runner.launch(debounce = true) { published += "stale" }
        runner.cancel()
        advanceTimeBy(100)
        runCurrent()

        assertTrue(published.isEmpty())
    }

    @Test
    fun replacingRunningWorkInvalidatesCancelledPublisher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = PhotoGridLayoutRunner(this, dispatcher)
        val cancelled = mutableListOf<String>()
        val published = mutableListOf<String>()

        runner.launch { generation ->
            try {
                awaitCancellation()
            } finally {
                cancelled += "first"
                if (runner.isCurrent(generation)) published += "first"
            }
        }
        runCurrent()

        runner.launch { generation ->
            if (runner.isCurrent(generation)) published += "second"
        }
        runCurrent()

        assertEquals(listOf("first"), cancelled)
        assertEquals(listOf("second"), published)
    }

    @Test
    fun cancelInvalidatesRunningGenerationBeforeCancellationHandlerPublishes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = PhotoGridLayoutRunner(this, dispatcher)
        val startedGeneration = CompletableDeferred<Long>()
        val published = mutableListOf<String>()

        runner.launch { generation ->
            startedGeneration.complete(generation)
            try {
                awaitCancellation()
            } finally {
                if (runner.isCurrent(generation)) published += "stale"
            }
        }
        runCurrent()

        val generation = startedGeneration.await()
        assertTrue(runner.isCurrent(generation))

        runner.cancel()
        assertFalse(runner.isCurrent(generation))
        runCurrent()

        assertTrue(published.isEmpty())
    }

    @Test
    fun failedCompareCancelsDisplacedLazyCandidateBeforeStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var runner: PhotoGridLayoutRunner
        var compareAttempts = 0
        val publishedGenerations = mutableListOf<Long>()

        runner = PhotoGridLayoutRunner(
            scope = this,
            dispatcher = dispatcher,
            debounceMillis = 0,
            beforeLaunchStateCompare = {
                if (compareAttempts++ == 0) runner.cancel()
            }
        )

        runner.launch { generation ->
            publishedGenerations += generation
        }
        runCurrent()

        assertFalse(runner.isCurrent(1L))
        assertEquals(listOf(2L), publishedGenerations)
    }

    @Test
    fun displacedInstalledLazyCandidateDoesNotStartAfterReplacement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var runner: PhotoGridLayoutRunner
        var startAttempts = 0
        val publishedGenerations = mutableListOf<Long>()

        runner = PhotoGridLayoutRunner(
            scope = this,
            dispatcher = dispatcher,
            debounceMillis = 0,
            beforeCandidateStart = {
                if (startAttempts++ == 0) {
                    runner.launch { generation ->
                        publishedGenerations += generation
                    }
                }
            }
        )

        runner.launch { generation ->
            publishedGenerations += generation
        }
        runCurrent()

        assertFalse(runner.isCurrent(1L))
        assertEquals(listOf(2L), publishedGenerations)
    }

    @Test
    fun queuedConcurrentReplaceAndCancelLeaveNoCurrentPublisher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = PhotoGridLayoutRunner(this, dispatcher)
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()

        val callers = listOf(
            launch {
                runner.launch { generation ->
                    release.await()
                    if (runner.isCurrent(generation)) published += "first"
                }
            },
            launch {
                runner.launch { generation ->
                    release.await()
                    if (runner.isCurrent(generation)) published += "second"
                }
            },
            launch { runner.cancel() }
        )
        callers.joinAll()

        release.complete(Unit)
        runCurrent()

        assertTrue(published.isEmpty())
    }
}
