package com.udnahc.immichgallery.ui.component

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticPhotoOverlayTest {
    @Test
    fun completeDotRowUsesMeasuredPixelWidthAtMdpi() {
        val density = Density(1f)

        assertTrue(allPageDotsFit(totalPages = 20, availableWidthPx = 320, density = density))
        assertTrue(allPageDotsFit(totalPages = 22, availableWidthPx = 320, density = density))
        assertFalse(allPageDotsFit(totalPages = 23, availableWidthPx = 320, density = density))
    }

    @Test
    fun completeDotRowRoundsEachElementAtFractionalDensity() {
        val density = Density(1.25f)

        assertTrue(allPageDotsFit(totalPages = 20, availableWidthPx = 400, density = density))
        assertFalse(allPageDotsFit(totalPages = 22, availableWidthPx = 400, density = density))
        assertFalse(allPageDotsFit(totalPages = 22, availableWidthPx = 410, density = density))
        assertTrue(allPageDotsFit(totalPages = 22, availableWidthPx = 411, density = density))
    }

    @Test
    fun dotWidthDecisionRejectsInvalidBounds() {
        val density = Density(1f)

        assertFalse(allPageDotsFit(totalPages = 0, availableWidthPx = 320, density = density))
        assertFalse(allPageDotsFit(totalPages = -1, availableWidthPx = 320, density = density))
        assertFalse(allPageDotsFit(totalPages = 20, availableWidthPx = 0, density = density))
        assertFalse(allPageDotsFit(totalPages = 20, availableWidthPx = -1, density = density))
    }

    @Test
    fun middlePageDragTransfersDotActivenessContinuously() {
        val forwardPosition = pageIndicatorPosition(
            currentPage = 9,
            totalPages = 20,
            currentPageOffsetFraction = 0.25f
        )
        assertEquals(9.25f, forwardPosition, absoluteTolerance = 0.0001f)
        assertEquals(0.75f, pageDotActiveness(9, 20, forwardPosition), absoluteTolerance = 0.0001f)
        assertEquals(0.25f, pageDotActiveness(10, 20, forwardPosition), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageDotActiveness(8, 20, forwardPosition), absoluteTolerance = 0.0001f)

        val crossedPosition = pageIndicatorPosition(
            currentPage = 10,
            totalPages = 20,
            currentPageOffsetFraction = -0.25f
        )
        assertEquals(9.75f, crossedPosition, absoluteTolerance = 0.0001f)
        assertEquals(0.25f, pageDotActiveness(9, 20, crossedPosition), absoluteTolerance = 0.0001f)
        assertEquals(0.75f, pageDotActiveness(10, 20, crossedPosition), absoluteTolerance = 0.0001f)
    }

    @Test
    fun segmentedProgressTracksFractionalMiddlePosition() {
        assertEquals(0.5125f, pageIndicatorProgress(20, 9.25f), absoluteTolerance = 0.0001f)
        assertEquals(0.5375f, pageIndicatorProgress(20, 9.75f), absoluteTolerance = 0.0001f)
        assertEquals(0.05f, pageIndicatorProgress(20, 0f), absoluteTolerance = 0.0001f)
        assertEquals(1f, pageIndicatorProgress(20, 19f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun fractionalIndicatorHelpersClampInvalidBounds() {
        assertEquals(0f, pageIndicatorPosition(-1, 20, 0f), absoluteTolerance = 0.0001f)
        assertEquals(19f, pageIndicatorPosition(20, 20, 0f), absoluteTolerance = 0.0001f)
        assertEquals(10f, pageIndicatorPosition(10, 20, Float.NaN), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageIndicatorPosition(10, 0, 0.25f), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageDotActiveness(-1, 20, 0f), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageDotActiveness(0, 0, 0f), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageDotActiveness(0, 20, Float.NaN), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageIndicatorProgress(0, 0f), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageIndicatorProgress(20, Float.NaN), absoluteTolerance = 0.0001f)
        assertEquals(0.05f, pageIndicatorProgress(20, -1f), absoluteTolerance = 0.0001f)
        assertEquals(1f, pageIndicatorProgress(20, 20f), absoluteTolerance = 0.0001f)
    }
}
