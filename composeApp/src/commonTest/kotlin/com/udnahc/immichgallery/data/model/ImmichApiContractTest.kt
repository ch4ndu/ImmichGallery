package com.udnahc.immichgallery.data.model

import com.udnahc.immichgallery.data.remote.AlbumAssetsSearchRequest
import com.udnahc.immichgallery.domain.model.toAssetEntityOrNull
import com.udnahc.immichgallery.domain.model.toDomainOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun unsupportedAssetTypesAreFiltered() {
        listOf("AUDIO", "OTHER").forEach { type ->
            val response = AssetResponse(id = type.lowercase(), type = type)
            assertNull(response.toDomainOrNull("https://example.test"))
            assertNull(response.toAssetEntityOrNull())
        }
    }

    @Test
    fun peopleResponseDecodesPaginationFlag() {
        val response = json.decodeFromString<PeopleResponse>(
            """{"people":[],"total":501,"hasNextPage":true}"""
        )

        assertEquals(true, response.hasNextPage)
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
    fun timelineColumnarResponseRequiresAlignedColumns() {
        val response = TimelineBucketColumnarResponse(
            id = listOf("a", "b"),
            isImage = listOf(true)
        )

        assertFailsWith<IllegalArgumentException> { response.toAssetResponses() }
    }

    @Test
    fun timelineColumnarStackMapsToNestedAssetStack() {
        val response = TimelineBucketColumnarResponse(
            id = listOf("a"),
            isImage = listOf(true),
            fileCreatedAt = listOf("2026-01-01T00:00:00Z"),
            stack = listOf(listOf("stack-a", "4"))
        )

        assertEquals(4, response.toAssetResponses().single().stack?.assetCount)
    }
}
