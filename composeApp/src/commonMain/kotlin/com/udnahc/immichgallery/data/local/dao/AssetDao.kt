package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Upsert
    suspend fun upsertAssets(assets: List<AssetEntity>)

    @Transaction
    @Query(
        """
        SELECT a.* FROM assets a
        INNER JOIN timeline_asset_refs r ON a.id = r.assetId
        WHERE r.timeBucket = :timeBucket
        ORDER BY r.sortOrder ASC
        """
    )
    fun observeTimelineAssets(timeBucket: String): Flow<List<AssetEntity>>

    @Transaction
    @Query(
        """
        SELECT a.* FROM assets a
        INNER JOIN timeline_asset_refs r ON a.id = r.assetId
        WHERE r.timeBucket = :timeBucket
        ORDER BY r.sortOrder ASC
        """
    )
    suspend fun getTimelineAssets(timeBucket: String): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM timeline_asset_refs WHERE timeBucket = :timeBucket")
    suspend fun getTimelineAssetCount(timeBucket: String): Int

    @Query("SELECT DISTINCT timeBucket FROM timeline_asset_refs")
    suspend fun getLoadedTimelineBuckets(): List<String>

    @Query("DELETE FROM assets")
    suspend fun clearAll()
}
