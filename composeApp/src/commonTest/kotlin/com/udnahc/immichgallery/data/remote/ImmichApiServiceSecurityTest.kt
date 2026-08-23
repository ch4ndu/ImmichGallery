package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.di.createAuthenticatedImageHttpClient
import com.udnahc.immichgallery.test.TestSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImmichApiServiceSecurityTest {
    @Test
    fun candidatePingIsAnonymousAndCandidateAuthHasExactlyOneCandidateKey() = runTest {
        val requests = mutableListOf<Pair<String, List<String>?>>()
        val engine = MockEngine { request ->
            requests += request.url.encodedPath to request.headers.getAll("x-api-key")
            val body = if (request.url.encodedPath.endsWith("/ping")) {
                "{\"res\":\"pong\"}"
            } else {
                "[]"
            }
            respond(body, headers = jsonHeaders)
        }
        val configuration = ServerConfigRepository(TestSettings()).apply {
            replaceSession("https://old.example", "old-key")
        }
        val client = HttpClientFactory.create(engine)
        try {
            ImmichApiService(client, configuration).validateConnection(
                "https://candidate.example/",
                "candidate-key"
            )

            assertEquals("/api/server/ping", requests[0].first)
            assertNull(requests[0].second)
            assertEquals("/api/albums", requests[1].first)
            assertEquals(listOf("candidate-key"), requests[1].second)
        } finally {
            client.close()
        }
    }

    @Test
    fun normalAuthenticatedRequestUsesCurrentKeyExplicitly() = runTest {
        val headersByPath = mutableMapOf<String, List<String>?>()
        val engine = MockEngine { request ->
            headersByPath[request.url.encodedPath] = request.headers.getAll("x-api-key")
            val body = if (request.url.encodedPath.endsWith("/features")) "{}" else "[]"
            respond(body, headers = jsonHeaders)
        }
        val configuration = ServerConfigRepository(TestSettings()).apply {
            replaceSession("https://immich.example", "current-key")
        }
        val client = HttpClientFactory.create(engine)
        try {
            val service = ImmichApiService(client, configuration)
            service.getAlbums()
            service.getSearchServerFeatures()
            assertEquals(listOf("current-key"), headersByPath["/api/albums"])
            assertEquals(listOf("current-key"), headersByPath["/api/server/features"])
        } finally {
            client.close()
        }
    }

    @Test
    fun sharedClientRefusesRedirects() = runTest {
        var requestCount = 0
        val engine = MockEngine {
            requestCount += 1
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://other.example/api/server/ping")
            )
        }
        val client = HttpClientFactory.create(engine)
        try {
            assertFailsWith<RedirectResponseException> {
                client.get("https://immich.example/api/server/ping")
            }
            assertEquals(1, requestCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun authenticatedImageClientAddsKeyOnlyForExactOrigin() = runTest {
        val headersByHost = mutableMapOf<String, List<String>?>()
        val engine = MockEngine { request ->
            headersByHost[request.url.host] = request.headers.getAll("x-api-key")
            respond("image")
        }
        val configuration = ServerConfigRepository(TestSettings()).apply {
            replaceSession("https://immich.example:443/base", "image-key")
        }
        val base = HttpClientFactory.create(engine)
        val imageClient = createAuthenticatedImageHttpClient(base, configuration)
        try {
            imageClient.get("https://immich.example/api/assets/1/thumbnail")
            imageClient.get("https://cdn.example/api/assets/1/thumbnail")

            assertEquals(listOf("image-key"), headersByHost["immich.example"])
            assertNull(headersByHost["cdn.example"])
        } finally {
            imageClient.close()
            base.close()
        }
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
