package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.data.repository.SessionOperationRejectedException
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.data.repository.StaleSessionOperationException
import com.udnahc.immichgallery.data.remote.TimelineApiCompatibilityException
import com.udnahc.immichgallery.data.remote.TimelineCompatibilityCategory
import com.udnahc.immichgallery.domain.action.auth.RunSessionOperationAction
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncCompatibilityException
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineBucketSyncResult
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import com.udnahc.immichgallery.ui.util.PlatformSyncActivityNotifier
import com.udnahc.immichgallery.ui.util.SyncActivityTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineManualSyncActionTest {
    @Test
    fun noOpSyncReportsSuccessWithoutMosaicPrecompute() = runTest {
        var prepareCalls = 0
        val action = action(
            assetResults = listOf(
                TimelineAssetSyncResult(successfulBucketIds = setOf("2026-05"), failedBucketIds = emptySet())
            ),
            prepare = { bucketIds ->
                prepareCalls++
                TimelineMosaicPrecomputeResult(bucketIds, emptySet())
            }
        )

        val result = action(request()).getOrThrow()

        assertEquals(setOf("2026-05"), result.assetSyncResult.successfulBucketIds)
        assertEquals(emptySet(), result.assetSyncResult.changedBucketIds)
        assertNull(result.mosaicPrecomputeResult)
        assertEquals(0, prepareCalls)
    }

    @Test
    fun changedAndRemovedBucketsAreSurfacedAndRemovedBucketsAreCleared() = runTest {
        val clearedBuckets = mutableListOf<Set<String>>()
        val action = action(
            bucketResult = TimelineBucketSyncResult(
                buckets = listOf(bucket("2026-05")),
                removedBucketIds = setOf("2026-04")
            ),
            assetResults = listOf(
                TimelineAssetSyncResult(
                    successfulBucketIds = setOf("2026-05"),
                    failedBucketIds = emptySet(),
                    changedBucketIds = setOf("2026-05")
                )
            ),
            clear = { clearedBuckets += it }
        )

        val result = action(request()).getOrThrow()

        assertEquals(setOf("2026-04"), result.bucketSyncResult.removedBucketIds)
        assertEquals(setOf("2026-05"), result.assetSyncResult.changedBucketIds)
        assertEquals(listOf(setOf("2026-04")), clearedBuckets)
    }

    @Test
    fun removedBucketsAreClearedBeforeAssetFailure() = runTest {
        val clearedBuckets = mutableListOf<Set<String>>()
        val failure = TimelineApiCompatibilityException(
            serverVersion = "1.142.0",
            endpoint = "/api/timeline/bucket",
            category = TimelineCompatibilityCategory.SHAPE
        )
        val action = TimelineManualSyncAction(
            syncBuckets = {
                Result.success(
                    TimelineBucketSyncResult(
                        buckets = listOf(bucket("2026-05")),
                        removedBucketIds = setOf("2026-04")
                    )
                )
            },
            syncAssets = { Result.failure(failure) },
            clearMosaicBuckets = { clearedBuckets += it },
            prepareMosaic = { _, _, _, _, _, _ ->
                Result.success(TimelineMosaicPrecomputeResult(emptySet(), emptySet()))
            }
        )

        val result = action(request())

        assertEquals(failure, result.exceptionOrNull())
        assertEquals(listOf(setOf("2026-04")), clearedBuckets)
    }

    @Test
    fun removedBucketsAreClearedBeforeChangedSiblingAssetCompatibilityFailureReturns() = runTest {
        val clearedBuckets = mutableListOf<Set<String>>()
        val failure = TimelineApiCompatibilityException(
            serverVersion = "1.142.0",
            endpoint = "/api/timeline/bucket",
            category = TimelineCompatibilityCategory.DECODING
        )
        val action = TimelineManualSyncAction(
            syncBuckets = {
                Result.success(
                    TimelineBucketSyncResult(
                        buckets = listOf(bucket("changed"), bucket("failed")),
                        removedBucketIds = setOf("removed")
                    )
                )
            },
            syncAssets = { Result.failure(failure) },
            clearMosaicBuckets = { clearedBuckets += it },
            prepareMosaic = { _, _, _, _, _, _ ->
                Result.success(TimelineMosaicPrecomputeResult(emptySet(), emptySet()))
            }
        )

        val result = action(request())

        assertEquals(failure, result.exceptionOrNull())
        assertEquals(listOf(setOf("removed")), clearedBuckets)
    }

    @Test
    fun firstCompatibilityPartialFailurePropagatesChangedResultWithoutNoOpRetry() = runTest {
        val failure = TimelineApiCompatibilityException(
            serverVersion = "1.142.0",
            endpoint = "/api/timeline/bucket",
            category = TimelineCompatibilityCategory.SHAPE
        )
        val partialResult = TimelineAssetSyncResult(
            successfulBucketIds = setOf("changed"),
            failedBucketIds = setOf("failed"),
            changedBucketIds = setOf("changed")
        )
        var syncCall = 0
        val action = TimelineManualSyncAction(
            syncBuckets = {
                Result.success(
                    TimelineBucketSyncResult(
                        buckets = listOf(bucket("changed"), bucket("failed"))
                    )
                )
            },
            syncAssets = {
                syncCall++
                if (syncCall == 1) {
                    Result.failure(TimelineAssetSyncCompatibilityException(partialResult, failure))
                } else {
                    Result.success(
                        TimelineAssetSyncResult(
                            successfulBucketIds = setOf("changed"),
                            failedBucketIds = emptySet(),
                            changedBucketIds = emptySet()
                        )
                    )
                }
            },
            clearMosaicBuckets = {},
            prepareMosaic = { _, _, _, _, _, _ ->
                Result.success(TimelineMosaicPrecomputeResult(emptySet(), emptySet()))
            }
        )

        val error = assertIs<TimelineAssetSyncCompatibilityException>(
            action(request()).exceptionOrNull()
        )

        assertEquals(partialResult, error.partialResult)
        assertEquals(failure, error.compatibilityCause)
        assertEquals(1, syncCall)
    }

    @Test
    fun retryCompatibilityFailureMergesFirstAndPartialRetryResults() = runTest {
        val failure = TimelineApiCompatibilityException(
            serverVersion = "1.142.0",
            endpoint = "/api/timeline/bucket",
            category = TimelineCompatibilityCategory.SHAPE
        )
        var syncCall = 0
        val action = TimelineManualSyncAction(
            syncBuckets = {
                Result.success(
                    TimelineBucketSyncResult(
                        buckets = listOf(bucket("first"), bucket("retry-changed"), bucket("retry-failed"))
                    )
                )
            },
            syncAssets = {
                syncCall++
                if (syncCall == 1) {
                    Result.success(
                        TimelineAssetSyncResult(
                            successfulBucketIds = setOf("first"),
                            failedBucketIds = setOf("retry-changed", "retry-failed"),
                            changedBucketIds = setOf("first")
                        )
                    )
                } else {
                    Result.failure(
                        TimelineAssetSyncCompatibilityException(
                            partialResult = TimelineAssetSyncResult(
                                successfulBucketIds = setOf("retry-changed"),
                                failedBucketIds = setOf("retry-failed"),
                                changedBucketIds = setOf("retry-changed")
                            ),
                            compatibilityCause = failure
                        )
                    )
                }
            },
            clearMosaicBuckets = {},
            prepareMosaic = { _, _, _, _, _, _ ->
                Result.success(TimelineMosaicPrecomputeResult(emptySet(), emptySet()))
            }
        )

        val error = assertIs<TimelineAssetSyncCompatibilityException>(
            action(request()).exceptionOrNull()
        )

        assertEquals(setOf("first", "retry-changed"), error.partialResult.successfulBucketIds)
        assertEquals(setOf("retry-failed"), error.partialResult.failedBucketIds)
        assertEquals(setOf("first", "retry-changed"), error.partialResult.changedBucketIds)
        assertEquals(failure, error.compatibilityCause)
    }

    @Test
    fun failuresRetryAndReportFailedBuckets() = runTest {
        val action = action(
            bucketResult = TimelineBucketSyncResult(
                buckets = listOf(bucket("a"), bucket("b"))
            ),
            assetResults = listOf(
                TimelineAssetSyncResult(
                    successfulBucketIds = setOf("a"),
                    failedBucketIds = setOf("b")
                ),
                TimelineAssetSyncResult(
                    successfulBucketIds = emptySet(),
                    failedBucketIds = setOf("b")
                )
            )
        )

        val result = action(request()).getOrThrow()

        assertEquals(setOf("a"), result.assetSyncResult.successfulBucketIds)
        assertEquals(setOf("b"), result.assetSyncResult.failedBucketIds)
    }

    @Test
    fun precomputeRunsOnlyWhenCacheAndGeometryAreAvailable() = runTest {
        var prepareCalls = 0
        val changedResult = TimelineAssetSyncResult(
            successfulBucketIds = setOf("2026-05"),
            failedBucketIds = emptySet(),
            changedBucketIds = setOf("2026-05")
        )
        val action = action(
            assetResults = listOf(changedResult, changedResult),
            prepare = { bucketIds ->
                prepareCalls++
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = bucketIds,
                    failedBucketIds = emptySet(),
                    bucketGeometrySummaries = listOf(TimelineBucketGeometrySummary("2026-05", 10f, 1))
                )
            }
        )

        val noGeometry = action(request(geometryRequest = null)).getOrThrow()
        val withGeometry = action(request()).getOrThrow()

        assertNull(noGeometry.mosaicPrecomputeResult)
        assertEquals(setOf("2026-05"), withGeometry.mosaicPrecomputeResult?.successfulBucketIds)
        assertEquals(1, prepareCalls)
    }

    @Test
    fun queuedPlatformCommandIsRejectedAfterSessionReplacement() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val coordinator = TimelineManualSyncCoordinator(
            action = action(assetResults = emptyList()),
            syncActivityTracker = SyncActivityTracker(
                object : PlatformSyncActivityNotifier {
                    override fun onActiveSyncCountChanged(activeCount: Int) = Unit
                }
            ),
            sessionStateRepository = sessionStateRepository
        )
        val oldSessionRequest = request()
        val cancellationEvent = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.first { it is TimelineManualSyncEvent.SessionCancelled }
        }

        sessionStateRepository.mutate {}

        assertFailsWith<StaleSessionOperationException> {
            coordinator.run(oldSessionRequest)
        }
        assertEquals(
            TimelineManualSyncEvent.SessionCancelled(oldSessionRequest),
            cancellationEvent.await()
        )
    }

    @Test
    fun closedAdmissionEmitsTerminalEventAndRemainsCancellation() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val coordinator = TimelineManualSyncCoordinator(
            action = action(assetResults = emptyList()),
            syncActivityTracker = syncActivityTracker(),
            sessionStateRepository = sessionStateRepository
        )
        val request = request()
        val mutationStarted = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = launch {
            sessionStateRepository.mutate {
                mutationStarted.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationStarted.await()
        val cancellationEvent = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.first { it is TimelineManualSyncEvent.SessionCancelled }
        }

        assertFailsWith<SessionOperationRejectedException> {
            coordinator.run(request)
        }
        assertEquals(
            TimelineManualSyncEvent.SessionCancelled(request),
            cancellationEvent.await()
        )

        releaseMutation.complete(Unit)
        mutation.join()
    }

    @Test
    fun sessionMutationCancellationEmitsTerminalEventAndRemainsCancellation() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val actionStarted = CompletableDeferred<Unit>()
        val coordinator = TimelineManualSyncCoordinator(
            action = TimelineManualSyncAction(
                syncBuckets = {
                    actionStarted.complete(Unit)
                    awaitCancellation()
                },
                syncAssets = { Result.success(TimelineAssetSyncResult(emptySet(), emptySet())) },
                clearMosaicBuckets = {},
                prepareMosaic = { _, _, _, _, _, _ ->
                    Result.success(TimelineMosaicPrecomputeResult(emptySet(), emptySet()))
                }
            ),
            syncActivityTracker = syncActivityTracker(),
            sessionStateRepository = sessionStateRepository
        )
        val request = request()
        val events = mutableListOf<TimelineManualSyncEvent>()
        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.take(2).toList(events)
        }
        val runningSync = async {
            coordinator.run(request)
        }
        actionStarted.await()

        assertFailsWith<IllegalStateException> {
            sessionStateRepository.mutate {
                error("clear failed")
            }
        }

        assertFailsWith<StaleSessionOperationException> {
            runningSync.await()
        }
        eventCollector.join()
        assertEquals(
            listOf(
                TimelineManualSyncEvent.Started(request),
                TimelineManualSyncEvent.SessionCancelled(request)
            ),
            events
        )
    }

    @Test
    fun bufferedResultEventsRequireCurrentStampButCancellationCanUnwindFailedMutationOwner() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val sessionOperationRunner = RunSessionOperationAction(sessionStateRepository)
        val oldRequest = request(
            sessionStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L)
        )
        assertFailsWith<IllegalStateException> {
            sessionStateRepository.mutate {
                error("clear failed")
            }
        }
        val result = result(oldRequest)
        val bufferedEvents = listOf<TimelineManualSyncEvent>(
            TimelineManualSyncEvent.Started(oldRequest),
            TimelineManualSyncEvent.Completed(result),
            TimelineManualSyncEvent.Failed(oldRequest, IllegalStateException("request failed"))
        )

        bufferedEvents.forEach { event ->
            var applied = false
            assertFalse(
                event.applyIfCurrentToTimelineOwner(sessionOperationRunner, ownerEpoch = 0L) {
                    applied = true
                }
            )
            assertFalse(applied)
        }
        var cancellationUnwoundUi = false
        assertTrue(
            TimelineManualSyncEvent.SessionCancelled(oldRequest).applyIfCurrentToTimelineOwner(
                sessionOperationRunner,
                ownerEpoch = 0L
            ) {
                cancellationUnwoundUi = true
            }
        )
        assertTrue(cancellationUnwoundUi)
    }

    @Test
    fun terminalEventsMatchOnlyTheirActiveManualRequest() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val sessionOperationRunner = RunSessionOperationAction(sessionStateRepository)
        val firstRequest = request(requestId = 1L)
        val sameSessionReplacement = request(requestId = 2L)

        assertFalse(
            TimelineManualSyncEvent.SessionCancelled(firstRequest)
                .matchesActiveRequest(sameSessionReplacement.requestId)
        )
        assertTrue(
            TimelineManualSyncEvent.Completed(result(sameSessionReplacement))
                .matchesActiveRequest(sameSessionReplacement.requestId)
        )

        assertFailsWith<IllegalStateException> {
            sessionStateRepository.mutate { error("clear failed") }
        }
        val nextGenerationRequest = request(
            requestId = 3L,
            sessionStamp = sessionOperationRunner.stampForOwner(ownerEpoch = 0L)
        )

        assertFalse(
            TimelineManualSyncEvent.SessionCancelled(firstRequest)
                .matchesActiveRequest(nextGenerationRequest.requestId)
        )
        assertTrue(
            TimelineManualSyncEvent.Completed(result(nextGenerationRequest))
                .matchesActiveRequest(nextGenerationRequest.requestId)
        )
    }

    @Test
    fun oldOwnerEventsAreIgnoredAfterSuccessfulSessionReplacement() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val sessionOperationRunner = RunSessionOperationAction(sessionStateRepository)
        val oldRequest = request(
            sessionStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L)
        )
        sessionStateRepository.mutate {}
        val oldEvents = listOf<TimelineManualSyncEvent>(
            TimelineManualSyncEvent.Started(oldRequest),
            TimelineManualSyncEvent.Completed(result(oldRequest)),
            TimelineManualSyncEvent.Failed(oldRequest, IllegalStateException("request failed")),
            TimelineManualSyncEvent.SessionCancelled(oldRequest)
        )

        oldEvents.forEach { event ->
            var applied = false
            assertFalse(
                event.applyIfCurrentToTimelineOwner(sessionOperationRunner, ownerEpoch = 0L) {
                    applied = true
                }
            )
            assertFalse(applied)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancellationWaitsForMutationOutcomeBeforeResumingOwnerWork() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val sessionOperationRunner = RunSessionOperationAction(sessionStateRepository)
        val oldRequest = request()
        val mutationStarted = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = launch {
            assertFailsWith<IllegalStateException> {
                sessionStateRepository.mutate {
                    mutationStarted.complete(Unit)
                    releaseMutation.await()
                    error("clear failed")
                }
            }
        }
        mutationStarted.await()
        var applied = false
        val handling = async {
            TimelineManualSyncEvent.SessionCancelled(oldRequest)
                .applyIfCurrentToTimelineOwner(
                    sessionOperationRunner = sessionOperationRunner,
                    ownerEpoch = 0L
                ) {
                    applied = true
                }
        }

        runCurrent()
        assertFalse(applied)

        releaseMutation.complete(Unit)
        mutation.join()
        assertTrue(handling.await())
        assertTrue(applied)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun olderCancellationWaitsForTheCurrentlyClosedMutation() = runTest {
        val sessionStateRepository = SessionStateRepository()
        val sessionOperationRunner = RunSessionOperationAction(sessionStateRepository)
        val oldRequest = request(
            sessionStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L)
        )
        assertFailsWith<IllegalStateException> {
            sessionStateRepository.mutate { error("first clear failed") }
        }
        val mutationStarted = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = launch {
            assertFailsWith<IllegalStateException> {
                sessionStateRepository.mutate {
                    mutationStarted.complete(Unit)
                    releaseMutation.await()
                    error("second clear failed")
                }
            }
        }
        mutationStarted.await()
        var applied = false
        val handling = async {
            TimelineManualSyncEvent.SessionCancelled(oldRequest)
                .applyIfCurrentToTimelineOwner(
                    sessionOperationRunner = sessionOperationRunner,
                    ownerEpoch = 0L
                ) {
                    applied = true
                }
        }

        runCurrent()
        assertFalse(applied)

        releaseMutation.complete(Unit)
        mutation.join()
        assertTrue(handling.await())
        assertTrue(applied)
    }

    private fun action(
        bucketResult: TimelineBucketSyncResult = TimelineBucketSyncResult(
            buckets = listOf(bucket("2026-05"))
        ),
        assetResults: List<TimelineAssetSyncResult>,
        clear: suspend (Set<String>) -> Unit = {},
        prepare: suspend (Set<String>) -> TimelineMosaicPrecomputeResult = {
            TimelineMosaicPrecomputeResult(it, emptySet())
        }
    ): TimelineManualSyncAction {
        val remainingAssetResults = assetResults.toMutableList()
        return TimelineManualSyncAction(
            syncBuckets = { Result.success(bucketResult) },
            syncAssets = { Result.success(remainingAssetResults.removeAt(0)) },
            clearMosaicBuckets = clear,
            prepareMosaic = { bucketIds, _, _, _, _, _ ->
                Result.success(prepare(bucketIds))
            }
        )
    }

    private fun request(
        requestId: Long = 1L,
        sessionStamp: SessionOperationStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L),
        geometryRequest: TimelineMosaicGeometryRequest? =
            TimelineMosaicGeometryRequest(availableWidth = 400f, maxRowHeight = 240f, spacing = 2f)
    ): TimelineManualSyncRequest =
        TimelineManualSyncRequest(
            requestId = requestId,
            sessionStamp = sessionStamp,
            groupSize = TimelineGroupSize.MONTH,
            columnCount = 4,
            families = MosaicTemplateFamily.defaultSet(),
            mosaicEnabled = true,
            cacheMosaicResults = true,
            geometryRequest = geometryRequest,
            hadBannerError = false
        )

    private fun result(request: TimelineManualSyncRequest): TimelineManualSyncResult =
        TimelineManualSyncResult(
            request = request,
            bucketSyncResult = TimelineBucketSyncResult(buckets = listOf(bucket("2026-05"))),
            assetSyncResult = TimelineAssetSyncResult(
                successfulBucketIds = setOf("2026-05"),
                failedBucketIds = emptySet()
            )
        )

    private fun syncActivityTracker(): SyncActivityTracker =
        SyncActivityTracker(
            object : PlatformSyncActivityNotifier {
                override fun onActiveSyncCountChanged(activeCount: Int) = Unit
            }
        )

    private fun bucket(timeBucket: String): TimelineBucket =
        TimelineBucket(displayLabel = timeBucket, timeBucket = timeBucket, count = 1)
}
