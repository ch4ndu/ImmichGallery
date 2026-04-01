package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.search.MetadataSearchUseCase
import com.udnahc.immichgallery.domain.usecase.search.SmartSearchUseCase
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
    val error: String? = null,
    val hasSearched: Boolean = false,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT
)

class SearchViewModel(
    private val smartSearchUseCase: SmartSearchUseCase,
    private val metadataSearchUseCase: MetadataSearchUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    private val log = logging()
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun updateSearchType(type: SearchType) {
        _state.update { it.copy(searchType = type) }
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _state.value.availableWidth) return
        _state.update { current ->
            val rows = if (current.results.isNotEmpty() && widthDp > 0f) {
                packIntoRows(current.results, availableWidth = widthDp, targetRowHeight = current.targetRowHeight, spacing = GRID_SPACING_DP)
            } else current.rows
            current.copy(availableWidth = widthDp, rows = rows)
        }
    }

    fun setTargetRowHeight(height: Float) {
        if (height == _state.value.targetRowHeight) return
        _state.update { current ->
            val rows = if (current.results.isNotEmpty() && current.availableWidth > 0f) {
                packIntoRows(current.results, availableWidth = current.availableWidth, targetRowHeight = height, spacing = GRID_SPACING_DP)
            } else current.rows
            current.copy(targetRowHeight = height, rows = rows)
        }
    }

    fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val currentState = _state.value
            if (currentState.query.isBlank()) return@launch

            log.d { "Searching '${currentState.query}' (${currentState.searchType})" }
            _state.update {
                it.copy(
                    isLoading = true,
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
                    val width = _state.value.availableWidth
                    val height = _state.value.targetRowHeight
                    val rows = if (width > 0f && searchResult.assets.isNotEmpty()) {
                        packIntoRows(searchResult.assets, availableWidth = width, targetRowHeight = height, spacing = GRID_SPACING_DP)
                    } else emptyList()
                    _state.update {
                        it.copy(
                            results = searchResult.assets,
                            rows = rows,
                            isLoading = false,
                            currentPage = 1,
                            hasMore = searchResult.hasMore
                        )
                    }
                },
                onFailure = { e ->
                    log.e(e) { "Search failed" }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Search failed"
                        )
                    }
                }
            )
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasMore || current.query.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val state = _state.value
            val nextPage = state.currentPage + 1
            log.d { "Loading more page=$nextPage for '${state.query}'" }
            _state.update { it.copy(isLoadingMore = true) }

            val result = when (state.searchType) {
                SearchType.SMART -> smartSearchUseCase(state.query, page = nextPage)
                SearchType.METADATA -> metadataSearchUseCase(state.query, page = nextPage)
            }
            result.fold(
                onSuccess = { searchResult ->
                    log.d { "Load more returned ${searchResult.assets.size} results, hasMore=${searchResult.hasMore}" }
                    val allAssets = _state.value.results + searchResult.assets
                    val width = _state.value.availableWidth
                    val height = _state.value.targetRowHeight
                    val rows = if (width > 0f && allAssets.isNotEmpty()) {
                        packIntoRows(allAssets, availableWidth = width, targetRowHeight = height, spacing = GRID_SPACING_DP)
                    } else emptyList()
                    _state.update {
                        it.copy(
                            results = allAssets,
                            rows = rows,
                            isLoadingMore = false,
                            currentPage = nextPage,
                            hasMore = searchResult.hasMore
                        )
                    }
                },
                onFailure = { e ->
                    log.e(e) { "Load more failed" }
                    _state.update { it.copy(isLoadingMore = false) }
                }
            )
        }
    }

}
