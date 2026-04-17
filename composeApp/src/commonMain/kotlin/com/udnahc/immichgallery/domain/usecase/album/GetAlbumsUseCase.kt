package com.udnahc.immichgallery.domain.usecase.album

import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.domain.model.Album
import kotlinx.coroutines.flow.Flow

class GetAlbumsUseCase(
    private val repository: AlbumRepository
) {
    fun observe(): Flow<List<Album>> = repository.observeAlbums()

    suspend fun sync(): Result<Unit> = repository.syncAlbums()

    suspend fun getLastSyncedAt(): Long? = repository.getLastSyncedAt()

    suspend fun hasCachedAlbums(): Boolean = repository.hasCachedAlbums()
}
