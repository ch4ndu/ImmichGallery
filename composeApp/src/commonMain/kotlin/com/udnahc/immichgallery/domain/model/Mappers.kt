package com.udnahc.immichgallery.domain.model

import com.udnahc.immichgallery.data.local.entity.AlbumEntity
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.PersonEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.model.AlbumResponse
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.PersonResponse
import com.udnahc.immichgallery.data.model.TimeBucketResponse
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun AssetResponse.computeAspectRatio(): Float {
    // For edited assets, the server does NOT update width/height/ratio after
    // a non-destructive edit — those reflect the original. Only the thumbnail
    // (and its thumbhash) is regenerated. Prefer thumbhash when edited so the
    // grid cell at least matches the displayed thumbnail's orientation; a
    // background enrichment pass fetches /edits and replaces this with the
    // precise cropped aspect.
    val sources: List<() -> Float?> = if (isEdited) {
        listOf(
            { thumbhash?.let { thumbhashToAspectRatio(it) } },
            { aspectFromWidthHeight() },
            { ratio?.takeIf { it > 0f } },
            { aspectFromExif() },
        )
    } else {
        listOf(
            { aspectFromWidthHeight() },
            { ratio?.takeIf { it > 0f } },
            { aspectFromExif() },
            { thumbhash?.let { thumbhashToAspectRatio(it) } },
        )
    }
    return sources.firstNotNullOfOrNull { it() } ?: 1f
}

private fun AssetResponse.aspectFromWidthHeight(): Float? {
    val w = width; val h = height
    return if (w != null && h != null && h > 0) w.toFloat() / h.toFloat() else null
}

private fun AssetResponse.aspectFromExif(): Float? = exifInfo?.let { exif ->
    val w = exif.exifImageWidth
    val h = exif.exifImageHeight
    if (w != null && h != null && h > 0) w.toFloat() / h.toFloat() else null
}

// --- AssetResponse -> Domain ---

fun AssetResponse.toDomain(baseUrl: String): Asset {
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
        aspectRatio = computeAspectRatio()
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

// --- AssetResponse -> Room Entity ---

fun AssetResponse.toAssetEntity(): AssetEntity {
    return AssetEntity(
        id = id,
        type = type,
        fileName = originalFileName,
        createdAt = fileCreatedAt,
        isFavorite = isFavorite,
        stackCount = stackCount,
        aspectRatio = computeAspectRatio(),
        isEdited = isEdited,
        editsResolved = false
    )
}

// --- Room Entity -> Domain ---

fun AssetEntity.toDomain(baseUrl: String): Asset = Asset(
    id = id,
    type = if (type == "VIDEO") AssetType.VIDEO else AssetType.IMAGE,
    fileName = fileName,
    createdAt = createdAt,
    thumbnailUrl = "$baseUrl/api/assets/$id/thumbnail?size=thumbnail&edited=true",
    originalUrl = "$baseUrl/api/assets/$id/original?edited=true",
    videoPlaybackUrl = "$baseUrl/api/assets/$id/video/playback?edited=true",
    isFavorite = isFavorite,
    stackCount = stackCount,
    aspectRatio = aspectRatio
)

// --- TimeBucket mappers ---

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

// --- Album mappers ---

fun AlbumResponse.toDomain(baseUrl: String): Album {
    return Album(
        id = id,
        name = albumName,
        assetCount = assetCount,
        thumbnailUrl = albumThumbnailAssetId?.let { "$baseUrl/api/assets/$it/thumbnail?size=thumbnail&edited=true" }
    )
}

fun AlbumResponse.toAlbumEntity(): AlbumEntity {
    return AlbumEntity(
        id = id,
        name = albumName,
        assetCount = assetCount,
        thumbnailAssetId = albumThumbnailAssetId,
        updatedAt = updatedAt
    )
}

fun AlbumEntity.toDomain(baseUrl: String): Album {
    return Album(
        id = id,
        name = name,
        assetCount = assetCount,
        thumbnailUrl = thumbnailAssetId?.let { "$baseUrl/api/assets/$it/thumbnail?size=thumbnail&edited=true" }
    )
}

// --- Person mappers ---

fun PersonResponse.toDomain(baseUrl: String): Person {
    return Person(
        id = id,
        name = name,
        thumbnailUrl = "$baseUrl/api/people/$id/thumbnail?edited=true"
    )
}

fun PersonResponse.toPersonEntity(sortOrder: Int): PersonEntity {
    return PersonEntity(
        id = id,
        name = name,
        isHidden = isHidden,
        sortOrder = sortOrder
    )
}

fun PersonEntity.toDomain(baseUrl: String): Person {
    return Person(
        id = id,
        name = name,
        thumbnailUrl = "$baseUrl/api/people/$id/thumbnail?edited=true"
    )
}
