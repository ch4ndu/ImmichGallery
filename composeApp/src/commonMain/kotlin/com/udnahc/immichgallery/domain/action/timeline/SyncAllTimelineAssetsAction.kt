package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest

class SyncAllTimelineAssetsAction(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(
        buckets: List<TimelineBucket>,
        groupSize: TimelineGroupSize? = null,
        columnCount: Int? = null,
        families: Set<MosaicTemplateFamily>? = null,
        geometryRequest: TimelineMosaicGeometryRequest? = null
    ): Result<TimelineAssetSyncResult> =
        repository.syncAllBucketAssets(buckets, groupSize, columnCount, families, geometryRequest)
}
