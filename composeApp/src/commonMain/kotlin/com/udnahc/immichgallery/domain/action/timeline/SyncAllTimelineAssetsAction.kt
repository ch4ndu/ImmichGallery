package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucket

class SyncAllTimelineAssetsAction(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(buckets: List<TimelineBucket>): Result<TimelineAssetSyncResult> =
        repository.syncAllBucketAssets(buckets)
}
