package com.udnahc.immichgallery.data.repository

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.MIGRATION_1_2
import com.udnahc.immichgallery.data.local.entity.AlbumAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.AlbumEntity
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.PersonAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.model.AssetResponse
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlbumRepositoryJvmTest {
    @Test
    fun timelineRevalidatesAlbumOnlyEditsAndKeepsAnUnchangedRefreshStable() = runTest {
        var detailRequests = 0
        var editRequests = 0
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/server/version" -> serverVersion
                "/api/timeline/bucket" -> timelineBucket(listOf("edited", "plain"))
                "/api/assets/edited" -> {
                    detailRequests++
                    assetDetail("edited", isEdited = true)
                }
                "/api/assets/edited/edits" -> {
                    editRequests++
                    croppedEdits
                }
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
            respond(body, headers = jsonHeaders)
        }
        withRepository(engine) {
            seedAlbumRefs("album-1", listOf("edited" to 0, "plain" to 1))
            database.assetDao().upsertAssets(listOf(
                assetEntity("edited").copy(fileName = "edited.jpg", isEdited = true, editsResolved = true, aspectRatio = 0.75f),
                assetEntity("plain").copy(fileName = "plain.jpg")
            ))

            assertTrue(timelineRepository.syncBucketAssets("2026-01").getOrThrow().changed)
            val assets = database.assetDao().getTimelineAssets("2026-01")
            assertEquals(listOf("edited.jpg", "plain.jpg"), assets.map { it.fileName })
            assertTrue(assets.first().isEdited)
            assertTrue(assets.first().editsResolved)
            assertEquals(0.5f, assets.first().aspectRatio)
            assertEquals(false, timelineRepository.syncBucketAssets("2026-01").getOrThrow().changed)
            assertEquals(2, detailRequests)
            assertEquals(2, editRequests)
        }
    }

    @Test
    fun timelineClearsKnownEditOnlyAfterAuthoritativeDetailResponse() = runTest {
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/server/version" -> serverVersion
                "/api/timeline/bucket" -> timelineBucket(listOf("edited"))
                "/api/assets/edited" -> assetDetail("edited", isEdited = false)
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
            respond(body, headers = jsonHeaders)
        }
        withRepository(engine) {
            seedTimelineEditedAsset()

            assertTrue(timelineRepository.syncBucketAssets("2026-01").getOrThrow().changed)

            val asset = database.assetDao().getTimelineAssets("2026-01").single()
            assertEquals(false, asset.isEdited)
            assertEquals(false, asset.editsResolved)
            assertEquals(2f, asset.aspectRatio)
            assertEquals("edited.jpg", asset.fileName)
        }
    }

    @Test
    fun timelineFailedEditRefreshPreservesEntireCachedBucket() = runTest {
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/server/version" -> serverVersion
                "/api/timeline/bucket" -> timelineBucket(listOf("new", "edited"))
                "/api/assets/edited" -> assetDetail("edited", isEdited = true)
                "/api/assets/edited/edits" -> error("Unavailable edits")
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
            respond(body, headers = jsonHeaders)
        }
        withRepository(engine) {
            seedTimelineEditedAsset()
            val before = database.assetDao().getTimelineAssets("2026-01")

            assertTrue(timelineRepository.syncBucketAssets("2026-01").isFailure)

            assertEquals(before, database.assetDao().getTimelineAssets("2026-01"))
            assertTrue(database.assetDao().getAssetsByIds(listOf("new")).isEmpty())
        }
    }

    @Test
    fun timelineConcurrentSharedMetadataChangePreservesNewerCache() = runTest {
        val detailStarted = CompletableDeferred<Unit>()
        val releaseDetail = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/server/version" -> serverVersion
                "/api/timeline/bucket" -> timelineBucket(listOf("edited"))
                "/api/assets/edited" -> {
                    detailStarted.complete(Unit)
                    releaseDetail.await()
                    assetDetail("edited", isEdited = false)
                }
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
            respond(body, headers = jsonHeaders)
        }
        withRepository(engine) {
            seedTimelineEditedAsset()
            val pending = async { timelineRepository.syncBucketAssets("2026-01") }
            detailStarted.await()
            val newer = database.assetDao().getTimelineAssets("2026-01").single()
                .copy(fileName = "newer.jpg", aspectRatio = 0.25f)
            database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    database.assetDao().upsertAssetsRefreshingTimelineSnapshots(listOf(newer))
                }
            }
            releaseDetail.complete(Unit)

            assertTrue(pending.await().isFailure)
            assertEquals(listOf(newer), database.assetDao().getTimelineAssets("2026-01"))
        }
    }

    @Test
    fun timelineEditedRevalidationSharesOneRequestBoundAcrossBuckets() = runTest {
        val activeRequests = AtomicInteger()
        val peakRequests = AtomicInteger()
        val detailRequests = AtomicInteger()
        val editRequests = AtomicInteger()
        val idsByBucket = mapOf(
            "2026-01" to List(16) { "first-$it" },
            "2026-02" to List(16) { "second-$it" }
        )
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path == "/api/server/version" -> serverVersion
                path == "/api/timeline/bucket" -> timelineBucket(idsByBucket.getValue(requireNotNull(request.url.parameters["timeBucket"])))
                path.startsWith("/api/assets/") -> {
                    val active = activeRequests.incrementAndGet()
                    peakRequests.updateAndGet { maxOf(it, active) }
                    try {
                        // Keep requests overlapping so a per-bucket semaphore would exceed the cap.
                        delay(25)
                        if (path.endsWith("/edits")) {
                            editRequests.incrementAndGet()
                            croppedEdits
                        } else {
                            detailRequests.incrementAndGet()
                            assetDetail(path.substringAfterLast('/'), isEdited = true)
                        }
                    } finally {
                        activeRequests.decrementAndGet()
                    }
                }
                else -> error("Unexpected request $path")
            }
            respond(body, headers = jsonHeaders)
        }
        withRepository(engine) {
            database.assetDao().upsertAssets(idsByBucket.values.flatten().map { id ->
                assetEntity(id).copy(isEdited = true, editsResolved = true, aspectRatio = 0.75f)
            })

            idsByBucket.keys.map { bucket ->
                async { timelineRepository.syncBucketAssets(bucket).getOrThrow() }
            }.awaitAll()

            assertEquals(32, detailRequests.get())
            assertEquals(32, editRequests.get())
            assertTrue(peakRequests.get() in 2..8, "Peak additional requests: ${peakRequests.get()}")
        }
    }

    @Test
    fun removedAssetCandidatesRespectEveryOwnerAndRollbackAtomically() = runTest {
        withRepository(albumAssetEngine()) {
            val ids = listOf("timeline", "album", "person", "unreferenced", "unrelated")
            database.assetDao().upsertAssets(ids.map(::assetEntity))
            database.timelineDao().replaceBucketRefs("2026-01", listOf(TimelineAssetCrossRef("2026-01", "timeline", 0)))
            database.albumDao().replaceAlbumRefs("album-1", listOf(AlbumAssetCrossRef("album-1", "album", 0)))
            database.personDao().replacePersonRefs("person-1", listOf(PersonAssetCrossRef("person-1", "person", 0)))
            database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    database.assetDao().pruneRemovedAssetCandidates(ids - "unrelated")
                }
            }
            assertEquals(setOf("timeline", "album", "person", "unrelated"), database.assetDao().getAssetsByIds(ids).map { it.id }.toSet())

            assertFailsWith<IllegalStateException> {
                database.useWriterConnection { writer ->
                    writer.immediateTransaction {
                        database.personDao().clearPersonRefs("person-1")
                        database.assetDao().pruneRemovedAssetCandidates(listOf("person"))
                        error("Roll back reference removal and pruning")
                    }
                }
            }
            assertEquals(listOf("person"), database.personDao().getPersonAssetRefOrders("person-1").map { it.assetId })
            assertEquals(1, database.assetDao().getAssetsByIds(listOf("person")).size)

            database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    database.timelineDao().clearTimelineRefsForBucket("2026-01")
                    database.albumDao().clearAlbumRefs("album-1")
                    database.personDao().clearPersonRefs("person-1")
                    database.assetDao().pruneRemovedAssetCandidates(ids - "unrelated")
                }
            }
            assertEquals(listOf("unrelated"), database.assetDao().getAssetsByIds(ids).map { it.id })
        }
    }

    @Test
    fun timelineRemovedBucketPrunesItsLastReferencesButKeepsAlbumAssets() = runTest {
        val engine = MockEngine { request ->
            respond(when (request.url.encodedPath) {
                "/api/server/version" -> serverVersion
                "/api/timeline/buckets" -> "[]"
                else -> error("Unexpected request ${request.url.encodedPath}")
            }, headers = jsonHeaders)
        }
        withRepository(engine) {
            seedAlbumRefs("album-1", listOf("shared" to 0))
            database.assetDao().upsertAssets(listOf(assetEntity("only-timeline")))
            database.timelineDao().replaceBuckets(listOf(TimelineBucketEntity("2026-01", "", 2, 0)))
            database.timelineDao().replaceBucketRefs("2026-01", listOf(
                TimelineAssetCrossRef("2026-01", "shared", 0),
                TimelineAssetCrossRef("2026-01", "only-timeline", 1)
            ))

            timelineRepository.syncBuckets().getOrThrow()

            assertEquals(listOf("shared"), database.assetDao().getAssetsByIds(listOf("shared", "only-timeline")).map { it.id })
        }
    }

    @Test
    fun fullOwnerPreparationAboveSqliteBindLimitRetainsCachedEditAspects() = runTest {
        withRepository(albumAssetEngine()) {
            // One more than the bundled SQLite limit. Only boundary rows need persisted data.
            val assetCount = 32_767
            val editedIds = setOf("image-0", "image-${assetCount - 1}")
            val responses = List(assetCount) { index ->
                val id = "image-$index"
                AssetResponse(
                    id = id,
                    isEdited = id in editedIds,
                    width = 400,
                    height = 300
                )
            }
            database.assetDao().upsertAssets(
                editedIds.map { id ->
                    assetEntity(id).copy(isEdited = true, editsResolved = true, aspectRatio = 0.5f)
                }
            )

            val prepared = prepareDetailAssetEntities(responses, database.assetDao(), editsEnricher)

            assertEquals(assetCount, prepared.size)
            assertEquals(responses.map { it.id }, prepared.map { it.id })
            assertTrue(prepared.first().editsResolved)
            assertTrue(prepared.last().editsResolved)
            assertEquals(0.5f, prepared.first().aspectRatio)
            assertEquals(0.5f, prepared.last().aspectRatio)
        }
    }

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
            assertTrue(database.assetDao().getAssetsByIds(listOf("old-1", "old-2", "skipped-first", "skipped-last", "page-3")).isEmpty())
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
            assertTrue(database.assetDao().getAssetsByIds(listOf("old-asset")).isEmpty())
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
        val editsEnricher = AssetEditsEnricher(service, database.assetDao(), session)
        val repository = AlbumRepository(
            database = database,
            apiService = service,
            albumDao = database.albumDao(),
            assetDao = database.assetDao(),
            syncMetadataDao = database.syncMetadataDao(),
            serverConfigRepository = config,
            editsEnricher = editsEnricher,
            detailMosaicCacheDao = database.detailMosaicCacheDao(),
            sessionStateRepository = session
        )
        val timelineRepository = TimelineRepository(
            database, service, database.timelineDao(), database.assetDao(),
            database.syncMetadataDao(), config, editsEnricher, session
        )
        try {
            RepositoryHarness(database, repository, client, tempDir, editsEnricher, timelineRepository).block()
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
        val tempDir: File,
        val editsEnricher: AssetEditsEnricher,
        val timelineRepository: TimelineRepository
    )

    private suspend fun RepositoryHarness.seedTimelineEditedAsset() {
        database.assetDao().upsertAssets(listOf(
            assetEntity("edited").copy(fileName = "edited.jpg", isEdited = true, editsResolved = true, aspectRatio = 0.5f)
        ))
        database.timelineDao().replaceBucketRefs("2026-01", listOf(TimelineAssetCrossRef("2026-01", "edited", 0)))
    }

    private fun assetDetail(id: String, isEdited: Boolean): String =
        """{"id":"$id","type":"IMAGE","originalFileName":"$id.jpg","isEdited":$isEdited,"width":400,"height":200}"""

    private fun timelineBucket(ids: List<String>): String {
        fun column(value: String) = ids.joinToString(",", "[", "]") { value }
        val encodedIds = ids.joinToString(",", "[", "]") { "\"$it\"" }
        return """{
            "id":$encodedIds,"createdAt":${column("\"\"")},"fileCreatedAt":${column("\"\"")},
            "duration":${column("null")},"isImage":${column("true")},"isFavorite":${column("false")},
            "isTrashed":${column("false")},"livePhotoVideoId":${column("null")},
            "localOffsetHours":${column("0")},"ownerId":${column("\"owner\"")},
            "projectionType":${column("null")},"thumbhash":${column("null")},
            "ratio":${column("2.0")},"visibility":${column("\"timeline\"")}
        }"""
    }

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
        const val serverVersion = """{"major":1,"minor":142,"patch":0}"""
        const val croppedEdits = """{"edits":[{"action":"crop","parameters":{"x":0,"y":0,"width":100,"height":200}}]}"""
        val jsonHeaders = headersOf(
            HttpHeaders.ContentType,
            ContentType.Application.Json.toString()
        )
    }
}
