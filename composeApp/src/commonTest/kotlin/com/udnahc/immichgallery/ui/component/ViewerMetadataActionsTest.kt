package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.timelineDaySections
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ViewerMetadataActionsTest {
    @Test
    fun coordinatesAcceptBothBoundariesAndZeroWithoutLocationText() {
        listOf(-90.0 to -180.0, 90.0 to 180.0, 0.0 to 0.0).forEach { (lat, lon) ->
            val coordinates = assertNotNull(viewerCoordinates(lat, lon))
            assertEquals(lat, coordinates.latitude)
            assertEquals(lon, coordinates.longitude)
        }
    }

    @Test
    fun missingNonFiniteAndOutOfRangeCoordinatesHaveNoActions() {
        assertNull(viewerCoordinates(null, 0.0))
        assertNull(viewerCoordinates(0.0, null))
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertNull(viewerCoordinates(value, 0.0))
            assertNull(viewerCoordinates(0.0, value))
        }
        listOf(-90.0001, 90.0001).forEach { assertNull(viewerCoordinates(it, 0.0)) }
        listOf(-180.0001, 180.0001).forEach { assertNull(viewerCoordinates(0.0, it)) }
    }

    @Test
    fun copyIsAPlainPairAndMapsHasOnlyValidatedCoordinates() {
        val coordinates = assertNotNull(viewerCoordinates(37.7749, -122.4194))
        assertEquals("37.7749, -122.4194", coordinates.text)
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=37.7749%2C-122.4194",
            coordinates.mapsUrl
        )
    }

    @Test
    fun canonicalCaptureDateTakesPrecedenceOverConflictingExif() {
        assertEquals(
            LocalDate(2024, 6, 14),
            viewerCaptureDate(
                captureDateTime = "2024-06-15T01:30:00Z",
                detailDateTime = "2020-01-01T12:00:00Z",
                timeZone = TimeZone.of("America/Chicago")
            )
        )
    }

    @Test
    fun offsetInstantsUseDeviceLocalDayIncludingCrossingMidnight() {
        assertEquals(
            LocalDate(2024, 6, 14),
            viewerCaptureDate("2024-06-15T01:30:00+02:00", null, TimeZone.UTC)
        )
        assertEquals(
            LocalDate(2024, 6, 16),
            viewerCaptureDate("2024-06-15T23:30:00-02:00", null, TimeZone.UTC)
        )
    }

    @Test
    fun validDetailFallbackSupportsInstantsDatesAndOffsetlessIsoExif() {
        val localZone = TimeZone.of("America/Chicago")
        assertEquals(
            LocalDate(2024, 2, 29),
            viewerCaptureDate(null, "2024-02-29T08:30:00Z", localZone)
        )
        assertEquals(
            LocalDate(2024, 2, 29),
            viewerCaptureDate(null, "2024-02-29", localZone)
        )
        assertEquals(
            LocalDate(2024, 2, 29),
            viewerCaptureDate(null, "2024-02-29T00:30:00.123", localZone)
        )
    }

    @Test
    fun invalidCanonicalDateCannotNavigateToAConflictingExifDay() {
        assertNull(viewerCaptureDate("invalid", "2024-02-29", TimeZone.UTC))
        assertNull(viewerCaptureDate("", "2024-02-29", TimeZone.UTC))
        assertNull(viewerCaptureDate("2024-02-30", "2024-02-29", TimeZone.UTC))
    }

    @Test
    fun malformedDatesCannotExposeBrokenTimelineActions() {
        listOf(
            "", " ", "not-a-date", "2023-02-29", "2024-13-01", "2024-02-30T12:00:00Z",
            "2024-02-29T25:00:00", "2024-02-29T12:00:00+25:00", "2024-02-29junk",
            "2024:02:29 12:30:00", "0000-01-01", "+10000-01-01"
        ).forEach { value ->
            assertNull(viewerCaptureDate(null, value, TimeZone.UTC), value)
        }
        assertNull(viewerCaptureDate(null, null, TimeZone.UTC))
        assertNull(viewerCaptureDate("invalid", "also invalid", TimeZone.UTC))
    }

    @Test
    fun canonicalDateAgreesWithActualTimelineGrouping() {
        listOf("2024-06-15T01:30:00Z", "2024-02-29", "2024-02-29T00:30:00").forEach { value ->
            val asset = Asset("a", AssetType.IMAGE, "a.jpg", value, "", "")
            val timelineDate = timelineDaySections("2024-06", listOf(asset)).single().date
            assertEquals(timelineDate, viewerCaptureDate(value, null), value)
        }
    }
}
