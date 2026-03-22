package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.data.model.AlbumDetailResponse
import com.udnahc.immichgallery.data.model.AlbumResponse
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.PeopleResponse
import com.udnahc.immichgallery.data.model.SearchResponse
import com.udnahc.immichgallery.data.model.ServerPingResponse
import com.udnahc.immichgallery.data.model.TimeBucketResponse
import com.udnahc.immichgallery.data.model.TimelineBucketColumnarResponse
import com.udnahc.immichgallery.data.model.UserResponse
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import org.lighthousegames.logging.logging

class ImmichApiService(
    private val httpClient: HttpClient,
    private val serverConfigRepository: ServerConfigRepository
) {
    private val log = logging()

    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')
    private fun apiKey(): String = serverConfigRepository.getApiKey()

    suspend fun ping(): ServerPingResponse {
        log.d { "GET ${baseUrl()}/api/server/ping" }
        return httpClient.get("${baseUrl()}/api/server/ping").body()
    }

    suspend fun getMyUser(): UserResponse {
        log.d { "GET ${baseUrl()}/api/users/me" }
        return httpClient.get("${baseUrl()}/api/users/me") {
            header("x-api-key", apiKey())
        }.body()
    }

    suspend fun validateConnection(
        serverUrl: String,
        apiKey: String
    ): UserResponse {
        val url = serverUrl.trimEnd('/')
        log.d { "Validating connection to $url" }
        val ping = httpClient.get("$url/api/server/ping").body<ServerPingResponse>()
        log.d { "Ping OK (${ping.res}), validating API key..." }
        val albums = httpClient.get("$url/api/albums") {
            header("x-api-key", apiKey)
        }.body<List<AlbumResponse>>()
        log.d { "API key validated successfully (${albums.size} albums)" }
        return UserResponse()
    }

    suspend fun getTimelineBuckets(): List<TimeBucketResponse> {
        val endpoint = "${baseUrl()}/api/timeline/buckets"
        log.d { "GET $endpoint" }
        val response = httpClient.get(endpoint) {
            header("x-api-key", apiKey())
        }
        log.d { "Timeline buckets response status: ${response.status}" }
        return response.body()
    }

    suspend fun getTimelineBucket(
        timeBucket: String
    ): List<AssetResponse> {
        val endpoint = "${baseUrl()}/api/timeline/bucket?timeBucket=$timeBucket"
        log.d { "GET $endpoint" }
        val response = httpClient.get(endpoint) {
            header("x-api-key", apiKey())
        }
        if (response.status.value != 200) {
            val body = response.bodyAsText()
            log.e { "Timeline bucket error ${response.status}: $body" }
            throw RuntimeException("Timeline bucket error: ${response.status}")
        }
        val columnar = response.body<TimelineBucketColumnarResponse>()
        val videoCount = columnar.isImage.count { it == false }
        log.d { "Timeline bucket parsed ${columnar.id.size} assets ($videoCount videos)" }
        return columnar.toAssetResponses()
    }

    suspend fun getAlbums(): List<AlbumResponse> {
        log.d { "GET ${baseUrl()}/api/albums" }
        return httpClient.get("${baseUrl()}/api/albums") {
            header("x-api-key", apiKey())
        }.body()
    }

    suspend fun getAlbumDetail(id: String): AlbumDetailResponse {
        log.d { "GET ${baseUrl()}/api/albums/$id" }
        return httpClient.get("${baseUrl()}/api/albums/$id") {
            header("x-api-key", apiKey())
        }.body()
    }

    suspend fun getPeople(): PeopleResponse {
        log.d { "GET ${baseUrl()}/api/people" }
        return httpClient.get("${baseUrl()}/api/people") {
            header("x-api-key", apiKey())
        }.body()
    }

    suspend fun getPersonAssets(
        id: String,
        page: Int = 1,
        size: Int = 250
    ): SearchResponse {
        log.d { "POST ${baseUrl()}/api/search/metadata personId=$id page=$page" }
        return httpClient.post("${baseUrl()}/api/search/metadata") {
            header("x-api-key", apiKey())
            contentType(ContentType.Application.Json)
            setBody(PersonAssetsSearchRequest(personIds = listOf(id), page = page, size = size))
        }.body()
    }

    suspend fun searchSmart(
        query: String,
        page: Int = 1,
        size: Int = 50
    ): SearchResponse {
        log.d { "POST ${baseUrl()}/api/search/smart query=$query page=$page" }
        return httpClient.post("${baseUrl()}/api/search/smart") {
            header("x-api-key", apiKey())
            contentType(ContentType.Application.Json)
            setBody(SmartSearchRequest(query = query, page = page, size = size))
        }.body()
    }

    suspend fun searchMetadata(
        query: String,
        page: Int = 1,
        size: Int = 50
    ): SearchResponse {
        log.d { "POST ${baseUrl()}/api/search/metadata query=$query page=$page" }
        return httpClient.post("${baseUrl()}/api/search/metadata") {
            header("x-api-key", apiKey())
            contentType(ContentType.Application.Json)
            setBody(MetadataSearchRequest(originalFileName = query, page = page, size = size))
        }.body()
    }

    suspend fun getAssetInfo(id: String): AssetResponse {
        log.d { "GET ${baseUrl()}/api/assets/$id" }
        return httpClient.get("${baseUrl()}/api/assets/$id") {
            header("x-api-key", apiKey())
        }.body()
    }

    fun thumbnailUrl(
        assetId: String,
        size: String = "preview"
    ): String {
        return "${baseUrl()}/api/assets/$assetId/thumbnail?size=$size"
    }

    fun originalUrl(assetId: String): String {
        return "${baseUrl()}/api/assets/$assetId/original"
    }

    fun personThumbnailUrl(personId: String): String {
        return "${baseUrl()}/api/people/$personId/thumbnail"
    }

    fun albumThumbnailUrl(albumThumbnailAssetId: String): String {
        return thumbnailUrl(albumThumbnailAssetId)
    }
}

@Serializable
private data class SmartSearchRequest(
    val query: String,
    val page: Int = 1,
    val size: Int = 50
)

@Serializable
private data class MetadataSearchRequest(
    val originalFileName: String,
    val page: Int = 1,
    val size: Int = 50
)

@Serializable
private data class PersonAssetsSearchRequest(
    val personIds: List<String>,
    val page: Int = 1,
    val size: Int = 250
)
