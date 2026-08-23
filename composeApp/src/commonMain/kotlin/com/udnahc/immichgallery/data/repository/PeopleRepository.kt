package com.udnahc.immichgallery.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.DetailMosaicCacheDao
import com.udnahc.immichgallery.data.local.dao.PersonDao
import com.udnahc.immichgallery.data.local.dao.PersonAssetRefOrder
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.entity.PersonAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.model.ImmichPaginationCompatibilityException
import com.udnahc.immichgallery.data.model.PeopleResponse
import com.udnahc.immichgallery.data.model.SearchResultAssets
import com.udnahc.immichgallery.data.model.nextPageNumberAfter
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.model.PersonAssetsSyncResult
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDomainOrNull
import com.udnahc.immichgallery.domain.model.toPersonEntity
import com.udnahc.immichgallery.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PeopleRepository(
    private val database: AppDatabase,
    private val apiService: ImmichApiService,
    private val personDao: PersonDao,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val serverConfigRepository: ServerConfigRepository,
    private val editsEnricher: AssetEditsEnricher,
    private val detailMosaicCacheDao: DetailMosaicCacheDao,
    private val sessionStateRepository: SessionStateRepository
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
                entities.mapNotNull { it.toDomainOrNull(base) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // --- Suspend reads ---

    suspend fun hasCachedPeople(): Boolean =
        withContext(Dispatchers.IO) {
            personDao.getPeopleCount() > 0
        }

    suspend fun hasCachedPersonAssets(personId: String): Boolean =
        withContext(Dispatchers.IO) {
            personDao.getPersonAssetCount(personId) > 0
        }

    suspend fun getPersonAssets(personId: String): List<Asset> {
        val base = baseUrl()
        return withContext(Dispatchers.IO) {
            personDao.getPersonAssets(personId).mapNotNull { it.toDomainOrNull(base) }
        }
    }

    suspend fun getLastSyncedAt(): Long? {
        return withContext(Dispatchers.IO) {
            syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_PEOPLE)
        }
    }

    suspend fun getPersonDetailLastSyncedAt(personId: String): Long? {
        return withContext(Dispatchers.IO) {
            syncMetadataDao.getLastSyncedAt("$SYNC_SCOPE_PERSON_PREFIX$personId")
        }
    }

    // --- Network sync ---

    suspend fun syncPeople(): Result<Unit> {
        return sessionStateRepository.withOperation { token -> runSuspendCatching {
            val people = mutableListOf<com.udnahc.immichgallery.data.model.PersonResponse>()
            val seenIds = mutableSetOf<String>()
            var page = 1
            while (true) {
                val response = apiService.getPeople(page, PEOPLE_PAGE_SIZE)
                val pageIds = response.people.map { it.id }
                val initialEmptyTerminal = page == 1 &&
                    response.total == 0 &&
                    response.hasNextPage != true
                if (pageIds.isEmpty() && !initialEmptyTerminal) {
                    peoplePaginationFailure()
                }
                val duplicateInPage = pageIds.toSet().size != pageIds.size
                val newIds = pageIds.filterNot { it in seenIds }
                if (duplicateInPage || newIds.size != pageIds.size) {
                    peoplePaginationFailure()
                }
                people += response.people
                seenIds += pageIds
                val loadNext = response.shouldLoadNextPeoplePage(rawCount = people.size)
                if (!loadNext) break
                page++
            }
            val entities = people.mapIndexed { index, person -> person.toPersonEntity(index) }
            sessionStateRepository.withCommit(token) {
                withContext(Dispatchers.IO) {
                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                        val removedPersonIds = personDao.getPersonIds().toSet() - entities.map { it.id }.toSet()
                        if (removedPersonIds.isNotEmpty()) {
                            val removedIds = removedPersonIds.toList()
                            personDao.clearPersonRefsForPeople(removedIds)
                            detailMosaicCacheDao.clearOwnerCaches(
                                DetailMosaicCacheOwnerType.PERSON.name,
                                removedIds
                            )
                            syncMetadataDao.clearScopes(
                                removedIds.map { "$SYNC_SCOPE_PERSON_PREFIX$it" }
                            )
                        }
                        personDao.replacePeople(entities)
                        syncMetadataDao.upsert(
                            SyncMetadataEntity(SYNC_SCOPE_PEOPLE, currentEpochMillis())
                        )
                        }
                    }
                }
            }
        } }
    }

    /**
     * Syncs a single page of person assets. Uses offset-based sortOrder
     * so appending pages doesn't require clearing previous pages.
     * Returns whether persisted ordered content changed plus hasMore. A page
     * sync can succeed without changing grid-visible content; detail screens
     * use that distinction to avoid unnecessary layout invalidation.
     */
    suspend fun syncPersonAssetsPage(
        personId: String,
        page: Int,
        size: Int = 250
    ): Result<PersonAssetsSyncResult> {
        return sessionStateRepository.withOperation { token -> runSuspendCatching {
            val pageWindow = peoplePaginationContract { detailRawPageWindow(page, size) }
            val baseRawOffset = pageWindow.startSortOrder
            val before = withContext(Dispatchers.IO) {
                personDao.getPersonAssets(personId)
            }
            val response = apiService.getPersonAssets(personId, page, size)
            val nextPage = response.assets.personNextPageNumberAfter(page)
            nextPage?.let { peoplePaginationContract { detailRawPageWindow(it, size) } }
            val rawItems = response.assets.items
            val supportedAssets = peoplePaginationContract {
                supportedDetailRawAssetResponses(rawItems, baseRawOffset)
            }
            val hasMore = nextPage != null
            val rawWindowEnd = if (hasMore) {
                pageWindow.endSortOrder
            } else {
                peoplePaginationContract { detailRawBoundaryAfter(baseRawOffset, rawItems.size) }
            }
            val skippedRange = peoplePaginationContract {
                detailSkippedForwardRawRange(page, nextPage, size)
            }
            val assetEntities = prepareDetailAssetEntities(
                supportedAssets.map { it.response },
                assetDao,
                editsEnricher
            )
            val fetchedRefs = detailRawAssetRefs(supportedAssets)
            val crossRefs = personRawCrossRefs(personId, supportedAssets)
            var changed = false
            sessionStateRepository.withCommit(token) {
                withContext(Dispatchers.IO) {
                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                        val existingWindowRefs = personDao.getPersonAssetRefOrdersInSortRange(
                            personId = personId,
                            startSortOrder = baseRawOffset,
                            endSortOrder = rawWindowEnd
                        ).personRefOrdersToDetailRefs()
                        val tailRefsAfterWindow = personDao.getPersonAssetRefOrdersFromSortOrder(
                            personId = personId,
                            startSortOrder = rawWindowEnd
                        ).personRefOrdersToDetailRefs()
                        val rawWindowChanged = detailRawWindowChanged(
                            existingWindowRefs = existingWindowRefs,
                            fetchedRefs = fetchedRefs,
                            tailRefsAfterWindow = tailRefsAfterWindow,
                            hasMore = hasMore
                        )
                        val skipAssetWrite = detailRawPageWriteUnchanged(
                            existingWindowRefs = existingWindowRefs,
                            fetchedRefs = fetchedRefs,
                            tailRefsAfterWindow = tailRefsAfterWindow,
                            existingAssetsById = before.associateBy { it.id },
                            candidates = assetEntities,
                            hasMore = hasMore
                        )
                        val skippedRefs = skippedRange
                            ?.takeIf { range -> range.startSortOrder < range.endSortOrder }
                            ?.let { range ->
                                personDao.getPersonAssetRefOrdersInSortRange(
                                    personId = personId,
                                    startSortOrder = range.startSortOrder,
                                    endSortOrder = range.endSortOrder
                                )
                            }
                            .orEmpty()
                        if (!skipAssetWrite) {
                            assetDao.upsertAssets(assetEntities)
                            if (rawWindowChanged) {
                                // If a fetched page changed, later cached pages may now be
                                // shifted. Truncate from this page and let loadMore refill a
                                // consistent snapshot instead of mixing old tail refs with
                                // fresh page refs.
                                personDao.replacePersonRefsFromSortOrder(
                                    personId,
                                    baseRawOffset,
                                    crossRefs
                                )
                            } else {
                                personDao.replacePersonRefsInSortRange(
                                    personId = personId,
                                    startSortOrder = baseRawOffset,
                                    endSortOrder = rawWindowEnd,
                                    refs = crossRefs
                                )
                            }
                        }
                        skippedRange
                            ?.takeIf { range ->
                                skippedRefs.isNotEmpty() && range.startSortOrder < range.endSortOrder
                            }
                            ?.let { range ->
                                personDao.clearPersonRefsInSortRange(
                                    personId = personId,
                                    startSortOrder = range.startSortOrder,
                                    endSortOrder = range.endSortOrder
                                )
                            }
                        val after = if (skipAssetWrite && skippedRefs.isEmpty()) {
                            before
                        } else {
                            personDao.getPersonAssets(personId)
                        }
                        changed = orderedAssetsChanged(before, after)
                        if (changed) {
                            detailMosaicCacheDao.clearOwnerCache(
                                DetailMosaicCacheOwnerType.PERSON.name,
                                personId
                            )
                        }
                        if (!hasMore) {
                            syncMetadataDao.upsert(
                                SyncMetadataEntity(
                                    "$SYNC_SCOPE_PERSON_PREFIX$personId",
                                    currentEpochMillis()
                                )
                            )
                        }
                        }
                    }
                }
            }
            PersonAssetsSyncResult(
                changed = changed,
                nextPage = nextPage
            )
        } }
    }

    /**
     * Full refresh: fetch all pages over the network into memory first, then
     * atomically swap Room state. Phase-ordered so Room Flow observers never
     * see an intermediate "cleared" state — otherwise the person grid would
     * flash empty and fill back in page-by-page.
     *
     * Memory cost: ~all items for the person held in RAM during the sync.
     * At 250 items/page and a few pages per person, this is a few hundred KB.
     */
    suspend fun syncAllPersonAssets(personId: String): Result<PersonAssetsSyncResult> {
        return sessionStateRepository.withOperation { token -> runSuspendCatching {
            val before = withContext(Dispatchers.IO) {
                personDao.getPersonAssets(personId)
            }
            val beforeRefs = withContext(Dispatchers.IO) {
                personDao.getPersonAssetRefOrders(personId).personRefOrdersToDetailRefs()
            }
            val pageSize = 250
            val allItems = mutableListOf<DetailRawAssetResponse>()
            val consumedPages = mutableSetOf<Int>()
            var page = 1
            while (true) {
                val pageWindow = peoplePaginationContract { detailRawPageWindow(page, pageSize) }
                consumedPages += page
                val response = apiService.getPersonAssets(personId, page, pageSize)
                allItems += peoplePaginationContract {
                    supportedDetailRawAssetResponses(
                        response.assets.items,
                        pageWindow.startSortOrder
                    )
                }
                val nextPage = response.assets.personNextPageNumberAfter(
                    currentPage = page,
                    consumedPages = consumedPages
                )
                nextPage?.let { peoplePaginationContract { detailRawPageWindow(it, pageSize) } }
                page = nextPage ?: break
            }
            val assetEntities = prepareDetailAssetEntities(
                allItems.map { it.response },
                assetDao,
                editsEnricher
            )
            val fetchedRefs = detailRawAssetRefs(allItems)
            val crossRefs = personRawCrossRefs(personId, allItems)
            var changed = false
            sessionStateRepository.withCommit(token) {
                withContext(Dispatchers.IO) {
                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                        val skipAssetWrite = detailRawFullWriteUnchanged(
                            existingRefs = beforeRefs,
                            fetchedRefs = fetchedRefs,
                            existingAssetsById = before.associateBy { it.id },
                            candidates = assetEntities
                        )
                        if (!skipAssetWrite) {
                            assetDao.upsertAssets(assetEntities)
                            personDao.replacePersonRefs(personId, crossRefs)
                        }
                        val after = if (skipAssetWrite) before else personDao.getPersonAssets(personId)
                        changed = !skipAssetWrite && orderedAssetsChanged(before, after)
                        if (changed) {
                            detailMosaicCacheDao.clearOwnerCache(
                                DetailMosaicCacheOwnerType.PERSON.name,
                                personId
                            )
                        }
                        syncMetadataDao.upsert(
                            SyncMetadataEntity(
                                "$SYNC_SCOPE_PERSON_PREFIX$personId",
                                currentEpochMillis()
                            )
                        )
                        }
                    }
                }
            }
            PersonAssetsSyncResult(
                changed = changed,
                nextPage = null
            )
        } }
    }

    internal suspend fun clearCacheForSessionMutation() {
        withContext(Dispatchers.IO) {
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    personDao.clearPeople()
                    personDao.clearAllPersonRefs()
                }
            }
        }
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val SYNC_SCOPE_PEOPLE = "people"
        const val SYNC_SCOPE_PERSON_PREFIX = "person:"
        private const val PEOPLE_PAGE_SIZE = 500
    }
}

class PeoplePaginationCompatibilityException internal constructor() :
    ImmichPaginationCompatibilityException("Invalid Immich people pagination response")

private fun peoplePaginationFailure(): Nothing =
    throw PeoplePaginationCompatibilityException()

private inline fun <T> peoplePaginationContract(block: () -> T): T =
    try {
        block()
    } catch (_: ImmichPaginationCompatibilityException) {
        peoplePaginationFailure()
    }

private fun PeopleResponse.shouldLoadNextPeoplePage(rawCount: Int): Boolean {
    if (rawCount > total) {
        peoplePaginationFailure()
    }
    return when (hasNextPage) {
        true -> true
        false -> {
            if (rawCount != total) {
                peoplePaginationFailure()
            }
            false
        }
        null -> rawCount < total
    }
}

private fun SearchResultAssets.personNextPageNumberAfter(
    currentPage: Int,
    consumedPages: Set<Int> = setOf(currentPage)
): Int? =
    try {
        nextPageNumberAfter(currentPage, consumedPages)
    } catch (_: ImmichPaginationCompatibilityException) {
        peoplePaginationFailure()
    }

internal fun personRawCrossRefs(
    personId: String,
    items: List<DetailRawAssetResponse>
): List<PersonAssetCrossRef> =
    items.map { item ->
        PersonAssetCrossRef(personId, item.response.id, item.sortOrder)
    }

private fun List<PersonAssetRefOrder>.personRefOrdersToDetailRefs(): List<DetailRawAssetRef> =
    map { ref -> DetailRawAssetRef(ref.assetId, ref.sortOrder) }
