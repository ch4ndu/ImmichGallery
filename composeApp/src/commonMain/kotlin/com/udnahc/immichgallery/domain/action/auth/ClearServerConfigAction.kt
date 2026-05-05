package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.data.repository.AssetRepository
import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.action.detail.ClearDetailMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.ClearTimelineMosaicCacheAction

class ClearServerConfigAction(
    private val serverConfigRepository: ServerConfigRepository,
    private val timelineRepository: TimelineRepository,
    private val albumRepository: AlbumRepository,
    private val peopleRepository: PeopleRepository,
    private val assetRepository: AssetRepository,
    private val clearDetailMosaicCacheAction: ClearDetailMosaicCacheAction,
    private val clearTimelineMosaicCacheAction: ClearTimelineMosaicCacheAction
) {
    suspend operator fun invoke() {
        serverConfigRepository.clear()
        timelineRepository.clearCache()
        albumRepository.clearCache()
        peopleRepository.clearCache()
        assetRepository.clearCache()
        clearDetailMosaicCacheAction.all()
        clearTimelineMosaicCacheAction.all()
    }
}
