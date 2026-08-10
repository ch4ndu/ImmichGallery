package com.udnahc.immichgallery.ui.screen.search

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchEndpoint
import com.udnahc.immichgallery.domain.model.SearchResult
import com.udnahc.immichgallery.domain.model.SearchTextMode
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
    fun editingQueryInvalidatesSubmittedSessionAndInFlightResponse() = runTest(dispatcher.scheduler) {
        val response = CompletableDeferred<Result<SearchResult>>()
        val viewModel = viewModel(searchAssets = { endpoint, criteria, _ ->
            assertEquals(SearchEndpoint.SMART, endpoint)
            assertEquals("beach", criteria.query)
            response.await()
        })

        viewModel.updateQuery("beach")
        viewModel.search()
        runCurrent()
        viewModel.updateQuery("mountains")
        response.complete(Result.success(SearchResult(listOf(asset("stale")), null)))
        advanceUntilIdle()

        assertEquals("mountains", viewModel.state.value.query)
        assertEquals(null, viewModel.state.value.submittedSession)
        assertEquals(emptyList(), viewModel.state.value.results)
        assertFalse(viewModel.state.value.hasSearched)
    }

    @Test
    fun changingTextModeInvalidatesSubmittedSession() = runTest(dispatcher.scheduler) {
        val response = CompletableDeferred<Result<SearchResult>>()
        val viewModel = viewModel(searchAssets = { _, _, _ -> response.await() })

        viewModel.updateQuery("beach")
        viewModel.search()
        runCurrent()
        viewModel.updateTextMode(SearchTextMode.FILENAME)
        response.complete(Result.success(SearchResult(listOf(asset("stale")), null)))
        advanceUntilIdle()

        assertEquals(SearchTextMode.FILENAME, viewModel.state.value.textMode)
        assertEquals(null, viewModel.state.value.submittedSession)
        assertEquals(emptyList(), viewModel.state.value.results)
    }

    @Test
    fun editedQueryCannotReceiveOldPaginationPage() = runTest(dispatcher.scheduler) {
        val pageTwo = CompletableDeferred<Result<SearchResult>>()
        val viewModel = viewModel(searchAssets = { _, _, page ->
            if (page == 1) {
                Result.success(SearchResult(listOf(asset("first")), 2))
            } else {
                pageTwo.await()
            }
        })

        viewModel.updateQuery("beach")
        viewModel.search()
        advanceUntilIdle()
        viewModel.loadMore()
        runCurrent()
        viewModel.updateQuery("mountains")
        pageTwo.complete(Result.success(SearchResult(listOf(asset("stale-page")), null)))
        advanceUntilIdle()

        assertEquals("mountains", viewModel.state.value.query)
        assertEquals(null, viewModel.state.value.submittedSession)
        assertEquals(emptyList(), viewModel.state.value.results)
        assertEquals(null, viewModel.state.value.nextPage)
    }

    @Test
    fun filterOnlyApplyFreezesNormalizedMetadataSession() = runTest(dispatcher.scheduler) {
        val calls = mutableListOf<Triple<SearchEndpoint, SearchCriteria, Int>>()
        val viewModel = viewModel(searchAssets = { endpoint, criteria, page ->
            calls += Triple(endpoint, criteria, page)
            Result.success(SearchResult(listOf(asset("filtered")), null))
        })

        viewModel.applyCriteria(SearchCriteria(personIds = listOf(" p2 ", "p1", "p2")))
        advanceUntilIdle()

        val session = viewModel.state.value.submittedSession
        val submittedCriteria = session?.criteria ?: error("Expected submitted session")
        assertEquals(SearchEndpoint.METADATA, session.endpoint)
        assertEquals(listOf("p2", "p1"), submittedCriteria.personIds)
        assertEquals(listOf(Triple(SearchEndpoint.METADATA, submittedCriteria, 1)), calls)
        assertEquals(listOf("filtered"), viewModel.state.value.results.map(Asset::id))
    }

    @Test
    fun applyingFiltersSubmitsCurrentlyVisibleQueryAndMode() = runTest(dispatcher.scheduler) {
        var submitted: Triple<SearchEndpoint, SearchCriteria, Int>? = null
        val viewModel = viewModel(searchAssets = { endpoint, criteria, page ->
            submitted = Triple(endpoint, criteria, page)
            Result.success(SearchResult(emptyList(), null))
        })

        viewModel.updateQuery("  beach.jpg  ")
        viewModel.updateTextMode(SearchTextMode.FILENAME)
        viewModel.applyCriteria(SearchCriteria(isFavorite = true))
        advanceUntilIdle()

        val request = submitted ?: error("Expected a submitted request")
        assertEquals(SearchEndpoint.METADATA, request.first)
        assertEquals("beach.jpg", request.second.query)
        assertEquals(SearchTextMode.FILENAME, request.second.textMode)
        assertEquals(true, request.second.isFavorite)
        assertEquals(1, request.third)
        assertEquals(request.second, viewModel.state.value.submittedSession?.criteria)
    }

    @Test
    fun clearingFinalFilterWithBlankTextReturnsToExploreWithoutRequest() = runTest(dispatcher.scheduler) {
        var calls = 0
        val viewModel = viewModel(searchAssets = { _, _, _ ->
            calls++
            Result.success(SearchResult(emptyList(), null))
        })
        viewModel.applyCriteria(SearchCriteria(isFavorite = true))
        advanceUntilIdle()

        viewModel.clearFilters()
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(null, viewModel.state.value.submittedSession)
        assertEquals(true, shouldShowExploreLanding(viewModel.state.value))
    }

    @Test
    fun paginationUsesSubmittedCriteriaAndKeepsExactFailedPageForRetry() = runTest(dispatcher.scheduler) {
        var pageTwoAttempts = 0
        val submitted = mutableListOf<Pair<SearchCriteria, Int>>()
        val viewModel = viewModel(searchAssets = { _, criteria, page ->
            submitted += criteria to page
            when (page) {
                1 -> Result.success(SearchResult(listOf(asset("first")), 4))
                else -> {
                    pageTwoAttempts++
                    if (pageTwoAttempts == 1) {
                        Result.failure(IllegalStateException("temporary"))
                    } else {
                        Result.success(SearchResult(listOf(asset("second")), null))
                    }
                }
            }
        })

        viewModel.updateQuery("beach")
        viewModel.search()
        advanceUntilIdle()
        val frozen = viewModel.state.value.submittedSession?.criteria
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(4, viewModel.state.value.nextPage)
        assertFalse(viewModel.state.value.isLoadingMore)
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1, 4, 4), submitted.map { it.second })
        assertEquals(listOf(frozen, frozen, frozen), submitted.map { it.first })
        assertEquals(listOf("first", "second"), viewModel.state.value.results.map(Asset::id))
    }

    @Test
    fun repeatedBackwardAndConsumedNextPagesTerminate() = runTest(dispatcher.scheduler) {
        val requestedPages = mutableListOf<Int>()
        val viewModel = viewModel(searchAssets = { _, _, page ->
            requestedPages += page
            Result.success(SearchResult(listOf(asset("page-$page")), page))
        })

        viewModel.updateQuery("beach")
        viewModel.search()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1), requestedPages)
        assertEquals(null, viewModel.state.value.nextPage)
    }

    @Test
    fun unsupportedOnlyPageIsConsumedAndDuplicateIdsKeepFirstSeenOrder() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel(searchAssets = { _, _, page ->
            when (page) {
                1 -> Result.success(SearchResult(listOf(asset("a"), asset("b")), 2))
                2 -> Result.success(SearchResult(emptyList(), 3))
                else -> Result.success(SearchResult(listOf(asset("b"), asset("c")), null))
            }
        })

        viewModel.updateQuery("beach")
        viewModel.search()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.nextPage)
        assertEquals(setOf(1, 2), viewModel.state.value.submittedSession?.consumedPages)
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), viewModel.state.value.results.map(Asset::id))
        assertEquals(setOf(1, 2, 3), viewModel.state.value.submittedSession?.consumedPages)
    }

    @Test
    fun queryEditPreventsPendingRowsFromPublishing() = runTest(dispatcher.scheduler) {
        val packingStarted = CompletableDeferred<Unit>()
        val releasePacking = CompletableDeferred<Unit>()
        val viewModel = viewModel(
            searchAssets = { _, _, _ -> Result.success(SearchResult(listOf(asset("stale")), null)) },
            rowPacker = { snapshot ->
                if (snapshot.results.isEmpty()) {
                    emptyList()
                } else {
                    packingStarted.complete(Unit)
                    try {
                        releasePacking.await()
                    } catch (_: CancellationException) {
                        // Models CPU work that reaches completion after cancellation.
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

        assertEquals(emptyList(), viewModel.state.value.results)
        assertEquals(emptyList(), viewModel.state.value.rows)
    }

    @Test
    fun automaticPagingAttemptsEachGenerationAndPageOnlyOnce() {
        val firstAttempt = SearchAutoLoadKey(requestGeneration = 3L, page = 2)

        assertEquals(true, shouldAutomaticallyLoadSearchPage(true, false, firstAttempt, null))
        assertEquals(false, shouldAutomaticallyLoadSearchPage(true, false, firstAttempt, firstAttempt))
        assertEquals(
            true,
            shouldAutomaticallyLoadSearchPage(
                true,
                false,
                SearchAutoLoadKey(requestGeneration = 4L, page = 2),
                firstAttempt
            )
        )
    }

    @Test
    fun exploreLandingRequiresBlankVisibleAndAppliedCriteria() {
        assertEquals(true, shouldShowExploreLanding(SearchState()))
        assertEquals(false, shouldShowExploreLanding(SearchState(query = "beach")))
        assertEquals(
            false,
            shouldShowExploreLanding(SearchState(appliedCriteria = SearchCriteria(isFavorite = true)))
        )
        assertEquals(false, shouldShowExploreLanding(SearchState(hasSearched = true)))
    }

    private fun viewModel(
        searchAssets: suspend (SearchEndpoint, SearchCriteria, Int) -> Result<SearchResult>,
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
        searchAssets = searchAssets,
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
