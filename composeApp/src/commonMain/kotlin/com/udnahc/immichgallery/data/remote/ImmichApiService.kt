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
import org.lighthousegames.logging.logging

class ImmichApiService(
    private val httpClient: HttpClient,
    private val serverConfigRepository: ServerConfigRepository
) {
    private val log = logging("ImmichApiService")
    private var timelineServerVersion: ServerVersionResponse? = null
    private var timelineServerVersionBaseUrl: String? = null

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
        val version = timelineServerVersion()
        log.d { "GET $endpoint" }
        return try {
            httpClient.get(endpoint) {
                header("x-api-key", apiKey())
            }.body()
        } catch (error: SerializationException) {
            throw TimelineApiCompatibilityException(version.toString(), endpoint, error)
        } catch (error: JsonConvertException) {
            throw TimelineApiCompatibilityException(version.toString(), endpoint, error)
        }
    }

    suspend fun getTimelineBucket(
        timeBucket: String
    ): List<AssetResponse> {
        val endpoint = "${baseUrl()}/api/timeline/bucket?timeBucket=$timeBucket"
        val version = timelineServerVersion()
        log.d { "GET $endpoint" }
        try {
            val columnar = httpClient.get(endpoint) {
                header("x-api-key", apiKey())
            }.body<TimelineBucketColumnarResponse>()
            val videoCount = columnar.isImage.count { it == false }
            log.d { "Timeline bucket parsed ${columnar.id.size} assets ($videoCount videos)" }
            return columnar.toAssetResponses()
        } catch (error: SerializationException) {
            throw TimelineApiCompatibilityException(version.toString(), endpoint, error)
        } catch (error: JsonConvertException) {
            throw TimelineApiCompatibilityException(version.toString(), endpoint, error)
        } catch (error: IllegalArgumentException) {
            throw TimelineApiCompatibilityException(version.toString(), endpoint, error)
        }
    }

    private suspend fun timelineServerVersion(): ServerVersionResponse {
        val currentBaseUrl = baseUrl()
        timelineServerVersion?.takeIf { timelineServerVersionBaseUrl == currentBaseUrl }
            ?.let { return it }
        val endpoint = "$currentBaseUrl/api/server/version"
        val version = httpClient.get(endpoint).body<ServerVersionResponse>()
        timelineServerVersion = version
        timelineServerVersionBaseUrl = currentBaseUrl
        log.d { "Timeline compatibility session uses Immich $version" }
        return version
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

    suspend fun getAlbumAssets(
        id: String,
        page: Int = 1,
        size: Int = 250,
        order: String = "desc"
    ): SearchResponse {
        log.d { "POST ${baseUrl()}/api/search/metadata albumId=$id page=$page" }
        return httpClient.post("${baseUrl()}/api/search/metadata") {
            header("x-api-key", apiKey())
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
        log.d { "GET ${baseUrl()}/api/people page=$page size=$size" }
        return httpClient.get("${baseUrl()}/api/people") {
            header("x-api-key", apiKey())
            parameter("page", page)
            parameter("size", size)
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
        criteria: SearchCriteria,
        page: Int = 1,
        size: Int = 50
    ): SearchResponse {
        log.d {
            "POST ${baseUrl()}/api/search/smart page=$page filters=${criteria.activeFilterCategoryCount()}"
        }
        return httpClient.post("${baseUrl()}/api/search/smart") {
            header("x-api-key", apiKey())
            contentType(ContentType.Application.Json)
            setBody(AdvancedSearchRequestBuilder.buildSmart(criteria, page, size))
        }.body()
    }

    suspend fun searchMetadata(
        criteria: SearchCriteria,
        page: Int = 1,
        size: Int = 50
    ): SearchResponse {
        log.d {
            "POST ${baseUrl()}/api/search/metadata page=$page filters=${criteria.activeFilterCategoryCount()}"
        }
        return httpClient.post("${baseUrl()}/api/search/metadata") {
            header("x-api-key", apiKey())
            contentType(ContentType.Application.Json)
            setBody(AdvancedSearchRequestBuilder.buildMetadata(criteria, page, size))
        }.body()
    }

    suspend fun getSearchServerFeatures(): SearchServerFeaturesResponse {
        log.d { "GET ${baseUrl()}/api/server/features" }
        return httpClient.get("${baseUrl()}/api/server/features").body()
    }

    suspend fun getSearchUserPreferences(): SearchUserPreferencesResponse {
        log.d { "GET ${baseUrl()}/api/users/me/preferences" }
        return httpClient.get("${baseUrl()}/api/users/me/preferences") {
            header("x-api-key", apiKey())
        }.body()
    }

    suspend fun getSearchTags(): List<SearchTagResponse> {
        log.d { "GET ${baseUrl()}/api/tags" }
        return httpClient.get("${baseUrl()}/api/tags") {
            header("x-api-key", apiKey())
        }.body()
    }

    suspend fun getSearchSuggestions(request: SearchSuggestionRequest): List<String?> {
        val normalized = request.normalized()
        val parameters = normalized.toQueryParameters()
        log.d {
            "GET ${baseUrl()}/api/search/suggestions type=${normalized.type} dependencies=${parameters.size - 2}"
        }
        return httpClient.get("${baseUrl()}/api/search/suggestions") {
            header("x-api-key", apiKey())
            parameters.forEach { (key, value) -> parameter(key, value) }
        }.body()
    }

    suspend fun searchFavorites(
        page: Int = 1,
        size: Int = 50
    ): SearchResponse {
        log.d { "POST ${baseUrl()}/api/search/metadata favorites page=$page" }
        return httpClient.post("${baseUrl()}/api/search/metadata") {
            header("x-api-key", apiKey())
            contentType(ContentType.Application.Json)
            setBody(FavoritesSearchRequest(page = page, size = size))
        }.body()
    }

    suspend fun getAssetInfo(id: String): AssetResponse {
        log.d { "GET ${baseUrl()}/api/assets/$id" }
        return httpClient.get("${baseUrl()}/api/assets/$id") {
            header("x-api-key", apiKey())
        }.body()
    }

    suspend fun getStack(id: String): StackResponse {
        log.d { "GET ${baseUrl()}/api/stacks/$id" }
        return httpClient.get("${baseUrl()}/api/stacks/$id") {
            header("x-api-key", apiKey())
        }.body()
    }

    suspend fun getMemories(forDate: LocalDate): List<MemoryResponse> {
        log.d { "GET ${baseUrl()}/api/memories for=$forDate" }
        return httpClient.get("${baseUrl()}/api/memories") {
            header("x-api-key", apiKey())
            parameter("for", memoryDateQuery(forDate))
            parameter("type", "on_this_day")
        }.body()
    }

    suspend fun getAssetEdits(id: String): AssetEditsResponse {
        log.d { "GET ${baseUrl()}/api/assets/$id/edits" }
        return httpClient.get("${baseUrl()}/api/assets/$id/edits") {
            header("x-api-key", apiKey())
        }.body()
    }

}

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

private fun SearchCriteria.activeFilterCategoryCount(): Int = listOf(
    query.isNotBlank(),
    personIds.isNotEmpty(),
    albumIds.isNotEmpty(),
    tags != com.udnahc.immichgallery.domain.model.TagConstraint.Any,
    country != com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any ||
            state != com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any ||
            city != com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any,
    make != com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any ||
            model != com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any ||
            lensModel != com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any,
    !takenDateRange.isEmpty,
    mediaType != com.udnahc.immichgallery.domain.model.SearchMediaType.ALL || motionOnly,
    isFavorite || isNotInAlbum,
    visibility != com.udnahc.immichgallery.domain.model.SearchVisibility.ALL_ACCESSIBLE,
    rating != com.udnahc.immichgallery.domain.model.RatingConstraint.Any
).count { it }
