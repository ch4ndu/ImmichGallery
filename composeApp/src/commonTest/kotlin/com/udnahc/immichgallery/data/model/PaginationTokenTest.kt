package com.udnahc.immichgallery.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PaginationTokenTest {
    @Test
    fun nullTokenEndsPagination() {
        assertNull(SearchResultAssets(nextPage = null).nextPageNumberAfter(currentPage = 1))
    }

    @Test
    fun positiveForwardTokenIsAccepted() {
        assertEquals(3, SearchResultAssets(nextPage = "3").nextPageNumberAfter(currentPage = 2))
    }

    @Test
    fun malformedTokenIsRejected() {
        assertFailsWith<ImmichPaginationCompatibilityException> {
            SearchResultAssets(nextPage = "later").nextPageNumberAfter(currentPage = 1)
        }
    }

    @Test
    fun nonPositiveTokenIsRejected() {
        assertFailsWith<ImmichPaginationCompatibilityException> {
            SearchResultAssets(nextPage = "0").nextPageNumberAfter(currentPage = 1)
        }
    }

    @Test
    fun equalTokenIsRejected() {
        assertFailsWith<ImmichPaginationCompatibilityException> {
            SearchResultAssets(nextPage = "2").nextPageNumberAfter(currentPage = 2)
        }
    }

    @Test
    fun backwardTokenIsRejected() {
        assertFailsWith<ImmichPaginationCompatibilityException> {
            SearchResultAssets(nextPage = "1").nextPageNumberAfter(currentPage = 2)
        }
    }

    @Test
    fun consumedTokenIsRejected() {
        assertFailsWith<ImmichPaginationCompatibilityException> {
            SearchResultAssets(nextPage = "4").nextPageNumberAfter(
                currentPage = 2,
                consumedPages = setOf(1, 4)
            )
        }
    }
}
