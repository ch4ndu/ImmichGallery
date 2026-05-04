package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class MosaicWorkSchedulerTest {
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
}
