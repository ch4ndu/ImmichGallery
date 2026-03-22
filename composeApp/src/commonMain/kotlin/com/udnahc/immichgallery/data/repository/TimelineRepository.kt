package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.TimeBucketResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService

class TimelineRepository(
    private val apiService: ImmichApiService
) {
    suspend fun getTimelineBuckets(): List<TimeBucketResponse> =
        apiService.getTimelineBuckets()

    suspend fun getBucketAssets(timeBucket: String): List<AssetResponse> {
        val allAssets = apiService.getTimelineBucket(timeBucket = timeBucket)
        return allAssets.filter { it.visibility != "hidden" }
    }

    suspend fun getAssetFileName(id: String): String {
        val response = apiService.getAssetInfo(id)
        return response.originalFileName
    }
}
