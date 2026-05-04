package com.udnahc.immichgallery.domain.usecase.people

import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.domain.model.PersonAssetsSyncResult

class GetPersonAssetsPageUseCase(
    private val repository: PeopleRepository
) {
    /**
     * Syncs a single page of person assets to Room.
     * Returns content-change state plus hasMore.
     */
    suspend operator fun invoke(
        personId: String,
        page: Int,
        size: Int = 250
    ): Result<PersonAssetsSyncResult> = repository.syncPersonAssetsPage(personId, page, size)
}
