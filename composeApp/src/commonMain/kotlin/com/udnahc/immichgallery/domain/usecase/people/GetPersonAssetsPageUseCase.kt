package com.udnahc.immichgallery.domain.usecase.people

import com.udnahc.immichgallery.data.repository.PeopleRepository

class GetPersonAssetsPageUseCase(
    private val repository: PeopleRepository
) {
    /**
     * Syncs a single page of person assets to Room.
     * Returns Result<hasMore>.
     */
    suspend operator fun invoke(
        personId: String,
        page: Int,
        size: Int = 250
    ): Result<Boolean> = repository.syncPersonAssetsPage(personId, page, size)
}
