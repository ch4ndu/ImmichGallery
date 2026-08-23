package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.test.TestSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ImmichApiServiceRequestTest {
    @Test
    fun timelineRequestsUseStackedQueryParameters() = runTest {
        val requests = mutableListOf<RequestSnapshot>()
        val engine = MockEngine { request ->
            requests += RequestSnapshot(
                path = request.url.encodedPath,
                timeBucket = request.url.parameters["timeBucket"],
                withStacked = request.url.parameters["withStacked"]
            )
            respond(
                content = when (request.url.encodedPath) {
                    "/api/server/version" -> """{"major":1,"minor":142,"patch":0}"""
                    "/api/timeline/buckets" -> """[{"timeBucket":"2026-01","count":1}]"""
                    "/api/timeline/bucket" -> timelineBucketResponse
                    else -> error("Unexpected request ${request.url.encodedPath}")
                },
                headers = jsonHeaders
            )
        }
        val client = HttpClientFactory.create(engine)
        val service = ImmichApiService(client, configuration())
        try {
            service.getTimelineBuckets()
            service.getTimelineBucket("2026-01-01T00:00:00.000+05:30/reserved value")
        } finally {
            client.close()
        }

        val timelineRequests = requests.filter { it.path.startsWith("/api/timeline") }
        assertEquals(
            listOf("/api/timeline/buckets", "/api/timeline/bucket"),
            timelineRequests.map { it.path }
        )
        assertEquals("true", timelineRequests[0].withStacked)
        assertNull(timelineRequests[0].timeBucket)
        assertEquals("true", timelineRequests[1].withStacked)
        assertEquals("2026-01-01T00:00:00.000+05:30/reserved value", timelineRequests[1].timeBucket)
    }

    @Test
    fun peopleRequestIncludesHiddenPeople() = runTest {
        var requestSnapshot: RequestSnapshot? = null
        val engine = MockEngine { request ->
            requestSnapshot = RequestSnapshot(
                path = request.url.encodedPath,
                page = request.url.parameters["page"],
                size = request.url.parameters["size"],
                withHidden = request.url.parameters["withHidden"]
            )
            respond("""{"people":[],"total":0,"hidden":0,"hasNextPage":false}""", headers = jsonHeaders)
        }
        val client = HttpClientFactory.create(engine)
        val service = ImmichApiService(client, configuration())
        try {
            service.getPeople(page = 2, size = 50)
        } finally {
            client.close()
        }

        val snapshot = requireNotNull(requestSnapshot)
        assertEquals("/api/people", snapshot.path)
        assertEquals("2", snapshot.page)
        assertEquals("50", snapshot.size)
        assertEquals("true", snapshot.withHidden)
    }

    @Test
    fun timelineOverlongStackTupleFailsAsSanitizedShapeCompatibilityError() = runTest {
        val secretBucket = "2026-01/private-bucket-token"
        val engine = MockEngine { request ->
            respond(
                content = when (request.url.encodedPath) {
                    "/api/server/version" -> """{"major":1,"minor":142,"patch":0}"""
                    "/api/timeline/bucket" -> overlongStackTimelineBucketResponse
                    else -> error("Unexpected request ${request.url.encodedPath}")
                },
                headers = jsonHeaders
            )
        }
        val client = HttpClientFactory.create(engine)
        val service = ImmichApiService(client, configuration())
        val error = try {
            assertFailsWith<TimelineApiCompatibilityException> {
                service.getTimelineBucket(secretBucket)
            }
        } finally {
            client.close()
        }

        assertEquals(TimelineCompatibilityCategory.SHAPE, error.category)
        assertEquals("/api/timeline/bucket", error.endpoint)
        assertFalse(error.message.orEmpty().contains(secretBucket))
        assertFalse(error.message.orEmpty().contains("extra-secret"))
    }

    private fun configuration(): ServerConfigRepository =
        ServerConfigRepository(TestSettings()).apply {
            replaceSession("https://immich.example", "api-key")
        }

    private data class RequestSnapshot(
        val path: String,
        val page: String? = null,
        val size: String? = null,
        val timeBucket: String? = null,
        val withStacked: String? = null,
        val withHidden: String? = null
    )

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        const val timelineBucketResponse = """{
            "createdAt":["2026-01-01T00:00:00Z"],
            "duration":[null],
            "id":["asset-1"],
            "isImage":[true],
            "fileCreatedAt":["2026-01-01T00:00:00Z"],
            "isFavorite":[false],
            "isTrashed":[false],
            "livePhotoVideoId":[null],
            "localOffsetHours":[5.5],
            "ownerId":["owner-1"],
            "projectionType":[""],
            "thumbhash":[null],
            "ratio":[1.5],
            "stack":[["stack-1","2"]],
            "visibility":["timeline"]
        }"""
        const val overlongStackTimelineBucketResponse = """{
            "createdAt":["2026-01-01T00:00:00Z"],
            "duration":[null],
            "id":["asset-1"],
            "isImage":[true],
            "fileCreatedAt":["2026-01-01T00:00:00Z"],
            "isFavorite":[false],
            "isTrashed":[false],
            "livePhotoVideoId":[null],
            "localOffsetHours":[5.5],
            "ownerId":["owner-1"],
            "projectionType":[""],
            "thumbhash":[null],
            "ratio":[1.5],
            "stack":[["stack-1","2","extra-secret"]],
            "visibility":["timeline"]
        }"""
    }
}
