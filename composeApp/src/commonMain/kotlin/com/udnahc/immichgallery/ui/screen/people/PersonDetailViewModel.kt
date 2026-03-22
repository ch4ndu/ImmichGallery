package com.udnahc.immichgallery.ui.screen.people

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.ui.theme.GRID_COLUMNS
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PersonDetailState(
    val assets: List<Asset> = emptyList(),
    val rows: List<List<Asset>> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class PersonDetailViewModel(
    private val getPersonAssetsUseCase: GetPersonAssetsUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val personId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    private val _state = MutableStateFlow(PersonDetailState())
    val state: StateFlow<PersonDetailState> = _state.asStateFlow()

    init {
        loadAssets()
    }

    fun loadAssets() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, error = null) }
            getPersonAssetsUseCase(personId).fold(
                onSuccess = { assets ->
                    _state.update {
                        it.copy(
                            assets = assets,
                            rows = assets.chunked(GRID_COLUMNS),
                            isLoading = false
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Failed to load photos"
                        )
                    }
                }
            )
        }
    }
}
