package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.SearchResponse
import com.udnahc.immichgallery.data.model.SearchResultAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GetFavoriteAssetsUseCaseTest {
    @Test
    fun sparsePartnerPageContinuesUntilOwnedSupportedAssets() = runTest {
        val requestedPages = mutableListOf<Int>()
        val useCase = useCase { page ->
            requestedPages += page
            when (page) {
                1 -> response(
                    items = listOf(
                        asset("partner", ownerId = "partner"),
                        asset("audio", ownerId = "owner", type = "AUDIO")
                    ),
                    nextPage = "2"
                )
                else -> response(
                    items = listOf(asset("owned", ownerId = "owner")),
                    nextPage = "3"
                )
            }
        }

        val outcome = useCase("owner", 1, emptySet()).getOrThrow()

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(listOf("owned"), outcome.assets.map { it.id })
        assertEquals(3, outcome.nextPage)
        assertEquals(setOf(1, 2), outcome.consumedPages)
    }

    @Test
    fun backwardTokenBecomesTerminalWithoutRevisitingConsumedPage() = runTest {
        val requestedPages = mutableListOf<Int>()
        val useCase = useCase { page ->
            requestedPages += page
            response(
                items = listOf(asset("page-3", ownerId = "owner")),
                nextPage = "2"
            )
        }

        val outcome = useCase("owner", 3, setOf(1, 2)).getOrThrow()

        assertEquals(listOf(3), requestedPages)
        assertEquals(listOf("page-3"), outcome.assets.map { it.id })
        assertNull(outcome.nextPage)
        assertEquals(setOf(1, 2, 3), outcome.consumedPages)
    }

    @Test
    fun failureCarriesConsumedProgressAndRetryStartsAtFirstUnconsumedPage() = runTest {
        val requestedPages = mutableListOf<Int>()
        var pageTwoAttempts = 0
        val useCase = useCase { page ->
            requestedPages += page
            when (page) {
                1 -> response(listOf(asset("partner", "partner")), "2")
                else -> {
                    pageTwoAttempts++
                    if (pageTwoAttempts == 1) error("temporary")
                    response(listOf(asset("owned", "owner")), null)
                }
            }
        }

        val firstFailure = assertIs<FavoritesLoadException>(
            useCase("owner", 1, emptySet()).exceptionOrNull()
        )
        assertEquals(setOf(1), firstFailure.consumedPages)
        assertEquals(2, firstFailure.firstUnconsumedPage)

        val retry = useCase(
            ownerId = "owner",
            startingPage = firstFailure.firstUnconsumedPage,
            previouslyConsumedPages = firstFailure.consumedPages
        ).getOrThrow()

        assertEquals(listOf(1, 2, 2), requestedPages)
        assertEquals(listOf("owned"), retry.assets.map { it.id })
        assertEquals(setOf(1, 2), retry.consumedPages)
    }

    @Test
    fun malformedAndRepeatedTokensAreTerminalAfterKeepingCurrentOwnedAssets() = runTest {
        val malformed = useCase {
            response(listOf(asset("owned", "owner")), "later")
        }("owner", 1, emptySet()).getOrThrow()
        val repeated = useCase {
            response(listOf(asset("owned", "owner")), "1")
        }("owner", 1, emptySet()).getOrThrow()

        assertEquals(listOf("owned"), malformed.assets.map { it.id })
        assertNull(malformed.nextPage)
        assertNull(repeated.nextPage)
    }

    @Test
    fun partnerAssetsRemainExcludedWhenFavoriteValueIsMasked() = runTest {
        val outcome = useCase {
            response(
                items = listOf(
                    asset("partner", ownerId = "partner", isFavorite = false),
                    asset("owned", ownerId = "owner", isFavorite = false)
                ),
                nextPage = null
            )
        }("owner", 1, emptySet()).getOrThrow()

        assertEquals(listOf("owned"), outcome.assets.map { it.id })
    }

    @Test
    fun cancellationRemainsControlFlow() = runTest {
        val useCase = useCase { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> {
            useCase("owner", 1, emptySet())
        }
    }

    private fun useCase(
        loadPage: suspend (Int) -> SearchResponse
    ): GetFavoriteAssetsUseCase = GetFavoriteAssetsUseCase(
        loadPage = loadPage,
        baseUrl = { "https://example.test" }
    )

    private fun response(
        items: List<AssetResponse>,
        nextPage: String?
    ): SearchResponse = SearchResponse(SearchResultAssets(items, nextPage))

    private fun asset(
        id: String,
        ownerId: String,
        type: String = "IMAGE",
        isFavorite: Boolean = true
    ): AssetResponse = AssetResponse(
        id = id,
        ownerId = ownerId,
        type = type,
        originalFileName = "$id.jpg",
        isFavorite = isFavorite
    )
}
