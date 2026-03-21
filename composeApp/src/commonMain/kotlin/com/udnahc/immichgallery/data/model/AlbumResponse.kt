package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class AlbumResponse(
    val id: String,
    val albumName: String,
    val assetCount: Int = 0,
    val albumThumbnailAssetId: String? = null,
    val updatedAt: String = ""
)

@Immutable
@Serializable
data class AlbumDetailResponse(
    val id: String,
    val albumName: String,
    val assets: List<AssetResponse> = emptyList(),
    val assetCount: Int = 0
)
