package com.udnahc.immichgallery.domain.action.detail

import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifactsUpsert

class UpsertDetailMosaicArtifactsAction(
    private val repository: DetailMosaicCacheRepository
) {
    suspend operator fun invoke(artifacts: DetailMosaicArtifactsUpsert) {
        repository.upsertArtifacts(artifacts)
    }
}
