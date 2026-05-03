package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
