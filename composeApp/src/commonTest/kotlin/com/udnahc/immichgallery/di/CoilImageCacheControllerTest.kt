package com.udnahc.immichgallery.di

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CoilImageCacheControllerTest {
    @Test
    fun registeredSingletonShutsDownDeletesRecreatesAndInstallsInOrder() = runTest {
        val events = mutableListOf<String>()
        val controller = CoilImageCacheController(
            shutdown = { loader: String -> events += "shutdown:$loader" },
            deleteClosedCache = { loader -> events += "delete:$loader" },
            install = { loader -> events += "install:$loader" }
        )
        controller.registerInitial(
            createInitial = { "old" },
            createReplacement = {
                events += "create:new"
                "new"
            }
        )

        controller.rotateAndClearAround {
            events += "clear-state-and-config"
        }

        assertEquals(
            listOf(
                "install:old",
                "shutdown:old",
                "delete:old",
                "create:new",
                "clear-state-and-config",
                "install:new"
            ),
            events
        )
        assertEquals("new", controller.currentImageLoader())
    }

    @Test
    fun lateOldRegistrationCannotReplaceRotatedSingleton() = runTest {
        val controller = CoilImageCacheController<String>(
            shutdown = {},
            deleteClosedCache = {},
            install = {}
        )
        controller.registerInitial(
            createInitial = { "old" },
            createReplacement = { "new" }
        )
        controller.rotateAndClearAround {}

        var attemptedLateCreation = false
        val accepted = controller.registerInitial(
            createInitial = {
                attemptedLateCreation = true
                "late-old"
            },
            createReplacement = { "wrong" }
        )

        assertEquals("new", accepted)
        assertEquals("new", controller.currentImageLoader())
        assertFalse(attemptedLateCreation)
    }

    @Test
    fun failedSessionClearInstallsFreshLoaderBeforeRethrowing() = runTest {
        val events = mutableListOf<String>()
        val controller = CoilImageCacheController<String>(
            shutdown = { events += "shutdown:$it" },
            deleteClosedCache = { events += "delete:$it" },
            install = { events += "install:$it" }
        )
        controller.registerInitial(
            createInitial = { "old" },
            createReplacement = {
                events += "create:new"
                "new"
            }
        )

        assertFailsWith<IllegalStateException> {
            controller.rotateAndClearAround {
                events += "clear-failed"
                error("clear failed")
            }
        }

        assertEquals("new", controller.currentImageLoader())
        assertEquals(
            listOf(
                "install:old",
                "shutdown:old",
                "delete:old",
                "create:new",
                "clear-failed",
                "install:new"
            ),
            events
        )
    }

    @Test
    fun losingConcurrentRegistrationShutsDownItsOwnedResources() = runTest {
        val shutdownLoaders = mutableListOf<String>()
        val controller = CoilImageCacheController<String>(
            shutdown = shutdownLoaders::add,
            deleteClosedCache = {},
            install = {}
        )
        controller.registerInitial(
            createInitial = {
                controller.registerInitial(
                    createInitial = { "winner" },
                    createReplacement = { "winner-replacement" }
                )
                "loser"
            },
            createReplacement = { "loser-replacement" }
        )

        assertEquals("winner", controller.currentImageLoader())
        assertEquals(listOf("loser"), shutdownLoaders)
    }

    @Test
    fun explicitProcessShutdownClosesOncePreservesCacheAndAllowsFreshRegistration() = runTest {
        val events = mutableListOf<String>()
        val controller = CoilImageCacheController<String>(
            shutdown = { events += "shutdown:$it" },
            deleteClosedCache = { events += "delete:$it" },
            install = { events += "install:$it" }
        )
        controller.registerInitial(
            createInitial = { "loader" },
            createReplacement = { "replacement" }
        )
        controller.close()
        controller.close()
        val recreatedLoader = controller.registerInitial(
            createInitial = { "recreated" },
            createReplacement = { "replacement-after-recreation" }
        )

        assertEquals("recreated", recreatedLoader)
        assertEquals(
            listOf(
                "install:loader",
                "shutdown:loader",
                "install:recreated"
            ),
            events
        )
    }
}
