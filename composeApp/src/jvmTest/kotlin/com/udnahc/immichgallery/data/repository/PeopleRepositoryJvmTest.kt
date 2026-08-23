package com.udnahc.immichgallery.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.MIGRATION_1_2
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.PersonAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.PersonEntity
import com.udnahc.immichgallery.data.remote.HttpClientFactory
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.test.TestSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeopleRepositoryJvmTest {
    @Test
    fun peopleSyncFollowsOmittedFlagUntilTotalAndKeepsHiddenRows() = runTest {
        val requestedPages = mutableListOf<Int>()
        val engine = MockEngine { request ->
            val page = request.url.parameters["page"]?.toInt()
            requestedPages += checkNotNull(page)
            val body = when (page) {
                1 -> peoplePage(
                    ids = (1..500).map { "person-$it" },
                    total = 501,
                    hidden = 1
                )
                2 -> peoplePage(
                    ids = listOf("person-501"),
                    total = 501,
                    hidden = 1,
                    hiddenIds = setOf("person-501")
                )
                else -> error("Unexpected people page $page")
            }
            respond(body, headers = jsonHeaders)
        }

        withRepository(engine) {
            val result = repository.syncPeople()

            assertTrue(result.isSuccess)
            assertEquals(listOf(1, 2), requestedPages)
            assertEquals(501, database.personDao().getPersonIds().size)
            assertEquals(500, database.personDao().getPeopleCount())
        }
    }

    @Test
    fun peopleSyncAcceptsExplicitTrueThenSatisfiedFalse() = runTest {
        val requestedPages = mutableListOf<Int>()
        val bodies = ArrayDeque(
            listOf(
                peoplePage(ids = listOf("person-1"), total = 2, hidden = 0, hasNextPage = true),
                peoplePage(ids = listOf("person-2"), total = 2, hidden = 0, hasNextPage = false)
            )
        )
        val engine = MockEngine { request ->
            requestedPages += checkNotNull(request.url.parameters["page"]?.toInt())
            respond(bodies.removeFirst(), headers = jsonHeaders)
        }

        withRepository(engine) {
            val result = repository.syncPeople()

            assertTrue(result.isSuccess)
            assertEquals(listOf(1, 2), requestedPages)
            assertEquals(listOf("person-1", "person-2"), database.personDao().getPersonIds())
        }
    }

    @Test
    fun peopleSyncFalseBelowTotalPreservesCachedPeopleAndRefs() = runTest {
        val engine = MockEngine {
            respond(
                peoplePage(
                    ids = listOf("new-person"),
                    total = 2,
                    hidden = 0,
                    hasNextPage = false
                ),
                headers = jsonHeaders
            )
        }

        withRepository(engine) {
            seedPeople("old-person")
            seedPersonRefs("old-person", listOf("old-asset" to 0))

            val result = repository.syncPeople()

            assertTrue(result.isFailure)
            assertIs<PeoplePaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(listOf("old-person"), database.personDao().getPersonIds())
            assertEquals(
                listOf("old-asset" to 0),
                database.personDao().getPersonAssetRefOrders("old-person").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun peopleSyncDuplicateOnlyContinuationPreservesCachedPeopleAndRefs() = runTest {
        val bodies = ArrayDeque(
            listOf(
                peoplePage(ids = listOf("new-person"), total = 2, hidden = 0, hasNextPage = true),
                peoplePage(ids = listOf("new-person"), total = 2, hidden = 0, hasNextPage = false)
            )
        )
        val engine = MockEngine {
            respond(bodies.removeFirst(), headers = jsonHeaders)
        }

        withRepository(engine) {
            seedPeople("old-person")
            seedPersonRefs("old-person", listOf("old-asset" to 0))

            val result = repository.syncPeople()

            assertTrue(result.isFailure)
            assertIs<PeoplePaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(listOf("old-person"), database.personDao().getPersonIds())
            assertEquals(
                listOf("old-asset" to 0),
                database.personDao().getPersonAssetRefOrders("old-person").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun peopleSyncEmptyContinuationPreservesCachedPeopleAndRefs() = runTest {
        val bodies = ArrayDeque(
            listOf(
                peoplePage(ids = listOf("new-person"), total = 2, hidden = 0, hasNextPage = true),
                peoplePage(ids = emptyList(), total = 2, hidden = 0, hasNextPage = false)
            )
        )
        val engine = MockEngine {
            respond(bodies.removeFirst(), headers = jsonHeaders)
        }

        withRepository(engine) {
            seedPeople("old-person")
            seedPersonRefs("old-person", listOf("old-asset" to 0))

            val result = repository.syncPeople()

            assertTrue(result.isFailure)
            assertIs<PeoplePaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(listOf("old-person"), database.personDao().getPersonIds())
            assertEquals(
                listOf("old-asset" to 0),
                database.personDao().getPersonAssetRefOrders("old-person").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun personPageSelfHealsCompactRefsWithoutReportingContentChange() = runTest {
        val engine = personAssetEngine(
            searchPage(
                items = listOf(
                    "image-1" to "IMAGE",
                    "audio-1" to "AUDIO",
                    "video-1" to "VIDEO"
                )
            )
        )

        withRepository(engine) {
            seedPeople("person-1")
            seedPersonRefs("person-1", listOf("image-1" to 0, "video-1" to 1))

            val result = repository.syncPersonAssetsPage("person-1", page = 1, size = 4).getOrThrow()

            assertEquals(false, result.changed)
            assertEquals(null, result.nextPage)
            assertEquals(
                listOf("image-1" to 0, "video-1" to 2),
                database.personDao().getPersonAssetRefOrders("person-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun personPageForwardJumpClearsSkippedRawRefRange() = runTest {
        val engine = personAssetEngine(
            searchPage(
                items = listOf("image-1" to "IMAGE", "image-2" to "IMAGE"),
                nextPage = "3"
            )
        )

        withRepository(engine) {
            seedPeople("person-1")
            seedPersonRefs(
                "person-1",
                listOf(
                    "image-1" to 0,
                    "image-2" to 1,
                    "skipped-first" to 2,
                    "skipped-last" to 3,
                    "page-3" to 4
                )
            )

            val result = repository.syncPersonAssetsPage("person-1", page = 1, size = 2).getOrThrow()

            assertEquals(true, result.changed)
            assertEquals(3, result.nextPage)
            assertEquals(
                listOf("image-1" to 0, "image-2" to 1, "page-3" to 4),
                database.personDao().getPersonAssetRefOrders("person-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun personPageRejectsOverflowingForwardTokenAndPreservesCachedRefs() = runTest {
        val engine = personAssetEngine(
            searchPage(items = listOf("new-asset" to "IMAGE"), nextPage = "8589936")
        )

        withRepository(engine) {
            seedPeople("person-1")
            seedPersonRefs("person-1", listOf("old-asset" to 0))

            val result = repository.syncPersonAssetsPage("person-1", page = 1, size = 250)

            assertTrue(result.isFailure)
            assertIs<PeoplePaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(
                listOf("old-asset" to 0),
                database.personDao().getPersonAssetRefOrders("person-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun personFullRefreshAcceptsForwardTerminalPageSequence() = runTest {
        val engine = personAssetEngine(
            searchPage(items = listOf("image-1" to "IMAGE"), nextPage = "3"),
            searchPage(items = listOf("video-1" to "VIDEO"))
        )

        withRepository(engine) {
            seedPeople("person-1")
            seedPersonRefs("person-1", listOf("old-asset" to 0))

            val result = repository.syncAllPersonAssets("person-1").getOrThrow()

            assertEquals(true, result.changed)
            assertEquals(null, result.nextPage)
            assertEquals(
                listOf("image-1" to 0, "video-1" to 500),
                database.personDao().getPersonAssetRefOrders("person-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun personFullRefreshRejectsCycleAndPreservesCachedRefs() = runTest {
        val engine = personAssetEngine(
            searchPage(items = listOf("new-1" to "IMAGE"), nextPage = "2"),
            searchPage(items = listOf("new-2" to "IMAGE"), nextPage = "1")
        )

        withRepository(engine) {
            seedPeople("person-1")
            seedPersonRefs("person-1", listOf("old-asset" to 0))

            val result = repository.syncAllPersonAssets("person-1")

            assertTrue(result.isFailure)
            assertIs<PeoplePaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(
                listOf("old-asset" to 0),
                database.personDao().getPersonAssetRefOrders("person-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun personFullRefreshRejectsOverflowingForwardTokenBeforeNextSearch() = runTest {
        var metadataRequests = 0
        val engine = MockEngine { request ->
            metadataRequests++
            respond(
                searchPage(items = listOf("new-asset" to "IMAGE"), nextPage = "8589936"),
                headers = jsonHeaders
            )
        }

        withRepository(engine) {
            seedPeople("person-1")
            seedPersonRefs("person-1", listOf("old-asset" to 0))

            val result = repository.syncAllPersonAssets("person-1")

            assertTrue(result.isFailure)
            assertIs<PeoplePaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(1, metadataRequests)
            assertEquals(
                listOf("old-asset" to 0),
                database.personDao().getPersonAssetRefOrders("person-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    private suspend fun withRepository(
        engine: MockEngine,
        block: suspend RepositoryHarness.() -> Unit
    ) {
        val tempDir = Files.createTempDirectory("immichgallery-people-test").toFile()
        val database = Room.databaseBuilder<AppDatabase>(
            name = File(tempDir, "test.db").absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_1_2)
            .build()
        val config = ServerConfigRepository(TestSettings()).apply {
            replaceSession("https://immich.example", "test-key")
        }
        val client = HttpClientFactory.create(engine)
        val service = ImmichApiService(client, config)
        val session = SessionStateRepository()
        val repository = PeopleRepository(
            database = database,
            apiService = service,
            personDao = database.personDao(),
            assetDao = database.assetDao(),
            syncMetadataDao = database.syncMetadataDao(),
            serverConfigRepository = config,
            editsEnricher = AssetEditsEnricher(service, database.assetDao(), session),
            detailMosaicCacheDao = database.detailMosaicCacheDao(),
            sessionStateRepository = session
        )
        try {
            RepositoryHarness(database, repository, client, tempDir).block()
        } finally {
            client.close()
            database.close()
            tempDir.deleteRecursively()
        }
    }

    private data class RepositoryHarness(
        val database: AppDatabase,
        val repository: PeopleRepository,
        val client: HttpClient,
        val tempDir: File
    )

    private suspend fun RepositoryHarness.seedPeople(vararg ids: String) {
        database.personDao().upsertPeople(
            ids.mapIndexed { index, id ->
                PersonEntity(id = id, name = id, isHidden = false, sortOrder = index)
            }
        )
    }

    private suspend fun RepositoryHarness.seedPersonRefs(
        personId: String,
        refs: List<Pair<String, Int>>
    ) {
        database.assetDao().upsertAssets(refs.map { (assetId, _) -> assetEntity(assetId) })
        database.personDao().replacePersonRefs(
            personId,
            refs.map { (assetId, sortOrder) -> PersonAssetCrossRef(personId, assetId, sortOrder) }
        )
    }

    private fun personAssetEngine(vararg bodies: String): MockEngine {
        val responses = ArrayDeque(bodies.toList())
        return MockEngine {
            respond(responses.removeFirst(), headers = jsonHeaders)
        }
    }

    private fun peoplePage(
        ids: List<String>,
        total: Int,
        hidden: Int,
        hasNextPage: Boolean? = null,
        hiddenIds: Set<String> = emptySet()
    ): String {
        val people = ids.joinToString(",") { id ->
            """{"id":"$id","name":"$id","isHidden":${id in hiddenIds}}"""
        }
        val flag = hasNextPage?.let { ""","hasNextPage":$it""" }.orEmpty()
        return """{"people":[$people],"total":$total,"hidden":$hidden$flag}"""
    }

    private fun searchPage(
        items: List<Pair<String, String>>,
        nextPage: String? = null
    ): String {
        val assets = items.joinToString(",") { (id, type) ->
            """{"id":"$id","type":"$type"}"""
        }
        val token = nextPage?.let { ""","nextPage":"$it"""" }.orEmpty()
        return """{"assets":{"items":[$assets]$token}}"""
    }

    private fun assetEntity(id: String): AssetEntity =
        AssetEntity(
            id = id,
            type = if (id.startsWith("video")) "VIDEO" else "IMAGE",
            fileName = "",
            createdAt = "",
            isFavorite = false,
            stackCount = 0,
            aspectRatio = 1f
        )

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
