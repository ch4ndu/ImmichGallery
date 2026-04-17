package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.local.dao.AlbumDao
import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.PersonDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class ClearServerConfigAction(
    private val serverConfigRepository: ServerConfigRepository,
    private val timelineDao: TimelineDao,
    private val albumDao: AlbumDao,
    private val personDao: PersonDao,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao
) {
    suspend operator fun invoke() {
        serverConfigRepository.clear()
        timelineDao.clearBuckets()
        timelineDao.clearAllTimelineRefs()
        albumDao.clearAlbums()
        albumDao.clearAllAlbumRefs()
        personDao.clearPeople()
        personDao.clearAllPersonRefs()
        assetDao.clearAll()
        syncMetadataDao.clearAll()
    }
}
