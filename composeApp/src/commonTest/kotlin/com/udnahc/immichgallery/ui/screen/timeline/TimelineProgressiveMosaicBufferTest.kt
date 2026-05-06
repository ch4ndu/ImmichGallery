package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.MosaicBandAssignment
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
