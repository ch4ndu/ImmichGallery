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

fun SearchResultAssets.nextPageNumberOrNull(): Int? =
    nextPage?.toIntOrNull()?.takeIf { it > 0 }
        ?: if (nextPage == null) null else error("Invalid Immich nextPage token: $nextPage")
