package com.udnahc.immichgallery.domain.usecase.detail

import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup

class GetDetailMosaicCacheUseCase(
    private val repository: DetailMosaicCacheRepository
) {
    suspend operator fun invoke(lookup: DetailMosaicCacheLookup): List<DetailMosaicCacheEntry> =
        repository.getDisplayCache(lookup)
}
