package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.placeholderGridKey
import kotlin.test.Test
import kotlin.test.assertNotEquals

class TimelinePlaceholderKeyTest {
    @Test
    fun placeholderKeysIncludeSectionIdentity() {
        val morning = placeholderGridKey(bucketIndex = 3, sectionLabel = "1 january 2025", chunkIndex = 0)
        val evening = placeholderGridKey(bucketIndex = 3, sectionLabel = "2 january 2025", chunkIndex = 0)

        assertNotEquals(morning, evening)
    }

    @Test
    fun bucketGeometryKeysIncludeGroupMode() {
        val request = TimelineMosaicGeometryRequest(
            availableWidth = 400f,
            maxRowHeight = 300f,
            spacing = 2f
        )

        val monthKey = timelineBucketGeometrySectionKey("2026-05", TimelineGroupSize.MONTH, request)
        val dayKey = timelineBucketGeometrySectionKey("2026-05", TimelineGroupSize.DAY, request)

        assertNotEquals(monthKey, dayKey)
    }

    @Test
    fun bucketGeometryKeysIncludeGeometryRequest() {
        val narrow = TimelineMosaicGeometryRequest(
            availableWidth = 400f,
            maxRowHeight = 300f,
            spacing = 2f
        )
        val wide = narrow.copy(availableWidth = 600f)

        val narrowKey = timelineBucketGeometrySectionKey("2026-05", TimelineGroupSize.MONTH, narrow)
        val wideKey = timelineBucketGeometrySectionKey("2026-05", TimelineGroupSize.MONTH, wide)

        assertNotEquals(narrowKey, wideKey)
    }
}
