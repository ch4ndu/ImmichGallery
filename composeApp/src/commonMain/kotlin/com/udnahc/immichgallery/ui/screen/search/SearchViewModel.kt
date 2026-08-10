package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.SearchCapabilities
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchEndpoint
import com.udnahc.immichgallery.domain.model.SearchLoadState
import com.udnahc.immichgallery.domain.model.SearchPreferenceCapabilities
import com.udnahc.immichgallery.domain.model.SearchResult
import com.udnahc.immichgallery.domain.model.SearchSession
import com.udnahc.immichgallery.domain.model.SearchSuggestion
import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest
import com.udnahc.immichgallery.domain.model.SearchSuggestionType
import com.udnahc.immichgallery.domain.model.SearchTag
import com.udnahc.immichgallery.domain.model.SearchTextMode
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.model.selectSearchEndpoint
import com.udnahc.immichgallery.domain.model.valueOrNull
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumsUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetStackUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPeopleUseCase
import com.udnahc.immichgallery.domain.usecase.search.GetSearchCapabilitiesUseCase
import com.udnahc.immichgallery.domain.usecase.search.GetSearchSuggestionsUseCase
import com.udnahc.immichgallery.domain.usecase.search.GetSearchTagsUseCase
import com.udnahc.immichgallery.domain.usecase.search.SearchAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.ui.model.SearchUiMessage
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

@Immutable
data class SearchState(
    val query: String = "",
    val textMode: SearchTextMode = SearchTextMode.CONTEXT,
    val appliedCriteria: SearchCriteria = SearchCriteria(),
    val submittedSession: SearchSession? = null,
    val results: List<Asset> = emptyList(),
    val rows: List<RowItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: SearchUiMessage? = null,
    val hasSearched: Boolean = false,
    val serverCapabilities: SearchLoadState<SearchCapabilities> = SearchLoadState.Idle,
    val preferenceCapabilities: SearchLoadState<SearchPreferenceCapabilities> = SearchLoadState.Idle,
    val peopleOptions: SearchLoadState<List<Person>> = SearchLoadState.Idle,
    val albumOptions: SearchLoadState<List<Album>> = SearchLoadState.Idle,
    val tagOptions: SearchLoadState<List<SearchTag>> = SearchLoadState.Idle,
    val suggestionOptions: Map<SearchSuggestionType, SearchSuggestionOptionState> = emptyMap(),
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f)
) {
    val nextPage: Int? get() = submittedSession?.nextPage

    internal val activeRequestGeneration: Long get() = submittedSession?.generation ?: 0L
}

@Immutable
data class SearchSuggestionOptionState(
    val request: SearchSuggestionRequest,
    val state: SearchLoadState<List<SearchSuggestion>>
)

class SearchViewModel internal constructor(
    private val searchAssets: suspend (SearchEndpoint, SearchCriteria, Int) -> Result<SearchResult>,
    val apiKey: String,
    private val loadAssetDetail: suspend (String) -> Result<AssetDetail>,
    private val loadAssetStack: suspend (String) -> Result<AssetStackLookup> = {
        Result.success(AssetStackLookup.NoStack)
    },
    hasSavedTargetRowHeight: Boolean,
    initialTargetRowHeight: Float,
    private val saveTargetRowHeight: (Float) -> Unit,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rowPacker: suspend (SearchState) -> List<RowItem> = { snapshot ->
        packSearchRows(snapshot)
    },
    private val loadServerCapabilities: suspend () -> Result<SearchCapabilities> = {
        Result.success(DEFAULT_SEARCH_CAPABILITIES)
    },
    private val loadPreferenceCapabilities: suspend () -> Result<SearchPreferenceCapabilities> = {
        Result.success(DEFAULT_SEARCH_PREFERENCES)
    },
    private val observePeople: () -> Flow<List<Person>> = { flowOf(emptyList()) },
    private val syncPeople: suspend () -> Result<Unit> = { Result.success(Unit) },
    private val observeAlbums: () -> Flow<List<Album>> = { flowOf(emptyList()) },
    private val syncAlbums: suspend () -> Result<Unit> = { Result.success(Unit) },
    private val loadTags: suspend () -> Result<List<SearchTag>> = { Result.success(emptyList()) },
    private val loadSuggestions: suspend (SearchSuggestionRequest) -> Result<List<SearchSuggestion>> = {
        Result.success(emptyList())
    },
    initialServerCapabilities: SearchLoadState<SearchCapabilities> =
        SearchLoadState.Content(DEFAULT_SEARCH_CAPABILITIES),
    initialPreferenceCapabilities: SearchLoadState<SearchPreferenceCapabilities> =
        SearchLoadState.Content(DEFAULT_SEARCH_PREFERENCES)
) : ViewModel() {

    constructor(
        searchAssetsUseCase: SearchAssetsUseCase,
        getSearchCapabilitiesUseCase: GetSearchCapabilitiesUseCase,
        getSearchTagsUseCase: GetSearchTagsUseCase,
        getSearchSuggestionsUseCase: GetSearchSuggestionsUseCase,
        getPeopleUseCase: GetPeopleUseCase,
        getAlbumsUseCase: GetAlbumsUseCase,
        getApiKeyUseCase: GetApiKeyUseCase,
        getAssetDetailUseCase: GetAssetDetailUseCase,
        getAssetStackUseCase: GetAssetStackUseCase,
        getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
        setTargetRowHeightAction: SetTargetRowHeightAction
    ) : this(
        searchAssets = { endpoint, criteria, page ->
            searchAssetsUseCase(endpoint, criteria, page)
        },
        apiKey = getApiKeyUseCase(),
        loadAssetDetail = { assetId -> getAssetDetailUseCase(assetId) },
        loadAssetStack = { assetId -> getAssetStackUseCase(assetId) },
        hasSavedTargetRowHeight = getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.SEARCH),
        initialTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.SEARCH),
        saveTargetRowHeight = { height ->
            setTargetRowHeightAction(RowHeightScope.SEARCH, height)
        },
        loadServerCapabilities = getSearchCapabilitiesUseCase::getServerCapabilities,
        loadPreferenceCapabilities = getSearchCapabilitiesUseCase::getPreferenceCapabilities,
        observePeople = getPeopleUseCase::observe,
        syncPeople = getPeopleUseCase::sync,
        observeAlbums = getAlbumsUseCase::observe,
        syncAlbums = getAlbumsUseCase::sync,
        loadTags = getSearchTagsUseCase::invoke,
        loadSuggestions = getSearchSuggestionsUseCase::invoke,
        initialServerCapabilities = SearchLoadState.Idle,
        initialPreferenceCapabilities = SearchLoadState.Idle
    )

    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging("SearchViewModel")
    private var hasSavedTargetRowHeight = hasSavedTargetRowHeight
    private var savedTargetRowHeight = initialTargetRowHeight
    private var availableViewportHeight = 0f
    private val layoutRunner = PhotoGridLayoutRunner(viewModelScope)
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope)
    private val _state = MutableStateFlow(
        SearchState(
            targetRowHeight = savedTargetRowHeight,
            serverCapabilities = initialServerCapabilities,
            preferenceCapabilities = initialPreferenceCapabilities
        )
    )
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var serverCapabilitiesJob: Job? = null
    private var preferenceCapabilitiesJob: Job? = null
    private var peopleSyncJob: Job? = null
    private var albumSyncJob: Job? = null
    private var tagJob: Job? = null
    private val suggestionJobs = mutableMapOf<SearchSuggestionType, Job>()
    private val suggestionCache = MutableStateFlow<Map<SearchSuggestionRequest, List<SearchSuggestion>>>(emptyMap())
    private var hasOpenedPeopleOptions = false
    private var hasOpenedAlbumOptions = false
    private var hasOpenedTagOptions = false

    init {
        observeEntityOptions()
        refreshServerCapabilities()
        refreshPreferenceCapabilities()
    }

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        loadAssetDetail(assetId)

    suspend fun getAssetStack(assetId: String): Result<AssetStackLookup> =
        loadAssetStack(assetId)

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private val requestGeneration = MutableStateFlow(0L)

    fun updateQuery(query: String) {
        if (query == _state.value.query) return
        invalidateRequests()
        _state.update { current -> current.clearedExecution().copy(query = query) }
    }

    fun updateTextMode(mode: SearchTextMode) {
        if (mode == _state.value.textMode) return
        invalidateRequests()
        _state.update { current -> current.clearedExecution().copy(textMode = mode) }
    }

    fun applyCriteria(criteria: SearchCriteria) {
        val metadataCriteria = criteria.metadataOnly().normalized()
        _state.update { it.copy(appliedCriteria = metadataCriteria) }
        submitVisibleCriteria()
    }

    fun clearFilters() {
        _state.update { it.copy(appliedCriteria = SearchCriteria()) }
        submitVisibleCriteria()
    }

    fun refreshServerCapabilities() {
        if (serverCapabilitiesJob?.isActive == true) return
        val previous = _state.value.serverCapabilities.valueOrNull()
        _state.update { it.copy(serverCapabilities = SearchLoadState.Loading(previous)) }
        serverCapabilitiesJob = viewModelScope.launch(searchDispatcher) {
            loadServerCapabilities().fold(
                onSuccess = { capabilities ->
                    _state.update { it.copy(serverCapabilities = SearchLoadState.Content(capabilities)) }
                },
                onFailure = { error ->
                    log.e(error) { "Failed to load Search server capabilities" }
                    _state.update {
                        it.copy(serverCapabilities = SearchLoadState.Error(previous))
                    }
                }
            )
        }
    }

    fun refreshPreferenceCapabilities() {
        if (preferenceCapabilitiesJob?.isActive == true) return
        val previous = _state.value.preferenceCapabilities.valueOrNull()
        _state.update { it.copy(preferenceCapabilities = SearchLoadState.Loading(previous)) }
        preferenceCapabilitiesJob = viewModelScope.launch(searchDispatcher) {
            loadPreferenceCapabilities().fold(
                onSuccess = { capabilities ->
                    _state.update { it.copy(preferenceCapabilities = SearchLoadState.Content(capabilities)) }
                    if (
                        capabilities.tagsEnabled &&
                        hasOpenedTagOptions &&
                        _state.value.tagOptions is SearchLoadState.Idle
                    ) {
                        loadTagOptions()
                    }
                },
                onFailure = { error ->
                    log.e(error) { "Failed to load Search preference capabilities" }
                    _state.update {
                        it.copy(preferenceCapabilities = SearchLoadState.Error(previous))
                    }
                }
            )
        }
    }

    fun openPeopleOptions() {
        if (hasOpenedPeopleOptions) return
        hasOpenedPeopleOptions = true
        syncPeopleOptions()
    }

    fun retryPeopleOptions() {
        syncPeopleOptions()
    }

    fun openAlbumOptions() {
        if (hasOpenedAlbumOptions) return
        hasOpenedAlbumOptions = true
        syncAlbumOptions()
    }

    fun retryAlbumOptions() {
        syncAlbumOptions()
    }

    fun openTagOptions() {
        if (hasOpenedTagOptions) return
        hasOpenedTagOptions = true
        if (_state.value.preferenceCapabilities.valueOrNull()?.tagsEnabled == true) {
            loadTagOptions()
        }
    }

    fun retryTagOptions() {
        loadTagOptions()
    }

    fun loadSuggestionOptions(request: SearchSuggestionRequest) {
        loadSuggestionOptions(request, force = false)
    }

    fun retrySuggestionOptions(type: SearchSuggestionType) {
        val request = _state.value.suggestionOptions[type]?.request ?: return
        loadSuggestionOptions(request, force = true)
    }

    fun getDisplayItemIndexForReturn(): Int? =
        lastViewedAssetId?.let { assetId ->
            _state.value.rows.indexOfFirst { row ->
                row.photos.any { it.asset.id == assetId }
            }.takeIf { it >= 0 }
        }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _state.value.availableWidth) return
        _state.update { current ->
            current.copy(
                availableWidth = widthDp,
                targetRowHeight = targetRowHeightForConfig(widthDp, current.rowHeightBounds)
            )
        }
        scheduleRows()
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        if (heightDp == availableViewportHeight) return
        availableViewportHeight = heightDp
        val bounds = rowHeightBoundsForViewport(heightDp)
        _state.update { current ->
            current.copy(
                targetRowHeight = targetRowHeightForConfig(current.availableWidth, bounds),
                rowHeightBounds = bounds
            )
        }
        scheduleRows()
    }

    fun setTargetRowHeight(height: Float) {
        val clamped = _state.value.rowHeightBounds.clamp(height)
        if (clamped == _state.value.targetRowHeight) return
        hasSavedTargetRowHeight = true
        savedTargetRowHeight = clamped
        rowHeightPersistenceRunner.launch(debounce = true) {
            saveTargetRowHeight(clamped)
        }
        _state.update { it.copy(targetRowHeight = clamped) }
        scheduleRows(debounce = true)
    }

    private fun targetRowHeightForConfig(
        availableWidth: Float,
        bounds: RowHeightBounds
    ): Float {
        val preferred = if (hasSavedTargetRowHeight) {
            savedTargetRowHeight
        } else {
            defaultTargetRowHeightForWidth(availableWidth)
        }
        return bounds.clamp(preferred)
    }

    fun search() {
        submitVisibleCriteria()
    }

    private fun submitVisibleCriteria() {
        val current = _state.value
        val criteria = current.appliedCriteria.copy(
            query = current.query,
            textMode = current.textMode
        ).normalized()
        if (criteria.isEmpty) {
            invalidateRequests()
            _state.update(SearchState::clearedExecution)
            return
        }
        val capabilityError = executionCapabilityError(criteria)
        if (capabilityError != null) {
            invalidateRequests()
            _state.update { it.clearedExecution().copy(error = capabilityError) }
            return
        }

        val session = beginSession(criteria)
        _state.update {
            it.copy(
                isLoading = true,
                isLoadingMore = false,
                error = null,
                hasSearched = true,
                results = emptyList(),
                rows = emptyList(),
                submittedSession = session
            )
        }
        searchJob = viewModelScope.launch(searchDispatcher) {
            log.d {
                "Search endpoint=${session.endpoint} mode=${session.criteria.textMode} page=1"
            }
            searchAssets(session.endpoint, session.criteria, 1).fold(
                onSuccess = { result -> publishFirstPage(session, result) },
                onFailure = { error ->
                    log.e(error) { "Search failed endpoint=${session.endpoint} page=1" }
                    updateForSession(session) {
                        it.copy(isLoading = false, error = SearchUiMessage.SearchFailed)
                    }
                }
            )
        }
    }

    fun loadMore() {
        val previous = _state.value
        val session = previous.submittedSession ?: return
        val page = session.nextPage ?: return
        if (previous.isLoadingMore) return
        if (page <= session.currentPage || page in session.consumedPages) {
            _state.compareAndSet(
                previous,
                previous.copy(submittedSession = session.copy(nextPage = null))
            )
            return
        }
        if (!_state.compareAndSet(previous, previous.copy(isLoadingMore = true))) return

        loadMoreJob = viewModelScope.launch(searchDispatcher) {
            log.d { "Search load more endpoint=${session.endpoint} page=$page" }
            searchAssets(session.endpoint, session.criteria, page).fold(
                onSuccess = { result -> publishNextPage(session, page, result) },
                onFailure = { error ->
                    log.e(error) { "Search load more failed endpoint=${session.endpoint} page=$page" }
                    updateForSession(session) { it.copy(isLoadingMore = false) }
                }
            )
        }
    }

    private fun publishFirstPage(session: SearchSession, result: SearchResult) {
        val consumedPages = setOf(1)
        val published = updateForSession(session) {
            it.copy(
                results = result.assets.distinctBy(Asset::id),
                isLoading = false,
                submittedSession = session.copy(
                    currentPage = 1,
                    nextPage = validNextPage(result.nextPage, 1, consumedPages),
                    consumedPages = consumedPages
                )
            )
        }
        if (published) scheduleRows()
    }

    private fun publishNextPage(
        session: SearchSession,
        page: Int,
        result: SearchResult
    ) {
        val published = updateForSession(session) { current ->
            val currentSession = current.submittedSession ?: return@updateForSession current
            val consumedPages = currentSession.consumedPages + page
            current.copy(
                results = (current.results + result.assets).distinctBy(Asset::id),
                isLoadingMore = false,
                submittedSession = currentSession.copy(
                    currentPage = page,
                    nextPage = validNextPage(result.nextPage, page, consumedPages),
                    consumedPages = consumedPages
                )
            )
        }
        if (published) scheduleRows()
    }

    private fun beginSession(criteria: SearchCriteria): SearchSession {
        invalidateRequests()
        return SearchSession(
            criteria = criteria,
            endpoint = selectSearchEndpoint(criteria),
            generation = requestGeneration.value
        )
    }

    private fun observeEntityOptions() {
        viewModelScope.launch(searchDispatcher) {
            observePeople().collect { people ->
                _state.update { current ->
                    current.copy(peopleOptions = current.peopleOptions.withObservedValue(people))
                }
            }
        }
        viewModelScope.launch(searchDispatcher) {
            observeAlbums().collect { albums ->
                _state.update { current ->
                    current.copy(albumOptions = current.albumOptions.withObservedValue(albums))
                }
            }
        }
    }

    private fun syncPeopleOptions() {
        if (peopleSyncJob?.isActive == true) return
        val previous = _state.value.peopleOptions.valueOrNull()
        _state.update { it.copy(peopleOptions = SearchLoadState.Loading(previous)) }
        peopleSyncJob = viewModelScope.launch(searchDispatcher) {
            syncPeople().fold(
                onSuccess = {
                    _state.update { current ->
                        current.copy(
                            peopleOptions = SearchLoadState.Content(
                                current.peopleOptions.valueOrNull().orEmpty()
                            )
                        )
                    }
                },
                onFailure = { error ->
                    log.e(error) { "Failed to sync Search people options" }
                    _state.update { current ->
                        current.copy(
                            peopleOptions = SearchLoadState.Error(
                                current.peopleOptions.valueOrNull()
                            )
                        )
                    }
                }
            )
        }
    }

    private fun syncAlbumOptions() {
        if (albumSyncJob?.isActive == true) return
        val previous = _state.value.albumOptions.valueOrNull()
        _state.update { it.copy(albumOptions = SearchLoadState.Loading(previous)) }
        albumSyncJob = viewModelScope.launch(searchDispatcher) {
            syncAlbums().fold(
                onSuccess = {
                    _state.update { current ->
                        current.copy(
                            albumOptions = SearchLoadState.Content(
                                current.albumOptions.valueOrNull().orEmpty()
                            )
                        )
                    }
                },
                onFailure = { error ->
                    log.e(error) { "Failed to sync Search album options" }
                    _state.update { current ->
                        current.copy(
                            albumOptions = SearchLoadState.Error(
                                current.albumOptions.valueOrNull()
                            )
                        )
                    }
                }
            )
        }
    }

    private fun loadTagOptions() {
        if (tagJob?.isActive == true) return
        if (_state.value.preferenceCapabilities.valueOrNull()?.tagsEnabled != true) return
        val previous = _state.value.tagOptions.valueOrNull()
        _state.update { it.copy(tagOptions = SearchLoadState.Loading(previous)) }
        tagJob = viewModelScope.launch(searchDispatcher) {
            loadTags().fold(
                onSuccess = { tags ->
                    _state.update { it.copy(tagOptions = SearchLoadState.Content(tags)) }
                },
                onFailure = { error ->
                    log.e(error) { "Failed to load Search tag options" }
                    _state.update { it.copy(tagOptions = SearchLoadState.Error(previous)) }
                }
            )
        }
    }

    private fun loadSuggestionOptions(
        request: SearchSuggestionRequest,
        force: Boolean
    ) {
        val normalized = request.normalized()
        if (!force) {
            suggestionCache.value[normalized]?.let { cached ->
                publishSuggestionState(
                    normalized,
                    SearchLoadState.Content(cached)
                )
                return
            }
        }
        val current = _state.value.suggestionOptions[normalized.type]
        if (!force && current?.request == normalized && current.state is SearchLoadState.Loading) return

        suggestionJobs.remove(normalized.type)?.cancel()
        val previous = current
            ?.takeIf { it.request == normalized }
            ?.state
            ?.valueOrNull()
        publishSuggestionState(normalized, SearchLoadState.Loading(previous))
        suggestionJobs[normalized.type] = viewModelScope.launch(searchDispatcher) {
            loadSuggestions(normalized).fold(
                onSuccess = { suggestions ->
                    if (isCurrentSuggestionRequest(normalized)) {
                        suggestionCache.update { it + (normalized to suggestions) }
                        publishSuggestionState(normalized, SearchLoadState.Content(suggestions))
                    }
                },
                onFailure = { error ->
                    log.e(error) { "Failed to load Search suggestions type=${normalized.type}" }
                    if (isCurrentSuggestionRequest(normalized)) {
                        publishSuggestionState(normalized, SearchLoadState.Error(previous))
                    }
                }
            )
        }
    }

    private fun publishSuggestionState(
        request: SearchSuggestionRequest,
        loadState: SearchLoadState<List<SearchSuggestion>>
    ) {
        _state.update { current ->
            current.copy(
                suggestionOptions = current.suggestionOptions + (
                    request.type to SearchSuggestionOptionState(request, loadState)
                )
            )
        }
    }

    private fun isCurrentSuggestionRequest(request: SearchSuggestionRequest): Boolean =
        _state.value.suggestionOptions[request.type]?.request == request

    private fun executionCapabilityError(criteria: SearchCriteria): SearchUiMessage? {
        val capabilities = _state.value.serverCapabilities.valueOrNull()
        if (capabilities?.searchEnabled == false) return SearchUiMessage.SearchUnavailable
        if (criteria.query.isBlank()) return null
        return when (criteria.textMode) {
            SearchTextMode.CONTEXT -> if (capabilities?.contextEnabled == true) {
                null
            } else {
                SearchUiMessage.ContextUnavailable
            }

            SearchTextMode.OCR -> if (capabilities?.ocrEnabled == true) {
                null
            } else {
                SearchUiMessage.OcrUnavailable
            }

            SearchTextMode.FILENAME,
            SearchTextMode.DESCRIPTION -> null
        }
    }

    private fun invalidateRequests() {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        layoutRunner.cancel()
        requestGeneration.update { it + 1L }
    }

    private fun updateForSession(
        session: SearchSession,
        transform: (SearchState) -> SearchState
    ): Boolean {
        val identity = session.identity()
        while (true) {
            val current = _state.value
            if (!identity.matches(current)) return false
            if (_state.compareAndSet(current, transform(current))) return true
        }
    }

    private fun scheduleRows(debounce: Boolean = false) {
        layoutRunner.launch(debounce = debounce) { generation ->
            val snapshot = _state.value
            val identity = snapshot.submittedSession?.identity() ?: return@launch
            val rows = rowPacker(snapshot)
            if (layoutRunner.isCurrent(generation)) {
                while (true) {
                    val current = _state.value
                    if (!identity.matches(current)) break
                    if (_state.compareAndSet(current, current.copy(rows = rows))) break
                }
            }
        }
    }
}

private fun SearchState.clearedExecution(): SearchState = copy(
    submittedSession = null,
    results = emptyList(),
    rows = emptyList(),
    isLoading = false,
    isLoadingMore = false,
    error = null,
    hasSearched = false
)

private fun <T> SearchLoadState<T>.withObservedValue(value: T): SearchLoadState<T> = when (this) {
    SearchLoadState.Idle -> SearchLoadState.Content(value)
    is SearchLoadState.Loading -> SearchLoadState.Loading(value)
    is SearchLoadState.Content -> SearchLoadState.Content(value)
    is SearchLoadState.Error -> SearchLoadState.Error(value)
}

private fun validNextPage(
    candidate: Int?,
    currentPage: Int,
    consumedPages: Set<Int>
): Int? = candidate?.takeIf { it > currentPage && it !in consumedPages }

private fun packSearchRows(snapshot: SearchState): List<RowItem> =
    if (snapshot.results.isNotEmpty() && snapshot.availableWidth > 0f) {
        packIntoRows(
            snapshot.results,
            availableWidth = snapshot.availableWidth,
            targetRowHeight = snapshot.targetRowHeight,
            spacing = GRID_SPACING_DP,
            maxRowHeight = snapshot.rowHeightBounds.max
        )
    } else {
        emptyList()
    }

@Immutable
internal data class SubmittedSearchIdentity(
    val criteria: SearchCriteria,
    val endpoint: SearchEndpoint,
    val generation: Long
) {
    fun matches(state: SearchState): Boolean {
        val session = state.submittedSession ?: return false
        return criteria == session.criteria &&
                endpoint == session.endpoint &&
                generation == session.generation
    }
}

private fun SearchSession.identity(): SubmittedSearchIdentity = SubmittedSearchIdentity(
    criteria = criteria,
    endpoint = endpoint,
    generation = generation
)

private val DEFAULT_SEARCH_CAPABILITIES = SearchCapabilities(
    searchEnabled = true,
    contextEnabled = true,
    ocrEnabled = true
)

private val DEFAULT_SEARCH_PREFERENCES = SearchPreferenceCapabilities(
    tagsEnabled = false,
    ratingsEnabled = false
)
