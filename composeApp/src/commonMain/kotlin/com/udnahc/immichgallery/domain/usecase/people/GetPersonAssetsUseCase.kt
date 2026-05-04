package com.udnahc.immichgallery.domain.usecase.people

import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.PersonAssetsSyncResult
import kotlinx.coroutines.flow.Flow

class GetPersonAssetsUseCase(
    private val repository: PeopleRepository
) {
    fun observe(personId: String): Flow<List<Asset>> =
        repository.observePersonAssets(personId)

    suspend fun getAssets(personId: String): List<Asset> =
        repository.getPersonAssets(personId)

    suspend fun syncAll(personId: String): Result<PersonAssetsSyncResult> =
        repository.syncAllPersonAssets(personId)

    suspend fun getLastSyncedAt(personId: String): Long? =
        repository.getPersonDetailLastSyncedAt(personId)

    suspend fun hasCachedAssets(personId: String): Boolean =
        repository.hasCachedPersonAssets(personId)
}
