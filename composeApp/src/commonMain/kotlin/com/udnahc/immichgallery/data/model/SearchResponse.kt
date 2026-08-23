package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class SearchResponse(
    val assets: SearchResultAssets
)

@Immutable
@Serializable
data class SearchResultAssets(
    val items: List<AssetResponse> = emptyList(),
    val nextPage: String? = null
)

open class ImmichPaginationCompatibilityException(
    message: String = "Invalid Immich nextPage token"
) : IllegalStateException(message)

fun SearchResultAssets.nextPageNumberOrNull(): Int? =
    nextPage?.toIntOrNull()?.takeIf { it > 0 }
        ?: if (nextPage == null) null else throw ImmichPaginationCompatibilityException()

fun SearchResultAssets.nextPageNumberAfter(
    currentPage: Int,
    consumedPages: Set<Int> = setOf(currentPage)
): Int? {
    val parsed = nextPageNumberOrNull() ?: return null
    if (parsed <= currentPage || parsed in consumedPages) {
        throw ImmichPaginationCompatibilityException()
    }
    return parsed
}
