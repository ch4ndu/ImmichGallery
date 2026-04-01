package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class TimelineRepository(
    private val apiService: ImmichApiService,
    private val timelineDao: TimelineDao,
    private val serverConfigRepository: ServerConfigRepository
) {
    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')

    suspend fun refreshBuckets(): List<TimelineBucket> {
        val responses = apiService.getTimelineBuckets()
        val entities = responses.mapIndexed { index, response ->
            response.toEntity(index)
        }
        withContext(Dispatchers.IO) {
            timelineDao.clearBuckets()
            timelineDao.upsertBuckets(entities)
        }
        return entities.map { it.toDomain() }
    }

    suspend fun getCachedBuckets(): List<TimelineBucket> {
        return timelineDao.getAllBuckets().map { it.toDomain() }
    }

    suspend fun loadBucketAssets(timeBucket: String): Result<Unit> {
        return try {
            val allAssets = apiService.getTimelineBucket(timeBucket)
            val baseUrl = baseUrl()
            val entities = allAssets
                .filter { it.visibility != "hidden" }
                .mapIndexed { index, response ->
                    response.toEntity(timeBucket, baseUrl, index)
                }
            withContext(Dispatchers.IO) {
                timelineDao.clearAssetsForBucket(timeBucket)
                timelineDao.upsertAssets(entities)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isBucketLoaded(timeBucket: String): Boolean {
        return timelineDao.getAssetCountForBucket(timeBucket) > 0
    }

    suspend fun getAssetsForBucket(timeBucket: String): List<Asset> {
        return timelineDao.getAssetsForBucket(timeBucket).map { it.toDomain() }
    }

    suspend fun getAssetFileName(id: String): String {
        val response = apiService.getAssetInfo(id)
        return response.originalFileName
    }
}
