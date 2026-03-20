package com.udnahc.immichgallery.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AlbumResponse(
    val id: String,
    val albumName: String,
    val assetCount: Int = 0,
    val albumThumbnailAssetId: String? = null,
    val updatedAt: String = ""
)

@Serializable
data class AlbumDetailResponse(
    val id: String,
    val albumName: String,
    val assets: List<AssetResponse> = emptyList(),
    val assetCount: Int = 0
)
