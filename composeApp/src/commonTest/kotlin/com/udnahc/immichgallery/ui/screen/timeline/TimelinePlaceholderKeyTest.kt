package com.udnahc.immichgallery.ui.screen.timeline

import kotlin.test.Test
import kotlin.test.assertNotEquals

class TimelinePlaceholderKeyTest {
    @Test
    fun placeholderKeysIncludeSectionIdentity() {
        val morning = placeholderGridKey(bucketIndex = 3, sectionLabel = "1 january 2025", chunkIndex = 0)
        val evening = placeholderGridKey(bucketIndex = 3, sectionLabel = "2 january 2025", chunkIndex = 0)

        assertNotEquals(morning, evening)
    }
}
