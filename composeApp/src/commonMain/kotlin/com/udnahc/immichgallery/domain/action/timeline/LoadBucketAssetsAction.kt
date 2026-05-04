package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.TimelineBucketAssetSyncResult

class LoadBucketAssetsAction(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(timeBucket: String): Result<TimelineBucketAssetSyncResult> =
        repository.syncBucketAssets(timeBucket)
}
