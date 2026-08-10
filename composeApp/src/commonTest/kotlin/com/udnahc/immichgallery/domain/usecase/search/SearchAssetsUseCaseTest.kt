package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.SearchResponse
import com.udnahc.immichgallery.data.model.SearchResultAssets
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchEndpoint
import com.udnahc.immichgallery.domain.model.SearchTextMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchAssetsUseCaseTest {
    @Test
    fun dispatchesFrozenEndpointAndMapsOnlySupportedAssets() = runTest {
        val calls = mutableListOf<String>()
        val useCase = SearchAssetsUseCase(
            searchSmart = { _, page, size ->
                calls += "smart:$page:$size"
                response(
                    AssetResponse(id = "image", type = "IMAGE"),
                    AssetResponse(id = "audio", type = "AUDIO"),
                    AssetResponse(id = "video", type = "VIDEO"),
                    nextPage = "7"
                )
            },
            searchMetadata = { _, _, _ -> error("Metadata fallback is not allowed") },
            getServerUrl = { "https://immich.example/" }
        )

        val result = useCase(
            SearchEndpoint.SMART,
            SearchCriteria(query = "beach").normalized(),
            page = 3,
            size = 50
        ).getOrThrow()

        assertEquals(listOf("smart:3:50"), calls)
        assertEquals(listOf("image", "video"), result.assets.map { it.id })
        assertEquals(
            "https://immich.example/api/assets/image/thumbnail?size=thumbnail&edited=true",
            result.assets.first().thumbnailUrl
        )
        assertEquals(7, result.nextPage)
    }

    @Test
    fun filterOnlyContextDispatchesOnlyToMetadata() = runTest {
        var metadataCalls = 0
        val criteria = SearchCriteria(isFavorite = true).normalized()
        val useCase = SearchAssetsUseCase(
            searchSmart = { _, _, _ -> error("Smart must not be called") },
            searchMetadata = { submitted, _, _ ->
                assertEquals(criteria, submitted)
                metadataCalls++
                response(nextPage = null)
            },
            getServerUrl = { "https://immich.example" }
        )

        useCase(SearchEndpoint.METADATA, criteria).getOrThrow()

        assertEquals(1, metadataCalls)
    }

    @Test
    fun endpointMismatchFailsBeforeAnyIo() = runTest {
        var called = false
        val useCase = SearchAssetsUseCase(
            searchSmart = { _, _, _ -> called = true; response() },
            searchMetadata = { _, _, _ -> called = true; response() },
            getServerUrl = { "https://immich.example" }
        )

        val result = useCase(
            SearchEndpoint.METADATA,
            SearchCriteria(query = "context").normalized()
        )

        assertTrue(result.isFailure)
        assertEquals(false, called)
    }

    @Test
    fun cancellationPropagatesWithoutFallback() = runTest {
        val useCase = SearchAssetsUseCase(
            searchSmart = { _, _, _ -> throw CancellationException("cancelled") },
            searchMetadata = { _, _, _ -> error("Metadata fallback is not allowed") },
            getServerUrl = { "https://immich.example" }
        )

        var cancelled = false
        try {
            useCase(SearchEndpoint.SMART, SearchCriteria(query = "beach").normalized())
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun response(
        vararg assets: AssetResponse,
        nextPage: String? = null
    ): SearchResponse = SearchResponse(
        assets = SearchResultAssets(items = assets.toList(), nextPage = nextPage)
    )
}
