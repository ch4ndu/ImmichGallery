package com.udnahc.immichgallery.domain.usecase.album

import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.AlbumDetail
import com.udnahc.immichgallery.domain.model.toDomain

class GetAlbumDetailUseCase(
    private val repository: AlbumRepository,
    private val serverConfigRepository: ServerConfigRepository
) {
    suspend operator fun invoke(albumId: String): Result<AlbumDetail> {
        return runCatching {
            val baseUrl = serverConfigRepository.getServerUrl().trimEnd('/')
            val response = repository.getAlbumDetail(albumId)
            AlbumDetail(
                id = response.id,
                name = response.albumName,
                assets = response.assets.map { it.toDomain(baseUrl) },
                assetCount = response.assetCount
            )
        }
    }
}
