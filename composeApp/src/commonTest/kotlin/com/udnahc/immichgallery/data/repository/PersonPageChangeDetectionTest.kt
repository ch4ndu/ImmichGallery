package com.udnahc.immichgallery.data.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonPageChangeDetectionTest {
    @Test
    fun unchangedNonFinalPageDoesNotNeedTailTruncation() {
        assertFalse(
            pagedOwnerPageChanged(
                beforeAssetIds = listOf("a", "b", "c", "d"),
                baseOffset = 0,
                fetchedAssetIds = listOf("a", "b"),
                hasMore = true
            )
        )
    }

    @Test
    fun reorderedPageNeedsTailTruncation() {
        assertTrue(
            pagedOwnerPageChanged(
                beforeAssetIds = listOf("a", "b", "c", "d"),
                baseOffset = 0,
                fetchedAssetIds = listOf("b", "a"),
                hasMore = true
            )
        )
    }

    @Test
    fun finalShortPageWithStaleTailNeedsCleanup() {
        assertTrue(
            pagedOwnerPageChanged(
                beforeAssetIds = listOf("a", "b", "c", "d"),
                baseOffset = 2,
                fetchedAssetIds = listOf("c"),
                hasMore = false
            )
        )
    }
}
