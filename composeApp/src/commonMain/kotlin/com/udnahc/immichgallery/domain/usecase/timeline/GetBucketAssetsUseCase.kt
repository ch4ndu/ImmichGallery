package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.toDomain

class GetBucketAssetsUseCase(
    private val repository: TimelineRepository,
    private val serverConfigRepository: ServerConfigRepository
) {
    suspend operator fun invoke(timeBucket: String): Result<List<Asset>> {
        return runCatching {
            val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
            repository.getBucketAssets(timeBucket)
                .map { it.toDomain(baseUrl) }
                .sortedByDescending { it.createdAt }
        }
    }
}
