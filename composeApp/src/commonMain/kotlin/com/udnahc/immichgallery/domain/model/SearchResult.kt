package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class SearchResult(
    val assets: List<Asset>,
    val hasMore: Boolean
)
