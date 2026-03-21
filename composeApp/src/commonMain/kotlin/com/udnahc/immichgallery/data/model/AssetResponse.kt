package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
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
    val visibility: String = "timeline",
    val exifInfo: ExifInfo? = null,
    val people: List<AssetPersonResponse> = emptyList()
)

@Immutable
@Serializable
data class ExifInfo(
    val make: String? = null,
    val model: String? = null,
    val focalLength: Double? = null,
    val fNumber: Double? = null,
    val exposureTime: String? = null,
    val iso: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val dateTimeOriginal: String? = null,
    val fileSizeInByte: Long? = null,
    val exifImageWidth: Int? = null,
    val exifImageHeight: Int? = null,
    val lensModel: String? = null,
    val description: String? = null
)

@Immutable
@Serializable
data class AssetPersonResponse(
    val id: String,
    val name: String = "",
    val thumbnailPath: String = ""
)

/**
 * Immich returns timeline bucket assets in columnar format:
 * {"id": ["a","b"], "isImage": [true,false], "stack": [["stackId","3"], null], ...}
 */
@Immutable
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
