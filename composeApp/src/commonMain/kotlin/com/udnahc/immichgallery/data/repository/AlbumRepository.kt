package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.AlbumDetailResponse
import com.udnahc.immichgallery.data.model.AlbumResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService

class AlbumRepository(
    private val apiService: ImmichApiService
) {
    suspend fun getAlbums(): List<AlbumResponse> =
        apiService.getAlbums()

    suspend fun getAlbumDetail(id: String): AlbumDetailResponse =
        apiService.getAlbumDetail(id)

    fun albumThumbnailUrl(albumThumbnailAssetId: String): String =
        apiService.albumThumbnailUrl(albumThumbnailAssetId)
}
