package com.udnahc.immichgallery.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MosaicDisplayCacheTest {
    @Test
    fun viewConfigDefaultsEnableCacheAndDisableMosaicZoom() {
        val config = ViewConfig()

        assertTrue(config.cacheMosaicResults)
        assertTrue(config.disableZoomWhenMosaicEnabled)
    }

    @Test
    fun cacheDimensionKeysMatchExpectedPrecision() {
        assertEquals(401, mosaicDisplayCacheWidthKey(400.6f))
        assertEquals(12346, mosaicDisplayCacheDimensionKey(123.456f))
    }

    @Test
    fun familiesKeyIsNormalizedAndStable() {
        val key = mosaicDisplayCacheFamiliesKey(
            setOf(MosaicTemplateFamily.SIX_TILE, MosaicTemplateFamily.FOUR_TILE)
        )

        assertEquals("FOUR_TILE|SIX_TILE", key)
    }
}
