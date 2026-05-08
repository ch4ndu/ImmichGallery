package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.DetailMosaicAggregateGeometryEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicAssignmentEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicDisplayCacheEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicSectionGeometryEntity

@Dao
interface DetailMosaicCacheDao {
    @Query(
        """
        SELECT * FROM detail_mosaic_assignments
        WHERE ownerType = :ownerType
            AND ownerId = :ownerId
            AND groupSize = :groupSize
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
        """
    )
    suspend fun getAssignments(
        ownerType: String,
        ownerId: String,
        groupSize: String,
        columnCount: Int,
        familiesKey: String
    ): List<DetailMosaicAssignmentEntity>

    @Query(
        """
        SELECT * FROM detail_mosaic_display_cache
        WHERE ownerType = :ownerType
            AND ownerId = :ownerId
            AND groupSize = :groupSize
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
            AND availableWidthKey = :availableWidthKey
            AND cellHeightKey = :cellHeightKey
            AND maxRowHeightKey = :maxRowHeightKey
            AND spacingKey = :spacingKey
            AND displayVersion = :displayVersion
        """
    )
    suspend fun getDisplayCache(
        ownerType: String,
        ownerId: String,
        groupSize: String,
        columnCount: Int,
        familiesKey: String,
        availableWidthKey: Int,
        cellHeightKey: Int,
        maxRowHeightKey: Int,
        spacingKey: Int,
        displayVersion: Int
    ): List<DetailMosaicDisplayCacheEntity>

    @Query(
        """
        SELECT * FROM detail_mosaic_section_geometry
        WHERE ownerType = :ownerType
            AND ownerId = :ownerId
            AND groupSize = :groupSize
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
            AND availableWidthKey = :availableWidthKey
            AND cellHeightKey = :cellHeightKey
            AND maxRowHeightKey = :maxRowHeightKey
            AND spacingKey = :spacingKey
            AND geometryVersion = :geometryVersion
        """
    )
    suspend fun getSectionGeometry(
        ownerType: String,
        ownerId: String,
        groupSize: String,
        columnCount: Int,
        familiesKey: String,
        availableWidthKey: Int,
        cellHeightKey: Int,
        maxRowHeightKey: Int,
        spacingKey: Int,
        geometryVersion: Int
    ): List<DetailMosaicSectionGeometryEntity>

    @Query(
        """
        SELECT * FROM detail_mosaic_aggregate_geometry
        WHERE ownerType = :ownerType
            AND ownerId = :ownerId
            AND groupSize = :groupSize
            AND columnCount = :columnCount
            AND familiesKey = :familiesKey
            AND availableWidthKey = :availableWidthKey
            AND cellHeightKey = :cellHeightKey
            AND maxRowHeightKey = :maxRowHeightKey
            AND spacingKey = :spacingKey
            AND geometryVersion = :geometryVersion
        """
    )
    suspend fun getAggregateGeometry(
        ownerType: String,
        ownerId: String,
        groupSize: String,
        columnCount: Int,
        familiesKey: String,
        availableWidthKey: Int,
        cellHeightKey: Int,
        maxRowHeightKey: Int,
        spacingKey: Int,
        geometryVersion: Int
    ): DetailMosaicAggregateGeometryEntity?

    @Upsert
    suspend fun upsertAssignments(rows: List<DetailMosaicAssignmentEntity>)

    @Upsert
    suspend fun upsertDisplayCache(rows: List<DetailMosaicDisplayCacheEntity>)

    @Upsert
    suspend fun upsertSectionGeometry(rows: List<DetailMosaicSectionGeometryEntity>)

    @Upsert
    suspend fun upsertAggregateGeometry(row: DetailMosaicAggregateGeometryEntity)

    @Query("DELETE FROM detail_mosaic_display_cache WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun clearOwnerDisplayCache(
        ownerType: String,
        ownerId: String
    )

    @Query("DELETE FROM detail_mosaic_assignments WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun clearOwnerAssignments(
        ownerType: String,
        ownerId: String
    )

    @Query("DELETE FROM detail_mosaic_section_geometry WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun clearOwnerSectionGeometry(
        ownerType: String,
        ownerId: String
    )

    @Query("DELETE FROM detail_mosaic_aggregate_geometry WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun clearOwnerAggregateGeometry(
        ownerType: String,
        ownerId: String
    )

    @Query("DELETE FROM detail_mosaic_display_cache")
    suspend fun clearAllDisplayCache()

    @Query("DELETE FROM detail_mosaic_assignments")
    suspend fun clearAllAssignments()

    @Query("DELETE FROM detail_mosaic_section_geometry")
    suspend fun clearAllSectionGeometry()

    @Query("DELETE FROM detail_mosaic_aggregate_geometry")
    suspend fun clearAllAggregateGeometry()

    @Transaction
    suspend fun upsertArtifacts(
        assignments: List<DetailMosaicAssignmentEntity>,
        displayCache: List<DetailMosaicDisplayCacheEntity>,
        sectionGeometry: List<DetailMosaicSectionGeometryEntity>,
        aggregateGeometry: DetailMosaicAggregateGeometryEntity?
    ) {
        if (assignments.isNotEmpty()) {
            upsertAssignments(assignments)
        }
        if (displayCache.isNotEmpty()) {
            upsertDisplayCache(displayCache)
        }
        if (sectionGeometry.isNotEmpty()) {
            upsertSectionGeometry(sectionGeometry)
        }
        if (aggregateGeometry != null) {
            upsertAggregateGeometry(aggregateGeometry)
        }
    }

    @Transaction
    suspend fun clearOwnerCache(
        ownerType: String,
        ownerId: String
    ) {
        clearOwnerAssignments(ownerType, ownerId)
        clearOwnerDisplayCache(ownerType, ownerId)
        clearOwnerSectionGeometry(ownerType, ownerId)
        clearOwnerAggregateGeometry(ownerType, ownerId)
    }

    @Transaction
    suspend fun clearAllArtifacts() {
        clearAllAssignments()
        clearAllDisplayCache()
        clearAllSectionGeometry()
        clearAllAggregateGeometry()
    }
}
