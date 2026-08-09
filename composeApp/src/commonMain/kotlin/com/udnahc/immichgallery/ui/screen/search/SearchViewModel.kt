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
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.SearchResult
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.search.MetadataSearchUseCase
import com.udnahc.immichgallery.domain.usecase.search.SmartSearchUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.ui.model.SearchUiMessage
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

enum class SearchType { SMART, METADATA }

@Immutable
data class SearchState(
    val query: String = "",
    val searchType: SearchType = SearchType.SMART,
    val results: List<Asset> = emptyList(),
    val rows: List<RowItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: SearchUiMessage? = null,
    val hasSearched: Boolean = false,
    val nextPage: Int? = null,
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    internal val activeRequestGeneration: Long = 0L,
    internal val loadedPages: Set<Int> = emptySet()
)

class SearchViewModel internal constructor(
    private val smartSearch: suspend (String, Int) -> Result<SearchResult>,
    private val metadataSearch: suspend (String, Int) -> Result<SearchResult>,
    val apiKey: String,
    private val loadAssetDetail: suspend (String) -> Result<AssetDetail>,
    hasSavedTargetRowHeight: Boolean,
    initialTargetRowHeight: Float,
    private val saveTargetRowHeight: (Float) -> Unit,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rowPacker: suspend (SearchState) -> List<RowItem> = { snapshot ->
        packSearchRows(snapshot)
    }
) : ViewModel() {

    constructor(
        smartSearchUseCase: SmartSearchUseCase,
        metadataSearchUseCase: MetadataSearchUseCase,
        getApiKeyUseCase: GetApiKeyUseCase,
        getAssetDetailUseCase: GetAssetDetailUseCase,
        getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
        setTargetRowHeightAction: SetTargetRowHeightAction
    ) : this(
        smartSearch = { query, page -> smartSearchUseCase(query, page) },
        metadataSearch = { query, page -> metadataSearchUseCase(query, page) },
        apiKey = getApiKeyUseCase(),
        loadAssetDetail = { assetId -> getAssetDetailUseCase(assetId) },
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

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private val requestGeneration = MutableStateFlow(0L)

    fun updateQuery(query: String) {
        if (query == _state.value.query) return
        invalidateRequests()
        _state.update {
            it.copy(
                query = query,
                results = emptyList(),
                rows = emptyList(),
                isLoading = false,
                isLoadingMore = false,
                error = null,
                hasSearched = false,
                nextPage = null,
                activeRequestGeneration = requestGeneration.value,
                loadedPages = emptySet()
            )
        }
    }

    fun updateSearchType(type: SearchType) {
        if (type == _state.value.searchType) return
        invalidateRequests()
        _state.update {
            it.copy(
                searchType = type,
                results = emptyList(),
                rows = emptyList(),
                isLoading = false,
                isLoadingMore = false,
                error = null,
                hasSearched = false,
                nextPage = null,
                activeRequestGeneration = requestGeneration.value,
                loadedPages = emptySet()
            )
        }
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
        val currentState = _state.value
        if (currentState.query.isBlank()) return
        val request = beginRequest(currentState.query, currentState.searchType)
        _state.update {
            it.copy(
                isLoading = true,
                isLoadingMore = false,
                error = null,
                hasSearched = true,
                results = emptyList(),
                rows = emptyList(),
                nextPage = null,
                activeRequestGeneration = request.generation,
                loadedPages = emptySet()
            )
        }
        searchJob = viewModelScope.launch(searchDispatcher) {
            log.d { "Searching '${request.query}' (${request.type})" }
            val result = when (request.type) {
                SearchType.SMART -> smartSearch(request.query, 1)
                SearchType.METADATA -> metadataSearch(request.query, 1)
            }
            result.fold(
                onSuccess = { searchResult ->
                    log.d { "Search returned ${searchResult.assets.size} results, hasMore=${searchResult.hasMore}" }
                    val published = updateForRequest(request) {
                        val loadedPages = setOf(1)
                        it.copy(
                            results = searchResult.assets,
                            isLoading = false,
                            nextPage = searchResult.nextPage?.takeUnless { page ->
                                page in loadedPages
                            },
                            loadedPages = loadedPages
                        )
                    }
                    if (published) scheduleRows()
                },
                onFailure = { e ->
                    log.e(e) { "Search failed" }
                    updateForRequest(request) {
                        it.copy(
                            isLoading = false,
                            error = SearchUiMessage.SearchFailed
                        )
                    }
                }
            )
        }
    }

    fun loadMore() {
        // Atomic claim: only the caller that flips isLoadingMore from false to
        // true proceeds. Without compareAndSet, two near-simultaneous near-end
        // triggers could both observe isLoadingMore=false before either wrote
        // it, and both would launch duplicate requests.
        val prev = _state.value
        val page = prev.nextPage ?: return
        if (prev.isLoadingMore || prev.query.isBlank()) return
        if (page in prev.loadedPages) {
            _state.compareAndSet(prev, prev.copy(nextPage = null))
            return
        }
        val request = SearchRequest(prev.query, prev.searchType, prev.activeRequestGeneration)
        if (!_state.compareAndSet(prev, prev.copy(isLoadingMore = true))) return

        loadMoreJob = viewModelScope.launch(searchDispatcher) {
            log.d { "Loading more page=$page for '${request.query}'" }

            val result = when (request.type) {
                SearchType.SMART -> smartSearch(request.query, page)
                SearchType.METADATA -> metadataSearch(request.query, page)
            }
            result.fold(
                onSuccess = { searchResult ->
                    log.d { "Load more returned ${searchResult.assets.size} results, hasMore=${searchResult.hasMore}" }
                    val published = updateForRequest(request) {
                        val loadedPages = it.loadedPages + page
                        it.copy(
                            results = it.results + searchResult.assets,
                            isLoadingMore = false,
                            nextPage = searchResult.nextPage?.takeUnless { nextPage ->
                                nextPage in loadedPages
                            },
                            loadedPages = loadedPages
                        )
                    }
                    if (published) scheduleRows()
                },
                onFailure = { e ->
                    log.e(e) { "Load more failed" }
                    updateForRequest(request) { it.copy(isLoadingMore = false) }
                }
            )
        }
    }

    private fun beginRequest(query: String, type: SearchType): SearchRequest {
        invalidateRequests()
        return SearchRequest(query, type, requestGeneration.value)
    }

    private fun invalidateRequests() {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        layoutRunner.cancel()
        requestGeneration.update { it + 1L }
    }

    private fun updateForRequest(
        request: SearchRequest,
        transform: (SearchState) -> SearchState
    ): Boolean {
        while (true) {
            val current = _state.value
            if (!request.matches(current)) return false
            if (_state.compareAndSet(current, transform(current))) return true
        }
    }

    private fun scheduleRows(debounce: Boolean = false) {
        layoutRunner.launch(debounce = debounce) { generation ->
            val snapshot = _state.value
            val request = SearchRequest(
                query = snapshot.query,
                type = snapshot.searchType,
                generation = snapshot.activeRequestGeneration
            )
            val rows = rowPacker(snapshot)
            if (layoutRunner.isCurrent(generation)) {
                updateForRequest(request) { current -> current.copy(rows = rows) }
            }
        }
    }

}

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

internal data class SearchRequest(
    val query: String,
    val type: SearchType,
    val generation: Long
) {
    fun matches(state: SearchState): Boolean =
        generation == state.activeRequestGeneration &&
                query == state.query &&
                type == state.searchType
}
