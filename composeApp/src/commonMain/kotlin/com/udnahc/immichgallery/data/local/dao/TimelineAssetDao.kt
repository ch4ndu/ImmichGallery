package com.udnahc.immichgallery.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.udnahc.immichgallery.data.local.entity.TimelineAssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineAssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assets: List<TimelineAssetEntity>)

    @Query("DELETE FROM timeline_assets WHERE timeBucket = :timeBucket")
    suspend fun deleteByBucket(timeBucket: String)

    @Transaction
    suspend fun replaceBucket(
        timeBucket: String,
        assets: List<TimelineAssetEntity>
    ) {
        deleteByBucket(timeBucket)
        insertAll(assets)
    }

    @Query("SELECT * FROM timeline_assets ORDER BY createdAt DESC")
    fun getAssetsPaging(): PagingSource<Int, TimelineAssetEntity>

    @Query(
        "SELECT COUNT(*) FROM timeline_assets WHERE createdAt > :createdAt " +
                "OR (createdAt = :createdAt AND id < :assetId)"
    )
    suspend fun getAssetPosition(
        assetId: String,
        createdAt: String
    ): Int

    @Query("SELECT * FROM timeline_assets WHERE timeBucket = :timeBucket ORDER BY createdAt DESC")
    fun getAssetsByBucket(timeBucket: String): Flow<List<TimelineAssetEntity>>

    @Query("UPDATE timeline_assets SET fileName = :fileName WHERE id = :id")
    suspend fun updateFileName(
        id: String,
        fileName: String
    )

    @Query("DELETE FROM timeline_assets")
    suspend fun deleteAll()
}
