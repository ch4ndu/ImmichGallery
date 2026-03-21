package com.udnahc.immichgallery.domain.model

import com.udnahc.immichgallery.data.local.entity.AlbumAssetEntity
import com.udnahc.immichgallery.data.local.entity.PersonAssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetEntity
import com.udnahc.immichgallery.data.model.AlbumResponse
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.PersonResponse
import com.udnahc.immichgallery.data.model.TimeBucketResponse
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

fun AssetResponse.toDomain(baseUrl: String): Asset {
    return Asset(
        id = id,
        type = if (type == "VIDEO") AssetType.VIDEO else AssetType.IMAGE,
        fileName = originalFileName,
        createdAt = fileCreatedAt,
        thumbnailUrl = "$baseUrl/api/assets/$id/thumbnail?size=preview",
        originalUrl = "$baseUrl/api/assets/$id/original",
        videoPlaybackUrl = "$baseUrl/api/assets/$id/video/playback",
        isFavorite = isFavorite,
        stackCount = stackCount
    )
}

fun TimeBucketResponse.toDomain(): TimelineBucket {
    val label = try {
        val instant = Instant.parse(timeBucket)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val monthName = local.month.name.lowercase().replaceFirstChar { it.uppercase() }
        "$monthName ${local.year}"
    } catch (_: Exception) {
        timeBucket
    }
    return TimelineBucket(
        displayLabel = label,
        timeBucket = timeBucket,
        count = count
    )
}

fun AlbumResponse.toDomain(baseUrl: String): Album {
    return Album(
        id = id,
        name = albumName,
        assetCount = assetCount,
        thumbnailUrl = albumThumbnailAssetId?.let { "$baseUrl/api/assets/$it/thumbnail?size=preview" }
    )
}

fun PersonResponse.toDomain(baseUrl: String): Person {
    return Person(
        id = id,
        name = name,
        thumbnailUrl = "$baseUrl/api/people/$id/thumbnail"
    )
}

// Entity mappers — API response → Room entity

fun AssetResponse.toTimelineEntity(timeBucket: String): TimelineAssetEntity {
    return TimelineAssetEntity(
        id = id,
        type = type,
        fileName = originalFileName,
        createdAt = fileCreatedAt,
        isFavorite = isFavorite,
        timeBucket = timeBucket,
        stackId = stackId,
        stackCount = stackCount
    )
}

fun AssetResponse.toAlbumEntity(
    albumId: String,
    sortOrder: Int
): AlbumAssetEntity {
    return AlbumAssetEntity(
        id = id,
        type = type,
        fileName = originalFileName,
        createdAt = fileCreatedAt,
        isFavorite = isFavorite,
        albumId = albumId,
        sortOrder = sortOrder
    )
}

fun AssetResponse.toPersonEntity(
    personId: String,
    sortOrder: Int
): PersonAssetEntity {
    return PersonAssetEntity(
        id = id,
        type = type,
        fileName = originalFileName,
        createdAt = fileCreatedAt,
        isFavorite = isFavorite,
        personId = personId,
        sortOrder = sortOrder
    )
}

// Entity → Domain model (reconstructs URLs from baseUrl)

fun TimelineAssetEntity.toDomain(baseUrl: String): Asset {
    return Asset(
        id = id,
        type = if (type == "VIDEO") AssetType.VIDEO else AssetType.IMAGE,
        fileName = fileName,
        createdAt = createdAt,
        thumbnailUrl = "$baseUrl/api/assets/$id/thumbnail?size=preview",
        originalUrl = "$baseUrl/api/assets/$id/original",
        videoPlaybackUrl = "$baseUrl/api/assets/$id/video/playback",
        isFavorite = isFavorite,
        stackCount = stackCount
    )
}

fun AlbumAssetEntity.toDomain(baseUrl: String): Asset {
    return Asset(
        id = id,
        type = if (type == "VIDEO") AssetType.VIDEO else AssetType.IMAGE,
        fileName = fileName,
        createdAt = createdAt,
        thumbnailUrl = "$baseUrl/api/assets/$id/thumbnail?size=preview",
        originalUrl = "$baseUrl/api/assets/$id/original",
        videoPlaybackUrl = "$baseUrl/api/assets/$id/video/playback",
        isFavorite = isFavorite
    )
}

fun PersonAssetEntity.toDomain(baseUrl: String): Asset {
    return Asset(
        id = id,
        type = if (type == "VIDEO") AssetType.VIDEO else AssetType.IMAGE,
        fileName = fileName,
        createdAt = createdAt,
        thumbnailUrl = "$baseUrl/api/assets/$id/thumbnail?size=preview",
        originalUrl = "$baseUrl/api/assets/$id/original",
        videoPlaybackUrl = "$baseUrl/api/assets/$id/video/playback",
        isFavorite = isFavorite
    )
}
