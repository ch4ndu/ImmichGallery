package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Album(
    val id: String,
    val name: String,
    val assetCount: Int,
    val thumbnailUrl: String?
)

@Immutable
data class AlbumDetail(
    val id: String,
    val name: String,
    val assets: List<Asset>,
    val assetCount: Int
)
