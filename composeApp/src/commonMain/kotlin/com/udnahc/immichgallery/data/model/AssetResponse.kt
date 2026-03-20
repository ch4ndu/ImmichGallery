package com.udnahc.immichgallery.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AssetResponse(
    val id: String,
    val type: String = "IMAGE",
    val originalFileName: String = "",
    val fileCreatedAt: String = "",
    val thumbhash: String? = null,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false
)

/**
 * Immich returns timeline bucket assets in columnar format:
 * {"id": ["a","b"], "type": ["IMAGE","VIDEO"], ...}
 */
@Serializable
data class TimelineBucketColumnarResponse(
    val id: List<String> = emptyList(),
    val isImage: List<Boolean?> = emptyList(),
    val fileCreatedAt: List<String?> = emptyList(),
    val isFavorite: List<Boolean?> = emptyList(),
    val duration: List<String?> = emptyList(),
    val thumbhash: List<String?> = emptyList()
) {
    fun toAssetResponses(): List<AssetResponse> {
        return id.indices.map { i ->
            val isImg = isImage.getOrElse(i) { true } ?: true
            AssetResponse(
                id = id[i],
                type = if (isImg) "IMAGE" else "VIDEO",
                originalFileName = "",
                fileCreatedAt = fileCreatedAt.getOrElse(i) { "" } ?: "",
                isFavorite = isFavorite.getOrElse(i) { false } ?: false,
                isArchived = false,
                thumbhash = thumbhash.getOrElse(i) { null }
            )
        }
    }
}
