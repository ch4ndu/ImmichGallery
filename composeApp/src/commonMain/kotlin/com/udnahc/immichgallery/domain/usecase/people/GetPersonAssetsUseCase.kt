package com.udnahc.immichgallery.domain.usecase.people

import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.toDomain

class GetPersonAssetsUseCase(
    private val repository: PeopleRepository,
    private val serverConfigRepository: ServerConfigRepository
) {
    suspend operator fun invoke(personId: String): Result<List<Asset>> {
        return runCatching {
            val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
            repository.getPersonAssets(personId).map { it.toDomain(baseUrl) }
        }
    }
}
