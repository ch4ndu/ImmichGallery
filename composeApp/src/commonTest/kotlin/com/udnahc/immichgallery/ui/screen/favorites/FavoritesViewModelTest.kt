package com.udnahc.immichgallery.ui.screen.favorites

import com.udnahc.immichgallery.data.model.UserResponse
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.usecase.search.FavoritesFailureKind
import com.udnahc.immichgallery.domain.usecase.search.FavoritesLoadException
import com.udnahc.immichgallery.domain.usecase.search.FavoritesLoadOutcome
import com.udnahc.immichgallery.ui.model.FavoritesUiMessage
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
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
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
    fun initialLoadResolvesOwnerAndPublishesFavorites() = runTest(dispatcher.scheduler) {
        val requested = mutableListOf<Triple<String, Int, Set<Int>>>()
        val viewModel = viewModel(
            loadFavorites = { ownerId, page, consumed ->
                requested += Triple(ownerId, page, consumed)
                Result.success(outcome(listOf(asset("favorite")), nextPage = 2, consumed = setOf(1)))
            }
        )

        advanceUntilIdle()

        assertEquals(listOf(Triple("owner", 1, emptySet())), requested)
        assertEquals(listOf("favorite"), viewModel.state.value.assets.map(Asset::id))
        assertEquals(2, viewModel.state.value.nextPage)
        assertEquals(setOf(1), viewModel.state.value.consumedPages)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun emptyAndCurrentUserFailureRemainDistinctInitialStates() = runTest(dispatcher.scheduler) {
        val emptyViewModel = viewModel(
            loadFavorites = { _, _, _ ->
                Result.success(outcome(emptyList(), nextPage = null, consumed = setOf(1)))
            }
        )
        val userFailureViewModel = viewModel(
            loadCurrentUser = { Result.failure(IllegalStateException("user failed")) }
        )

        advanceUntilIdle()

        assertEquals(emptyList(), emptyViewModel.state.value.assets)
        assertNull(emptyViewModel.state.value.error)
        assertEquals(FavoritesUiMessage.LoadFailed, userFailureViewModel.state.value.error)
        assertEquals(1, userFailureViewModel.state.value.firstUnconsumedPage)
    }

    @Test
    fun permissionFailurePublishesTypedMessageAndRetryProgress() = runTest(dispatcher.scheduler) {
        val failure = FavoritesLoadException(
            consumedPages = setOf(1),
            firstUnconsumedPage = 2,
            kind = FavoritesFailureKind.PERMISSION_DENIED,
            cause = IllegalStateException("forbidden")
        )
        val viewModel = viewModel(
            loadFavorites = { _, _, _ -> Result.failure(failure) }
        )

        advanceUntilIdle()

        assertEquals(FavoritesUiMessage.PermissionDenied, viewModel.state.value.error)
        assertEquals(setOf(1), viewModel.state.value.consumedPages)
        assertEquals(2, viewModel.state.value.firstUnconsumedPage)
    }

    @Test
    fun initialRetryStartsAtFirstUnconsumedPage() = runTest(dispatcher.scheduler) {
        val requests = mutableListOf<Pair<Int, Set<Int>>>()
        var attempts = 0
        val viewModel = viewModel(
            loadFavorites = { _, page, consumed ->
                requests += page to consumed
                attempts++
                if (attempts == 1) {
                    Result.failure(
                        FavoritesLoadException(
                            consumedPages = setOf(1),
                            firstUnconsumedPage = 2,
                            kind = FavoritesFailureKind.REQUEST_FAILED,
                            cause = IllegalStateException("temporary")
                        )
                    )
                } else {
                    Result.success(outcome(listOf(asset("owned")), null, setOf(1, 2)))
                }
            }
        )
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf(1 to emptySet(), 2 to setOf(1)), requests)
        assertEquals(listOf("owned"), viewModel.state.value.assets.map(Asset::id))
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun loadMoreAppendsAndFailureRemainsExplicitlyRetryable() = runTest(dispatcher.scheduler) {
        var pageTwoAttempts = 0
        val viewModel = viewModel(
            loadFavorites = { _, page, _ ->
                when (page) {
                    1 -> Result.success(outcome(listOf(asset("first")), 2, setOf(1)))
                    else -> {
                        pageTwoAttempts++
                        if (pageTwoAttempts == 1) {
                            Result.failure(
                                FavoritesLoadException(
                                    consumedPages = setOf(1),
                                    firstUnconsumedPage = 2,
                                    kind = FavoritesFailureKind.REQUEST_FAILED,
                                    cause = IllegalStateException("temporary")
                                )
                            )
                        } else {
                            Result.success(outcome(listOf(asset("second")), null, setOf(1, 2)))
                        }
                    }
                }
            }
        )
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(FavoritesUiMessage.LoadFailed, viewModel.state.value.loadMoreError)
        assertEquals(2, viewModel.state.value.nextPage)
        assertEquals(listOf("first"), viewModel.state.value.assets.map(Asset::id))

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), viewModel.state.value.assets.map(Asset::id))
        assertNull(viewModel.state.value.nextPage)
        assertNull(viewModel.state.value.loadMoreError)
    }

    @Test
    fun duplicateLoadMoreClaimsIssueOneRequest() = runTest(dispatcher.scheduler) {
        val pageTwo = CompletableDeferred<Result<FavoritesLoadOutcome>>()
        var pageTwoRequests = 0
        val viewModel = viewModel(
            loadFavorites = { _, page, _ ->
                if (page == 1) {
                    Result.success(outcome(listOf(asset("first")), 2, setOf(1)))
                } else {
                    pageTwoRequests++
                    pageTwo.await()
                }
            }
        )
        advanceUntilIdle()

        viewModel.loadMore()
        viewModel.loadMore()
        runCurrent()

        assertEquals(1, pageTwoRequests)
        pageTwo.complete(Result.success(outcome(listOf(asset("second")), null, setOf(1, 2))))
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), viewModel.state.value.assets.map(Asset::id))
    }

    @Test
    fun retryRejectsStaleInitialResult() = runTest(dispatcher.scheduler) {
        val firstResult = CompletableDeferred<Result<FavoritesLoadOutcome>>()
        var calls = 0
        val viewModel = viewModel(
            loadFavorites = { _, _, _ ->
                calls++
                if (calls == 1) {
                    try {
                        firstResult.await()
                    } catch (_: CancellationException) {
                        Result.success(outcome(listOf(asset("stale")), null, setOf(1)))
                    }
                } else {
                    Result.success(outcome(listOf(asset("fresh")), null, setOf(1)))
                }
            }
        )
        runCurrent()

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf("fresh"), viewModel.state.value.assets.map(Asset::id))
    }

    @Test
    fun staleOwnerResultCannotStartFavoritesRequest() = runTest(dispatcher.scheduler) {
        val firstOwner = CompletableDeferred<Result<UserResponse>>()
        val requestedOwners = mutableListOf<String>()
        var ownerCalls = 0
        val viewModel = viewModel(
            loadCurrentUser = {
                ownerCalls++
                if (ownerCalls == 1) {
                    try {
                        firstOwner.await()
                    } catch (_: CancellationException) {
                        Result.success(UserResponse(id = "stale-owner"))
                    }
                } else {
                    Result.success(UserResponse(id = "fresh-owner"))
                }
            },
            loadFavorites = { ownerId, _, _ ->
                requestedOwners += ownerId
                Result.success(outcome(listOf(asset("fresh")), null, setOf(1)))
            }
        )
        runCurrent()

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf("fresh-owner"), requestedOwners)
        assertEquals("fresh-owner", viewModel.state.value.ownerId)
    }

    @Test
    fun staleRowProjectionCannotReplaceRowsForRetriedAssets() = runTest(dispatcher.scheduler) {
        val packingStarted = CompletableDeferred<Unit>()
        val releasePacking = CompletableDeferred<Unit>()
        var loads = 0
        val viewModel = viewModel(
            loadFavorites = { _, _, _ ->
                loads++
                val id = if (loads == 1) "stale" else "fresh"
                Result.success(outcome(listOf(asset(id)), null, setOf(1)))
            },
            rowPacker = { snapshot ->
                if (snapshot.assets.firstOrNull()?.id == "stale") {
                    packingStarted.complete(Unit)
                    try {
                        releasePacking.await()
                    } catch (_: CancellationException) {
                        // Model non-cooperative projection completing after cancellation.
                    }
                }
                if (snapshot.assets.isEmpty() || snapshot.availableWidth <= 0f) {
                    emptyList()
                } else {
                    packIntoRows(
                        assets = snapshot.assets,
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
        packingStarted.await()

        viewModel.retry()
        releasePacking.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("fresh"), viewModel.state.value.rows.flatMap { row ->
            row.photos.map { it.asset.id }
        })
    }

    @Test
    fun rowProjectionDensityPersistenceAndCurrentReturnTargetAreIndependent() =
        runTest(dispatcher.scheduler) {
            val savedHeights = mutableListOf<Float>()
            val viewModel = viewModel(
                loadFavorites = { _, _, _ ->
                    Result.success(
                        outcome(
                            listOf(asset("first"), asset("second")),
                            null,
                            setOf(1)
                        )
                    )
                },
                saveTargetRowHeight = savedHeights::add
            )
            viewModel.setAvailableViewportHeight(1_000f)
            viewModel.setAvailableWidth(500f)
            advanceUntilIdle()

            viewModel.setTargetRowHeight(220f)
            advanceUntilIdle()
            viewModel.lastViewedAssetId = "second"

            assertEquals(220f, savedHeights.last())
            assertEquals(0, viewModel.getDisplayItemIndexForReturn())
            assertEquals(listOf("first", "second"), viewModel.state.value.rows.flatMap { row ->
                row.photos.map { it.asset.id }
            })
        }

    @Test
    fun automaticPagingAttemptsEachGenerationAndPageOnlyOnce() {
        val key = FavoritesAutoLoadKey(requestGeneration = 2L, page = 3)

        assertEquals(true, shouldAutomaticallyLoadFavoritesPage(true, false, key, null))
        assertEquals(false, shouldAutomaticallyLoadFavoritesPage(true, false, key, key))
        assertEquals(false, shouldAutomaticallyLoadFavoritesPage(true, true, key, null))
    }

    private fun viewModel(
        loadCurrentUser: suspend () -> Result<UserResponse> = {
            Result.success(UserResponse(id = "owner"))
        },
        loadFavorites: suspend (String, Int, Set<Int>) -> Result<FavoritesLoadOutcome> =
            { _, _, _ -> Result.success(outcome(emptyList(), null, setOf(1))) },
        saveTargetRowHeight: (Float) -> Unit = {},
        rowPacker: suspend (FavoritesState) -> List<RowItem> = { snapshot ->
            if (snapshot.assets.isEmpty() || snapshot.availableWidth <= 0f) {
                emptyList()
            } else {
                packIntoRows(
                    assets = snapshot.assets,
                    availableWidth = snapshot.availableWidth,
                    targetRowHeight = snapshot.targetRowHeight,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = snapshot.rowHeightBounds.max
                )
            }
        }
    ): FavoritesViewModel = FavoritesViewModel(
        loadCurrentUser = loadCurrentUser,
        loadFavorites = loadFavorites,
        apiKey = "api-key",
        loadAssetDetail = { Result.failure(IllegalStateException("not used")) },
        loadAssetStack = { Result.failure(IllegalStateException("not used")) },
        hasSavedTargetRowHeight = false,
        initialTargetRowHeight = DEFAULT_TARGET_ROW_HEIGHT,
        saveTargetRowHeight = saveTargetRowHeight,
        requestDispatcher = dispatcher,
        layoutDispatcher = dispatcher,
        rowPacker = rowPacker
    )

    private fun outcome(
        assets: List<Asset>,
        nextPage: Int?,
        consumed: Set<Int>
    ): FavoritesLoadOutcome = FavoritesLoadOutcome(assets, nextPage, consumed)

    private fun asset(id: String): Asset = Asset(
        id = id,
        type = AssetType.IMAGE,
        fileName = "$id.jpg",
        createdAt = "2026-08-09T12:00:00Z",
        thumbnailUrl = "https://example.test/$id/thumb",
        originalUrl = "https://example.test/$id/original"
    )
}
