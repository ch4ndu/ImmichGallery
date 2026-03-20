package com.udnahc.immichgallery.data.repository

import androidx.paging.PagingSource
import com.udnahc.immichgallery.data.local.dao.TimelineAssetDao
import com.udnahc.immichgallery.data.local.entity.TimelineAssetEntity
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.TimeBucketResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.toTimelineEntity

class TimelineRepository(
    private val apiService: ImmichApiService,
    private val dao: TimelineAssetDao
) {
    suspend fun getTimelineBuckets(): List<TimeBucketResponse> =
        apiService.getTimelineBuckets()

    suspend fun getBucketAssets(timeBucket: String): List<AssetResponse> {
        val assets = apiService.getTimelineBucket(timeBucket = timeBucket)
        dao.replaceBucket(timeBucket, assets.map { it.toTimelineEntity(timeBucket) })
        return assets
    }

    fun getTimelineAssetsPaging(): PagingSource<Int, TimelineAssetEntity> =
        dao.getAssetsPaging()

    suspend fun getAssetPosition(assetId: String, createdAt: String): Int =
        dao.getAssetPosition(assetId, createdAt)

    suspend fun getAssetFileName(id: String): String {
        val response = apiService.getAssetInfo(id)
        val fileName = response.originalFileName
        if (fileName.isNotEmpty()) {
            dao.updateFileName(id, fileName)
        }
        return fileName
    }
}
