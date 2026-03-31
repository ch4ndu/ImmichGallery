package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

enum class AssetType { IMAGE, VIDEO }

@Immutable
data class Asset(
    val id: String,
    val type: AssetType,
    val fileName: String,
    val createdAt: String,
    val thumbnailUrl: String,
    val originalUrl: String,
    val videoPlaybackUrl: String = "",
    val isFavorite: Boolean = false,
    val stackCount: Int = 0,
    val aspectRatio: Float = 1f
)
