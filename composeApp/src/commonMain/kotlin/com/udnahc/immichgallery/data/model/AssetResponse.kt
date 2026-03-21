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
    val isArchived: Boolean = false,
    val stackId: String? = null,
    val stackCount: Int = 0,
    val visibility: String = "timeline"
)

/**
 * Immich returns timeline bucket assets in columnar format:
 * {"id": ["a","b"], "isImage": [true,false], "stack": [["stackId","3"], null], ...}
 */
@Serializable
data class TimelineBucketColumnarResponse(
    val id: List<String> = emptyList(),
    val isImage: List<Boolean?> = emptyList(),
    val fileCreatedAt: List<String?> = emptyList(),
    val isFavorite: List<Boolean?> = emptyList(),
    val duration: List<String?> = emptyList(),
    val thumbhash: List<String?> = emptyList(),
    val stack: List<List<String>?> = emptyList(),
    val visibility: List<String?> = emptyList()
) {
    fun toAssetResponses(): List<AssetResponse> {
        return id.indices.map { i ->
            val isImg = isImage.getOrElse(i) { true } ?: true
            val stackTuple = stack.getOrElse(i) { null }
            AssetResponse(
                id = id[i],
                type = if (isImg) "IMAGE" else "VIDEO",
                originalFileName = "",
                fileCreatedAt = fileCreatedAt.getOrElse(i) { "" } ?: "",
                isFavorite = isFavorite.getOrElse(i) { false } ?: false,
                isArchived = false,
                thumbhash = thumbhash.getOrElse(i) { null },
                stackId = stackTuple?.getOrNull(0),
                stackCount = stackTuple?.getOrNull(1)?.toIntOrNull() ?: 0,
                visibility = visibility.getOrElse(i) { "timeline" } ?: "timeline"
            )
        }
    }
}
