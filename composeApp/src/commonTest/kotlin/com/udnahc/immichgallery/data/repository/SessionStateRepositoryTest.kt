package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.domain.action.auth.RunSessionOperationAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateRepositoryTest {
    @Test
    fun lateResponseCannotCommitAfterMutation() = runTest {
        val repository = SessionStateRepository()
        val initialGeneration = repository.generationSnapshot()
        lateinit var oldToken: SessionOperationToken
        repository.withOperation { token -> oldToken = token }

        repository.mutate {}

        assertEquals(initialGeneration + 1, repository.generationSnapshot())
        assertFailsWith<StaleSessionOperationException> {
            repository.withCommit(oldToken) {}
        }
    }

    @Test
    fun generationBoundRunnerRejectsQueuedWorkFromPreviousSession() = runTest {
        val repository = SessionStateRepository()
        val runner = RunSessionOperationAction(repository)
        val oldGeneration = runner.generationSnapshot()
        runner.runForGeneration(oldGeneration) {}

        repository.mutate {}

        assertFailsWith<StaleSessionOperationException> {
            runner.runForGeneration(oldGeneration) {}
        }
    }

    @Test
    fun failedMutationLetsLongLivedOwnerStampNewWorkWithoutRevivingOldWork() = runTest {
        val repository = SessionStateRepository()
        val runner = RunSessionOperationAction(repository)
        val ownerEpoch = runner.ownerEpochSnapshot()
        val oldGeneration = runner.runStamped { generation -> generation }

        assertFailsWith<IllegalStateException> {
            repository.mutate {
                assertEquals(oldGeneration, runner.generationSnapshot())
                assertEquals(ownerEpoch, runner.ownerEpochSnapshot())
                assertFailsWith<SessionOperationRejectedException> {
                    runner.run {}
                }
                error("clear failed")
            }
        }

        val newGeneration = runner.runStamped { generation -> generation }
        assertEquals(newGeneration, runner.generationSnapshot())
        assertEquals(ownerEpoch, runner.ownerEpochSnapshot())
        assertEquals(oldGeneration + 1, newGeneration)
        assertFailsWith<StaleSessionOperationException> {
            runner.runForGeneration(oldGeneration) {}
        }
        runner.runForGeneration(newGeneration) {}
        runner.runForOwner(ownerEpoch) {}
    }

    @Test
    fun successfulMutationInvalidatesOldOwnerBeforeNavigationDisposesIt() = runTest {
        val repository = SessionStateRepository()
        val runner = RunSessionOperationAction(repository)
        val oldOwnerEpoch = runner.ownerEpochSnapshot()

        repository.mutate {}

        val postMutationStampFromOldOwner = runner.stampForOwner(oldOwnerEpoch)
        assertFailsWith<StaleSessionOperationException> {
            runner.runForOwner(oldOwnerEpoch) {}
        }
        assertFailsWith<StaleSessionOperationException> {
            runner.runForStamp(postMutationStampFromOldOwner) {}
        }
        val newOwnerEpoch = runner.ownerEpochSnapshot()
        assertEquals(oldOwnerEpoch + 1, newOwnerEpoch)
        runner.runForOwner(newOwnerEpoch) {}
    }

    @Test
    fun closedAdmissionIsCancellationControlFlow() {
        acceptsCancellation(SessionOperationRejectedException())
    }

    @Test
    fun operationRegistersBeforeWorkAndChildrenInheritToken() = runTest {
        val repository = SessionStateRepository()
        repository.withOperation { parentToken ->
            assertEquals(1, repository.registeredJobCount())
            coroutineScope {
                async {
                    repository.withOperation { childToken ->
                        assertSame(parentToken, childToken)
                        assertEquals(2, repository.registeredJobCount())
                    }
                }.await()
            }
            assertEquals(1, repository.registeredJobCount())
        }
        assertEquals(0, repository.registeredJobCount())
    }

    @Test
    fun mutationClosesAdmissionCancelsAndJoinsBeforeClearThenAllowsNewGeneration() = runTest {
        val repository = SessionStateRepository()
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val operation = launch {
            repository.withOperation {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    events += "operation-finally"
                }
            }
        }
        started.await()

        repository.mutate {
            assertFailsWith<SessionOperationRejectedException> {
                repository.withOperation {}
            }
            events += "clear"
        }
        operation.join()

        assertEquals(listOf("operation-finally", "clear"), events)
        repository.withOperation { token ->
            repository.withCommit(token) { events += "new-commit" }
        }
        assertEquals("new-commit", events.last())
    }

    @Test
    fun cancelledMutationKeepsAdmissionClosedUntilOldJobFinishesAndSkipsClear() = runTest {
        val repository = SessionStateRepository()
        val operationStarted = CompletableDeferred<Unit>()
        val oldJobCleanupStarted = CompletableDeferred<Unit>()
        val releaseOldJobCleanup = CompletableDeferred<Unit>()
        val oldJob = launch {
            repository.withOperation {
                operationStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    oldJobCleanupStarted.complete(Unit)
                    withContext(NonCancellable) {
                        releaseOldJobCleanup.await()
                    }
                }
            }
        }
        operationStarted.await()
        var clearRan = false
        val mutation = launch {
            repository.mutate {
                clearRan = true
            }
        }
        oldJobCleanupStarted.await()

        mutation.cancel()

        assertFalse(mutation.isCompleted)
        assertFalse(clearRan)
        assertFailsWith<SessionOperationRejectedException> {
            repository.withOperation {}
        }

        releaseOldJobCleanup.complete(Unit)
        advanceUntilIdle()

        assertTrue(oldJob.isCompleted)
        assertTrue(mutation.isCancelled)
        assertFalse(clearRan)
        repository.withOperation {}
    }

    @Test
    fun repositoryCommitEntersOnceAndNestedEntryFailsFast() = runTest {
        val repository = SessionStateRepository()
        var commits = 0
        repository.withOperation { token ->
            repository.withCommit(token) {
                commits += 1
                assertFailsWith<IllegalStateException> {
                    repository.withCommit(token) { commits += 1 }
                }
            }
        }
        assertEquals(1, commits)
    }

    @Test
    fun mutationClearBypassesCommitAdmissionAndFinallyCleanupDoesNotDeadlock() = runTest {
        val repository = SessionStateRepository()
        var mutationRan = false
        val job = launch {
            repository.withOperation {
                try {
                    awaitCancellation()
                } finally {
                    assertTrue(repository.registeredJobCount() >= 1)
                }
            }
        }
        advanceUntilIdle()

        repository.mutate { mutationRan = true }
        job.join()

        assertTrue(mutationRan)
        assertEquals(0, repository.registeredJobCount())
    }

    private fun acceptsCancellation(error: CancellationException) = Unit
}
