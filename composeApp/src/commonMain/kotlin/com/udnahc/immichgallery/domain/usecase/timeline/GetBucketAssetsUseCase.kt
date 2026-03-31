package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.Asset

class GetBucketAssetsUseCase(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(timeBucket: String): List<Asset> =
        repository.getAssetsForBucket(timeBucket)
}
