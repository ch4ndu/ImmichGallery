package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineDao {
    @Upsert
    suspend fun upsertBuckets(buckets: List<TimelineBucketEntity>)

    @Query("SELECT * FROM timeline_buckets ORDER BY sortOrder ASC")
    suspend fun getAllBuckets(): List<TimelineBucketEntity>

    @Query("SELECT * FROM timeline_buckets ORDER BY sortOrder ASC")
    fun observeBuckets(): Flow<List<TimelineBucketEntity>>

    @Upsert
    suspend fun upsertTimelineRefs(refs: List<TimelineAssetCrossRef>)

    @Query("DELETE FROM timeline_asset_refs WHERE timeBucket = :timeBucket")
    suspend fun clearTimelineRefsForBucket(timeBucket: String)

    @Query("SELECT COUNT(*) FROM timeline_buckets")
    suspend fun getBucketCount(): Int

    @Query("DELETE FROM timeline_buckets")
    suspend fun clearBuckets()

    @Query("DELETE FROM timeline_asset_refs")
    suspend fun clearAllTimelineRefs()

    @Transaction
    suspend fun replaceBuckets(buckets: List<TimelineBucketEntity>) {
        clearBuckets()
        upsertBuckets(buckets)
    }

    @Transaction
    suspend fun replaceBucketRefs(timeBucket: String, refs: List<TimelineAssetCrossRef>) {
        clearTimelineRefsForBucket(timeBucket)
        upsertTimelineRefs(refs)
    }
}
