package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.data.repository.AssetRepository
import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.action.detail.ClearDetailMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.ClearTimelineMosaicCacheAction
import com.udnahc.immichgallery.runSuspendCatching

class ClearServerConfigAction internal constructor(
    private val clearCaches: suspend () -> Unit,
    private val clearConfiguration: () -> Unit
) {
    constructor(
        serverConfigRepository: ServerConfigRepository,
        timelineRepository: TimelineRepository,
        albumRepository: AlbumRepository,
        peopleRepository: PeopleRepository,
        assetRepository: AssetRepository,
        clearDetailMosaicCacheAction: ClearDetailMosaicCacheAction,
        clearTimelineMosaicCacheAction: ClearTimelineMosaicCacheAction
    ) : this(
        clearCaches = {
            timelineRepository.clearCache()
            albumRepository.clearCache()
            peopleRepository.clearCache()
            assetRepository.clearCache()
            clearDetailMosaicCacheAction.all()
            clearTimelineMosaicCacheAction.all()
        },
        clearConfiguration = serverConfigRepository::clear
    )

    suspend operator fun invoke(): Result<Unit> = runSuspendCatching {
        // Keep the active server configuration until every server-scoped cache
        // has cleared. A failure can then be retried without exposing partial
        // data under a different login.
        clearCaches()
        clearConfiguration()
    }
}
