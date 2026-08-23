package com.udnahc.immichgallery.domain.action.detail

import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry

class UpsertDetailMosaicCacheAction(
    private val repository: DetailMosaicCacheRepository,
    private val sessionStateRepository: SessionStateRepository
) {
    suspend operator fun invoke(entry: DetailMosaicCacheEntry) {
        sessionStateRepository.withOperation { repository.upsertDisplayCache(entry) }
    }
}
