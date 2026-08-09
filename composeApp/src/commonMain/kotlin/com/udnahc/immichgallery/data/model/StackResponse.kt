package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class StackResponse(
    val id: String,
    val primaryAssetId: String,
    val assets: List<AssetResponse> = emptyList()
)
