package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AlbumDao
import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.entity.AlbumAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.toAlbumEntity
import com.udnahc.immichgallery.domain.model.toAssetEntity
import com.udnahc.immichgallery.domain.model.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AlbumRepository(
    private val apiService: ImmichApiService,
    private val albumDao: AlbumDao,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val serverConfigRepository: ServerConfigRepository,
    private val editsEnricher: AssetEditsEnricher
) {
    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')

    // --- Reactive Room reads ---

    fun observeAlbums(): Flow<List<Album>> =
        albumDao.observeAlbums()
            .map { entities ->
                val base = baseUrl()
                entities.map { it.toDomain(base) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun observeAlbumAssets(albumId: String): Flow<List<Asset>> =
        albumDao.observeAlbumAssets(albumId)
            .map { entities ->
                val base = baseUrl()
                entities.map { it.toDomain(base) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // --- Suspend reads ---

    suspend fun hasCachedAlbums(): Boolean =
        albumDao.getAlbumCount() > 0

    suspend fun hasCachedAlbumAssets(albumId: String): Boolean =
        albumDao.getAlbumAssetCount(albumId) > 0

    suspend fun getCachedAlbumName(albumId: String): String? {
        return albumDao.getAlbum(albumId)?.name
    }

    suspend fun getAlbumAssets(albumId: String): List<Asset> {
        val base = baseUrl()
        return albumDao.getAlbumAssets(albumId).map { it.toDomain(base) }
    }

    suspend fun getLastSyncedAt(): Long? {
        return syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_ALBUMS)
    }

    suspend fun getAlbumDetailLastSyncedAt(albumId: String): Long? {
        return syncMetadataDao.getLastSyncedAt("$SYNC_SCOPE_ALBUM_PREFIX$albumId")
    }

    // --- Network sync ---

    suspend fun syncAlbums(): Result<Unit> {
        return try {
            val responses = apiService.getAlbums()
            val entities = responses.map { it.toAlbumEntity() }
            withContext(Dispatchers.IO) {
                albumDao.replaceAlbums(entities)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(SYNC_SCOPE_ALBUMS, currentEpochMillis())
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncAlbumDetail(albumId: String): Result<String> {
        return try {
            val response = apiService.getAlbumDetail(albumId)
            val assetEntities = response.assets.map { it.toAssetEntity() }
            val crossRefs = response.assets.mapIndexed { index, asset ->
                AlbumAssetCrossRef(albumId, asset.id, index)
            }
            withContext(Dispatchers.IO) {
                assetDao.upsertAssets(assetEntities)
                albumDao.replaceAlbumRefs(albumId, crossRefs)
                syncMetadataDao.upsert(
                    SyncMetadataEntity("$SYNC_SCOPE_ALBUM_PREFIX$albumId", currentEpochMillis())
                )
            }
            // Precise aspect for edited assets: fetch /edits in parallel and
            // replace the sync-time approximation with the simulated
            // post-edit aspect. Runs after the main upsert so the grid
            // renders immediately; updates land via Room's Flow as they
            // resolve.
            editsEnricher.enrich(response.assets)
            Result.success(response.albumName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val SYNC_SCOPE_ALBUMS = "albums"
        const val SYNC_SCOPE_ALBUM_PREFIX = "album:"
    }
}
