package com.udnahc.immichgallery.di

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

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
    fun failedDeletionRecoversCurrentIdentityAndLaterRetryCanClear() = runTest {
        val events = mutableListOf<String>()
        val deletionFailure = IllegalStateException("delete failed")
        var shouldFailDeletion = true
        var replacementCount = 0
        val controller = CoilImageCacheController<String>(
            shutdown = { events += "shutdown:$it" },
            deleteClosedCache = {
                events += "delete:$it"
                if (shouldFailDeletion) throw deletionFailure
            },
            install = { events += "install:$it" }
        )
        controller.registerInitial({ "old" }) { "new-${++replacementCount}" }

        val failure = assertFailsWith<IllegalStateException> {
            controller.rotateAndClearAround { events += "clear-state-and-config" }
        }

        assertSame(deletionFailure, failure)
        assertEquals("new-1", controller.currentImageLoader())
        assertEquals(listOf("install:old", "shutdown:old", "delete:old", "install:new-1"), events)

        shouldFailDeletion = false
        controller.rotateAndClearAround { events += "clear-state-and-config" }
        assertEquals("new-2", controller.currentImageLoader())
        assertEquals("install:new-2", events.last())
        assertEquals(1, events.count { it == "clear-state-and-config" })
    }

    @Test
    fun failedInitialReplacementCreationRecoversWithoutRunningSessionMutation() = runTest {
        val creationFailure = IllegalStateException("create failed")
        val events = mutableListOf<String>()
        var createCount = 0
        val controller = CoilImageCacheController<String>(
            shutdown = { events += "shutdown:$it" },
            deleteClosedCache = { events += "delete:$it" },
            install = { events += "install:$it" }
        )
        controller.registerInitial({ "old" }) {
            if (++createCount == 1) throw creationFailure
            "recovered"
        }

        val failure = assertFailsWith<IllegalStateException> {
            controller.rotateAndClearAround { events += "clear-state-and-config" }
        }

        assertSame(creationFailure, failure)
        assertEquals(2, createCount)
        assertEquals("recovered", controller.currentImageLoader())
        assertEquals(listOf("install:old", "shutdown:old", "delete:old", "install:recovered"), events)
    }

    @Test
    fun failedRecoveryKeepsOriginalFailureAndDoesNotChangeConfiguration() = runTest {
        val deletionFailure = IllegalStateException("delete failed")
        val recoveryFailure = IllegalArgumentException("create failed")
        val installed = mutableListOf<String>()
        var mutated = false
        val controller = CoilImageCacheController<String>(
            shutdown = {},
            deleteClosedCache = { throw deletionFailure },
            install = installed::add
        )
        controller.registerInitial({ "old" }) { throw recoveryFailure }

        val failure = assertFailsWith<IllegalStateException> {
            controller.rotateAndClearAround { mutated = true }
        }

        assertSame(deletionFailure, failure)
        assertEquals(listOf(recoveryFailure), failure.suppressedExceptions)
        assertEquals(listOf("old"), installed)
        assertFalse(mutated)
    }

    @Test
    fun unconfirmedShutdownDoesNotOpenACompetingCacheOrMutateSession() = runTest {
        val shutdownFailure = IllegalStateException("shutdown failed")
        val events = mutableListOf<String>()
        val controller = CoilImageCacheController<String>(
            shutdown = { throw shutdownFailure },
            deleteClosedCache = { events += "delete" },
            install = { events += "install:$it" }
        )
        controller.registerInitial({ "old" }) {
            events += "create"
            "new"
        }

        val failure = assertFailsWith<IllegalStateException> {
            controller.rotateAndClearAround { events += "mutate" }
        }

        assertSame(shutdownFailure, failure)
        assertEquals(listOf("install:old"), events)
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
