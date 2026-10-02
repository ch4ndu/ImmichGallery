package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.AssetDetail
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewerMetadataActionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var detail by mutableStateOf(metadata())
    private var captureDateTime by mutableStateOf<String?>(null)
    private var feedback by mutableStateOf<ViewerMetadataFeedback?>(null)
    private var nextCopyFeedback = ViewerMetadataFeedback.CoordinatesCopied
    private val mapUrls = mutableListOf<String>()
    private val copiedValues = mutableListOf<String>()
    private val timelineDates = mutableListOf<LocalDate>()

    @Test
    fun coordinateOnlyMetadataWiresMapsCopyAndTheCanonicalTimelineDate() {
        captureDateTime = "2024-06-15"
        detail = metadata(dateTime = "2020-01-01")
        show()

        compose.onNodeWithText("Location").assertIsDisplayed()
        compose.onNodeWithText("37.7749, -122.4194").assertIsDisplayed()
        click("Open in maps")
        click("Copy coordinates")
        click("Show in Timeline")

        compose.runOnIdle {
            assertEquals(
                listOf("https://www.google.com/maps/search/?api=1&query=37.7749%2C-122.4194"),
                mapUrls
            )
            assertEquals(listOf("37.7749, -122.4194"), copiedValues)
            assertEquals(listOf(LocalDate(2024, 6, 15)), timelineDates)
        }
    }

    @Test
    fun invalidAndMissingCoordinatesNeverExposeLocationActions() {
        detail = metadata(latitude = Double.NaN, longitude = 0.0, dateTime = "invalid")
        show()
        val invalidPairs = listOf(
            Double.NaN to 0.0,
            0.0 to Double.POSITIVE_INFINITY,
            91.0 to 0.0,
            0.0 to -181.0,
            null to 0.0,
            0.0 to null
        )
        invalidPairs.forEach { (latitude, longitude) ->
            compose.runOnIdle {
                detail = metadata(latitude, longitude, dateTime = "invalid")
            }
            compose.onNodeWithText("Open in maps").assertDoesNotExist()
            compose.onNodeWithText("Copy coordinates").assertDoesNotExist()
            compose.onNodeWithText("Show in Timeline").assertDoesNotExist()
        }
        compose.runOnIdle {
            assertTrue(mapUrls.isEmpty())
            assertTrue(copiedValues.isEmpty())
            assertTrue(timelineDates.isEmpty())
        }
    }

    @Test
    fun missingOrMalformedCanonicalDateDoesNotRemoveValidCoordinateActions() {
        detail = metadata(latitude = 0.0, longitude = 0.0, dateTime = null)
        show()
        compose.onNodeWithText("Show in Timeline").assertDoesNotExist()
        compose.onNodeWithText("0.0, 0.0").assertIsDisplayed()
        click("Copy coordinates")

        compose.runOnIdle {
            captureDateTime = "not-a-date"
            detail = detail.copy(dateTime = "2024-06-15")
        }
        compose.onNodeWithText("Show in Timeline").assertDoesNotExist()
        click("Open in maps")
        compose.runOnIdle {
            assertEquals(listOf("0.0, 0.0"), copiedValues)
            assertEquals(listOf("https://www.google.com/maps/search/?api=1&query=0.0%2C0.0"), mapUrls)
            assertTrue(timelineDates.isEmpty())
        }
    }

    @Test
    fun successAndFailureFeedbackIsVisibleAndAnnouncedWithoutPlatformActions() {
        show()
        compose.onNodeWithText("Coordinates copied").assertDoesNotExist()
        click("Copy coordinates")
        assertFeedback("Coordinates copied")

        compose.runOnIdle { nextCopyFeedback = ViewerMetadataFeedback.CopyFailed }
        click("Copy coordinates")
        assertFeedback("Could not copy coordinates. Try again.")
        compose.onNodeWithText("Coordinates copied").assertDoesNotExist()

        click("Open in maps")
        assertFeedback("Could not open maps on this device.")
        compose.onNodeWithText("Could not copy coordinates. Try again.").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, copiedValues.size)
            assertEquals(1, mapUrls.size)
        }
    }

    @Test
    fun validDateWithoutANavigationCallbackHasNoDeadTimelineAction() {
        show(withTimelineAction = false)
        compose.onNodeWithText("Show in Timeline").assertDoesNotExist()
        click("Copy coordinates")
        compose.runOnIdle { assertEquals(listOf("37.7749, -122.4194"), copiedValues) }
    }

    private fun show(withTimelineAction: Boolean = true) {
        compose.mainClock.autoAdvance = true
        val timelineCallback: ((LocalDate) -> Unit)? = if (withTimelineAction) {
            { date -> timelineDates.add(date) }
        } else {
            null
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(420.dp).height(640.dp)) {
                    AssetDetailContent(
                        detail = detail,
                        onPersonClick = { _, _ -> },
                        onOpenMaps = { url ->
                            mapUrls.add(url)
                            feedback = ViewerMetadataFeedback.MapsFailed
                        },
                        onCopyCoordinates = { value ->
                            copiedValues.add(value)
                            feedback = nextCopyFeedback
                        },
                        captureDateTime = captureDateTime,
                        onShowInTimeline = timelineCallback,
                        actionFeedback = feedback
                    )
                }
            }
        }
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun assertFeedback(text: String) {
        compose.onNodeWithText(text)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    private fun metadata(
        latitude: Double? = 37.7749,
        longitude: Double? = -122.4194,
        dateTime: String? = "2024-06-15"
    ) = AssetDetail(
        id = "fixture",
        fileName = "fixture.jpg",
        dateTime = dateTime,
        cameraMake = null,
        cameraModel = null,
        lensModel = null,
        focalLength = null,
        aperture = null,
        shutterSpeed = null,
        iso = null,
        latitude = latitude,
        longitude = longitude,
        city = null,
        state = null,
        country = null,
        fileSizeInByte = null,
        width = null,
        height = null,
        description = null,
        people = emptyList()
    )
}
