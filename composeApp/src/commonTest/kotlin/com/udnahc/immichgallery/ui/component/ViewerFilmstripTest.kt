package com.udnahc.immichgallery.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerFilmstripTest {
    @Test
    fun selectionOutsideTheViewportJumpsDirectlyWithoutTraversingIntermediatePages() {
        assertEquals(999, viewerFilmstripScrollTarget(1_000, 999, listOf(0, 1, 2, 3)))
        assertEquals(0, viewerFilmstripScrollTarget(1_000, 0, listOf(996, 997, 998, 999)))
    }

    @Test
    fun fullyVisibleSelectionPreservesStripPositionButClippedSelectionIsRevealed() {
        assertNull(viewerFilmstripScrollTarget(1_000, 500, listOf(499, 500, 501)))
        // A partially clipped tile is absent from the fully visible subset.
        assertEquals(502, viewerFilmstripScrollTarget(1_000, 502, listOf(499, 500, 501)))
    }

    @Test
    fun emptyAndShrinkingSourcesNeverRequestAnInvalidListIndex() {
        assertNull(viewerFilmstripScrollTarget(0, 999, emptyList()))
        assertNull(viewerFilmstripScrollTarget(-1, 999, emptyList()))
        assertEquals(2, viewerFilmstripScrollTarget(3, 999, emptyList()))
        assertEquals(0, viewerFilmstripScrollTarget(3, -1, emptyList()))
        assertNull(viewerFilmstripScrollTarget(3, 999, listOf(2)))
    }
}
