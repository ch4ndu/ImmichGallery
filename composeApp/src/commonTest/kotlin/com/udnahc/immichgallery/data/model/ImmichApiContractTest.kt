package com.udnahc.immichgallery.data.model

import com.udnahc.immichgallery.data.remote.AlbumAssetsSearchRequest
import com.udnahc.immichgallery.data.remote.FavoritesSearchRequest
import com.udnahc.immichgallery.data.remote.memoryDateQuery
import com.udnahc.immichgallery.domain.model.toAssetEntityOrNull
import com.udnahc.immichgallery.domain.model.toDomainOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImmichApiContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun assetResponseDecodesNestedStack() {
        val response = json.decodeFromString<AssetResponse>(
            """{
                "id":"asset-1",
                "type":"IMAGE",
                "stack":{"id":"stack-1","primaryAssetId":"asset-1","assetCount":3}
            }"""
        )

        assertEquals("stack-1", response.stack?.id)
        assertEquals(3, response.toDomainOrNull("https://example.test")?.stackCount)
        assertEquals(3, response.toAssetEntityOrNull()?.stackCount)
    }

    @Test
    fun completeStackResponseDecodesOrderedMixedMembers() {
        val response = json.decodeFromString<StackResponse>(
            """{
                "id":"stack-1",
                "primaryAssetId":"image-1",
                "assets":[
                    {"id":"image-1","type":"IMAGE"},
                    {"id":"audio-1","type":"AUDIO"},
                    {"id":"video-1","type":"VIDEO"}
                ],
                "unknownFutureField":true
            }"""
        )

        assertEquals("stack-1", response.id)
        assertEquals("image-1", response.primaryAssetId)
        assertEquals(listOf("image-1", "audio-1", "video-1"), response.assets.map(AssetResponse::id))
    }

    @Test
    fun memoryResponseDecodesOnThisDayAndOrderedMixedAssets() {
        val response = json.decodeFromString<MemoryResponse>(
            """{
                "id":"memory-1",
                "type":"on_this_day",
                "memoryAt":"2020-08-09T12:00:00.000Z",
                "data":{"year":2020},
                "assets":[
                    {"id":"image-1","type":"IMAGE"},
                    {"id":"audio-1","type":"AUDIO"},
                    {"id":"video-1","type":"VIDEO"}
                ],
                "isSaved":false,
                "unknownFutureField":true
            }"""
        )

        assertEquals("memory-1", response.id)
        assertEquals(2020, response.data?.year)
        assertEquals(listOf("image-1", "audio-1", "video-1"), response.assets.map(AssetResponse::id))
    }

    @Test
    fun memoryResponseKeepsMissingOptionalConsumedFieldsCompatible() {
        val response = json.decodeFromString<MemoryResponse>(
            """{"id":"memory-1","assets":[]}"""
        )

        assertEquals("", response.type)
        assertEquals("", response.memoryAt)
        assertNull(response.data)
    }

    @Test
    fun memoryDateQueryUsesExactIsoDateOnlyValue() {
        assertEquals("2026-08-09", memoryDateQuery(LocalDate(2026, 8, 9)))
    }

    @Test
    fun unsupportedAssetTypesAreFiltered() {
        listOf("AUDIO", "OTHER").forEach { type ->
            val response = AssetResponse(id = type.lowercase(), type = type)
            assertNull(response.toDomainOrNull("https://example.test"))
            assertNull(response.toAssetEntityOrNull())
        }
    }

    @Test
    fun assetResponseDecodesOwnerForFavoritesFiltering() {
        val response = json.decodeFromString<AssetResponse>(
            """{"id":"asset-1","ownerId":"owner-1","type":"IMAGE"}"""
        )
        val compatibleMissingOwner = json.decodeFromString<AssetResponse>(
            """{"id":"asset-2","type":"IMAGE"}"""
        )

        assertEquals("owner-1", response.ownerId)
        assertEquals("", compatibleMissingOwner.ownerId)
    }

    @Test
    fun favoritesSearchEncodesOwnerSafeCollectionRequest() {
        val encoded = json.parseToJsonElement(
            json.encodeToString(FavoritesSearchRequest(page = 4, size = 50))
        ).jsonObject

        assertEquals(true, encoded.getValue("isFavorite").jsonPrimitive.content.toBoolean())
        assertEquals("desc", encoded.getValue("order").jsonPrimitive.content)
        assertEquals(4, encoded.getValue("page").jsonPrimitive.content.toInt())
        assertEquals(50, encoded.getValue("size").jsonPrimitive.content.toInt())
        assertEquals(true, encoded.getValue("withStacked").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun peopleResponseDecodesPaginationFlag() {
        val response = json.decodeFromString<PeopleResponse>(
            """{"people":[],"total":501,"hidden":1,"hasNextPage":true}"""
        )

        assertEquals(true, response.hasNextPage)
        assertEquals(501, response.total)
        assertEquals(1, response.hidden)
    }

    @Test
    fun peopleResponseAllowsOmittedPaginationFlagButRequiresPinnedFields() {
        val response = json.decodeFromString<PeopleResponse>(
            """{"people":[],"total":501,"hidden":1}"""
        )

        assertNull(response.hasNextPage)
        listOf(
            """{"total":501,"hidden":1}""",
            """{"people":[],"hidden":1}""",
            """{"people":[],"total":501}"""
        ).forEach { payload ->
            assertFailsWith<SerializationException> {
                json.decodeFromString<PeopleResponse>(payload)
            }
        }
    }

    @Test
    fun albumDetailDecodesV3MetadataWithoutEmbeddedAssets() {
        val response = json.decodeFromString<AlbumDetailResponse>(
            """{
                "id":"album-1",
                "albumName":"Trips",
                "assetCount":5230,
                "order":"asc"
            }"""
        )

        assertEquals("Trips", response.albumName)
        assertEquals(5230, response.assetCount)
        assertEquals("asc", response.order)
    }

    @Test
    fun albumAssetsSearchEncodesPaginationAndOrder() {
        val encoded = json.parseToJsonElement(
            json.encodeToString(
                AlbumAssetsSearchRequest(
                    albumIds = listOf("album-1"),
                    order = "asc",
                    page = 2,
                    size = 250
                )
            )
        ).jsonObject

        assertEquals("album-1", encoded.getValue("albumIds").jsonArray.single().jsonPrimitive.content)
        assertEquals("asc", encoded.getValue("order").jsonPrimitive.content)
        assertEquals(2, encoded.getValue("page").jsonPrimitive.content.toInt())
        assertEquals(250, encoded.getValue("size").jsonPrimitive.content.toInt())
    }

    @Test
    fun nextPageUsesReturnedTokenAndRejectsMalformedValues() {
        assertEquals(7, SearchResultAssets(nextPage = "7").nextPageNumberOrNull())
        assertNull(SearchResultAssets(nextPage = null).nextPageNumberOrNull())
        assertFailsWith<IllegalStateException> {
            SearchResultAssets(nextPage = "later").nextPageNumberOrNull()
        }
    }

    @Test
    fun optionalSearchContractsDecodeMissingAndFutureFields() {
        val features = json.decodeFromString<SearchServerFeaturesResponse>(
            """{"search":true,"unknownFutureField":true}"""
        )
        val preferences = json.decodeFromString<SearchUserPreferencesResponse>(
            """{"tags":{"enabled":true,"sidebarWeb":false}}"""
        )
        val tag = json.decodeFromString<SearchTagResponse>(
            """{
                "id":"tag-1",
                "name":"Beach",
                "value":"Travel/Beach",
                "parentId":null,
                "unknownFutureField":true
            }"""
        )

        assertEquals(true, features.search)
        assertNull(features.smartSearch)
        assertNull(features.ocr)
        assertEquals(true, preferences.tags?.enabled)
        assertNull(preferences.ratings)
        assertEquals("Travel/Beach", tag.value)
        assertNull(tag.color)
    }

    @Test
    fun nullableSearchSuggestionsDecodeExplicitUnknown() {
        assertEquals(
            listOf("Austin", null, "Dallas"),
            json.decodeFromString<List<String?>>("""["Austin",null,"Dallas"]""")
        )
    }

    @Test
    fun timelineColumnarResponseRequiresAlignedColumns() {
        val shortColumns = listOf(
            timelineColumnar(id = listOf("a", "b"), createdAt = listOf("2026-01-01T00:00:00Z")),
            timelineColumnar(id = listOf("a", "b"), duration = listOf(null)),
            timelineColumnar(id = listOf("a", "b"), isImage = listOf(true)),
            timelineColumnar(id = listOf("a", "b"), fileCreatedAt = listOf("2026-01-01T00:00:00Z")),
            timelineColumnar(id = listOf("a", "b"), isFavorite = listOf(false)),
            timelineColumnar(id = listOf("a", "b"), isTrashed = listOf(false)),
            timelineColumnar(id = listOf("a", "b"), livePhotoVideoId = listOf(null)),
            timelineColumnar(id = listOf("a", "b"), localOffsetHours = listOf(5.5)),
            timelineColumnar(id = listOf("a", "b"), ownerId = listOf("owner-1")),
            timelineColumnar(id = listOf("a", "b"), projectionType = listOf("")),
            timelineColumnar(id = listOf("a", "b"), thumbhash = listOf(null)),
            timelineColumnar(id = listOf("a", "b"), ratio = listOf(1f)),
            timelineColumnar(id = listOf("a", "b"), visibility = listOf("timeline"))
        )

        shortColumns.forEach { response ->
            assertFailsWith<IllegalArgumentException> { response.toAssetResponses() }
        }
    }

    @Test
    fun timelineColumnarStackMapsToNestedAssetStack() {
        val response = timelineColumnar(
            id = listOf("a"),
            isImage = listOf(true),
            fileCreatedAt = listOf("2026-01-01T00:00:00Z"),
            stack = listOf(listOf("stack-a", "4"))
        )

        assertEquals(4, response.toAssetResponses().single().stack?.assetCount)
    }

    @Test
    fun timelineColumnarRequiredFieldsMustBePresent() {
        val fields = mapOf(
            "createdAt" to """"createdAt":["2026-01-01T00:00:00Z"]""",
            "duration" to """"duration":[null]""",
            "id" to """"id":["a"]""",
            "isImage" to """"isImage":[true]""",
            "fileCreatedAt" to """"fileCreatedAt":["2026-01-01T00:00:00Z"]""",
            "isFavorite" to """"isFavorite":[false]""",
            "isTrashed" to """"isTrashed":[false]""",
            "livePhotoVideoId" to """"livePhotoVideoId":[null]""",
            "localOffsetHours" to """"localOffsetHours":[5.5]""",
            "ownerId" to """"ownerId":["owner-1"]""",
            "projectionType" to """"projectionType":[""]""",
            "thumbhash" to """"thumbhash":[null]""",
            "ratio" to """"ratio":[1.5]""",
            "visibility" to """"visibility":["timeline"]"""
        )

        assertFailsWith<SerializationException> {
            json.decodeFromString<TimelineBucketColumnarResponse>("{}")
        }
        fields.keys.forEach { missing ->
            val body = fields
                .filterKeys { it != missing }
                .values
                .joinToString(prefix = "{", postfix = "}", separator = ",")
            assertFailsWith<SerializationException>("Expected missing $missing to fail") {
                json.decodeFromString<TimelineBucketColumnarResponse>(body)
            }
        }
    }

    @Test
    fun timelineColumnarStackMayBeAbsent() {
        val response = json.decodeFromString<TimelineBucketColumnarResponse>(
            """{
                "createdAt":["2026-01-01T00:00:00Z"],
                "duration":[null],
                "id":["a"],
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
                "visibility":["timeline"],
                "unknownFutureField":true
            }"""
        )

        val asset = response.toAssetResponses().single()

        assertEquals("a", asset.id)
        assertNull(asset.stack)
    }

    @Test
    fun timelineColumnarRejectsPresentShortStackColumn() {
        listOf(
            timelineColumnar(id = listOf("a"), stack = emptyList()),
            timelineColumnar(id = listOf("a", "b"), stack = listOf(null))
        ).forEach { response ->
            assertFailsWith<IllegalArgumentException> {
                response.toAssetResponses()
            }
        }
    }

    @Test
    fun timelineColumnarRequiredFieldsRejectNullElements() {
        val fields = mapOf(
            "createdAt" to "null",
            "isImage" to "null",
            "fileCreatedAt" to "null",
            "isFavorite" to "null",
            "isTrashed" to "null",
            "localOffsetHours" to "null",
            "ownerId" to "null",
            "ratio" to "null",
            "visibility" to "null"
        )

        fields.forEach { (field, nullValue) ->
            assertFailsWith<SerializationException>("Expected null $field element to fail") {
                json.decodeFromString<TimelineBucketColumnarResponse>(
                    timelineColumnarJson(field to nullValue)
                )
            }
        }
    }

    @Test
    fun timelineColumnarRejectsMalformedStackTuple() {
        listOf(
            listOf("stack-a"),
            listOf("", "4"),
            listOf("stack-a", "not-a-count"),
            listOf("stack-a", "4", "extra")
        ).forEach { malformedStack ->
            assertFailsWith<IllegalArgumentException> {
                timelineColumnar(
                    id = listOf("a"),
                    stack = listOf(malformedStack)
                ).toAssetResponses()
            }
        }
    }

    private fun timelineColumnar(
        id: List<String>,
        createdAt: List<String> = id.map { "2026-01-01T00:00:00Z" },
        duration: List<Int?> = id.map { null },
        isImage: List<Boolean> = id.map { true },
        fileCreatedAt: List<String> = id.map { "2026-01-01T00:00:00Z" },
        isFavorite: List<Boolean> = id.map { false },
        isTrashed: List<Boolean> = id.map { false },
        livePhotoVideoId: List<String?> = id.map { null },
        localOffsetHours: List<Double> = id.map { 5.5 },
        ownerId: List<String> = id.map { "owner-1" },
        projectionType: List<String?> = id.map { "" },
        thumbhash: List<String?> = id.map { null },
        ratio: List<Float> = id.map { 1f },
        stack: List<List<String>?>? = null,
        visibility: List<String> = id.map { "timeline" }
    ): TimelineBucketColumnarResponse =
        TimelineBucketColumnarResponse(
            createdAt = createdAt,
            duration = duration,
            id = id,
            isImage = isImage,
            fileCreatedAt = fileCreatedAt,
            isFavorite = isFavorite,
            isTrashed = isTrashed,
            livePhotoVideoId = livePhotoVideoId,
            localOffsetHours = localOffsetHours,
            ownerId = ownerId,
            projectionType = projectionType,
            thumbhash = thumbhash,
            ratio = ratio,
            visibility = visibility,
            stack = stack
        )

    private fun timelineColumnarJson(replacement: Pair<String, String>): String {
        val fields = mapOf(
            "createdAt" to """"2026-01-01T00:00:00Z"""",
            "duration" to "null",
            "id" to """"a"""",
            "isImage" to "true",
            "fileCreatedAt" to """"2026-01-01T00:00:00Z"""",
            "isFavorite" to "false",
            "isTrashed" to "false",
            "livePhotoVideoId" to "null",
            "localOffsetHours" to "5.5",
            "ownerId" to """"owner-1"""",
            "projectionType" to "\"\"",
            "thumbhash" to "null",
            "ratio" to "1.5",
            "visibility" to """"timeline""""
        ) + replacement
        return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            """"$key":[$value]"""
        }
    }
}
