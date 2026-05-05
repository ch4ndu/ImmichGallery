package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.DetailMosaicDisplayCacheEntity

@Dao
interface DetailMosaicCacheDao {
    @Query(
        """
        SELECT * FROM detail_mosaic_display_cache
        WHERE ownerType = :ownerType
            AND ownerId = :ownerId
            AND groupSize = :groupSize
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
        familiesKey: String,
        availableWidthKey: Int,
        cellHeightKey: Int,
        maxRowHeightKey: Int,
        spacingKey: Int,
        displayVersion: Int
    ): List<DetailMosaicDisplayCacheEntity>

    @Upsert
    suspend fun upsertDisplayCache(rows: List<DetailMosaicDisplayCacheEntity>)

    @Query("DELETE FROM detail_mosaic_display_cache WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun clearOwnerCache(ownerType: String, ownerId: String)

    @Query("DELETE FROM detail_mosaic_display_cache")
    suspend fun clearAll()
}
