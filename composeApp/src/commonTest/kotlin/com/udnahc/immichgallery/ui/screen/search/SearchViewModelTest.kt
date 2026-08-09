package com.udnahc.immichgallery.ui.screen.search

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.SearchResult
import com.udnahc.immichgallery.domain.model.packIntoRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun editingQueryInvalidatesInFlightResponse() = runTest(dispatcher.scheduler) {
        val beachResponse = CompletableDeferred<Result<SearchResult>>()
        val viewModel = viewModel(
            smartSearch = { query, _ ->
                assertEquals("beach", query)
                beachResponse.await()
            }
        )

        viewModel.updateQuery("beach")
        viewModel.search()
        runCurrent()
        viewModel.updateQuery("mountains")
        beachResponse.complete(Result.success(SearchResult(listOf(asset("beach")), null)))
        advanceUntilIdle()

        assertEquals("mountains", viewModel.state.value.query)
        assertEquals(emptyList(), viewModel.state.value.results)
        assertFalse(viewModel.state.value.hasSearched)
    }

    @Test
    fun changingSearchTypeInvalidatesInFlightResponse() = runTest(dispatcher.scheduler) {
        val response = CompletableDeferred<Result<SearchResult>>()
        val viewModel = viewModel(smartSearch = { _, _ -> response.await() })

        viewModel.updateQuery("beach")
        viewModel.search()
        runCurrent()
        viewModel.updateSearchType(SearchType.METADATA)
        response.complete(Result.success(SearchResult(listOf(asset("stale")), null)))
        advanceUntilIdle()

        assertEquals(SearchType.METADATA, viewModel.state.value.searchType)
        assertEquals(emptyList(), viewModel.state.value.results)
    }

    @Test
    fun editedQueryCannotReceiveOldPaginationPage() = runTest(dispatcher.scheduler) {
        val pageTwo = CompletableDeferred<Result<SearchResult>>()
        val viewModel = viewModel(
            smartSearch = { _, page ->
                if (page == 1) Result.success(SearchResult(listOf(asset("first")), 2))
                else pageTwo.await()
            }
        )

        viewModel.updateQuery("beach")
        viewModel.search()
        advanceUntilIdle()
        viewModel.loadMore()
        runCurrent()
        viewModel.updateQuery("mountains")
        pageTwo.complete(Result.success(SearchResult(listOf(asset("stale-page")), null)))
        advanceUntilIdle()

        assertEquals("mountains", viewModel.state.value.query)
        assertEquals(emptyList(), viewModel.state.value.results)
        assertEquals(null, viewModel.state.value.nextPage)
    }

    @Test
    fun repeatedNextPageTokenTerminatesPagination() = runTest(dispatcher.scheduler) {
        val requestedPages = mutableListOf<Int>()
        val viewModel = viewModel(
            smartSearch = { _, page ->
                requestedPages += page
                Result.success(SearchResult(listOf(asset("page-$page")), 1))
            }
        )

        viewModel.updateQuery("beach")
        viewModel.search()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1), requestedPages)
        assertEquals(null, viewModel.state.value.nextPage)
        assertEquals(listOf("page-1"), viewModel.state.value.results.map(Asset::id))
    }

    @Test
    fun failedPageRemainsAvailableForExplicitRetry() = runTest(dispatcher.scheduler) {
        var pageTwoAttempts = 0
        val viewModel = viewModel(
            smartSearch = { _, page ->
                when (page) {
                    1 -> Result.success(SearchResult(listOf(asset("first")), 2))
                    else -> {
                        pageTwoAttempts++
                        if (pageTwoAttempts == 1) {
                            Result.failure(IllegalStateException("temporary failure"))
                        } else {
                            Result.success(SearchResult(listOf(asset("second")), null))
                        }
                    }
                }
            }
        )

        viewModel.updateQuery("beach")
        viewModel.search()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(1, pageTwoAttempts)
        assertEquals(2, viewModel.state.value.nextPage)
        assertFalse(viewModel.state.value.isLoadingMore)

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(2, pageTwoAttempts)
        assertEquals(null, viewModel.state.value.nextPage)
        assertEquals(listOf("first", "second"), viewModel.state.value.results.map(Asset::id))
    }

    @Test
    fun automaticPagingAttemptsEachGenerationAndPageOnlyOnce() {
        val firstAttempt = SearchAutoLoadKey(requestGeneration = 3L, page = 2)

        assertEquals(
            true,
            shouldAutomaticallyLoadSearchPage(
                shouldLoadMore = true,
                isLoadingMore = false,
                candidate = firstAttempt,
                lastAttempt = null
            )
        )
        assertEquals(
            false,
            shouldAutomaticallyLoadSearchPage(
                shouldLoadMore = true,
                isLoadingMore = false,
                candidate = firstAttempt,
                lastAttempt = firstAttempt
            )
        )
        assertEquals(
            true,
            shouldAutomaticallyLoadSearchPage(
                shouldLoadMore = true,
                isLoadingMore = false,
                candidate = SearchAutoLoadKey(requestGeneration = 4L, page = 2),
                lastAttempt = firstAttempt
            )
        )
    }

    @Test
    fun queryEditPreventsPendingRowsFromPublishing() = runTest(dispatcher.scheduler) {
        val packingStarted = CompletableDeferred<Unit>()
        val releasePacking = CompletableDeferred<Unit>()
        val viewModel = viewModel(
            smartSearch = { _, _ -> Result.success(SearchResult(listOf(asset("stale")), null)) },
            rowPacker = { snapshot ->
                if (snapshot.results.isEmpty()) {
                    emptyList()
                } else {
                    packingStarted.complete(Unit)
                    try {
                        releasePacking.await()
                    } catch (_: CancellationException) {
                        // Model non-cooperative CPU work that completes after cancellation.
                    }
                    packIntoRows(
                        snapshot.results,
                        availableWidth = snapshot.availableWidth,
                        targetRowHeight = snapshot.targetRowHeight,
                        spacing = GRID_SPACING_DP,
                        maxRowHeight = snapshot.rowHeightBounds.max
                    )
                }
            }
        )

        viewModel.setAvailableWidth(500f)
        runCurrent()
        viewModel.updateQuery("beach")
        viewModel.search()
        runCurrent()
        packingStarted.await()

        viewModel.updateQuery("mountains")
        releasePacking.complete(Unit)
        advanceUntilIdle()

        assertEquals("mountains", viewModel.state.value.query)
        assertEquals(emptyList(), viewModel.state.value.results)
        assertEquals(emptyList(), viewModel.state.value.rows)
    }

    private fun viewModel(
        smartSearch: suspend (String, Int) -> Result<SearchResult>,
        rowPacker: suspend (SearchState) -> List<RowItem> = { snapshot ->
            if (snapshot.results.isEmpty() || snapshot.availableWidth <= 0f) {
                emptyList()
            } else {
                packIntoRows(
                    snapshot.results,
                    availableWidth = snapshot.availableWidth,
                    targetRowHeight = snapshot.targetRowHeight,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = snapshot.rowHeightBounds.max
                )
            }
        }
    ): SearchViewModel = SearchViewModel(
        smartSearch = smartSearch,
        metadataSearch = { _, _ -> Result.success(SearchResult(emptyList(), null)) },
        apiKey = "",
        loadAssetDetail = { Result.failure(IllegalStateException("not used")) },
        hasSavedTargetRowHeight = false,
        initialTargetRowHeight = DEFAULT_TARGET_ROW_HEIGHT,
        saveTargetRowHeight = {},
        searchDispatcher = dispatcher,
        rowPacker = rowPacker
    )

    private fun asset(id: String): Asset = Asset(
        id = id,
        type = AssetType.IMAGE,
        fileName = "$id.jpg",
        createdAt = "2026-05-10T12:00:00Z",
        thumbnailUrl = "https://example.test/$id/thumb",
        originalUrl = "https://example.test/$id/original"
    )
}
