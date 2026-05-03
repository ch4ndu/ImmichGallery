package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.toDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class AssetRepository(
    private val apiService: ImmichApiService,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val serverConfigRepository: ServerConfigRepository
) {
    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')

    suspend fun getAssetDetail(assetId: String): AssetDetail {
        val response = apiService.getAssetInfo(assetId)
        return response.toDetail(baseUrl())
    }

    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            assetDao.clearAll()
            syncMetadataDao.clearAll()
        }
    }
}
