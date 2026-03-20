package com.udnahc.immichgallery.domain.usecase.album

import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.model.toDomain

class GetAlbumsUseCase(
    private val repository: AlbumRepository,
    private val serverConfigRepository: ServerConfigRepository
) {
    suspend operator fun invoke(): Result<List<Album>> {
        return runCatching {
            val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
            repository.getAlbums().map { it.toDomain(baseUrl) }
        }
    }
}
