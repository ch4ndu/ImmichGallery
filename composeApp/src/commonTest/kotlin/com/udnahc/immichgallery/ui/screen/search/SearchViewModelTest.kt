package com.udnahc.immichgallery.ui.screen.search

import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.model.SearchCapabilities
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchEndpoint
import com.udnahc.immichgallery.domain.model.SearchLoadState
import com.udnahc.immichgallery.domain.model.SearchPreferenceCapabilities
import com.udnahc.immichgallery.domain.model.SearchResult
import com.udnahc.immichgallery.domain.model.SearchSuggestion
import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest
import com.udnahc.immichgallery.domain.model.SearchSuggestionType
import com.udnahc.immichgallery.domain.model.SearchTag
import com.udnahc.immichgallery.domain.model.SearchTextMode
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.valueOrNull
import com.udnahc.immichgallery.ui.model.SearchUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.lighthousegames.logging.KmLog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun peopleDraftApplyAndDirectRemovalDriveFilterOnlySessionsThenRestoreExplore() =
        runTest(dispatcher.scheduler) {
            val submitted = mutableListOf<SearchCriteria>()
            val viewModel = viewModel(
                searchAssets = { endpoint, criteria, page ->
                    assertEquals(SearchEndpoint.METADATA, endpoint)
                    assertEquals(1, page)
                    submitted += criteria
                    Result.success(SearchResult(emptyList(), null))
                }
            )

            viewModel.applyCriteria(SearchCriteria(personIds = listOf("p1", "p2")))
            advanceUntilIdle()
            assertEquals(listOf("p1", "p2"), submitted.last().personIds)
            assertFalse(shouldShowExploreLanding(viewModel.state.value))

            viewModel.applyCriteria(viewModel.state.value.appliedCriteria.copy(personIds = listOf("p2")))
            advanceUntilIdle()
            assertEquals(listOf("p2"), submitted.last().personIds)

            viewModel.applyCriteria(viewModel.state.value.appliedCriteria.copy(personIds = emptyList()))
            advanceUntilIdle()
            assertEquals(2, submitted.size)
            assertTrue(shouldShowExploreLanding(viewModel.state.value))
            assertEquals(null, viewModel.state.value.submittedSession)
        }

    @Test
    fun capabilityAndPreferenceFailuresPublishIndependently() = runTest(dispatcher.scheduler) {
        val serverResult = CompletableDeferred<Result<SearchCapabilities>>()
        val viewModel = viewModel(
            searchAssets = { _, _, _ -> Result.success(SearchResult(emptyList(), null)) },
            loadServerCapabilities = { serverResult.await() },
            loadPreferenceCapabilities = {
                Result.failure(IllegalStateException("permission denied"))
            },
            initialServerCapabilities = SearchLoadState.Idle,
            initialPreferenceCapabilities = SearchLoadState.Idle
        )

        runCurrent()
        assertIs<SearchLoadState.Loading<SearchCapabilities>>(
            viewModel.state.value.serverCapabilities
        )
        assertIs<SearchLoadState.Error<SearchPreferenceCapabilities>>(
            viewModel.state.value.preferenceCapabilities
        )

        serverResult.complete(
            Result.success(
                SearchCapabilities(
                    searchEnabled = true,
                    contextEnabled = true,
                    ocrEnabled = false
                )
            )
        )
        advanceUntilIdle()

        assertEquals(
            true,
            viewModel.state.value.serverCapabilities.valueOrNull()?.contextEnabled
        )
        assertIs<SearchLoadState.Error<SearchPreferenceCapabilities>>(
            viewModel.state.value.preferenceCapabilities
        )
    }

    @Test
    fun contextRequiresPositiveCapabilityWhileMetadataWorksDuringCapabilityLoad() =
        runTest(dispatcher.scheduler) {
            val serverResult = CompletableDeferred<Result<SearchCapabilities>>()
            var searchCalls = 0
            val viewModel = viewModel(
                searchAssets = { _, _, _ ->
                    searchCalls++
                    Result.success(SearchResult(emptyList(), null))
                },
                loadServerCapabilities = { serverResult.await() },
                initialServerCapabilities = SearchLoadState.Idle
            )

            viewModel.updateQuery("beach")
            viewModel.search()
            assertEquals(SearchUiMessage.ContextUnavailable, viewModel.state.value.error)
            assertEquals(0, searchCalls)

            viewModel.updateTextMode(SearchTextMode.FILENAME)
            viewModel.search()
            advanceUntilIdle()
            assertEquals(1, searchCalls)

            serverResult.complete(
                Result.success(
                    SearchCapabilities(
                        searchEnabled = false,
                        contextEnabled = false,
                        ocrEnabled = false
                    )
                )
            )
            advanceUntilIdle()
            viewModel.search()

            assertEquals(SearchUiMessage.SearchUnavailable, viewModel.state.value.error)
            assertEquals(1, searchCalls)
        }

    @Test
    fun peopleAndAlbumsObserveRoomImmediatelyAndSyncOnlyWhenOpened() = runTest(dispatcher.scheduler) {
        val people = MutableStateFlow(listOf(person("person-1")))
        val albums = MutableStateFlow(listOf(album("album-1")))
        var peopleSyncs = 0
        var albumSyncs = 0
        val viewModel = viewModel(
            searchAssets = { _, _, _ -> Result.success(SearchResult(emptyList(), null)) },
            observePeople = { people },
            syncPeople = {
                peopleSyncs++
                Result.failure(IllegalStateException("people refresh failed"))
            },
            observeAlbums = { albums },
            syncAlbums = {
                albumSyncs++
                Result.failure(IllegalStateException("album refresh failed"))
            }
        )
        runCurrent()

        assertEquals(listOf("person-1"), viewModel.state.value.peopleOptions.valueOrNull()?.map(Person::id))
        assertEquals(listOf("album-1"), viewModel.state.value.albumOptions.valueOrNull()?.map(Album::id))
        assertEquals(0, peopleSyncs)
        assertEquals(0, albumSyncs)

        viewModel.openPeopleOptions()
        viewModel.openPeopleOptions()
        viewModel.openAlbumOptions()
        viewModel.openAlbumOptions()
        advanceUntilIdle()

        assertEquals(1, peopleSyncs)
        assertEquals(1, albumSyncs)
        assertIs<SearchLoadState.Error<List<Person>>>(viewModel.state.value.peopleOptions)
        assertIs<SearchLoadState.Error<List<Album>>>(viewModel.state.value.albumOptions)
        assertEquals(listOf("person-1"), viewModel.state.value.peopleOptions.valueOrNull()?.map(Person::id))
        assertEquals(listOf("album-1"), viewModel.state.value.albumOptions.valueOrNull()?.map(Album::id))
    }

    @Test
    fun tagsLoadLazilyOnceAndRemainRetryable() = runTest(dispatcher.scheduler) {
        var calls = 0
        val tags = listOf(searchTag("tag-1"))
        val enabledPreferences = SearchPreferenceCapabilities(
            tagsEnabled = true,
            ratingsEnabled = false
        )
        val viewModel = viewModel(
            searchAssets = { _, _, _ -> Result.success(SearchResult(emptyList(), null)) },
            loadPreferenceCapabilities = { Result.success(enabledPreferences) },
            loadTags = {
                calls++
                if (calls == 1) Result.failure(IllegalStateException("tag failure"))
                else Result.success(tags)
            },
            initialPreferenceCapabilities = SearchLoadState.Content(enabledPreferences)
        )

        assertEquals(0, calls)
        viewModel.openTagOptions()
        viewModel.openTagOptions()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertIs<SearchLoadState.Error<List<SearchTag>>>(viewModel.state.value.tagOptions)

        viewModel.retryTagOptions()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(tags, viewModel.state.value.tagOptions.valueOrNull())
    }

    @Test
    fun tagRequestWaitsForPositivePreferenceCapability() = runTest(dispatcher.scheduler) {
        val preferences = CompletableDeferred<Result<SearchPreferenceCapabilities>>()
        var calls = 0
        val viewModel = viewModel(
            searchAssets = { _, _, _ -> Result.success(SearchResult(emptyList(), null)) },
            loadPreferenceCapabilities = { preferences.await() },
            loadTags = {
                calls++
                Result.success(listOf(searchTag("tag-1")))
            },
            initialPreferenceCapabilities = SearchLoadState.Idle
        )

        viewModel.openTagOptions()
        runCurrent()
        assertEquals(0, calls)

        preferences.complete(
            Result.success(
                SearchPreferenceCapabilities(tagsEnabled = true, ratingsEnabled = false)
            )
        )
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(
            listOf("tag-1"),
            viewModel.state.value.tagOptions.valueOrNull()?.map(SearchTag::id)
        )
    }

    @Test
    fun successfulSuggestionsAreCachedByNormalizedDependencyTuple() = runTest(dispatcher.scheduler) {
        val calls = mutableListOf<SearchSuggestionRequest>()
        val result = listOf(SearchSuggestion.Value("Austin"), SearchSuggestion.Unknown)
        val viewModel = viewModel(
            searchAssets = { _, _, _ -> Result.success(SearchResult(emptyList(), null)) },
            loadSuggestions = { request ->
                calls += request
                Result.success(result)
            }
        )

        viewModel.loadSuggestionOptions(
            SearchSuggestionRequest(
                type = SearchSuggestionType.CITY,
                country = " US ",
                state = " Texas "
            )
        )
        advanceUntilIdle()
        viewModel.loadSuggestionOptions(
            SearchSuggestionRequest(
                type = SearchSuggestionType.CITY,
                country = "US",
                state = "Texas"
            )
        )
        advanceUntilIdle()

        assertEquals(
            listOf(SearchSuggestionRequest(SearchSuggestionType.CITY, "US", "Texas")),
            calls
        )
        assertEquals(
            result,
            viewModel.state.value.suggestionOptions
                .getValue(SearchSuggestionType.CITY)
                .state
                .valueOrNull()
        )
    }

    @Test
    fun suggestionFailureLogOmitsThrowableAndRequestDetails() = runTest(dispatcher.scheduler) {
        data class LogEntry(val message: String, val throwable: Throwable?)

        val entries = mutableListOf<LogEntry>()
        val logger = object : KmLog("SearchViewModel") {
            override fun error(tag: String, msg: String, t: Throwable?) {
                entries += LogEntry(msg, t)
            }
        }
        val request = SearchSuggestionRequest(
            type = SearchSuggestionType.STATE,
            country = "Private Country"
        )
        val failure = IllegalStateException(
            "GET https://private.example/api/search/suggestions?country=Private%20Country response=secret"
        )
        val viewModel = viewModel(
            searchAssets = { _, _, _ -> Result.success(SearchResult(emptyList(), null)) },
            loadSuggestions = { Result.failure(failure) },
            log = logger
        )

        viewModel.loadSuggestionOptions(request)
        advanceUntilIdle()

        assertEquals(1, entries.size)
        assertEquals(
            "Failed to load Search suggestions type=STATE failure=IllegalStateException",
            entries.single().message
        )
        assertEquals(null, entries.single().throwable)
        assertFalse(entries.single().message.contains("Private Country"))
        assertFalse(entries.single().message.contains("private.example"))
        assertFalse(entries.single().message.contains("secret"))
        assertIs<SearchLoadState.Error<List<SearchSuggestion>>>(
            viewModel.state.value.suggestionOptions.getValue(SearchSuggestionType.STATE).state
        )
    }

    @Test
    fun changedSuggestionDependenciesCancelAndRejectStalePublication() =
        runTest(dispatcher.scheduler) {
            val first = CompletableDeferred<Result<List<SearchSuggestion>>>()
            val second = CompletableDeferred<Result<List<SearchSuggestion>>>()
            val viewModel = viewModel(
                searchAssets = { _, _, _ -> Result.success(SearchResult(emptyList(), null)) },
                loadSuggestions = { request ->
                    if (request.country == "US") {
                        try {
                            first.await()
                        } catch (_: CancellationException) {
                            Result.success(listOf(SearchSuggestion.Value("stale")))
                        }
                    } else {
                        second.await()
                    }
                }
            )

            viewModel.loadSuggestionOptions(
                SearchSuggestionRequest(SearchSuggestionType.STATE, country = "US")
            )
            runCurrent()
            viewModel.loadSuggestionOptions(
                SearchSuggestionRequest(SearchSuggestionType.STATE, country = "CA")
            )
            runCurrent()
            second.complete(Result.success(listOf(SearchSuggestion.Value("Ontario"))))
            advanceUntilIdle()

            val state = viewModel.state.value.suggestionOptions
                .getValue(SearchSuggestionType.STATE)
            assertEquals(
                SearchSuggestionRequest(SearchSuggestionType.STATE, country = "CA"),
                state.request
            )
            assertEquals(
                listOf(SearchSuggestion.Value("Ontario")),
                state.state.valueOrNull()
            )
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
        },
        loadServerCapabilities: suspend () -> Result<SearchCapabilities> = {
            Result.success(enabledSearchCapabilities())
        },
        loadPreferenceCapabilities: suspend () -> Result<SearchPreferenceCapabilities> = {
            Result.success(SearchPreferenceCapabilities(tagsEnabled = false, ratingsEnabled = false))
        },
        observePeople: () -> Flow<List<Person>> = { flowOf(emptyList()) },
        syncPeople: suspend () -> Result<Unit> = { Result.success(Unit) },
        observeAlbums: () -> Flow<List<Album>> = { flowOf(emptyList()) },
        syncAlbums: suspend () -> Result<Unit> = { Result.success(Unit) },
        loadTags: suspend () -> Result<List<SearchTag>> = { Result.success(emptyList()) },
        loadSuggestions: suspend (SearchSuggestionRequest) -> Result<List<SearchSuggestion>> = {
            Result.success(emptyList())
        },
        initialServerCapabilities: SearchLoadState<SearchCapabilities> =
            SearchLoadState.Content(enabledSearchCapabilities()),
        initialPreferenceCapabilities: SearchLoadState<SearchPreferenceCapabilities> =
            SearchLoadState.Content(
                SearchPreferenceCapabilities(tagsEnabled = false, ratingsEnabled = false)
            ),
        log: KmLog = KmLog("SearchViewModelTest")
    ): SearchViewModel = SearchViewModel(
        searchAssets = searchAssets,
        apiKey = "",
        loadAssetDetail = { Result.failure(IllegalStateException("not used")) },
        hasSavedTargetRowHeight = false,
        initialTargetRowHeight = DEFAULT_TARGET_ROW_HEIGHT,
        saveTargetRowHeight = {},
        searchDispatcher = dispatcher,
        rowPacker = rowPacker,
        loadServerCapabilities = loadServerCapabilities,
        loadPreferenceCapabilities = loadPreferenceCapabilities,
        observePeople = observePeople,
        syncPeople = syncPeople,
        observeAlbums = observeAlbums,
        syncAlbums = syncAlbums,
        loadTags = loadTags,
        loadSuggestions = loadSuggestions,
        initialServerCapabilities = initialServerCapabilities,
        initialPreferenceCapabilities = initialPreferenceCapabilities,
        log = log
    )

    private fun enabledSearchCapabilities(): SearchCapabilities = SearchCapabilities(
        searchEnabled = true,
        contextEnabled = true,
        ocrEnabled = true
    )

    private fun person(id: String): Person = Person(
        id = id,
        name = "Person $id",
        thumbnailUrl = "https://example.test/$id/face"
    )

    private fun album(id: String): Album = Album(
        id = id,
        name = "Album $id",
        assetCount = 1,
        thumbnailUrl = "https://example.test/$id/cover"
    )

    private fun searchTag(id: String): SearchTag = SearchTag(
        id = id,
        parentId = null,
        name = "Tag $id",
        value = "Tag $id",
        color = null
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
