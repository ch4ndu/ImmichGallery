package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineBucketSyncResult
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import kotlinx.coroutines.CancellationException
import org.lighthousegames.logging.logging

data class TimelineManualSyncRequest(
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val families: Set<MosaicTemplateFamily>,
    val mosaicEnabled: Boolean,
    val cacheMosaicResults: Boolean,
    val geometryRequest: TimelineMosaicGeometryRequest?,
    val hadBannerError: Boolean
)

data class TimelineManualSyncResult(
    val request: TimelineManualSyncRequest,
    val bucketSyncResult: TimelineBucketSyncResult,
    val assetSyncResult: TimelineAssetSyncResult,
    val mosaicPrecomputeResult: TimelineMosaicPrecomputeResult? = null
)

class TimelineManualSyncAction internal constructor(
    private val syncBuckets: suspend () -> Result<TimelineBucketSyncResult>,
    private val syncAssets: suspend (List<TimelineBucket>) -> Result<TimelineAssetSyncResult>,
    private val clearMosaicBuckets: suspend (Set<String>) -> Unit,
    private val prepareMosaic: suspend (
        Set<String>,
        TimelineGroupSize,
        Int,
        Set<MosaicTemplateFamily>,
        TimelineMosaicGeometryRequest,
        Boolean
    ) -> Result<TimelineMosaicPrecomputeResult>
) {
    constructor(
        getTimelineBucketsUseCase: GetTimelineBucketsUseCase,
        syncAllTimelineAssetsAction: SyncAllTimelineAssetsAction,
        clearTimelineMosaicCacheAction: ClearTimelineMosaicCacheAction,
        prepareTimelineMosaicCacheAction: PrepareTimelineMosaicCacheAction
    ) : this(
        syncBuckets = getTimelineBucketsUseCase::sync,
        syncAssets = syncAllTimelineAssetsAction::invoke,
        clearMosaicBuckets = { bucketIds -> clearTimelineMosaicCacheAction.buckets(bucketIds) },
        prepareMosaic = { bucketIds, groupSize, columnCount, families, geometryRequest, cacheDisplayResults ->
            prepareTimelineMosaicCacheAction(
                timeBuckets = bucketIds,
                groupSize = groupSize,
                columnCount = columnCount,
                families = families,
                geometryRequest = geometryRequest,
                cacheDisplayResults = cacheDisplayResults
            )
        }
    )

    private val log = logging("TimelineManualSyncAction")

    suspend operator fun invoke(request: TimelineManualSyncRequest): Result<TimelineManualSyncResult> =
        try {
            val bucketResult = syncBuckets().getOrElse { return Result.failure(it) }
            val assetResult = syncAssetsWithRetry(bucketResult.buckets)
                .getOrElse { return Result.failure(it) }
            val changedSuccessfulBucketIds =
                assetResult.changedBucketIds intersect assetResult.successfulBucketIds
            val bucketsToClear = bucketResult.removedBucketIds + changedSuccessfulBucketIds
            if (bucketsToClear.isNotEmpty()) {
                clearMosaicBuckets(bucketsToClear)
            }
            val mosaicResult =
                if (request.mosaicEnabled &&
                    request.cacheMosaicResults &&
                    request.geometryRequest != null &&
                    changedSuccessfulBucketIds.isNotEmpty()
                ) {
                    prepareMosaic(
                        changedSuccessfulBucketIds,
                        request.groupSize,
                        request.columnCount,
                        request.families,
                        request.geometryRequest,
                        request.cacheMosaicResults
                    ).getOrElse { e ->
                        log.e(e) { "Manual Timeline Mosaic precompute failed" }
                        TimelineMosaicPrecomputeResult(
                            successfulBucketIds = emptySet(),
                            failedBucketIds = changedSuccessfulBucketIds
                        )
                    }
                } else {
                    null
                }
            Result.success(
                TimelineManualSyncResult(
                    request = request,
                    bucketSyncResult = bucketResult,
                    assetSyncResult = assetResult,
                    mosaicPrecomputeResult = mosaicResult
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun syncAssetsWithRetry(
        buckets: List<TimelineBucket>
    ): Result<TimelineAssetSyncResult> {
        val first = syncAssets(buckets).getOrElse { return Result.failure(it) }
        if (first.failedBucketIds.isEmpty()) return Result.success(first)
        val retryBuckets = buckets.filter { it.timeBucket in first.failedBucketIds }
        val retry = syncAssets(retryBuckets).getOrElse { return Result.failure(it) }
        val retrySuccessful = retry.successfulBucketIds
        return Result.success(
            TimelineAssetSyncResult(
                successfulBucketIds = first.successfulBucketIds + retrySuccessful,
                failedBucketIds = (first.failedBucketIds - retrySuccessful) + retry.failedBucketIds,
                changedBucketIds = first.changedBucketIds + retry.changedBucketIds
            )
        )
    }
}
