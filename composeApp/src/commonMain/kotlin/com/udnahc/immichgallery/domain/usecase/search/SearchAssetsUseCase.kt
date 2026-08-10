package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.model.SearchResponse
import com.udnahc.immichgallery.data.model.nextPageNumberOrNull
import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchEndpoint
import com.udnahc.immichgallery.domain.model.SearchResult
import com.udnahc.immichgallery.domain.model.selectSearchEndpoint
import com.udnahc.immichgallery.domain.model.toDomainOrNull
import com.udnahc.immichgallery.runSuspendCatching

class SearchAssetsUseCase internal constructor(
    private val searchSmart: suspend (SearchCriteria, Int, Int) -> SearchResponse,
    private val searchMetadata: suspend (SearchCriteria, Int, Int) -> SearchResponse,
    private val getServerUrl: () -> String
) {
    constructor(
        repository: SearchRepository,
        serverConfigRepository: ServerConfigRepository
    ) : this(
        searchSmart = repository::searchSmart,
        searchMetadata = repository::searchMetadata,
        getServerUrl = serverConfigRepository::getServerUrl
    )

    suspend operator fun invoke(
        endpoint: SearchEndpoint,
        criteria: SearchCriteria,
        page: Int = 1,
        size: Int = 50
    ): Result<SearchResult> = runSuspendCatching {
        require(criteria == criteria.normalized()) { "Search criteria must be normalized" }
        require(!criteria.isEmpty) { "Search criteria must not be empty" }
        require(endpoint == selectSearchEndpoint(criteria)) {
            "Search endpoint does not match the submitted criteria"
        }

        val response = when (endpoint) {
            SearchEndpoint.SMART -> searchSmart(criteria, page, size)
            SearchEndpoint.METADATA -> searchMetadata(criteria, page, size)
        }
        val baseUrl = getServerUrl().trimEnd('/')
        SearchResult(
            assets = response.assets.items.mapNotNull { it.toDomainOrNull(baseUrl) },
            nextPage = response.assets.nextPageNumberOrNull()
        )
    }
}
