package com.udnahc.immichgallery.domain.model

import com.udnahc.immichgallery.data.local.entity.TimelineAssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.model.AlbumResponse
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.PersonResponse
import com.udnahc.immichgallery.data.model.TimeBucketResponse
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val MIN_ASPECT_RATIO = 0.5f
private const val MAX_ASPECT_RATIO = 2.0f

fun AssetResponse.toDomain(baseUrl: String): Asset {
    val computedRatio = ratio?.takeIf { it > 0f }
        ?: exifInfo?.let { exif ->
            val w = exif.exifImageWidth
            val h = exif.exifImageHeight
            if (w != null && h != null && h > 0) w.toFloat() / h.toFloat() else null
        }
        ?: thumbhash?.let { thumbhashToAspectRatio(it) }
    return Asset(
        id = id,
        type = if (type == "VIDEO") AssetType.VIDEO else AssetType.IMAGE,
        fileName = originalFileName,
        createdAt = fileCreatedAt,
        thumbnailUrl = "$baseUrl/api/assets/$id/thumbnail?size=thumbnail&edited=true",
        originalUrl = "$baseUrl/api/assets/$id/original?edited=true",
        videoPlaybackUrl = "$baseUrl/api/assets/$id/video/playback?edited=true",
        isFavorite = isFavorite,
        stackCount = stackCount,
        aspectRatio = (computedRatio ?: 1f).coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
    )
}

fun AssetResponse.toDetail(baseUrl: String): AssetDetail {
    val exif = exifInfo
    return AssetDetail(
        id = id,
        fileName = originalFileName,
        dateTime = exif?.dateTimeOriginal ?: fileCreatedAt,
        cameraMake = exif?.make,
        cameraModel = exif?.model,
        lensModel = exif?.lensModel,
        focalLength = exif?.focalLength,
        aperture = exif?.fNumber,
        shutterSpeed = exif?.exposureTime,
        iso = exif?.iso,
        latitude = exif?.latitude,
        longitude = exif?.longitude,
        city = exif?.city,
        state = exif?.state,
        country = exif?.country,
        fileSizeInByte = exif?.fileSizeInByte,
        width = exif?.exifImageWidth,
        height = exif?.exifImageHeight,
        description = exif?.description,
        people = people.map { person ->
            AssetDetailPerson(
                id = person.id,
                name = person.name,
                thumbnailUrl = "$baseUrl/api/people/${person.id}/thumbnail?edited=true"
            )
        }
    )
}

fun TimeBucketResponse.toDomain(): TimelineBucket {
    val label = try {
        val instant = Instant.parse(timeBucket)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val monthName = local.month.name.lowercase().replaceFirstChar { it.uppercase() }
        "$monthName ${local.year}"
    } catch (_: Exception) {
        try {
            val date = LocalDate.parse(timeBucket.take(10))
            val monthName = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
            "$monthName ${date.year}"
        } catch (_: Exception) {
            timeBucket
        }
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
        thumbnailUrl = albumThumbnailAssetId?.let { "$baseUrl/api/assets/$it/thumbnail?size=thumbnail&edited=true" }
    )
}

fun PersonResponse.toDomain(baseUrl: String): Person {
    return Person(
        id = id,
        name = name,
        thumbnailUrl = "$baseUrl/api/people/$id/thumbnail?edited=true"
    )
}

fun TimeBucketResponse.toEntity(sortOrder: Int): TimelineBucketEntity {
    val domain = toDomain()
    return TimelineBucketEntity(
        timeBucket = timeBucket,
        displayLabel = domain.displayLabel,
        count = count,
        sortOrder = sortOrder
    )
}

fun TimelineBucketEntity.toDomain(): TimelineBucket = TimelineBucket(
    displayLabel = displayLabel,
    timeBucket = timeBucket,
    count = count
)

fun TimelineAssetEntity.toDomain(): Asset = Asset(
    id = id,
    type = if (type == "VIDEO") AssetType.VIDEO else AssetType.IMAGE,
    fileName = fileName,
    createdAt = createdAt,
    thumbnailUrl = thumbnailUrl,
    originalUrl = originalUrl,
    videoPlaybackUrl = videoPlaybackUrl,
    isFavorite = isFavorite,
    stackCount = stackCount,
    aspectRatio = aspectRatio
)

fun AssetResponse.toEntity(timeBucket: String, baseUrl: String, sortOrder: Int): TimelineAssetEntity {
    val computedRatio = ratio?.takeIf { it > 0f }
        ?: exifInfo?.let { exif ->
            val w = exif.exifImageWidth
            val h = exif.exifImageHeight
            if (w != null && h != null && h > 0) w.toFloat() / h.toFloat() else null
        }
        ?: thumbhash?.let { thumbhashToAspectRatio(it) }
    return TimelineAssetEntity(
        id = id,
        timeBucket = timeBucket,
        type = type,
        fileName = originalFileName,
        createdAt = fileCreatedAt,
        thumbnailUrl = "$baseUrl/api/assets/$id/thumbnail?size=thumbnail&edited=true",
        originalUrl = "$baseUrl/api/assets/$id/original?edited=true",
        videoPlaybackUrl = "$baseUrl/api/assets/$id/video/playback?edited=true",
        isFavorite = isFavorite,
        stackCount = stackCount,
        aspectRatio = (computedRatio ?: 1f).coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO),
        sortOrder = sortOrder
    )
}
