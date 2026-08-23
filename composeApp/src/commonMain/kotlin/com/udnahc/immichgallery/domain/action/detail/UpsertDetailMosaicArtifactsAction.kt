package com.udnahc.immichgallery.domain.action.detail

import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifactsUpsert

class UpsertDetailMosaicArtifactsAction(
    private val repository: DetailMosaicCacheRepository,
    private val sessionStateRepository: SessionStateRepository
) {
    suspend operator fun invoke(artifacts: DetailMosaicArtifactsUpsert) {
        sessionStateRepository.withOperation { repository.upsertArtifacts(artifacts) }
    }
}
