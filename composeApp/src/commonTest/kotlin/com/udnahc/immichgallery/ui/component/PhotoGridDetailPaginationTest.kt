package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.data.model.ImmichPaginationCompatibilityException
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun compatibilityFailureExhaustsRejectedCursor() {
        assertEquals(
            DetailPaginationCursor(hasMore = false, nextPage = null),
            detailPaginationCursorAfterFailure(
                hasMore = true,
                nextPage = 2,
                failure = ImmichPaginationCompatibilityException()
            )
        )
    }

    @Test
    fun transientFailurePreservesRetryableCursor() {
        assertEquals(
            DetailPaginationCursor(hasMore = true, nextPage = 2),
            detailPaginationCursorAfterFailure(
                hasMore = true,
                nextPage = 2,
                failure = IllegalStateException("temporary failure")
            )
        )
    }
}
