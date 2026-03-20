package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository

class GetBucketAssetsUseCase(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(timeBucket: String): Result<Unit> {
        return runCatching {
            repository.getBucketAssets(timeBucket)
            Unit
        }
    }
}
