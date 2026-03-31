package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository

class LoadBucketAssetsAction(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(timeBucket: String): Result<Unit> =
        repository.loadBucketAssets(timeBucket)
}
