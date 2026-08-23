package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.domain.model.MosaicAssignmentCheckpoint
import com.udnahc.immichgallery.domain.model.MosaicBandAssignment
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicSectionState
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineRuntimeStateStoreTest {
    @Test
    fun staleDayGroupsDoNotReinsertAfterBucketInvalidation() {
        val store = TimelineRuntimeStateStore()
        val key = DayGroupsCacheKey(timeBucket = "2026-01", assetRevision = 1)

        val stale = store.dayGroupsFor(key) {
            store.invalidateBucket("2026-01")
            listOf(dayGroup("stale"))
        }
        val current = store.dayGroupsFor(key) {
            listOf(dayGroup("current"))
        }

        assertEquals(listOf(dayGroup("stale")), stale)
        assertEquals(listOf(dayGroup("current")), current)
    }

    @Test
    fun resumeCheckpointIsRejectedAfterGlobalInvalidation() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val stamp = SessionOperationStamp(generation = 1L, ownerEpoch = 1L)
        val generationStamp = store.generationStampFor("2026-01")
        val resumeState = TimelineRuntimeMosaicResumeState(
            sessionStamp = stamp,
            checkpoint = checkpoint(),
            chunks = emptyList()
        )

        assertTrue(store.saveResumeState(key, resumeState, generationStamp))
        store.advanceGlobalGeneration()

        assertNull(store.resumeStateFor(key, stamp, generationStamp))
    }

    @Test
    fun bucketInvalidationAtomicallyRemovesBucketOwnedRuntimeState() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val stamp = SessionOperationStamp(generation = 1L, ownerEpoch = 1L)
        val generationStamp = store.generationStampFor("2026-01")

        store.dayGroupsFor(DayGroupsCacheKey("2026-01", 1)) {
            listOf(dayGroup("cached"))
        }
        store.saveResumeState(
            key = key,
            resumeState = TimelineRuntimeMosaicResumeState(
                sessionStamp = stamp,
                checkpoint = checkpoint(),
                chunks = emptyList()
            ),
            expectedStamp = generationStamp
        )
        store.deferMaterializedBuckets(
            timeBuckets = setOf("2026-01"),
            expectedGenerations = mapOf(
                "2026-01" to materializedStamp(store, "2026-01")
            )
        )
        store.deferMosaicPublish(pendingPublish(key, globalGeneration = 0L, bucketGeneration = 0L))

        store.invalidateBucket("2026-01")

        assertEquals(0, store.snapshotSizeForTests())
    }

    @Test
    fun staleResumeSaveIsRejectedAfterBucketInvalidation() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val stamp = SessionOperationStamp(generation = 1L, ownerEpoch = 1L)
        val generationStamp = store.generationStampFor("2026-01")

        store.invalidateBucket("2026-01")

        assertTrue(
            !store.saveResumeState(
                key = key,
                resumeState = TimelineRuntimeMosaicResumeState(
                    sessionStamp = stamp,
                    checkpoint = checkpoint(),
                    chunks = emptyList()
                ),
                expectedStamp = generationStamp
            )
        )
    }

    @Test
    fun oldWorkerCannotRemoveNewerSessionCheckpointWithSameRuntimeGeneration() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val generationStamp = store.generationStampFor("2026-01")
        val oldSession = SessionOperationStamp(generation = 1L, ownerEpoch = 1L)
        val newSession = SessionOperationStamp(generation = 2L, ownerEpoch = 1L)
        val resumeState = TimelineRuntimeMosaicResumeState(
            sessionStamp = newSession,
            checkpoint = checkpoint(),
            chunks = emptyList()
        )

        assertTrue(store.saveResumeState(key, resumeState, generationStamp))
        store.removeResumeState(
            key = key,
            expectedStamp = generationStamp,
            sessionStamp = oldSession
        )

        assertEquals(resumeState, store.resumeStateFor(key, newSession, generationStamp))
    }

    @Test
    fun oldSessionResumeLookupDoesNotDeleteNewerSessionCheckpoint() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val generationStamp = store.generationStampFor("2026-01")
        val oldSession = SessionOperationStamp(generation = 1L, ownerEpoch = 1L)
        val newSession = SessionOperationStamp(generation = 2L, ownerEpoch = 1L)
        val resumeState = TimelineRuntimeMosaicResumeState(
            sessionStamp = newSession,
            checkpoint = checkpoint(),
            chunks = emptyList()
        )

        assertTrue(store.saveResumeState(key, resumeState, generationStamp))

        assertNull(store.resumeStateFor(key, oldSession, generationStamp))
        assertEquals(resumeState, store.resumeStateFor(key, newSession, generationStamp))
    }

    @Test
    fun oldSessionResumeSaveDoesNotOverwriteNewerSessionCheckpoint() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val generationStamp = store.generationStampFor("2026-01")
        val oldSession = SessionOperationStamp(generation = 1L, ownerEpoch = 1L)
        val newSession = SessionOperationStamp(generation = 2L, ownerEpoch = 1L)
        val newerResumeState = TimelineRuntimeMosaicResumeState(
            sessionStamp = newSession,
            checkpoint = checkpoint(sourceIndex = 2),
            chunks = emptyList()
        )
        val olderResumeState = TimelineRuntimeMosaicResumeState(
            sessionStamp = oldSession,
            checkpoint = checkpoint(sourceIndex = 1),
            chunks = emptyList()
        )

        assertTrue(store.saveResumeState(key, newerResumeState, generationStamp))

        assertTrue(!store.saveResumeState(key, olderResumeState, generationStamp))
        assertEquals(newerResumeState, store.resumeStateFor(key, newSession, generationStamp))
    }

    @Test
    fun deferredMaterializedBucketsDrainAtomicallyForPriorityWindow() {
        val store = TimelineRuntimeStateStore()

        store.deferMaterializedBuckets(
            timeBuckets = setOf("2026-01", "2026-02"),
            expectedGenerations = mapOf(
                "2026-01" to materializedStamp(store, "2026-01"),
                "2026-02" to materializedStamp(store, "2026-02")
            )
        )

        assertEquals(
            mapOf("2026-01" to materializedStamp(store, "2026-01")),
            store.drainDeferredMaterializedBuckets(setOf("2026-01"))
        )
        assertEquals(emptyMap(), store.drainDeferredMaterializedBuckets(setOf("2026-02")))
    }

    @Test
    fun staleMaterializedOfferAfterInvalidationIsRejected() {
        val store = TimelineRuntimeStateStore()
        val staleGeneration = materializedStamp(store, "2026-01")

        store.invalidateBucket("2026-01")

        assertTrue(
            !store.deferMaterializedBuckets(
                timeBuckets = setOf("2026-01"),
                expectedGenerations = mapOf("2026-01" to staleGeneration)
            )
        )
        assertEquals(emptyMap(), store.drainDeferredMaterializedBuckets(setOf("2026-01")))
    }

    @Test
    fun emptyPriorityDrainClearsDeferredRuntimeState() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val generation = materializedStamp(store, "2026-01")

        store.deferMaterializedBuckets(
            timeBuckets = setOf("2026-01"),
            expectedGenerations = mapOf("2026-01" to generation)
        )
        store.deferMosaicPublish(pendingPublish(key, globalGeneration = 0L, bucketGeneration = 0L))

        assertEquals(emptyMap(), store.drainDeferredMaterializedBuckets(emptySet()))
        assertEquals(emptyList(), store.drainDeferredMosaicPublishes(emptySet()))
        assertEquals(0, store.snapshotSizeForTests())
    }

    @Test
    fun drainedMaterializedBucketRetainsStampForFinalPublishRejection() {
        val store = TimelineRuntimeStateStore()
        val stamp = materializedStamp(store, "2026-01")

        store.deferMaterializedBuckets(
            timeBuckets = setOf("2026-01"),
            expectedGenerations = mapOf("2026-01" to stamp)
        )
        val drained = store.drainDeferredMaterializedBuckets(setOf("2026-01"))
        store.invalidateBucket("2026-01")

        assertEquals(mapOf("2026-01" to stamp), drained)
        assertTrue(
            !store.generationSnapshot()
                .matches(drained.getValue("2026-01").bucketGenerationStamp, "2026-01")
        )
    }

    @Test
    fun lateOlderSessionMaterializedOfferDoesNotReplaceNewerPendingOffer() {
        val store = TimelineRuntimeStateStore()
        val oldSession = SessionOperationStamp(generation = 1L, ownerEpoch = 0L)
        val newSession = SessionOperationStamp(generation = 2L, ownerEpoch = 0L)
        val oldStamp = materializedStamp(store, "2026-01", oldSession)
        val newStamp = materializedStamp(store, "2026-01", newSession)

        assertTrue(
            store.deferMaterializedBuckets(
                timeBuckets = setOf("2026-01"),
                expectedGenerations = mapOf("2026-01" to newStamp)
            )
        )
        assertTrue(
            !store.deferMaterializedBuckets(
                timeBuckets = setOf("2026-01"),
                expectedGenerations = mapOf("2026-01" to oldStamp)
            )
        )

        assertEquals(
            mapOf("2026-01" to newStamp),
            store.drainDeferredMaterializedBuckets(setOf("2026-01"))
        )
    }

    @Test
    fun publishSignalBufferKeepsDistinctKeysAndNewestValue() = runTest {
        val buffer = TimelineMosaicPublishSignalBuffer()
        val first = key("2026-01")
        val second = key("2026-02")

        buffer.offer(pendingPublish(first, globalGeneration = 2L, bucketGeneration = 0L))
        buffer.offer(pendingPublish(second, globalGeneration = 2L, bucketGeneration = 0L))
        buffer.offer(
            pendingPublish(
                key = first,
                state = MosaicSectionState.Ready(assignments = emptyList()),
                globalGeneration = 2L,
                bucketGeneration = 0L
            )
        )

        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = buffer.receiveBatch().orEmpty(),
            currentGlobalGeneration = 2L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(MosaicSectionState.Ready(assignments = emptyList()), coalesced?.stateUpdates?.get(first))
        assertEquals(MosaicSectionState.Failed, coalesced?.stateUpdates?.get(second))
    }

    @Test
    fun publishSignalBufferDrainsStateAndGeometryFromOnePublishTogether() = runTest {
        val buffer = TimelineMosaicPublishSignalBuffer()
        val key = key("2026-01")

        buffer.offer(pendingPublish(key, globalGeneration = 2L, bucketGeneration = 0L))

        val pending = buffer.receiveBatch().orEmpty()
        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = pending,
            currentGlobalGeneration = 2L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(2, pending.size)
        assertEquals(setOf(key), coalesced?.stateUpdates?.keys)
        assertEquals(setOf(key), coalesced?.geometryUpdates?.keys)
    }

    @Test
    fun repairSignalBufferKeepsNewestRequestPerBucketAndDistinctBuckets() = runTest {
        val buffer = TimelineMosaicRepairSignalBuffer()

        buffer.offer(repairRequest("2026-01", assetRevision = 1))
        buffer.offer(repairRequest("2026-02", assetRevision = 3))
        buffer.offer(repairRequest("2026-01", assetRevision = 2))

        val requests = buffer.receiveBatch().orEmpty()

        assertEquals(listOf("2026-01", "2026-02"), requests.map { it.timeBucket })
        assertEquals(2, requests.first().assetRevision)
        assertEquals(3, requests.last().assetRevision)
    }

    @Test
    fun publishSignalBufferDoesNotLetLateObsoleteValueReplaceCurrentValue() = runTest {
        val buffer = TimelineMosaicPublishSignalBuffer()
        val key = key("2026-01")

        buffer.offer(pendingPublish(key, globalGeneration = 3L, bucketGeneration = 0L))
        buffer.offer(
            pendingPublish(
                key = key,
                state = MosaicSectionState.Ready(assignments = emptyList()),
                globalGeneration = 2L,
                bucketGeneration = 0L
            )
        )

        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = buffer.receiveBatch().orEmpty(),
            currentGlobalGeneration = 3L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(MosaicSectionState.Failed, coalesced?.stateUpdates?.get(key))
    }

    @Test
    fun repairSignalBufferDoesNotLetLateObsoleteRequestReplaceCurrentRequest() = runTest {
        val buffer = TimelineMosaicRepairSignalBuffer()

        buffer.offer(repairRequest("2026-01", assetRevision = 3, globalGeneration = 3L))
        buffer.offer(repairRequest("2026-01", assetRevision = 4, globalGeneration = 2L))

        val requests = buffer.receiveBatch().orEmpty()

        assertEquals(listOf(3), requests.map { it.assetRevision })
        assertEquals(listOf(3L), requests.map { it.globalGeneration })
    }

    @Test
    fun repairSignalBufferKeepsSingleWakeupUnderHeavyOfferPressure() = runTest {
        val buffer = TimelineMosaicRepairSignalBuffer()

        repeat(10_000) { index ->
            buffer.offer(repairRequest("2026-01", assetRevision = index))
        }

        assertEquals(1, buffer.pendingWakeupCountForTests())
        assertEquals(listOf(9_999), buffer.receiveBatch().orEmpty().map { it.assetRevision })
        assertEquals(0, buffer.pendingWakeupCountForTests())
    }

    @Test
    fun publishSignalBufferKeepsSingleWakeupAndNewestValueUnderHeavyOfferPressure() = runTest {
        val buffer = TimelineMosaicPublishSignalBuffer()
        val key = key("2026-01")

        repeat(10_000) { index ->
            buffer.offer(pendingPublish(key, globalGeneration = index.toLong(), bucketGeneration = 0L))
        }

        assertEquals(1, buffer.pendingWakeupCountForTests())
        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = buffer.receiveBatch().orEmpty(),
            currentGlobalGeneration = 9_999L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(setOf(key), coalesced?.stateUpdates?.keys)
        assertEquals(0, buffer.pendingWakeupCountForTests())
    }

    @Test
    fun signalBufferDrainIsAtomicAcrossOfferAndDrainCycles() = runTest {
        val buffer = TimelineMosaicRepairSignalBuffer()

        buffer.offer(repairRequest("2026-01", assetRevision = 1))
        assertEquals(listOf(1), buffer.drainAvailable().map { it.assetRevision })

        buffer.offer(repairRequest("2026-01", assetRevision = 2))
        buffer.offer(repairRequest("2026-02", assetRevision = 3))

        val drained = buffer.receiveBatch().orEmpty()

        assertEquals(listOf("2026-01", "2026-02"), drained.map { it.timeBucket })
        assertEquals(listOf(2, 3), drained.map { it.assetRevision })
    }

    @Test
    fun signalBufferOfferRacingDrainIsDrainedOrRetainedForNextCycle() = runTest {
        repeat(250) { index ->
            val buffer = TimelineMosaicRepairSignalBuffer()
            val timeBucket = "2026-${index.toString().padStart(2, '0')}"
            val start = CompletableDeferred<Unit>()

            buffer.offer(repairRequest(timeBucket, assetRevision = 1))
            val firstDrain = async(Dispatchers.Default) {
                start.await()
                buffer.receiveBatch().orEmpty()
            }
            val racingOffer = async(Dispatchers.Default) {
                start.await()
                buffer.offer(repairRequest(timeBucket, assetRevision = 2))
            }
            start.complete(Unit)
            racingOffer.await()

            val observed = firstDrain.await() + buffer.drainAvailable()

            assertTrue(
                observed.any { it.timeBucket == timeBucket && it.assetRevision == 2 },
                "racing offer was neither drained nor retained for timeBucket=$timeBucket"
            )
        }
    }

    @Test
    fun publishSignalOfferAllDrainsMatchingStateAndGeometryTogether() = runTest {
        val buffer = TimelineMosaicPublishSignalBuffer()
        val first = key("2026-01")
        val second = key("2026-02")

        buffer.offerAll(
            listOf(
                pendingPublish(first, globalGeneration = 0L, bucketGeneration = 0L),
                pendingPublish(second, globalGeneration = 0L, bucketGeneration = 0L)
            )
        )

        val drained = buffer.receiveBatch().orEmpty()
        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = drained,
            currentGlobalGeneration = 0L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(setOf(first, second), coalesced?.stateUpdates?.keys)
        assertEquals(setOf(first, second), coalesced?.geometryUpdates?.keys)
        assertEquals(0, buffer.pendingWakeupCountForTests())
    }

    @Test
    fun publishBufferCoalescingPreservesOriginFields() {
        val buffer = TimelineMosaicPublishBuffer()
        val key = key("2026-01")
        val originConfig = ActiveMosaicConfig(
            groupSize = TimelineGroupSize.MONTH,
            columnCount = 4,
            families = setOf(MosaicTemplateFamily.FOUR_TILE)
        )
        val originGeometry = TimelineMosaicGeometryRequest(
            availableWidth = 100f,
            maxRowHeight = 200f,
            spacing = 2f
        )
        val publish = pendingPublish(key, globalGeneration = 0L, bucketGeneration = 0L).copy(
            originConfig = originConfig,
            originGeometryRequest = originGeometry,
            originAssetRevisions = mapOf("2026-01" to 7)
        )

        val coalesced = buffer.coalesceCurrent(
            pending = listOf(publish),
            currentGlobalGeneration = 0L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(originConfig, coalesced?.originConfig)
        assertEquals(originGeometry, coalesced?.originGeometryRequest)
        assertEquals(mapOf("2026-01" to 7), coalesced?.originAssetRevisions)
    }

    @Test
    fun obsoleteGenerationPublishIsRemovedDuringCoalescing() = runTest {
        val buffer = TimelineMosaicPublishSignalBuffer()
        val current = key("2026-01")

        buffer.offer(pendingPublish(current, globalGeneration = 3L, bucketGeneration = 0L))
        buffer.offer(pendingPublish(current, globalGeneration = 2L, bucketGeneration = 0L))

        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = buffer.receiveBatch().orEmpty(),
            currentGlobalGeneration = 3L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(setOf(current), coalesced?.stateUpdates?.keys)
        assertEquals(1, coalesced?.stateUpdates?.size)
    }

    @Test
    fun staleOriginPublishAfterGlobalAdvanceIsRejected() = runTest {
        val store = TimelineRuntimeStateStore()
        val buffer = TimelineMosaicPublishSignalBuffer()
        val key = key("2026-01")
        val originStamp = store.publishStampFor(setOf(key))

        store.advanceGlobalGeneration()
        buffer.offer(
            PendingMosaicPublish(
                sessionStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L),
                stateUpdates = mapOf(key to MosaicSectionState.Failed),
                geometryUpdates = emptyMap(),
                stamp = originStamp
            )
        )

        val currentGeneration = store.generationSnapshot()
        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = buffer.receiveBatch().orEmpty(),
            currentGlobalGeneration = currentGeneration.globalGeneration,
            currentBucketGenerations = currentGeneration.bucketGenerations
        )

        assertNull(coalesced)
    }

    @Test
    fun configIdentityReturningToOriginalStillRejectsPreChangeOrigin() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val originStamp = store.publishStampFor(setOf(key))

        store.advanceGlobalGeneration()
        store.advanceGlobalGeneration()

        assertFalse(store.isCurrent(originStamp))
    }

    @Test
    fun staleDeferredMosaicOfferAfterInvalidationIsRejectedInsideStore() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        val staleStamp = store.publishStampFor(setOf(key))

        store.invalidateBucket("2026-01")

        assertTrue(
            !store.deferMosaicPublish(
                PendingMosaicPublish(
                    sessionStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L),
                    stateUpdates = mapOf(key to MosaicSectionState.Failed),
                    geometryUpdates = emptyMap(),
                    stamp = staleStamp
                )
            )
        )
        assertEquals(emptyList(), store.drainDeferredMosaicPublishes(setOf("2026-01")))
    }

    @Test
    fun deferredMosaicSameKeyOffersAreBoundedAndKeepNewestValue() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")
        repeat(10_000) { index ->
            store.deferMosaicPublish(
                pendingStatePublish(
                    key = key,
                    state = if (index % 2 == 0) {
                        MosaicSectionState.Failed
                    } else {
                        MosaicSectionState.Pending
                    }
                )
            )
        }

        assertEquals(1, store.snapshotSizeForTests())

        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = store.drainDeferredMosaicPublishes(setOf("2026-01")),
            currentGlobalGeneration = 0L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(MosaicSectionState.Pending, coalesced?.stateUpdates?.get(key))
    }

    @Test
    fun deferredMosaicRequeueDoesNotReplaceEqualVersionExistingValue() {
        val store = TimelineRuntimeStateStore()
        val key = key("2026-01")

        store.deferMosaicPublish(pendingStatePublish(key, MosaicSectionState.Pending))
        store.requeueMosaicPublishes(
            listOf(pendingStatePublish(key, MosaicSectionState.Failed))
        )

        val coalesced = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = store.drainDeferredMosaicPublishes(setOf("2026-01")),
            currentGlobalGeneration = 0L,
            currentBucketGenerations = emptyMap()
        )

        assertEquals(MosaicSectionState.Pending, coalesced?.stateUpdates?.get(key))
    }

    private fun dayGroup(sectionKey: String): DayGroup =
        DayGroup(
            sectionKey = sectionKey,
            label = TimelineSectionLabel.Literal(sectionKey),
            assets = emptyList()
        )

    private fun key(timeBucket: String): MosaicCacheKey =
        MosaicCacheKey(
            timeBucket = timeBucket,
            sectionKey = "$timeBucket|month",
            columnCount = 4,
            assetRevision = 1,
            familiesKey = MosaicTemplateFamily.FOUR_TILE.persistedId
        )

    private fun pendingStatePublish(
        key: MosaicCacheKey,
        state: MosaicSectionState
    ): PendingMosaicPublish =
        PendingMosaicPublish(
            sessionStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L),
            stateUpdates = mapOf(key to state),
            geometryUpdates = emptyMap(),
            stamp = TimelineMosaicPublishStamp(
                globalGeneration = 0L,
                bucketGenerations = mapOf(key.timeBucket to 0L)
            )
        )

    private fun pendingPublish(
        key: MosaicCacheKey,
        state: MosaicSectionState = MosaicSectionState.Failed,
        globalGeneration: Long,
        bucketGeneration: Long
    ): PendingMosaicPublish =
        PendingMosaicPublish(
            sessionStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L),
            stateUpdates = mapOf(key to state),
            geometryUpdates = mapOf(key to TimelineMosaicSectionGeometry(100f, geometryRanges())),
            stamp = TimelineMosaicPublishStamp(
                globalGeneration = globalGeneration,
                bucketGenerations = mapOf(key.timeBucket to bucketGeneration)
            )
        )

    private fun repairRequest(
        timeBucket: String,
        assetRevision: Int,
        globalGeneration: Long = 0L
    ): TimelineMosaicRepairRequest =
        TimelineMosaicRepairRequest(
            sessionStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L),
            timeBucket = timeBucket,
            groupSize = TimelineGroupSize.MONTH,
            columnCount = 4,
            families = setOf(MosaicTemplateFamily.FOUR_TILE),
            geometryRequest = TimelineMosaicGeometryRequest(
                availableWidth = 400f,
                maxRowHeight = 300f,
                spacing = 2f
            ),
            assetRevision = assetRevision,
            globalGeneration = globalGeneration,
            bucketGeneration = 0L,
            sectionsByKey = emptyMap()
        )

    private fun materializedStamp(
        store: TimelineRuntimeStateStore,
        timeBucket: String,
        sessionStamp: SessionOperationStamp = SessionOperationStamp(generation = 0L, ownerEpoch = 0L)
    ): TimelineDeferredMaterializedBucketStamp =
        TimelineDeferredMaterializedBucketStamp(
            sessionStamp = sessionStamp,
            bucketGenerationStamp = store.generationSnapshot().bucketGenerationStampFor(timeBucket)
        )

    private fun checkpoint(sourceIndex: Int = 0): MosaicAssignmentCheckpoint =
        MosaicAssignmentCheckpoint(
            assignments = emptyList(),
            sourceIndex = sourceIndex,
            bandIndex = 0,
            chunkStartIndex = 0,
            lastEmittedBandCount = 0
        )

    private fun geometryRanges(): List<MosaicSectionGeometryRange> =
        listOf(
            MosaicSectionGeometryRange(
                sourceStartIndex = 0,
                sourceCount = 1,
                height = 100f
            )
        )

    @Suppress("unused")
    private fun assignment(): MosaicBandAssignment =
        MosaicBandAssignment(
            bandIndex = 0,
            sourceStartIndex = 0,
            sourceCount = 1,
            templateId = "test",
            tiles = emptyList()
        )

    @Suppress("unused")
    private fun keyScope(): MosaicKeyScope =
        MosaicKeyScope(
            owner = MosaicOwnerKey(MosaicOwnerScope.TIMELINE_BUCKET, "2026-01"),
            sectionKey = "2026-01|month",
            columnCount = 4,
            familiesKey = MosaicTemplateFamily.FOUR_TILE.persistedId,
            contentFingerprint = "1"
        )
}
