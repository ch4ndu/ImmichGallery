package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucket

class SyncAllTimelineAssetsAction(
    private val repository: TimelineRepository,
    private val sessionStateRepository: SessionStateRepository
) {
    suspend operator fun invoke(
        buckets: List<TimelineBucket>
    ): Result<TimelineAssetSyncResult> =
        sessionStateRepository.withOperation { repository.syncAllBucketAssets(buckets) }
}
