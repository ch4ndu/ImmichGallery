package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.domain.action.auth.RunSessionOperationAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineMosaicSessionSinkTest {
    @Test
    fun failedMutationCancelsOldSinkWithoutKillingConsumerAndNewGenerationPublishes() = runTest {
        val repository = SessionStateRepository()
        val runner = RunSessionOperationAction(repository)
        val originalStamp = runner.stampForOwner(runner.ownerEpochSnapshot())
        val enteredSink = CompletableDeferred<Unit>()

        val oldPublish = async {
            runTimelineMosaicSessionSink(runner, originalStamp) {
                enteredSink.complete(Unit)
                awaitCancellation()
            }
        }
        enteredSink.await()

        assertTrue(runCatching { repository.mutate { error("clear failed") } }.isFailure)
        assertFalse(oldPublish.await())

        var currentGenerationPublished = false
        val currentStamp = runner.stampForOwner(originalStamp.ownerEpoch)
        assertTrue(
            runTimelineMosaicSessionSink(runner, currentStamp) {
                currentGenerationPublished = true
            }
        )
        assertTrue(currentGenerationPublished)
    }

    @Test
    fun successfulMutationRejectsOldOwnerAndCurrentOwnerStillPublishes() = runTest {
        val repository = SessionStateRepository()
        val runner = RunSessionOperationAction(repository)
        val originalStamp = runner.stampForOwner(runner.ownerEpochSnapshot())
        val enteredSink = CompletableDeferred<Unit>()

        val oldPublish = async {
            runTimelineMosaicSessionSink(runner, originalStamp) {
                enteredSink.complete(Unit)
                awaitCancellation()
            }
        }
        enteredSink.await()

        repository.mutate { }
        assertFalse(oldPublish.await())

        var oldOwnerPublished = false
        assertFalse(
            runTimelineMosaicSessionSink(runner, originalStamp) {
                oldOwnerPublished = true
            }
        )
        assertFalse(oldOwnerPublished)

        var currentOwnerPublished = false
        val currentStamp = runner.stampForOwner(runner.ownerEpochSnapshot())
        assertTrue(
            runTimelineMosaicSessionSink(runner, currentStamp) {
                currentOwnerPublished = true
            }
        )
        assertTrue(currentOwnerPublished)
    }

    @Test
    fun isolatedRuntimeResetCancellationDoesNotCancelLongLivedConsumer() = runTest {
        val first = runIsolatedTimelineRuntimeReset {
            throw CancellationException("session changed")
        }
        var secondRan = false
        val second = runIsolatedTimelineRuntimeReset {
            secondRan = true
        }

        assertTrue(first.isFailure)
        assertTrue(second.isFailure.not())
        assertTrue(second.isSuccess)
        assertTrue(secondRan)
    }

    @Test
    fun isolatedRuntimeResetRethrowsWhenCallerCoroutineIsCancelled() = runTest {
        val enteredReset = CompletableDeferred<Unit>()
        var swallowedCallerCancellation = false

        val callerJob = async {
            val result = runIsolatedTimelineRuntimeReset {
                enteredReset.complete(Unit)
                awaitCancellation()
            }
            swallowedCallerCancellation = result.isFailure
        }
        enteredReset.await()
        callerJob.cancel(CancellationException("consumer cancelled"))
        val outcome = runCatching { callerJob.await() }

        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertFalse(swallowedCallerCancellation)
    }

    @Test
    fun manualMosaicOriginAcceptsUnseenBucketOnlyAtItsInitialGeneration() {
        val origin = TimelineManualMosaicRuntimeOrigin(
            requestId = 7L,
            generationSnapshot = TimelineRuntimeGenerationSnapshot(
                globalGeneration = 3L,
                bucketGenerations = mapOf("existing" to 2L)
            ),
            assetRevisions = mapOf("existing" to 4)
        )

        assertTrue(
            origin.isCurrentFor(
                timeBuckets = setOf("existing", "new"),
                currentGenerationSnapshot = TimelineRuntimeGenerationSnapshot(
                    globalGeneration = 3L,
                    bucketGenerations = mapOf("existing" to 2L)
                ),
                currentAssetRevisions = mapOf("existing" to 4)
            )
        )
        assertFalse(
            origin.isCurrentFor(
                timeBuckets = setOf("existing", "new"),
                currentGenerationSnapshot = TimelineRuntimeGenerationSnapshot(
                    globalGeneration = 3L,
                    bucketGenerations = mapOf("existing" to 2L, "new" to 1L)
                ),
                currentAssetRevisions = mapOf("existing" to 4, "new" to 1)
            )
        )
    }

    @Test
    fun manualMosaicOriginRejectsCompetingRevisionWithoutGenerationChange() {
        val origin = TimelineManualMosaicRuntimeOrigin(
            requestId = 8L,
            generationSnapshot = TimelineRuntimeGenerationSnapshot(
                globalGeneration = 5L,
                bucketGenerations = mapOf("bucket" to 3L)
            ),
            assetRevisions = mapOf("bucket" to 9)
        )

        assertFalse(
            origin.isCurrentFor(
                timeBuckets = setOf("bucket"),
                currentGenerationSnapshot = origin.generationSnapshot,
                currentAssetRevisions = mapOf("bucket" to 10)
            )
        )
    }
}
