package com.udnahc.immichgallery.domain.usecase.detail

import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifacts
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup

class GetDetailMosaicArtifactsUseCase(
    private val repository: DetailMosaicCacheRepository
) {
    suspend operator fun invoke(
        lookup: DetailMosaicCacheLookup,
        geometryVersion: Int
    ): DetailMosaicArtifacts =
        repository.getArtifacts(lookup, geometryVersion)
}
