package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.data.model.AlbumDetailResponse
import com.udnahc.immichgallery.data.model.AlbumResponse
import com.udnahc.immichgallery.data.model.AssetEditsResponse
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.MemoryResponse
import com.udnahc.immichgallery.data.model.PeopleResponse
import com.udnahc.immichgallery.data.model.SearchResponse
import com.udnahc.immichgallery.data.model.SearchServerFeaturesResponse
import com.udnahc.immichgallery.data.model.SearchTagResponse
import com.udnahc.immichgallery.data.model.SearchUserPreferencesResponse
import com.udnahc.immichgallery.data.model.ServerPingResponse
import com.udnahc.immichgallery.data.model.ServerVersionResponse
import com.udnahc.immichgallery.data.model.StackResponse
import com.udnahc.immichgallery.data.model.TimeBucketResponse
import com.udnahc.immichgallery.data.model.TimelineBucketColumnarResponse
import com.udnahc.immichgallery.data.model.UserResponse
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest
import com.udnahc.immichgallery.domain.model.SearchSuggestionType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.datetime.LocalDate

class ImmichApiService(
    private val httpClient: HttpClient,
    private val serverConfigRepository: ServerConfigRepository
) {
    private var timelineServerVersion: ServerVersionResponse? = null
    private var timelineServerVersionBaseUrl: String? = null

    private fun requestSession(): RequestSession {
        val session = serverConfigRepository.currentSession()
        return RequestSession(
            baseUrl = session.serverUrl.trimEnd('/'),
            apiKey = session.apiKey
        )
    }

    suspend fun ping(): ServerPingResponse {
        return httpClient.get("${requestSession().baseUrl}/api/server/ping").body()
    }

    suspend fun getMyUser(): UserResponse {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/users/me") {
            header("x-api-key", session.apiKey)
        }.body()
    }

    suspend fun validateConnection(
        serverUrl: String,
        apiKey: String
    ): UserResponse {
        val url = serverUrl.trimEnd('/')
        httpClient.get("$url/api/server/ping").body<ServerPingResponse>()
        httpClient.get("$url/api/albums") {
            header("x-api-key", apiKey)
        }.body<List<AlbumResponse>>()
        return UserResponse()
    }

    suspend fun getTimelineBuckets(): List<TimeBucketResponse> {
        val endpoint = "/api/timeline/buckets"
        val session = requestSession()
        val version = timelineServerVersion(session.baseUrl)
        return try {
            httpClient.get("${session.baseUrl}$endpoint") {
                header("x-api-key", session.apiKey)
            }.body()
        } catch (_: SerializationException) {
            throw TimelineApiCompatibilityException(
                version.toString(), endpoint, TimelineCompatibilityCategory.DECODING
            )
        } catch (_: JsonConvertException) {
            throw TimelineApiCompatibilityException(
                version.toString(), endpoint, TimelineCompatibilityCategory.DECODING
            )
        }
    }

    suspend fun getTimelineBucket(
        timeBucket: String
    ): List<AssetResponse> {
        val endpoint = "/api/timeline/bucket"
        val session = requestSession()
        val version = timelineServerVersion(session.baseUrl)
        try {
            val columnar = httpClient.get("${session.baseUrl}$endpoint?timeBucket=$timeBucket") {
                header("x-api-key", session.apiKey)
            }.body<TimelineBucketColumnarResponse>()
            return columnar.toAssetResponses()
        } catch (_: SerializationException) {
            throw TimelineApiCompatibilityException(
                version.toString(), endpoint, TimelineCompatibilityCategory.DECODING
            )
        } catch (_: JsonConvertException) {
            throw TimelineApiCompatibilityException(
                version.toString(), endpoint, TimelineCompatibilityCategory.DECODING
            )
        } catch (_: IllegalArgumentException) {
            throw TimelineApiCompatibilityException(
                version.toString(), endpoint, TimelineCompatibilityCategory.SHAPE
            )
        }
    }

    private suspend fun timelineServerVersion(currentBaseUrl: String): ServerVersionResponse {
        timelineServerVersion?.takeIf { timelineServerVersionBaseUrl == currentBaseUrl }
            ?.let { return it }
        val endpoint = "$currentBaseUrl/api/server/version"
        val version = httpClient.get(endpoint).body<ServerVersionResponse>()
        timelineServerVersion = version
        timelineServerVersionBaseUrl = currentBaseUrl
        return version
    }

    fun clearSessionState() {
        timelineServerVersion = null
        timelineServerVersionBaseUrl = null
    }

    suspend fun getAlbums(): List<AlbumResponse> {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/albums") {
            header("x-api-key", session.apiKey)
        }.body()
    }

    suspend fun getAlbumDetail(id: String): AlbumDetailResponse {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/albums/$id") {
            header("x-api-key", session.apiKey)
        }.body()
    }

    suspend fun getAlbumAssets(
        id: String,
        page: Int = 1,
        size: Int = 250,
        order: String = "desc"
    ): SearchResponse {
        val session = requestSession()
        return httpClient.post("${session.baseUrl}/api/search/metadata") {
            header("x-api-key", session.apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                AlbumAssetsSearchRequest(
                    albumIds = listOf(id),
                    order = order,
                    page = page,
                    size = size
                )
            )
        }.body()
    }

    suspend fun getPeople(page: Int, size: Int): PeopleResponse {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/people") {
            header("x-api-key", session.apiKey)
            parameter("page", page)
            parameter("size", size)
        }.body()
    }

    suspend fun getPersonAssets(
        id: String,
        page: Int = 1,
        size: Int = 250
    ): SearchResponse {
        val session = requestSession()
        return httpClient.post("${session.baseUrl}/api/search/metadata") {
            header("x-api-key", session.apiKey)
            contentType(ContentType.Application.Json)
            setBody(PersonAssetsSearchRequest(personIds = listOf(id), page = page, size = size))
        }.body()
    }

    suspend fun searchSmart(
        criteria: SearchCriteria,
        page: Int = 1,
        size: Int = 50
    ): SearchResponse {
        val session = requestSession()
        return httpClient.post("${session.baseUrl}/api/search/smart") {
            header("x-api-key", session.apiKey)
            contentType(ContentType.Application.Json)
            setBody(AdvancedSearchRequestBuilder.buildSmart(criteria, page, size))
        }.body()
    }

    suspend fun searchMetadata(
        criteria: SearchCriteria,
        page: Int = 1,
        size: Int = 50
    ): SearchResponse {
        val session = requestSession()
        return httpClient.post("${session.baseUrl}/api/search/metadata") {
            header("x-api-key", session.apiKey)
            contentType(ContentType.Application.Json)
            setBody(AdvancedSearchRequestBuilder.buildMetadata(criteria, page, size))
        }.body()
    }

    suspend fun getSearchServerFeatures(): SearchServerFeaturesResponse {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/server/features") {
            header("x-api-key", session.apiKey)
        }.body()
    }

    suspend fun getSearchUserPreferences(): SearchUserPreferencesResponse {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/users/me/preferences") {
            header("x-api-key", session.apiKey)
        }.body()
    }

    suspend fun getSearchTags(): List<SearchTagResponse> {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/tags") {
            header("x-api-key", session.apiKey)
        }.body()
    }

    suspend fun getSearchSuggestions(request: SearchSuggestionRequest): List<String?> {
        val normalized = request.normalized()
        val parameters = normalized.toQueryParameters()
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/search/suggestions") {
            header("x-api-key", session.apiKey)
            parameters.forEach { (key, value) -> parameter(key, value) }
        }.body()
    }

    suspend fun searchFavorites(
        page: Int = 1,
        size: Int = 50
    ): SearchResponse {
        val session = requestSession()
        return httpClient.post("${session.baseUrl}/api/search/metadata") {
            header("x-api-key", session.apiKey)
            contentType(ContentType.Application.Json)
            setBody(FavoritesSearchRequest(page = page, size = size))
        }.body()
    }

    suspend fun getAssetInfo(id: String): AssetResponse {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/assets/$id") {
            header("x-api-key", session.apiKey)
        }.body()
    }

    suspend fun getStack(id: String): StackResponse {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/stacks/$id") {
            header("x-api-key", session.apiKey)
        }.body()
    }

    suspend fun getMemories(forDate: LocalDate): List<MemoryResponse> {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/memories") {
            header("x-api-key", session.apiKey)
            parameter("for", memoryDateQuery(forDate))
            parameter("type", "on_this_day")
        }.body()
    }

    suspend fun getAssetEdits(id: String): AssetEditsResponse {
        val session = requestSession()
        return httpClient.get("${session.baseUrl}/api/assets/$id/edits") {
            header("x-api-key", session.apiKey)
        }.body()
    }

}

private data class RequestSession(
    val baseUrl: String,
    val apiKey: String
)

internal fun memoryDateQuery(forDate: LocalDate): String = forDate.toString()

internal fun SearchSuggestionRequest.toQueryParameters(): List<Pair<String, String>> {
    val normalized = normalized()
    return buildList {
        add("type" to normalized.type.apiValue)
        add("includeNull" to "true")
        normalized.country?.let { add("country" to it) }
        normalized.state?.let { add("state" to it) }
        normalized.make?.let { add("make" to it) }
        normalized.model?.let { add("model" to it) }
    }
}

private val SearchSuggestionType.apiValue: String
    get() = when (this) {
        SearchSuggestionType.COUNTRY -> "country"
        SearchSuggestionType.STATE -> "state"
        SearchSuggestionType.CITY -> "city"
        SearchSuggestionType.CAMERA_MAKE -> "camera-make"
        SearchSuggestionType.CAMERA_MODEL -> "camera-model"
        SearchSuggestionType.LENS_MODEL -> "camera-lens-model"
    }

@Serializable
internal data class FavoritesSearchRequest(
    val isFavorite: Boolean = true,
    val order: String = "desc",
    val page: Int = 1,
    val size: Int = 50,
    val withStacked: Boolean = true
)

@Serializable
private data class PersonAssetsSearchRequest(
    val personIds: List<String>,
    val page: Int = 1,
    val size: Int = 250
)

@Serializable
internal data class AlbumAssetsSearchRequest(
    val albumIds: List<String>,
    val order: String = "desc",
    val page: Int = 1,
    val size: Int = 250
)
