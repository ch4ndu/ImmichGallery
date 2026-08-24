package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.remote.TimelineApiCompatibilityException
import com.udnahc.immichgallery.data.remote.TimelineCompatibilityCategory
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.timelineBucketDisplayLabel
import com.udnahc.immichgallery.domain.model.TimelineBucketAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncCompatibilityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineBulkAssetSyncTest {
    @Test
    fun forcedWriteWithoutOrderedContentChangeIsNotChanged() {
        val before = listOf(asset("edited", isEdited = true, editsResolved = false))
        val finalAssets = listOf(asset("edited", isEdited = true, editsResolved = false))

        assertFalse(
            shouldSkipTimelineBucketAssetWrite(
                before = before,
                candidateAssets = finalAssets,
                editedAssetIdsWithDimensions = setOf("edited")
            )
        )
        assertFalse(timelineBucketAssetsChanged(before, finalAssets))
    }

    @Test
    fun orderedContentChangeIsChanged() {
        val before = listOf(asset("a"), asset("b"))
        val finalAssets = listOf(asset("b"), asset("a"))

        assertTrue(timelineBucketAssetsChanged(before, finalAssets))
    }

    @Test
    fun changedSiblingResultIsPreservedBeforeAggregateCompatibilityFailureReturns() = runTest {
        val compatibilityFailure = compatibilityFailure()
        val aggregate = syncTimelineBucketAssetsConcurrently(
            buckets = listOf(bucket("changed"), bucket("failed")),
            syncBucketAssets = { bucket ->
                if (bucket.timeBucket == "changed") {
                    Result.success(TimelineBucketAssetSyncResult("changed", changed = true))
                } else {
                    Result.failure(compatibilityFailure)
                }
            }
        )

        val aggregateFailure = assertIs<TimelineAssetSyncCompatibilityException>(
            aggregate.exceptionOrNull()
        )
        assertEquals(compatibilityFailure, aggregateFailure.compatibilityCause)
        assertEquals(setOf("changed"), aggregateFailure.partialResult.successfulBucketIds)
        assertEquals(setOf("failed"), aggregateFailure.partialResult.failedBucketIds)
        assertEquals(setOf("changed"), aggregateFailure.partialResult.changedBucketIds)
    }

    @Test
    fun thrownCompatibilityFailureIsPreservedWithSiblingResult() = runTest {
        val compatibilityFailure = compatibilityFailure()

        val result = syncTimelineBucketAssetsConcurrently(
            buckets = listOf(bucket("changed"), bucket("failed")),
            syncBucketAssets = { bucket ->
                if (bucket.timeBucket == "changed") {
                    Result.success(TimelineBucketAssetSyncResult("changed", changed = true))
                } else {
                    throw compatibilityFailure
                }
            }
        )

        val aggregateFailure = assertIs<TimelineAssetSyncCompatibilityException>(
            result.exceptionOrNull()
        )
        assertEquals(compatibilityFailure, aggregateFailure.compatibilityCause)
        assertEquals(setOf("changed"), aggregateFailure.partialResult.successfulBucketIds)
        assertEquals(setOf("failed"), aggregateFailure.partialResult.failedBucketIds)
        assertEquals(setOf("changed"), aggregateFailure.partialResult.changedBucketIds)
    }

    @Test
    fun ordinaryBucketFailuresRemainRetryableFailedBuckets() = runTest {
        val result = syncTimelineBucketAssetsConcurrently(
            buckets = listOf(bucket("ok"), bucket("failed")),
            syncBucketAssets = { bucket ->
                if (bucket.timeBucket == "ok") {
                    Result.success(TimelineBucketAssetSyncResult("ok", changed = false))
                } else {
                    Result.failure(IllegalStateException("network failed"))
                }
            }
        ).getOrThrow()

        assertEquals(setOf("ok"), result.successfulBucketIds)
        assertEquals(setOf("failed"), result.failedBucketIds)
        assertEquals(emptySet(), result.changedBucketIds)
    }

    @Test
    fun thrownOrdinaryBucketFailuresRemainRetryableFailedBuckets() = runTest {
        val result = syncTimelineBucketAssetsConcurrently(
            buckets = listOf(bucket("ok"), bucket("failed")),
            syncBucketAssets = { bucket ->
                if (bucket.timeBucket == "ok") {
                    Result.success(TimelineBucketAssetSyncResult("ok", changed = false))
                } else {
                    throw IllegalStateException("network failed")
                }
            }
        ).getOrThrow()

        assertEquals(setOf("ok"), result.successfulBucketIds)
        assertEquals(setOf("failed"), result.failedBucketIds)
        assertEquals(emptySet(), result.changedBucketIds)
    }

    @Test
    fun cancellationStillPropagates() = runTest {
        assertFailsWith<CancellationException> {
            syncTimelineBucketAssetsConcurrently(
                buckets = listOf(bucket("cancelled")),
                syncBucketAssets = { throw CancellationException("cancelled") }
            )
        }
    }

    private fun compatibilityFailure(): TimelineApiCompatibilityException =
        TimelineApiCompatibilityException(
            serverVersion = "1.142.0",
            endpoint = "/api/timeline/bucket",
            category = TimelineCompatibilityCategory.SHAPE
        )

    private fun bucket(timeBucket: String): TimelineBucket =
        TimelineBucket(
            displayLabel = timelineBucketDisplayLabel(timeBucket),
            timeBucket = timeBucket,
            count = 1
        )

    private fun asset(
        id: String,
        type: String = "IMAGE",
        fileName: String = "$id.jpg",
        createdAt: String = "2026-05-03T00:00:00Z",
        isFavorite: Boolean = false,
        stackCount: Int = 0,
        aspectRatio: Float = 1f,
        isEdited: Boolean = false,
        editsResolved: Boolean = false
    ): AssetEntity =
        AssetEntity(
            id = id,
            type = type,
            fileName = fileName,
            createdAt = createdAt,
            isFavorite = isFavorite,
            stackCount = stackCount,
            aspectRatio = aspectRatio,
            isEdited = isEdited,
            editsResolved = editsResolved
        )
}
