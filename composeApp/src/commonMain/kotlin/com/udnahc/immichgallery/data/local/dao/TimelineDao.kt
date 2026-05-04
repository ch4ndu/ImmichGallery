package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicAssignmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineDao {
    @Upsert
    suspend fun upsertBuckets(buckets: List<TimelineBucketEntity>)

    @Query("SELECT * FROM timeline_buckets ORDER BY sortOrder ASC")
    fun observeBuckets(): Flow<List<TimelineBucketEntity>>

    @Query("SELECT * FROM timeline_buckets ORDER BY sortOrder ASC")
    suspend fun getBuckets(): List<TimelineBucketEntity>

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

    @Upsert
    suspend fun upsertMosaicAssignments(assignments: List<TimelineMosaicAssignmentEntity>)

    @Query(
        """
        SELECT * FROM timeline_mosaic_assignments
        WHERE timeBucket IN (:timeBuckets)
            AND groupMode = :groupMode
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
        """
    )
    suspend fun getMosaicAssignments(
        timeBuckets: List<String>,
        groupMode: String,
        columnCount: Int,
        familiesKey: String
    ): List<TimelineMosaicAssignmentEntity>

    @Query(
        """
        DELETE FROM timeline_mosaic_assignments
        WHERE timeBucket = :timeBucket
            AND groupMode = :groupMode
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
        """
    )
    suspend fun clearMosaicAssignmentsForBucketConfig(
        timeBucket: String,
        groupMode: String,
        columnCount: Int,
        familiesKey: String
    )

    @Query("DELETE FROM timeline_mosaic_assignments WHERE timeBucket IN (:timeBuckets)")
    suspend fun clearMosaicAssignmentsForBuckets(timeBuckets: List<String>)

    @Query("DELETE FROM timeline_mosaic_assignments")
    suspend fun clearAllTimelineMosaicAssignments()

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

    @Transaction
    suspend fun replaceMosaicAssignmentsForBucketConfig(
        timeBucket: String,
        groupMode: String,
        columnCount: Int,
        familiesKey: String,
        assignments: List<TimelineMosaicAssignmentEntity>
    ) {
        clearMosaicAssignmentsForBucketConfig(timeBucket, groupMode, columnCount, familiesKey)
        if (assignments.isNotEmpty()) {
            upsertMosaicAssignments(assignments)
        }
    }
}
