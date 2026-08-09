package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class MemoryResponse(
    val id: String,
    val type: String = "",
    val memoryAt: String = "",
    val data: OnThisDayResponse? = null,
    val assets: List<AssetResponse> = emptyList()
)

@Immutable
@Serializable
data class OnThisDayResponse(
    val year: Int
)
