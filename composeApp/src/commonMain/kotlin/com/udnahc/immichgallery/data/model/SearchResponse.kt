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
