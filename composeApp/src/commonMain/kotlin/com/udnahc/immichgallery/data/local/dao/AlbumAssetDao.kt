package com.udnahc.immichgallery.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.udnahc.immichgallery.data.local.entity.AlbumAssetEntity

@Dao
interface AlbumAssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assets: List<AlbumAssetEntity>)

    @Query("DELETE FROM album_assets WHERE albumId = :albumId")
    suspend fun deleteByAlbum(albumId: String)

    @Transaction
    suspend fun replaceAlbum(albumId: String, assets: List<AlbumAssetEntity>) {
        deleteByAlbum(albumId)
        insertAll(assets)
    }

    @Query("SELECT * FROM album_assets WHERE albumId = :albumId ORDER BY sortOrder")
    fun getAssetsPaging(albumId: String): PagingSource<Int, AlbumAssetEntity>

    @Query("SELECT COUNT(*) FROM album_assets WHERE albumId = :albumId AND sortOrder < :sortOrder")
    suspend fun getAssetPosition(albumId: String, sortOrder: Int): Int

    @Query("DELETE FROM album_assets")
    suspend fun deleteAll()
}
