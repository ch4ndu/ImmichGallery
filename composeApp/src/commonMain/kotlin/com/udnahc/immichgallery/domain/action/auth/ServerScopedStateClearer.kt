package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.data.repository.AssetRepository
import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.data.repository.TimelineRepository

class ServerScopedStateClearer internal constructor(
    private val clearSteps: List<suspend () -> Unit>
) {
    constructor(
        timelineRepository: TimelineRepository,
        albumRepository: AlbumRepository,
        peopleRepository: PeopleRepository,
        assetRepository: AssetRepository,
        detailMosaicCacheRepository: DetailMosaicCacheRepository,
        timelineMosaicCacheRepository: TimelineMosaicCacheRepository,
        apiService: ImmichApiService
    ) : this(
        listOf(
            { timelineRepository.clearCacheForSessionMutation() },
            { albumRepository.clearCacheForSessionMutation() },
            { peopleRepository.clearCacheForSessionMutation() },
            { assetRepository.clearCacheForSessionMutation() },
            { detailMosaicCacheRepository.clearAllForSessionMutation() },
            { timelineMosaicCacheRepository.clearAllForSessionMutation() },
            { apiService.clearSessionState() }
        )
    )

    suspend fun clear() = clearSteps.forEach { clear -> clear() }
}
