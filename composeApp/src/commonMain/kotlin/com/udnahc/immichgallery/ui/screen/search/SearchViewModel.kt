package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchEndpoint
import com.udnahc.immichgallery.domain.model.SearchResult
import com.udnahc.immichgallery.domain.model.SearchSession
import com.udnahc.immichgallery.domain.model.SearchTextMode
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.model.selectSearchEndpoint
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetStackUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.search.SearchAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.ui.model.SearchUiMessage
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f)
) {
    val nextPage: Int? get() = submittedSession?.nextPage

    internal val activeRequestGeneration: Long get() = submittedSession?.generation ?: 0L
}

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
    }
) : ViewModel() {

    constructor(
        searchAssetsUseCase: SearchAssetsUseCase,
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
        }
    )

    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging("SearchViewModel")
    private var hasSavedTargetRowHeight = hasSavedTargetRowHeight
    private var savedTargetRowHeight = initialTargetRowHeight
    private var availableViewportHeight = 0f
    private val layoutRunner = PhotoGridLayoutRunner(viewModelScope)
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope)
    private val _state = MutableStateFlow(SearchState(targetRowHeight = savedTargetRowHeight))
    val state: StateFlow<SearchState> = _state.asStateFlow()

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
