package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineBucketSyncResult
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun changedAndRemovedBucketsAreSurfacedAndCleared() = runTest {
        val clearedBuckets = mutableSetOf<String>()
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
        assertEquals(setOf("2026-04", "2026-05"), clearedBuckets)
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
            syncAssets = {
                Result.success(remainingAssetResults.removeAt(0))
            },
            clearMosaicBuckets = clear,
            prepareMosaic = { bucketIds, _, _, _, _, _ ->
                Result.success(prepare(bucketIds))
            }
        )
    }

    private fun request(
        geometryRequest: TimelineMosaicGeometryRequest? =
            TimelineMosaicGeometryRequest(availableWidth = 400f, maxRowHeight = 240f, spacing = 2f)
    ): TimelineManualSyncRequest =
        TimelineManualSyncRequest(
            groupSize = TimelineGroupSize.MONTH,
            columnCount = 4,
            families = MosaicTemplateFamily.defaultSet(),
            mosaicEnabled = true,
            cacheMosaicResults = true,
            geometryRequest = geometryRequest,
            hadBannerError = false
        )

    private fun bucket(timeBucket: String): TimelineBucket =
        TimelineBucket(displayLabel = timeBucket, timeBucket = timeBucket, count = 1)
}
