package com.udnahc.immichgallery.domain.usecase.people

import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.toDomain

class GetPersonAssetsPageUseCase(
    private val repository: PeopleRepository,
    private val serverConfigRepository: ServerConfigRepository
) {
    /**
     * Fetches a single page of person assets.
     * Returns (assets, hasMore).
     */
    suspend operator fun invoke(
        personId: String,
        page: Int,
        size: Int = 250
    ): Result<Pair<List<Asset>, Boolean>> {
        return runCatching {
            val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
            val (responses, hasMore) = repository.getPersonAssetsPage(personId, page, size)
            responses.map { it.toDomain(baseUrl) } to hasMore
        }
    }
}
