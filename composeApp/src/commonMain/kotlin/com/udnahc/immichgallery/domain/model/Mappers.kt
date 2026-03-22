package com.udnahc.immichgallery.domain.model

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
                thumbnailUrl = "$baseUrl/api/people/${person.id}/thumbnail"
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
