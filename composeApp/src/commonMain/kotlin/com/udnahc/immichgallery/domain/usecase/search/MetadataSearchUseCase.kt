package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.SearchResult
import com.udnahc.immichgallery.domain.model.toDomain

class MetadataSearchUseCase(
    private val repository: SearchRepository,
    private val serverConfigRepository: ServerConfigRepository
) {
    suspend operator fun invoke(
        query: String,
        page: Int = 1
    ): Result<SearchResult> {
        return runCatching {
            val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
            val response = repository.searchMetadata(query, page)
            SearchResult(
                assets = response.assets.items.map { it.toDomain(baseUrl) },
                hasMore = response.assets.nextPage != null
            )
        }
    }
}
