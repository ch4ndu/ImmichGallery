package com.udnahc.immichgallery.ui.screen.timeline

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

        buffer.add(key, chunk(0, 40))

        assertTrue(buffer.drainEligible(emptySet()).isEmpty())
        assertEquals(1, buffer.snapshotSize())

        val drained = buffer.drainEligible(setOf("2026-01"))

        assertEquals(listOf(chunk(0, 40)), drained[key])
        assertEquals(0, buffer.snapshotSize())
    }

    @Test
    fun duplicateChunksReplaceExistingRange() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val key = key("2026-01")
        val first = chunk(0, 40, bandIndex = 0)
        val replacement = chunk(0, 40, bandIndex = 1)

        buffer.add(key, first)
        buffer.add(key, replacement)

        val drained = buffer.drainEligible(setOf("2026-01"))

        assertEquals(listOf(replacement), drained[key])
    }

    @Test
    fun clearKeysAndBucketsRemoveBufferedChunks() = runTest {
        val buffer = TimelineProgressiveMosaicBuffer()
        val january = key("2026-01")
        val february = key("2026-02")

        buffer.add(january, chunk(0, 40))
        buffer.add(february, chunk(0, 40))
        buffer.clearKeys(setOf(january))

        assertEquals(mapOf(february to listOf(chunk(0, 40))), buffer.drainEligible(setOf("2026-02")))

        buffer.add(january, chunk(0, 40))
        buffer.clearBuckets(setOf("2026-01"))

        assertTrue(buffer.drainEligible(setOf("2026-01")).isEmpty())
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
            stateUpdates = mapOf(key to MosaicSectionState.Failed),
            geometryUpdates = mapOf(key to TimelineMosaicSectionGeometry(100f, geometryRanges(100f))),
            stamp = TimelineMosaicPublishStamp(
                globalGeneration = globalGeneration,
                bucketGenerations = mapOf(key.timeBucket to bucketGeneration)
            )
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
