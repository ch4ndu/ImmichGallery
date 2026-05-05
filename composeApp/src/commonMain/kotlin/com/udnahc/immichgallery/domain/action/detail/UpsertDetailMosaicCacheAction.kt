package com.udnahc.immichgallery.domain.action.detail

import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry

class UpsertDetailMosaicCacheAction(
    private val repository: DetailMosaicCacheRepository
) {
    suspend operator fun invoke(entry: DetailMosaicCacheEntry) {
        repository.upsertDisplayCache(entry)
    }
}
