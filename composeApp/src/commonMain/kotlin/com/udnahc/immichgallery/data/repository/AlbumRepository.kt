package com.udnahc.immichgallery.data.repository

import androidx.paging.PagingSource
import com.udnahc.immichgallery.data.local.dao.AlbumAssetDao
import com.udnahc.immichgallery.data.local.entity.AlbumAssetEntity
import com.udnahc.immichgallery.data.model.AlbumDetailResponse
import com.udnahc.immichgallery.data.model.AlbumResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.toAlbumEntity

class AlbumRepository(
    private val apiService: ImmichApiService,
    private val dao: AlbumAssetDao
) {
    suspend fun getAlbums(): List<AlbumResponse> =
        apiService.getAlbums()

    suspend fun getAlbumDetail(id: String): AlbumDetailResponse {
        val response = apiService.getAlbumDetail(id)
        dao.replaceAlbum(id, response.assets.mapIndexed { i, a -> a.toAlbumEntity(id, i) })
        return response
    }

    fun getAlbumAssetsPaging(albumId: String): PagingSource<Int, AlbumAssetEntity> =
        dao.getAssetsPaging(albumId)

    suspend fun getAssetPosition(
        albumId: String,
        sortOrder: Int
    ): Int =
        dao.getAssetPosition(albumId, sortOrder)

    fun albumThumbnailUrl(albumThumbnailAssetId: String): String =
        apiService.albumThumbnailUrl(albumThumbnailAssetId)
}
