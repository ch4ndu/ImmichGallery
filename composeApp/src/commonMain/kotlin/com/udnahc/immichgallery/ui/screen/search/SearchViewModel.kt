package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.search.MetadataSearchUseCase
import com.udnahc.immichgallery.domain.usecase.search.SmartSearchUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.ui.model.UiMessage
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.Dispatchers
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
    val error: UiMessage? = null,
    val hasSearched: Boolean = false,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f)
)

class SearchViewModel(
    private val smartSearchUseCase: SmartSearchUseCase,
    private val metadataSearchUseCase: MetadataSearchUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val setTargetRowHeightAction: SetTargetRowHeightAction
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()
    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging("SearchViewModel")
    private var hasSavedTargetRowHeight = getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.SEARCH)
    private var savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.SEARCH)
    private var availableViewportHeight = 0f
    private val layoutRunner = PhotoGridLayoutRunner(viewModelScope)
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope)
    private val _state = MutableStateFlow(SearchState(targetRowHeight = savedTargetRowHeight))
    val state: StateFlow<SearchState> = _state.asStateFlow()

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun updateSearchType(type: SearchType) {
        _state.update { it.copy(searchType = type) }
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
            setTargetRowHeightAction(RowHeightScope.SEARCH, clamped)
        }
        _state.update { it.copy(targetRowHeight = clamped) }
        scheduleRows(debounce = true)
    }

    private fun targetRowHeightForConfig(availableWidth: Float, bounds: RowHeightBounds): Float {
        val preferred = if (hasSavedTargetRowHeight) {
            savedTargetRowHeight
        } else {
            defaultTargetRowHeightForWidth(availableWidth)
        }
        return bounds.clamp(preferred)
    }

    fun search() {
        // Cancel both searchJob AND any in-flight loadMore. Otherwise an
        // old-query page-2 fetch that completes after the new search resets
        // state would append stale results via `_state.value.results + ...`.
        searchJob?.cancel()
        loadMoreJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val currentState = _state.value
            if (currentState.query.isBlank()) return@launch

            log.d { "Searching '${currentState.query}' (${currentState.searchType})" }
            _state.update {
                it.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    error = null,
                    hasSearched = true,
                    results = emptyList(),
                    rows = emptyList(),
                    currentPage = 1,
                    hasMore = false
                )
            }
            val result = when (currentState.searchType) {
                SearchType.SMART -> smartSearchUseCase(currentState.query, page = 1)
                SearchType.METADATA -> metadataSearchUseCase(currentState.query, page = 1)
            }
            result.fold(
                onSuccess = { searchResult ->
                    log.d { "Search returned ${searchResult.assets.size} results, hasMore=${searchResult.hasMore}" }
                    _state.update {
                        it.copy(
                            results = searchResult.assets,
                            isLoading = false,
                            currentPage = 1,
                            hasMore = searchResult.hasMore
                        )
                    }
                    scheduleRows()
                },
                onFailure = { e ->
                    log.e(e) { "Search failed" }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = UiMessage.SearchFailed
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
        if (prev.isLoadingMore || !prev.hasMore || prev.query.isBlank()) return
        if (!_state.compareAndSet(prev, prev.copy(isLoadingMore = true))) return

        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            val state = _state.value
            val nextPage = state.currentPage + 1
            log.d { "Loading more page=$nextPage for '${state.query}'" }

            val result = when (state.searchType) {
                SearchType.SMART -> smartSearchUseCase(state.query, page = nextPage)
                SearchType.METADATA -> metadataSearchUseCase(state.query, page = nextPage)
            }
            result.fold(
                onSuccess = { searchResult ->
                    log.d { "Load more returned ${searchResult.assets.size} results, hasMore=${searchResult.hasMore}" }
                    val allAssets = _state.value.results + searchResult.assets
                    _state.update {
                        it.copy(
                            results = allAssets,
                            isLoadingMore = false,
                            currentPage = nextPage,
                            hasMore = searchResult.hasMore
                        )
                    }
                    scheduleRows()
                },
                onFailure = { e ->
                    log.e(e) { "Load more failed" }
                    _state.update { it.copy(isLoadingMore = false) }
                }
            )
        }
    }

    private fun scheduleRows(debounce: Boolean = false) {
        layoutRunner.launch(debounce = debounce) { generation ->
            val snapshot = _state.value
            val rows = if (snapshot.results.isNotEmpty() && snapshot.availableWidth > 0f) {
                packIntoRows(
                    snapshot.results,
                    availableWidth = snapshot.availableWidth,
                    targetRowHeight = snapshot.targetRowHeight,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = snapshot.rowHeightBounds.max
                )
            } else emptyList()
            if (layoutRunner.isCurrent(generation)) {
                _state.update { current -> current.copy(rows = rows) }
            }
        }
    }

}
