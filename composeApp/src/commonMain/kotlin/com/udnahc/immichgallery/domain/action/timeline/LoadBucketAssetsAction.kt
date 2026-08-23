package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.domain.model.TimelineBucketAssetSyncResult

class LoadBucketAssetsAction(
    private val repository: TimelineRepository,
    private val sessionStateRepository: SessionStateRepository
) {
    suspend operator fun invoke(timeBucket: String): Result<TimelineBucketAssetSyncResult> =
        sessionStateRepository.withOperation { repository.syncBucketAssets(timeBucket) }
}
