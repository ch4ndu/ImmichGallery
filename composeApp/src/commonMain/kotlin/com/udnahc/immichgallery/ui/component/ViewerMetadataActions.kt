package com.udnahc.immichgallery.ui.component

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal data class ViewerCoordinates(val latitude: Double, val longitude: Double) {
    // Double's string representation is locale-independent and preserves precision.
    val text: String get() = "$latitude, $longitude"
    val mapsUrl: String
        get() = "https://www.google.com/maps/search/?api=1&query=$latitude%2C$longitude"
}

internal fun viewerCoordinates(latitude: Double?, longitude: Double?): ViewerCoordinates? {
    if (latitude == null || longitude == null) return null
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
    return ViewerCoordinates(latitude, longitude)
}

/**
 * Prefer the source Asset.createdAt (Immich fileCreatedAt), which Timeline groups.
 * Offset-bearing instants use the same device time zone as Timeline. Valid ISO
 * dates and offsetless ISO datetimes retain their calendar date, matching its
 * fallback without accepting a valid prefix followed by malformed EXIF text.
 * A supplied but invalid canonical value has no target; EXIF is only a fallback
 * when the source date was not supplied, since it may represent another day.
 */
internal fun viewerCaptureDate(
    captureDateTime: String?,
    detailDateTime: String?,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): LocalDate? = parseViewerCaptureDate(captureDateTime ?: detailDateTime, timeZone)

private fun parseViewerCaptureDate(value: String?, timeZone: TimeZone): LocalDate? {
    if (value.isNullOrBlank()) return null
    val date = try {
        Instant.parse(value).toLocalDateTime(timeZone).date
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(value).date
        } catch (_: Exception) {
            try {
                LocalDate.parse(value)
            } catch (_: Exception) {
                null
            }
        }
    }
    return date?.takeIf { it.year in 1..9999 }
}

internal enum class ViewerMetadataFeedback {
    CoordinatesCopied,
    CopyFailed,
    MapsFailed
}
