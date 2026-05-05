package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewConfigTest {
    @Test
    fun normalizedCoercesMosaicColumnCountIntoSupportedRange() {
        assertEquals(
            SUPPORTED_MOSAIC_COLUMN_COUNTS.first,
            ViewConfig(mosaicColumnCount = SUPPORTED_MOSAIC_COLUMN_COUNTS.first - 1).normalized.mosaicColumnCount
        )
        assertEquals(
            SUPPORTED_MOSAIC_COLUMN_COUNTS.last,
            ViewConfig(mosaicColumnCount = SUPPORTED_MOSAIC_COLUMN_COUNTS.last + 1).normalized.mosaicColumnCount
        )
    }

    @Test
    fun defaultMosaicColumnCountIsSupported() {
        assertEquals(DEFAULT_MOSAIC_COLUMN_COUNT, ViewConfig().normalized.mosaicColumnCount)
    }
}
