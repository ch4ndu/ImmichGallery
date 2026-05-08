package com.udnahc.immichgallery.domain.action.detail

import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType

class ClearDetailMosaicCacheAction(
    private val repository: DetailMosaicCacheRepository
) {
    suspend fun owner(
        ownerType: DetailMosaicCacheOwnerType,
        ownerId: String
    ) {
        repository.clearOwnerCache(ownerType, ownerId)
    }

    suspend fun all() {
        repository.clearAll()
    }
}
