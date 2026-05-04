package com.udnahc.immichgallery.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineMosaicGeometryTest {
    @Test
    fun widthKeyRoundsToIntegerDp() {
        assertEquals(401, timelineMosaicGeometryWidthKey(400.6f))
        assertEquals(400, timelineMosaicGeometryWidthKey(400.4f))
    }

    @Test
    fun dimensionKeyPreservesTwoDecimalPlaces() {
        assertEquals(12346, timelineMosaicGeometryDimensionKey(123.456f))
        assertEquals(200, timelineMosaicGeometryDimensionKey(2f))
    }
}
