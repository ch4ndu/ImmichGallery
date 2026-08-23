package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.domain.model.MosaicBandAssignment
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicSectionState
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineProgressiveMosaicBufferTest {
    @Test
    fun offscreenChunksRemainBufferedUntilEligible() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val key = key("2026-01")

        buffer.add(key, chunk(0, 40), stamp(), generationStamp())

        assertTrue(buffer.drainEligible(emptySet()).isEmpty())
        assertEquals(1, buffer.snapshotSize())

        val drained = buffer.drainEligible(setOf("2026-01")).single().updatesByKey

        assertEquals(listOf(chunk(0, 40)), drained[key])
        assertEquals(0, buffer.snapshotSize())
    }

    @Test
    fun duplicateChunksReplaceExistingRange() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val key = key("2026-01")
        val first = chunk(0, 40, bandIndex = 0)
        val replacement = chunk(0, 40, bandIndex = 1)

        buffer.add(key, first, stamp(), generationStamp())
        buffer.add(key, replacement, stamp(), generationStamp())

        val drained = buffer.drainEligible(setOf("2026-01")).single().updatesByKey

        assertEquals(listOf(replacement), drained[key])
    }

    @Test
    fun chunksRemainBufferedUntilSectionGeometryKeyIsEligible() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val key = key("2026-01")
        val firstChunk = chunk(0, 40)

        buffer.add(key, firstChunk, stamp(), generationStamp())

        assertTrue(buffer.drainEligibleKeys(emptySet()).isEmpty())
        assertEquals(1, buffer.snapshotSize())
        assertEquals(
            mapOf(key to listOf(firstChunk)),
            buffer.drainEligibleKeys(setOf(key)).single().updatesByKey
        )
        assertEquals(0, buffer.snapshotSize())
    }

    @Test
    fun clearKeysAndBucketsRemoveBufferedChunks() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val january = key("2026-01")
        val february = key("2026-02")

        buffer.add(january, chunk(0, 40), stamp(), generationStamp())
        buffer.add(february, chunk(0, 40), stamp(), generationStamp())
        buffer.clearKeys(setOf(january))

        assertEquals(
            mapOf(february to listOf(chunk(0, 40))),
            buffer.drainEligible(setOf("2026-02")).single().updatesByKey
        )

        buffer.add(january, chunk(0, 40), stamp(), generationStamp())
        buffer.clearBuckets(setOf("2026-01"))

        assertTrue(buffer.drainEligible(setOf("2026-01")).isEmpty())
    }

    @Test
    fun drainedPublishOriginIsRejectedAfterBucketClearBeforeApply() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val key = key("2026-01")

        buffer.add(key, chunk(0, 40), stamp(), generationStamp())
        val publish = buffer.drainEligibleKeys(setOf(key)).single()
        val origin = publish.clearOriginsByKey.getValue(key)

        buffer.clearBuckets(setOf("2026-01"))

        assertEquals(false, buffer.isCurrentOrigin(key, origin))
    }

    @Test
    fun drainedPublishOriginCannotRequeueAfterGlobalClear() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val key = key("2026-01")
        val chunk = chunk(0, 40)
        val stamp = stamp()
        val generationStamp = generationStamp()

        buffer.add(key, chunk, stamp, generationStamp)
        val publish = buffer.drainEligibleKeys(setOf(key)).single()
        buffer.clearAll()

        val requeued = buffer.requeueIfCurrent(
            key = key,
            chunk = chunk,
            sessionStamp = stamp,
            generationStamp = generationStamp,
            clearOrigin = publish.clearOriginsByKey.getValue(key)
        )

        assertEquals(false, requeued)
        assertEquals(0, buffer.snapshotSize())
    }

    @Test
    fun newerSessionChunksReplaceOlderSessionChunksForSameKey() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val key = key("2026-01")
        val firstStamp = SessionOperationStamp(generation = 1L, ownerEpoch = 2L)
        val secondStamp = SessionOperationStamp(generation = 3L, ownerEpoch = 2L)

        buffer.add(key, chunk(0, 40), firstStamp, generationStamp())
        buffer.add(key, chunk(40, 80), secondStamp, generationStamp())

        val publishes = buffer.drainEligibleKeys(setOf(key))

        assertEquals(listOf(secondStamp), publishes.map { it.sessionStamp })
        assertEquals(listOf(chunk(40, 80)), publishes.single().updatesByKey[key])
    }

    @Test
    fun newerGenerationChunksRejectLateOlderRangesForSameKey() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val key = key("2026-01")

        buffer.add(
            key,
            chunk(0, 40, bandIndex = 1),
            stamp(),
            generationStamp(globalGeneration = 2L)
        )
        buffer.add(
            key,
            chunk(40, 80, bandIndex = 0),
            stamp(),
            generationStamp(globalGeneration = 1L)
        )

        val publish = buffer.drainEligibleKeys(setOf(key)).single()

        assertEquals(listOf(chunk(0, 40, bandIndex = 1)), publish.updatesByKey[key])
        assertEquals(generationStamp(globalGeneration = 2L), publish.generationStampsByKey[key])
    }

    @Test
    fun timelineLocalKeysUseBucketAndSectionIdentity() {
        assertEquals(
            "pl_2026-01_2026-01|month_0",
            timelinePlaceholderGridKey("pl", "2026-01", "2026-01|month", 0)
        )
        assertEquals(
            "plp_2026-01_2026-01|month_0_40_1",
            timelinePlaceholderGridKey("plp", "2026-01", "2026-01|month_0_40", 1)
        )
        assertEquals(
            "mosaic_2026-01_2026-01|month_a_b_c",
            timelineMosaicBandGridKey("2026-01", "2026-01|month", listOf("a", "b", "c"))
        )
    }

    @Test
    fun publishBufferDropsStaleGlobalGeneration() {
        val buffer = TimelineMosaicPublishBuffer()
        val key = key("2026-01")
        val publish = pendingPublish(
            key = key,
            globalGeneration = 1L,
            bucketGeneration = 0L
        )

        assertNull(
            buffer.coalesceCurrent(
                pending = listOf(publish),
                currentGlobalGeneration = 2L,
                currentBucketGenerations = emptyMap()
            )
        )
    }

    @Test
    fun publishBufferDropsOnlyStaleBucketGeneration() {
        val buffer = TimelineMosaicPublishBuffer()
        val stale = key("2026-01")
        val current = key("2026-02")
        val publish = PendingMosaicPublish(
            sessionStamp = stamp(),
            stateUpdates = mapOf(
                stale to MosaicSectionState.Failed,
                current to MosaicSectionState.Failed
            ),
            geometryUpdates = mapOf(
                stale to TimelineMosaicSectionGeometry(100f, geometryRanges(40f, 60f)),
                current to TimelineMosaicSectionGeometry(200f, geometryRanges(80f, 120f))
            ),
            stamp = TimelineMosaicPublishStamp(
                globalGeneration = 1L,
                bucketGenerations = mapOf("2026-01" to 1L, "2026-02" to 0L)
            )
        )

        val result = buffer.coalesceCurrent(
            pending = listOf(publish),
            currentGlobalGeneration = 1L,
            currentBucketGenerations = mapOf("2026-01" to 2L)
        )

        assertEquals(mapOf(current to MosaicSectionState.Failed), result?.stateUpdates)
        assertEquals(
            mapOf(current to TimelineMosaicSectionGeometry(200f, geometryRanges(80f, 120f))),
            result?.geometryUpdates
        )
    }

    @Test
    fun publishBufferCoalescesCurrentPublishes() {
        val buffer = TimelineMosaicPublishBuffer()
        val first = key("2026-01")
        val second = key("2026-02")

        val result = buffer.coalesceCurrent(
            pending = listOf(
                pendingPublish(first, globalGeneration = 3L, bucketGeneration = 0L),
                pendingPublish(second, globalGeneration = 3L, bucketGeneration = 4L)
            ),
            currentGlobalGeneration = 3L,
            currentBucketGenerations = mapOf("2026-02" to 4L)
        )

        assertEquals(setOf(first, second), result?.stateUpdates?.keys)
        assertEquals(setOf(first, second), result?.geometryUpdates?.keys)
        assertEquals(stamp(), result?.sessionStamp)
    }

    @Test
    fun progressiveStateUpdatesDoNotOverwriteReadyState() {
        val key = key("2026-01")
        val ready = MosaicSectionState.Ready(assignments = emptyList())

        val result = timelineProgressiveMosaicStateUpdates(
            states = mapOf(key to ready),
            updatesByKey = mapOf(key to listOf(chunk(0, 40)))
        )

        assertEquals(ready, result[key])
    }

    @Test
    fun genericStateUpdatesDoNotOverwriteReadyWithFailure() {
        val key = key("2026-01")
        val ready = MosaicSectionState.Ready(assignments = emptyList())

        val result = timelineMergeMosaicStateUpdatesPreservingReady(
            states = mapOf(key to ready),
            updates = mapOf(key to MosaicSectionState.Failed)
        )

        assertEquals(ready, result[key])
    }

    @Test
    fun genericStateUpdatesAllowNewReadyToReplaceNonReady() {
        val key = key("2026-01")
        val ready = MosaicSectionState.Ready(assignments = emptyList())

        val result = timelineMergeMosaicStateUpdatesPreservingReady(
            states = mapOf(key to MosaicSectionState.Failed),
            updates = mapOf(key to ready)
        )

        assertEquals(ready, result[key])
    }

    @Test
    fun configPreparationResetPreservesTerminalStateWhileAppliedResetClearsIt() {
        val readyKey = key("2026-01")
        val failedKey = key("2026-02")
        val partialKey = key("2026-03")
        val ready = MosaicSectionState.Ready(assignments = emptyList())
        val states = mapOf(
            readyKey to ready,
            failedKey to MosaicSectionState.Failed,
            partialKey to MosaicSectionState.Partial(listOf(chunk(0, 40)))
        )

        assertEquals(
            mapOf(
                readyKey to ready,
                failedKey to MosaicSectionState.Failed
            ),
            timelineMosaicStatesAfterReset(
                states = states,
                mode = TimelineMosaicRuntimeResetMode.PreservePublished
            )
        )
        assertTrue(
            timelineMosaicStatesAfterReset(
                states = states,
                mode = TimelineMosaicRuntimeResetMode.ClearPublished
            ).isEmpty()
        )
    }

    @Test
    fun progressiveStateUpdatesMergePartialChunksInSourceOrder() {
        val key = key("2026-01")
        val existing = chunk(40, 80)
        val duplicateReplacement = chunk(40, 80, bandIndex = 2)
        val earlier = chunk(0, 40)

        val result = timelineProgressiveMosaicStateUpdates(
            states = mapOf(key to MosaicSectionState.Partial(listOf(existing))),
            updatesByKey = mapOf(key to listOf(duplicateReplacement, earlier))
        )

        assertEquals(
            MosaicSectionState.Partial(listOf(earlier, existing)),
            result[key]
        )
    }

    private fun key(timeBucket: String): MosaicCacheKey =
        MosaicCacheKey(
            timeBucket = timeBucket,
            sectionKey = "$timeBucket|month",
            columnCount = 4,
            assetRevision = 1,
            familiesKey = MosaicTemplateFamily.FOUR_TILE.persistedId
        )

    private fun chunk(
        sourceStartIndex: Int,
        sourceEndExclusive: Int,
        bandIndex: Int = 0
    ): RuntimeMosaicProgressChunk =
        RuntimeMosaicProgressChunk(
            keyScope = MosaicKeyScope(
                owner = MosaicOwnerKey(MosaicOwnerScope.TIMELINE_BUCKET, "2026-01"),
                sectionKey = "2026-01|month",
                columnCount = 4,
                familiesKey = MosaicTemplateFamily.FOUR_TILE.persistedId,
                contentFingerprint = "1"
            ),
            sectionLabel = "January 2026",
            sourceStartIndex = sourceStartIndex,
            sourceEndExclusive = sourceEndExclusive,
            assignments = listOf(
                MosaicBandAssignment(
                    bandIndex = bandIndex,
                    sourceStartIndex = sourceStartIndex,
                    sourceCount = sourceEndExclusive - sourceStartIndex,
                    templateId = "test",
                    tiles = emptyList()
                )
            )
        )

    private fun pendingPublish(
        key: MosaicCacheKey,
        globalGeneration: Long,
        bucketGeneration: Long
    ): PendingMosaicPublish =
        PendingMosaicPublish(
            sessionStamp = stamp(),
            stateUpdates = mapOf(key to MosaicSectionState.Failed),
            geometryUpdates = mapOf(key to TimelineMosaicSectionGeometry(100f, geometryRanges(100f))),
            stamp = TimelineMosaicPublishStamp(
                globalGeneration = globalGeneration,
                bucketGenerations = mapOf(key.timeBucket to bucketGeneration)
            )
        )

    private fun stamp(): SessionOperationStamp = SessionOperationStamp(0L, 0L)

    private fun generationStamp(
        globalGeneration: Long = 0L,
        bucketGeneration: Long = 0L
    ): TimelineRuntimeGenerationStamp =
        TimelineRuntimeGenerationStamp(
            globalGeneration = globalGeneration,
            bucketGeneration = bucketGeneration
        )

    private fun geometryRanges(vararg heights: Float): List<MosaicSectionGeometryRange> {
        var cursor = 0
        return heights.map { height ->
            MosaicSectionGeometryRange(
                sourceStartIndex = cursor,
                sourceCount = 1,
                height = height
            ).also { cursor += it.sourceCount }
        }
    }
}
