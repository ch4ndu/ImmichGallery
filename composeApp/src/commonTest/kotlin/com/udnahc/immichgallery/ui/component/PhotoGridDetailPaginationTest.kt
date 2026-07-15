package com.udnahc.immichgallery.ui.component

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoGridDetailPaginationTest {
    @Test
    fun initialNearEndDoesNotAutoLoadButManualLoadRemainsAvailable() {
        assertFalse(
            canAutoLoadMore(
                hasMore = true,
                isLoadingMore = false,
                canScrollBackward = false,
                lastVisibleIndex = 4,
                totalItemsCount = 5
            )
        )
        assertTrue(canManuallyLoadMore(hasMore = true, isLoadingMore = false))
    }

    @Test
    fun scrolledNearEndAutoLoadsOneEligiblePage() {
        assertTrue(
            canAutoLoadMore(
                hasMore = true,
                isLoadingMore = false,
                canScrollBackward = true,
                lastVisibleIndex = 8,
                totalItemsCount = 10
            )
        )
    }

    @Test
    fun loadingOrExhaustedPagesAreNotEligibleForMoreLoads() {
        assertFalse(
            canAutoLoadMore(
                hasMore = true,
                isLoadingMore = true,
                canScrollBackward = true,
                lastVisibleIndex = 8,
                totalItemsCount = 10
            )
        )
        assertFalse(
            canAutoLoadMore(
                hasMore = false,
                isLoadingMore = false,
                canScrollBackward = true,
                lastVisibleIndex = 8,
                totalItemsCount = 10
            )
        )
        assertFalse(canManuallyLoadMore(hasMore = false, isLoadingMore = false))
    }
}
