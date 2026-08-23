package com.udnahc.immichgallery.domain.usecase.search

import androidx.compose.runtime.Immutable
import com.udnahc.immichgallery.data.model.SearchResponse
import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.toDomainOrNull
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

@Immutable
data class FavoritesLoadOutcome(
    val assets: List<Asset>,
    val nextPage: Int?,
    val consumedPages: Set<Int>
)

enum class FavoritesFailureKind {
    PERMISSION_DENIED,
    REQUEST_FAILED
}

class FavoritesLoadException(
    val consumedPages: Set<Int>,
    val firstUnconsumedPage: Int,
    val kind: FavoritesFailureKind,
    override val cause: Throwable
) : Exception("Favorite asset request failed", cause)

class GetFavoriteAssetsUseCase internal constructor(
    private val loadPage: suspend (Int) -> SearchResponse,
    private val baseUrl: () -> String
) {
    constructor(
        repository: SearchRepository,
        serverConfigRepository: ServerConfigRepository
    ) : this(
        loadPage = repository::searchFavorites,
        baseUrl = { serverConfigRepository.getServerUrl().trimEnd('/') }
    )

    suspend operator fun invoke(
        ownerId: String,
        startingPage: Int,
        previouslyConsumedPages: Set<Int>
    ): Result<FavoritesLoadOutcome> {
        val consumedPages = previouslyConsumedPages.toMutableSet()
        var page = startingPage

        while (page !in consumedPages) {
            val response = try {
                loadPage(page)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return Result.failure(
                    FavoritesLoadException(
                        consumedPages = consumedPages.toSet(),
                        firstUnconsumedPage = page,
                        kind = error.toFavoritesFailureKind(),
                        cause = error
                    )
                )
            }

            consumedPages += page
            val ownedAssets = response.assets.items
                .asSequence()
                .filter { it.ownerId == ownerId }
                .mapNotNull { it.toDomainOrNull(baseUrl()) }
                .toList()
            val nextPage = response.assets.nextPage
                ?.toIntOrNull()
                ?.takeIf { it > page && it !in consumedPages }

            if (ownedAssets.isNotEmpty() || nextPage == null) {
                return Result.success(
                    FavoritesLoadOutcome(
                        assets = ownedAssets,
                        nextPage = nextPage,
                        consumedPages = consumedPages.toSet()
                    )
                )
            }

            page = nextPage
        }

        return Result.success(
            FavoritesLoadOutcome(
                assets = emptyList(),
                nextPage = null,
                consumedPages = consumedPages.toSet()
            )
        )
    }
}

private fun Throwable.toFavoritesFailureKind(): FavoritesFailureKind =
    if (
        this is ClientRequestException &&
        (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden)
    ) {
        FavoritesFailureKind.PERMISSION_DENIED
    } else {
        FavoritesFailureKind.REQUEST_FAILED
    }
