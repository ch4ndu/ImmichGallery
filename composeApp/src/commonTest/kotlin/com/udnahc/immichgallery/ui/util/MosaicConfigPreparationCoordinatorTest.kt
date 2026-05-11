package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MosaicConfigPreparationCoordinatorTest {
    @Test
    fun cancelObsoleteWorkInvokesRegisteredOwners() = runTest {
        val coordinator = MosaicConfigPreparationCoordinator()
        val invoked = mutableSetOf<String>()

        coordinator.register("timeline") { invoked += "timeline" }
        coordinator.register("album") { invoked += "album" }

        coordinator.cancelObsoleteWork()

        assertEquals(setOf("timeline", "album"), invoked)
    }

    @Test
    fun unregisterStopsFutureCancellationCallbacks() = runTest {
        val coordinator = MosaicConfigPreparationCoordinator()
        val invoked = mutableListOf<String>()

        coordinator.register("timeline") { invoked += "timeline" }
        coordinator.register("album") { invoked += "album" }
        coordinator.unregister("timeline")

        coordinator.cancelObsoleteWork()

        assertEquals(listOf("album"), invoked)
    }
}
