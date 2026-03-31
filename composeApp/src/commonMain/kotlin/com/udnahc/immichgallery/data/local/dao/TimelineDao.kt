package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.TimelineAssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity

@Dao
interface TimelineDao {
    @Upsert
    suspend fun upsertBuckets(buckets: List<TimelineBucketEntity>)

    @Query("SELECT * FROM timeline_buckets ORDER BY sortOrder ASC")
    suspend fun getAllBuckets(): List<TimelineBucketEntity>

    @Upsert
    suspend fun upsertAssets(assets: List<TimelineAssetEntity>)

    @Query("SELECT * FROM timeline_assets WHERE timeBucket = :timeBucket ORDER BY sortOrder ASC")
    suspend fun getAssetsForBucket(timeBucket: String): List<TimelineAssetEntity>

    @Query("SELECT COUNT(*) FROM timeline_assets WHERE timeBucket = :timeBucket")
    suspend fun getAssetCountForBucket(timeBucket: String): Int

    @Query("DELETE FROM timeline_buckets")
    suspend fun clearBuckets()

    @Query("DELETE FROM timeline_assets")
    suspend fun clearAssets()

    @Query("DELETE FROM timeline_assets WHERE timeBucket = :timeBucket")
    suspend fun clearAssetsForBucket(timeBucket: String)
}
