package com.udnahc.immichgallery.domain.usecase.album

import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.domain.model.AlbumDetailSyncResult
import com.udnahc.immichgallery.domain.model.Asset
import kotlinx.coroutines.flow.Flow

class GetAlbumDetailUseCase(
    private val repository: AlbumRepository
) {
    fun observeAssets(albumId: String): Flow<List<Asset>> =
        repository.observeAlbumAssets(albumId)

    suspend fun sync(albumId: String): Result<AlbumDetailSyncResult> =
        repository.syncAlbumDetail(albumId)

    suspend fun getCachedAlbumName(albumId: String): String? =
        repository.getCachedAlbumName(albumId)

    suspend fun getAlbumAssets(albumId: String): List<Asset> =
        repository.getAlbumAssets(albumId)

    suspend fun getLastSyncedAt(albumId: String): Long? =
        repository.getAlbumDetailLastSyncedAt(albumId)

    suspend fun hasCachedAssets(albumId: String): Boolean =
        repository.hasCachedAlbumAssets(albumId)
}
