package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class AssetResponse(
    val id: String,
    val ownerId: String = "",
    val type: String = "IMAGE",
    val originalFileName: String = "",
    val fileCreatedAt: String = "",
    val thumbhash: String? = null,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isEdited: Boolean = false,
    val stack: AssetStackResponse? = null,
    val visibility: String = "timeline",
    val ratio: Float? = null,
    val width: Int? = null,
    val height: Int? = null,
    val exifInfo: ExifInfo? = null,
    val people: List<AssetPersonResponse> = emptyList()
)

@Immutable
@Serializable
data class AssetStackResponse(
    val id: String,
    val primaryAssetId: String,
    val assetCount: Int = 0
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
    val createdAt: List<String>,
    val duration: List<Int?>,
    val id: List<String>,
    val isImage: List<Boolean>,
    val fileCreatedAt: List<String>,
    val isFavorite: List<Boolean>,
    val isTrashed: List<Boolean>,
    val livePhotoVideoId: List<String?>,
    val localOffsetHours: List<Double>,
    val ownerId: List<String>,
    val projectionType: List<String?>,
    val thumbhash: List<String?>,
    val ratio: List<Float>,
    val visibility: List<String>,
    val stack: List<List<String>?>? = null
) {
    fun toAssetResponses(): List<AssetResponse> {
        validateColumnLengths()
        return id.indices.map { i ->
            val isImg = isImage[i]
            val stackTuple = stack?.getOrElse(i) { null }
            AssetResponse(
                id = id[i],
                ownerId = ownerId[i],
                type = if (isImg) "IMAGE" else "VIDEO",
                originalFileName = "",
                fileCreatedAt = fileCreatedAt[i],
                isFavorite = isFavorite[i],
                isArchived = false,
                thumbhash = thumbhash[i],
                ratio = ratio[i],
                stack = stackTuple?.toAssetStack(primaryAssetId = id[i]),
                visibility = visibility[i]
            )
        }
    }

    private fun validateColumnLengths() {
        val expected = id.size
        val requiredColumns = listOf(
            "createdAt" to createdAt.size,
            "duration" to duration.size,
            "isImage" to isImage.size,
            "fileCreatedAt" to fileCreatedAt.size,
            "isFavorite" to isFavorite.size,
            "isTrashed" to isTrashed.size,
            "livePhotoVideoId" to livePhotoVideoId.size,
            "localOffsetHours" to localOffsetHours.size,
            "ownerId" to ownerId.size,
            "projectionType" to projectionType.size,
            "thumbhash" to thumbhash.size,
            "ratio" to ratio.size,
            "visibility" to visibility.size
        )
        val invalidRequired = requiredColumns.filter { (_, size) -> size != expected }
        require(invalidRequired.isEmpty()) {
            "Timeline required column lengths do not match id count=$expected: " +
                invalidRequired.joinToString { (name, size) -> "$name=$size" }
        }
        val optionalColumns = listOf(
            "stack" to stack?.size,
        )
        val invalid = optionalColumns.filter { (_, size) -> size != null && size != expected }
        require(invalid.isEmpty()) {
            "Timeline column lengths do not match id count=$expected: " +
                invalid.joinToString { (name, size) -> "$name=$size" }
        }
    }

    private fun List<String>.toAssetStack(primaryAssetId: String): AssetStackResponse {
        require(size == 2) { "Malformed Timeline stack tuple" }
        val stackId = getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Malformed Timeline stack tuple")
        val assetCount = getOrNull(1)?.toIntOrNull()
            ?: throw IllegalArgumentException("Malformed Timeline stack count")
        return AssetStackResponse(
            id = stackId,
            primaryAssetId = primaryAssetId,
            assetCount = assetCount
        )
    }
}
