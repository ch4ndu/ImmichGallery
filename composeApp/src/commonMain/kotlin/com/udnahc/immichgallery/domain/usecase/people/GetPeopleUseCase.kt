package com.udnahc.immichgallery.domain.usecase.people

import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.model.toDomain

class GetPeopleUseCase(
    private val repository: PeopleRepository,
    private val serverConfigRepository: ServerConfigRepository
) {
    suspend operator fun invoke(): Result<List<Person>> {
        return runCatching {
            val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
            repository.getPeople().people
                .filter { !it.isHidden }
                .map { it.toDomain(baseUrl) }
        }
    }
}
