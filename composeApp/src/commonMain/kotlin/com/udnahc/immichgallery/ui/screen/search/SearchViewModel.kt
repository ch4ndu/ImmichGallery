package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.ui.theme.GRID_COLUMNS
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
    val rows: List<List<Asset>> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false
)

class SearchViewModel(
    private val smartSearchUseCase: SmartSearchUseCase,
    private val metadataSearchUseCase: MetadataSearchUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    val getAssetDetailUseCase: GetAssetDetailUseCase
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    private val log = logging()
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(query = query) }
        }
    }

    fun updateSearchType(type: SearchType) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(searchType = type) }
        }
    }

    fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val currentState = _state.value
            if (currentState.query.isBlank()) return@launch

            log.d { "Searching '${currentState.query}' (${currentState.searchType})" }
            _state.update { it.copy(isLoading = true, error = null, hasSearched = true) }
            val result = when (currentState.searchType) {
                SearchType.SMART -> smartSearchUseCase(currentState.query)
                SearchType.METADATA -> metadataSearchUseCase(currentState.query)
            }
            result.fold(
                onSuccess = { assets ->
                    log.d { "Search returned ${assets.size} results" }
                    _state.update {
                        it.copy(
                            results = assets,
                            rows = assets.chunked(GRID_COLUMNS),
                            isLoading = false
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
}
