package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketGeometryEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicAssignmentEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicDisplayCacheEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicGeometryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineDao {
    @Upsert
    suspend fun upsertBuckets(buckets: List<TimelineBucketEntity>)

    @Query("SELECT * FROM timeline_buckets ORDER BY sortOrder ASC")
    fun observeBuckets(): Flow<List<TimelineBucketEntity>>

    @Query("SELECT * FROM timeline_buckets ORDER BY sortOrder ASC")
    suspend fun getBuckets(): List<TimelineBucketEntity>

    @Query("SELECT count FROM timeline_buckets WHERE timeBucket = :timeBucket")
    suspend fun getBucketAssetCount(timeBucket: String): Int?

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

    @Upsert
    suspend fun upsertMosaicDisplayCache(rows: List<TimelineMosaicDisplayCacheEntity>)

    @Upsert
    suspend fun upsertMosaicGeometry(geometry: List<TimelineMosaicGeometryEntity>)

    @Upsert
    suspend fun upsertBucketGeometry(geometry: List<TimelineBucketGeometryEntity>)

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
        SELECT * FROM timeline_mosaic_display_cache
        WHERE timeBucket IN (:timeBuckets)
            AND groupMode = :groupMode
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
            AND availableWidthKey = :availableWidthKey
            AND displayVersion = :displayVersion
        """
    )
    suspend fun getMosaicDisplayCache(
        timeBuckets: List<String>,
        groupMode: String,
        columnCount: Int,
        familiesKey: String,
        availableWidthKey: Int,
        displayVersion: Int
    ): List<TimelineMosaicDisplayCacheEntity>

    @Query(
        """
        SELECT * FROM timeline_mosaic_geometry
        WHERE timeBucket IN (:timeBuckets)
            AND groupMode = :groupMode
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
            AND availableWidthKey = :availableWidthKey
            AND geometryVersion = :geometryVersion
        """
    )
    suspend fun getMosaicGeometry(
        timeBuckets: List<String>,
        groupMode: String,
        columnCount: Int,
        familiesKey: String,
        availableWidthKey: Int,
        geometryVersion: Int
    ): List<TimelineMosaicGeometryEntity>

    @Query(
        """
        SELECT * FROM timeline_bucket_geometry
        WHERE timeBucket IN (:timeBuckets)
            AND groupMode = :groupMode
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
            AND availableWidthKey = :availableWidthKey
            AND geometryVersion = :geometryVersion
        """
    )
    suspend fun getBucketGeometry(
        timeBuckets: List<String>,
        groupMode: String,
        columnCount: Int,
        familiesKey: String,
        availableWidthKey: Int,
        geometryVersion: Int
    ): List<TimelineBucketGeometryEntity>

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

    @Query(
        """
        DELETE FROM timeline_mosaic_geometry
        WHERE timeBucket = :timeBucket
            AND groupMode = :groupMode
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
        """
    )
    suspend fun clearMosaicGeometryForBucketConfig(
        timeBucket: String,
        groupMode: String,
        columnCount: Int,
        familiesKey: String
    )

    @Query(
        """
        DELETE FROM timeline_mosaic_display_cache
        WHERE timeBucket = :timeBucket
            AND groupMode = :groupMode
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
        """
    )
    suspend fun clearMosaicDisplayCacheForBucketConfig(
        timeBucket: String,
        groupMode: String,
        columnCount: Int,
        familiesKey: String
    )

    @Query(
        """
        DELETE FROM timeline_bucket_geometry
        WHERE timeBucket = :timeBucket
            AND groupMode = :groupMode
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
        """
    )
    suspend fun clearBucketGeometryForBucketConfig(
        timeBucket: String,
        groupMode: String,
        columnCount: Int,
        familiesKey: String
    )

    @Query("DELETE FROM timeline_mosaic_assignments WHERE timeBucket IN (:timeBuckets)")
    suspend fun clearMosaicAssignmentsForBuckets(timeBuckets: List<String>)

    @Query("DELETE FROM timeline_mosaic_geometry WHERE timeBucket IN (:timeBuckets)")
    suspend fun clearMosaicGeometryForBuckets(timeBuckets: List<String>)

    @Query("DELETE FROM timeline_mosaic_display_cache WHERE timeBucket IN (:timeBuckets)")
    suspend fun clearMosaicDisplayCacheForBuckets(timeBuckets: List<String>)

    @Query("DELETE FROM timeline_bucket_geometry WHERE timeBucket IN (:timeBuckets)")
    suspend fun clearBucketGeometryForBuckets(timeBuckets: List<String>)

    @Query("DELETE FROM timeline_mosaic_assignments")
    suspend fun clearAllTimelineMosaicAssignments()

    @Query("DELETE FROM timeline_mosaic_geometry")
    suspend fun clearAllTimelineMosaicGeometry()

    @Query("DELETE FROM timeline_mosaic_display_cache")
    suspend fun clearAllTimelineMosaicDisplayCache()

    @Query("DELETE FROM timeline_bucket_geometry")
    suspend fun clearAllTimelineBucketGeometry()

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
        assignments: List<TimelineMosaicAssignmentEntity>,
        displayCache: List<TimelineMosaicDisplayCacheEntity> = emptyList(),
        geometry: List<TimelineMosaicGeometryEntity> = emptyList(),
        bucketGeometry: TimelineBucketGeometryEntity? = null
    ) {
        clearMosaicAssignmentsForBucketConfig(timeBucket, groupMode, columnCount, familiesKey)
        clearMosaicDisplayCacheForBucketConfig(timeBucket, groupMode, columnCount, familiesKey)
        clearMosaicGeometryForBucketConfig(timeBucket, groupMode, columnCount, familiesKey)
        clearBucketGeometryForBucketConfig(timeBucket, groupMode, columnCount, familiesKey)
        if (assignments.isNotEmpty()) {
            upsertMosaicAssignments(assignments)
        }
        if (displayCache.isNotEmpty()) {
            upsertMosaicDisplayCache(displayCache)
        }
        if (geometry.isNotEmpty()) {
            upsertMosaicGeometry(geometry)
        }
        if (bucketGeometry != null) {
            upsertBucketGeometry(listOf(bucketGeometry))
        }
    }
}
