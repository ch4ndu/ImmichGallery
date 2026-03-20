package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.toDomain

class MetadataSearchUseCase(
    private val repository: SearchRepository,
    private val serverConfigRepository: ServerConfigRepository
) {
    suspend operator fun invoke(query: String, page: Int = 1): Result<List<Asset>> {
        return runCatching {
            val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
            repository.searchMetadata(query, page).assets.items.map { it.toDomain(baseUrl) }
        }
    }
}
