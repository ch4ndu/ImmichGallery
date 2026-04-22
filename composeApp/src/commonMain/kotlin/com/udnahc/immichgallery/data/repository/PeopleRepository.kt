package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.PersonDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.entity.PersonAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.model.toAssetEntity
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toPersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PeopleRepository(
    private val apiService: ImmichApiService,
    private val personDao: PersonDao,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val serverConfigRepository: ServerConfigRepository,
    private val editsEnricher: AssetEditsEnricher
) {
    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')

    // --- Reactive Room reads ---

    fun observePeople(): Flow<List<Person>> =
        personDao.observePeople()
            .map { entities ->
                val base = baseUrl()
                entities.map { it.toDomain(base) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun observePersonAssets(personId: String): Flow<List<Asset>> =
        personDao.observePersonAssets(personId)
            .map { entities ->
                val base = baseUrl()
                entities.map { it.toDomain(base) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // --- Suspend reads ---

    suspend fun hasCachedPeople(): Boolean =
        personDao.getPeopleCount() > 0

    suspend fun hasCachedPersonAssets(personId: String): Boolean =
        personDao.getPersonAssetCount(personId) > 0

    suspend fun getPersonAssets(personId: String): List<Asset> {
        val base = baseUrl()
        return personDao.getPersonAssets(personId).map { it.toDomain(base) }
    }

    suspend fun getLastSyncedAt(): Long? {
        return syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_PEOPLE)
    }

    suspend fun getPersonDetailLastSyncedAt(personId: String): Long? {
        return syncMetadataDao.getLastSyncedAt("$SYNC_SCOPE_PERSON_PREFIX$personId")
    }

    // --- Network sync ---

    suspend fun syncPeople(): Result<Unit> {
        return try {
            val response = apiService.getPeople()
            val entities = response.people.mapIndexed { index, person -> person.toPersonEntity(index) }
            withContext(Dispatchers.IO) {
                personDao.replacePeople(entities)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(SYNC_SCOPE_PEOPLE, currentEpochMillis())
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Syncs a single page of person assets. Uses offset-based sortOrder
     * so appending pages doesn't require clearing previous pages.
     * Returns (hasMore).
     */
    suspend fun syncPersonAssetsPage(
        personId: String,
        page: Int,
        size: Int = 250
    ): Result<Boolean> {
        return try {
            val response = apiService.getPersonAssets(personId, page, size)
            val items = response.assets.items
            val hasMore = response.assets.nextPage != null
            val assetEntities = items.map { it.toAssetEntity() }
            val baseOffset = (page - 1) * size
            val crossRefs = items.mapIndexed { index, asset ->
                PersonAssetCrossRef(personId, asset.id, baseOffset + index)
            }
            withContext(Dispatchers.IO) {
                assetDao.upsertAssets(assetEntities)
                personDao.upsertPersonRefs(crossRefs)
                if (!hasMore) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity("$SYNC_SCOPE_PERSON_PREFIX$personId", currentEpochMillis())
                    )
                }
            }
            editsEnricher.enrich(items)
            Result.success(hasMore)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Full refresh: clears existing refs and re-fetches from page 1.
     */
    suspend fun syncAllPersonAssets(personId: String): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                personDao.clearPersonRefs(personId)
            }
            var page = 1
            while (true) {
                val hasMore = syncPersonAssetsPage(personId, page).getOrThrow()
                if (!hasMore) break
                page++
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val SYNC_SCOPE_PEOPLE = "people"
        const val SYNC_SCOPE_PERSON_PREFIX = "person:"
    }
}
