package com.udnahc.immichgallery.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.dao.AlbumDao
import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.entity.AlbumAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.AlbumEntity
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.nextPageNumberOrNull
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.model.AlbumDetailSyncResult
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.toAlbumEntity
import com.udnahc.immichgallery.domain.model.toAssetEntityOrNull
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDomainOrNull
import com.udnahc.immichgallery.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AlbumRepository(
    private val database: AppDatabase,
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
                entities.mapNotNull { it.toDomainOrNull(base) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // --- Suspend reads ---

    suspend fun hasCachedAlbums(): Boolean =
        withContext(Dispatchers.IO) {
            albumDao.getAlbumCount() > 0
        }

    suspend fun hasCachedAlbumAssets(albumId: String): Boolean =
        withContext(Dispatchers.IO) {
            albumDao.getAlbumAssetCount(albumId) > 0
        }

    suspend fun getCachedAlbumName(albumId: String): String? {
        return withContext(Dispatchers.IO) {
            albumDao.getAlbum(albumId)?.name
        }
    }

    suspend fun getAlbumAssets(albumId: String): List<Asset> {
        val base = baseUrl()
        return withContext(Dispatchers.IO) {
            albumDao.getAlbumAssets(albumId).mapNotNull { it.toDomainOrNull(base) }
        }
    }

    suspend fun getLastSyncedAt(): Long? {
        return withContext(Dispatchers.IO) {
            syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_ALBUMS)
        }
    }

    suspend fun getAlbumDetailLastSyncedAt(albumId: String): Long? {
        return withContext(Dispatchers.IO) {
            syncMetadataDao.getLastSyncedAt("$SYNC_SCOPE_ALBUM_PREFIX$albumId")
        }
    }

    // --- Network sync ---

    suspend fun syncAlbums(): Result<Unit> {
        return runSuspendCatching {
            val responses = apiService.getAlbums()
            val entities = responses.map { it.toAlbumEntity() }
            withContext(Dispatchers.IO) {
                database.useWriterConnection { transactor ->
                    transactor.immediateTransaction {
                        albumDao.replaceAlbums(entities)
                        syncMetadataDao.upsert(
                            SyncMetadataEntity(SYNC_SCOPE_ALBUMS, currentEpochMillis())
                        )
                    }
                }
            }
            Unit
        }
    }

    suspend fun syncAlbumAssetsPage(
        albumId: String,
        page: Int,
        order: String? = null,
        size: Int = ALBUM_PAGE_SIZE
    ): Result<AlbumDetailSyncResult> {
        return runSuspendCatching {
            val before = withContext(Dispatchers.IO) {
                albumDao.getAlbumAssets(albumId)
            }
            val detail = if (page == 1) apiService.getAlbumDetail(albumId) else null
            val resolvedOrder = normalizeAlbumOrder(detail?.order ?: order)
            val response = apiService.getAlbumAssets(albumId, page, size, resolvedOrder)
            val nextPage = response.assets.nextPageNumberOrNull()
            val supportedAssets = response.assets.items.filter { it.toAssetEntityOrNull() != null }
            val hasMore = nextPage != null
            val assetEntities = supportedAssets.mapNotNull { it.toAssetEntityOrNull() }
            val baseOffset = (page - 1) * size
            val pageChanged = pagedOwnerPageChanged(
                beforeAssetIds = before.map { it.id },
                baseOffset = baseOffset,
                fetchedAssetIds = supportedAssets.map { it.id },
                hasMore = hasMore
            )
            val crossRefs = supportedAssets.mapIndexed { index, asset ->
                AlbumAssetCrossRef(albumId, asset.id, baseOffset + index)
            }
            val cachedAlbum = withContext(Dispatchers.IO) {
                albumDao.getAlbum(albumId)
            }
            val detailAlbum = detail?.let {
                AlbumEntity(
                    id = it.id,
                    name = it.albumName,
                    assetCount = it.assetCount,
                    thumbnailAssetId = cachedAlbum?.thumbnailAssetId,
                    updatedAt = cachedAlbum?.updatedAt ?: ""
                )
            }
            withContext(Dispatchers.IO) {
                database.useWriterConnection { transactor ->
                    transactor.immediateTransaction {
                        if (detailAlbum != null) {
                            albumDao.upsertAlbums(listOf(detailAlbum))
                        }
                        assetDao.upsertAssets(assetEntities)
                        if (pageChanged || !hasMore) {
                            albumDao.replaceAlbumRefsFromSortOrder(
                                albumId,
                                baseOffset,
                                crossRefs
                            )
                        } else {
                            albumDao.replaceAlbumRefsInSortRange(
                                albumId = albumId,
                                startSortOrder = baseOffset,
                                endSortOrder = baseOffset + size,
                                refs = crossRefs
                            )
                        }
                        if (!hasMore) {
                            syncMetadataDao.upsert(
                                SyncMetadataEntity(
                                    "$SYNC_SCOPE_ALBUM_PREFIX$albumId",
                                    currentEpochMillis()
                                )
                            )
                        }
                    }
                }
            }
            editsEnricher.enrich(supportedAssets)
            val after = withContext(Dispatchers.IO) {
                albumDao.getAlbumAssets(albumId)
            }
            AlbumDetailSyncResult(
                albumName = detail?.albumName ?: cachedAlbum?.name.orEmpty(),
                changed = orderedAssetsChanged(before, after),
                nextPage = nextPage,
                order = resolvedOrder
            )
        }
    }

    suspend fun syncAllAlbumAssets(albumId: String): Result<AlbumDetailSyncResult> {
        return runSuspendCatching {
            val before = withContext(Dispatchers.IO) {
                albumDao.getAlbumAssets(albumId)
            }
            val detail = apiService.getAlbumDetail(albumId)
            val order = normalizeAlbumOrder(detail.order)
            val allItems = mutableListOf<AssetResponse>()
            var page = 1
            while (true) {
                val response = apiService.getAlbumAssets(albumId, page, ALBUM_PAGE_SIZE, order)
                allItems += response.assets.items.filter { it.toAssetEntityOrNull() != null }
                page = response.assets.nextPageNumberOrNull() ?: break
            }
            val assetEntities = allItems.mapNotNull { it.toAssetEntityOrNull() }
            val crossRefs = allItems.mapIndexed { index, asset ->
                AlbumAssetCrossRef(albumId, asset.id, index)
            }
            val cachedAlbum = withContext(Dispatchers.IO) {
                albumDao.getAlbum(albumId)
            }
            val detailAlbum = AlbumEntity(
                id = detail.id,
                name = detail.albumName,
                assetCount = detail.assetCount,
                thumbnailAssetId = cachedAlbum?.thumbnailAssetId,
                updatedAt = cachedAlbum?.updatedAt ?: ""
            )
            withContext(Dispatchers.IO) {
                database.useWriterConnection { transactor ->
                    transactor.immediateTransaction {
                        albumDao.upsertAlbums(listOf(detailAlbum))
                        assetDao.upsertAssets(assetEntities)
                        albumDao.replaceAlbumRefs(albumId, crossRefs)
                        syncMetadataDao.upsert(
                            SyncMetadataEntity(
                                "$SYNC_SCOPE_ALBUM_PREFIX$albumId",
                                currentEpochMillis()
                            )
                        )
                    }
                }
            }
            editsEnricher.enrich(allItems)
            val after = withContext(Dispatchers.IO) {
                albumDao.getAlbumAssets(albumId)
            }
            AlbumDetailSyncResult(
                albumName = detail.albumName,
                changed = orderedAssetsChanged(before, after),
                nextPage = null,
                order = order
            )
        }
    }

    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    albumDao.clearAlbums()
                    albumDao.clearAllAlbumRefs()
                }
            }
        }
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun normalizeAlbumOrder(order: String?): String =
        if (order.equals("asc", ignoreCase = true)) "asc" else "desc"

    companion object {
        const val SYNC_SCOPE_ALBUMS = "albums"
        const val SYNC_SCOPE_ALBUM_PREFIX = "album:"
        private const val ALBUM_PAGE_SIZE = 250
    }
}
