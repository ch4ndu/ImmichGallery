package com.udnahc.immichgallery.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

class StaticPhotoOverlayTest {
    @Test
    fun shortPageSetsShowEveryDot() {
        assertEquals(0..4, visiblePageDotIndices(currentPage = 0, totalPages = 5))
        assertEquals(0..4, visiblePageDotIndices(currentPage = 4, totalPages = 5))
    }

    @Test
    fun longPageSetsShiftTheVisibleDotWindowWithTheSettledPage() {
        assertEquals(0..6, visiblePageDotIndices(currentPage = 0, totalPages = 10))
        assertEquals(0..6, visiblePageDotIndices(currentPage = 3, totalPages = 10))
        assertEquals(1..7, visiblePageDotIndices(currentPage = 4, totalPages = 10))
        assertEquals(3..9, visiblePageDotIndices(currentPage = 8, totalPages = 10))
        assertEquals(3..9, visiblePageDotIndices(currentPage = 9, totalPages = 10))
    }

    @Test
    fun dotWindowClampsInvalidInputs() {
        assertEquals(0..6, visiblePageDotIndices(currentPage = -1, totalPages = 10))
        assertEquals(3..9, visiblePageDotIndices(currentPage = 20, totalPages = 10))
        assertEquals(IntRange.EMPTY, visiblePageDotIndices(currentPage = 0, totalPages = 0))
        assertEquals(
            IntRange.EMPTY,
            visiblePageDotIndices(currentPage = 0, totalPages = 10, maxVisibleDots = 0)
        )
    }
}
