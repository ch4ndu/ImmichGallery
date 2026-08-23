package com.udnahc.immichgallery.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.MIGRATION_1_2
import com.udnahc.immichgallery.data.local.entity.AlbumAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.AlbumEntity
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.model.ImmichPaginationCompatibilityException
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

class AlbumRepositoryJvmTest {
    @Test
    fun albumPageForwardJumpClearsSkippedRawRefRange() = runTest {
        val engine = albumAssetEngine(
            searchPage(
                items = listOf("image-1" to "IMAGE", "image-2" to "IMAGE"),
                nextPage = "3"
            )
        )

        withRepository(engine) {
            seedAlbumRefs(
                "album-1",
                listOf(
                    "image-1" to 0,
                    "image-2" to 1,
                    "skipped-first" to 2,
                    "skipped-last" to 3,
                    "page-3" to 4
                )
            )

            val result = repository.syncAlbumAssetsPage("album-1", page = 1, size = 2).getOrThrow()

            assertEquals(true, result.changed)
            assertEquals(3, result.nextPage)
            assertEquals(
                listOf("image-1" to 0, "image-2" to 1, "page-3" to 4),
                database.albumDao().getAlbumAssetRefOrders("album-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun albumPageForwardJumpWithChangedWindowTruncatesTail() = runTest {
        val engine = albumAssetEngine(
            searchPage(
                items = listOf("image-1" to "IMAGE", "image-2" to "IMAGE"),
                nextPage = "3"
            )
        )

        withRepository(engine) {
            seedAlbumRefs(
                "album-1",
                listOf(
                    "old-1" to 0,
                    "old-2" to 1,
                    "skipped-first" to 2,
                    "skipped-last" to 3,
                    "page-3" to 4
                )
            )

            val result = repository.syncAlbumAssetsPage("album-1", page = 1, size = 2).getOrThrow()

            assertEquals(true, result.changed)
            assertEquals(3, result.nextPage)
            assertEquals(
                listOf("image-1" to 0, "image-2" to 1),
                database.albumDao().getAlbumAssetRefOrders("album-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun albumPageSelfHealsCompactRefsWithoutReportingContentChange() = runTest {
        val engine = albumAssetEngine(
            searchPage(
                items = listOf(
                    "image-1" to "IMAGE",
                    "audio-1" to "AUDIO",
                    "video-1" to "VIDEO"
                )
            )
        )

        withRepository(engine) {
            seedAlbumRefs("album-1", listOf("image-1" to 0, "video-1" to 1))

            val result = repository.syncAlbumAssetsPage("album-1", page = 1, size = 4).getOrThrow()

            assertEquals(false, result.changed)
            assertEquals(null, result.nextPage)
            assertEquals(
                listOf("image-1" to 0, "video-1" to 2),
                database.albumDao().getAlbumAssetRefOrders("album-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun albumPageRejectsOverflowingForwardTokenAndPreservesCachedRefs() = runTest {
        val engine = albumAssetEngine(
            searchPage(items = listOf("new-asset" to "IMAGE"), nextPage = "8589936")
        )

        withRepository(engine) {
            seedAlbumRefs("album-1", listOf("old-asset" to 0))

            val result = repository.syncAlbumAssetsPage("album-1", page = 1, size = 250)

            assertTrue(result.isFailure)
            assertIs<ImmichPaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(
                listOf("old-asset" to 0),
                database.albumDao().getAlbumAssetRefOrders("album-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun albumFullRefreshAcceptsForwardTerminalPageSequence() = runTest {
        val engine = albumAssetEngine(
            searchPage(items = listOf("image-1" to "IMAGE"), nextPage = "3"),
            searchPage(items = listOf("video-1" to "VIDEO"))
        )

        withRepository(engine) {
            seedAlbumRefs("album-1", listOf("old-asset" to 0))

            val result = repository.syncAllAlbumAssets("album-1").getOrThrow()

            assertEquals(true, result.changed)
            assertEquals(null, result.nextPage)
            assertEquals(
                listOf("image-1" to 0, "video-1" to 500),
                database.albumDao().getAlbumAssetRefOrders("album-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun albumFullRefreshRejectsCycleAndPreservesCachedRefs() = runTest {
        val engine = albumAssetEngine(
            searchPage(items = listOf("new-1" to "IMAGE"), nextPage = "2"),
            searchPage(items = listOf("new-2" to "IMAGE"), nextPage = "1")
        )

        withRepository(engine) {
            seedAlbumRefs("album-1", listOf("old-asset" to 0))

            val result = repository.syncAllAlbumAssets("album-1")

            assertTrue(result.isFailure)
            assertIs<ImmichPaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(
                listOf("old-asset" to 0),
                database.albumDao().getAlbumAssetRefOrders("album-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    @Test
    fun albumFullRefreshRejectsOverflowingForwardTokenBeforeNextSearch() = runTest {
        var metadataRequests = 0
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/albums/album-1" -> albumDetail()
                "/api/search/metadata" -> {
                    metadataRequests++
                    searchPage(items = listOf("new-asset" to "IMAGE"), nextPage = "8589936")
                }
                else -> error("Unexpected album request ${request.url}")
            }
            respond(body, headers = jsonHeaders)
        }

        withRepository(engine) {
            seedAlbumRefs("album-1", listOf("old-asset" to 0))

            val result = repository.syncAllAlbumAssets("album-1")

            assertTrue(result.isFailure)
            assertIs<ImmichPaginationCompatibilityException>(result.exceptionOrNull())
            assertEquals(1, metadataRequests)
            assertEquals(
                listOf("old-asset" to 0),
                database.albumDao().getAlbumAssetRefOrders("album-1").map { it.assetId to it.sortOrder }
            )
        }
    }

    private suspend fun withRepository(
        engine: MockEngine,
        block: suspend RepositoryHarness.() -> Unit
    ) {
        val tempDir = Files.createTempDirectory("immichgallery-album-test").toFile()
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
        val repository = AlbumRepository(
            database = database,
            apiService = service,
            albumDao = database.albumDao(),
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
        val repository: AlbumRepository,
        val client: HttpClient,
        val tempDir: File
    )

    private suspend fun RepositoryHarness.seedAlbumRefs(
        albumId: String,
        refs: List<Pair<String, Int>>
    ) {
        database.albumDao().upsertAlbums(
            listOf(
                AlbumEntity(
                    id = albumId,
                    name = albumId,
                    assetCount = refs.size,
                    thumbnailAssetId = null,
                    updatedAt = ""
                )
            )
        )
        database.assetDao().upsertAssets(refs.map { (assetId, _) -> assetEntity(assetId) })
        database.albumDao().replaceAlbumRefs(
            albumId,
            refs.map { (assetId, sortOrder) -> AlbumAssetCrossRef(albumId, assetId, sortOrder) }
        )
    }

    private fun albumAssetEngine(vararg bodies: String): MockEngine {
        val responses = ArrayDeque(bodies.toList())
        return MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/albums/album-1" -> albumDetail()
                "/api/search/metadata" -> responses.removeFirst()
                else -> error("Unexpected album request ${request.url}")
            }
            respond(body, headers = jsonHeaders)
        }
    }

    private fun albumDetail(): String =
        """{"id":"album-1","albumName":"album-1","assetCount":0,"order":"desc"}"""

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
        val jsonHeaders = headersOf(
            HttpHeaders.ContentType,
            ContentType.Application.Json.toString()
        )
    }
}
