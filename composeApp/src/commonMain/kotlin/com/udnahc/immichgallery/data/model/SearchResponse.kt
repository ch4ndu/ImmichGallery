package com.udnahc.immichgallery.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val assets: SearchResultAssets
)

@Serializable
data class SearchResultAssets(
    val items: List<AssetResponse> = emptyList(),
    val nextPage: String? = null
)
