package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.AlbumAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.AlbumEntity
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums ORDER BY name ASC")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :albumId")
    suspend fun getAlbum(albumId: String): AlbumEntity?

    @Upsert
    suspend fun upsertAlbumRefs(refs: List<AlbumAssetCrossRef>)

    @Query("DELETE FROM album_asset_refs WHERE albumId = :albumId")
    suspend fun clearAlbumRefs(albumId: String)

    @Transaction
    @Query(
        """
        SELECT a.* FROM assets a
        INNER JOIN album_asset_refs r ON a.id = r.assetId
        WHERE r.albumId = :albumId
        ORDER BY r.sortOrder ASC
        """
    )
    fun observeAlbumAssets(albumId: String): Flow<List<AssetEntity>>

    @Transaction
    @Query(
        """
        SELECT a.* FROM assets a
        INNER JOIN album_asset_refs r ON a.id = r.assetId
        WHERE r.albumId = :albumId
        ORDER BY r.sortOrder ASC
        """
    )
    suspend fun getAlbumAssets(albumId: String): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM album_asset_refs WHERE albumId = :albumId")
    suspend fun getAlbumAssetCount(albumId: String): Int

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getAlbumCount(): Int

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM album_asset_refs")
    suspend fun clearAllAlbumRefs()

    @Transaction
    suspend fun replaceAlbums(albums: List<AlbumEntity>) {
        clearAlbums()
        upsertAlbums(albums)
    }

    @Transaction
    suspend fun replaceAlbumRefs(albumId: String, refs: List<AlbumAssetCrossRef>) {
        clearAlbumRefs(albumId)
        upsertAlbumRefs(refs)
    }
}
