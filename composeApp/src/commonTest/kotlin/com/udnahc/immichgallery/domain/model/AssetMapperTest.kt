package com.udnahc.immichgallery.domain.model

import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.ExifInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetMapperTest {
    @Test
    fun detailDimensionsPreferExifOverTopLevelValues() {
        val detail = AssetResponse(
            id = "asset-1",
            width = 4000,
            height = 3000,
            exifInfo = ExifInfo(exifImageWidth = 1200, exifImageHeight = 800)
        ).toDetail("https://example.test")

        assertEquals(1200, detail.width)
        assertEquals(800, detail.height)
    }

    @Test
    fun detailDimensionsFallBackIndependentlyToTopLevelValues() {
        val detail = AssetResponse(
            id = "asset-1",
            width = 4000,
            height = 3000,
            exifInfo = ExifInfo(exifImageWidth = 1200, exifImageHeight = null)
        ).toDetail("https://example.test")

        assertEquals(1200, detail.width)
        assertEquals(3000, detail.height)
    }

    @Test
    fun videoDetailDimensionsFallBackIndependentlyToTopLevelWidth() {
        val detail = AssetResponse(
            id = "asset-1",
            type = "VIDEO",
            width = 4000,
            height = 3000,
            exifInfo = ExifInfo(exifImageWidth = null, exifImageHeight = 800)
        ).toDetail("https://example.test")

        assertEquals(4000, detail.width)
        assertEquals(800, detail.height)
    }

    @Test
    fun detailDimensionsRemainAbsentWhenExifAndTopLevelValuesAreAbsent() {
        val detail = AssetResponse(id = "asset-1").toDetail("https://example.test")

        assertNull(detail.width)
        assertNull(detail.height)
    }
}
