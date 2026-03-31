package com.udnahc.immichgallery.ui.screen.people

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
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsPageUseCase
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
    val rows: List<RowItem> = emptyList(),
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null
)

class PersonDetailViewModel(
    private val getPersonAssetsUseCase: GetPersonAssetsUseCase,
    private val getPersonAssetsPageUseCase: GetPersonAssetsPageUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val personId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    private val _state = MutableStateFlow(PersonDetailState())
    val state: StateFlow<PersonDetailState> = _state.asStateFlow()

    private var currentPage = 1

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    init {
        loadAssets()
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _state.value.availableWidth) return
        _state.update { it.copy(availableWidth = widthDp) }
        repackRows()
    }

    fun setTargetRowHeight(height: Float) {
        if (height == _state.value.targetRowHeight) return
        _state.update { it.copy(targetRowHeight = height) }
        repackRows()
    }

    fun loadAssets() {
        currentPage = 1
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, error = null, assets = emptyList(), rows = emptyList(), hasMore = true) }
            getPersonAssetsPageUseCase(personId, page = 1).fold(
                onSuccess = { (assets, hasMore) ->
                    currentPage = 2
                    val width = _state.value.availableWidth
                    val height = _state.value.targetRowHeight
                    val rows = if (width > 0f && assets.isNotEmpty()) {
                        packIntoRows(assets, availableWidth = width, targetRowHeight = height, spacing = GRID_SPACING_DP)
                    } else emptyList()
                    _state.update {
                        it.copy(
                            assets = assets,
                            rows = rows,
                            hasMore = hasMore,
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

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasMore) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoadingMore = true) }
            getPersonAssetsPageUseCase(personId, page = currentPage).fold(
                onSuccess = { (newAssets, hasMore) ->
                    currentPage++
                    val allAssets = _state.value.assets + newAssets
                    val width = _state.value.availableWidth
                    val height = _state.value.targetRowHeight
                    val rows = if (width > 0f && allAssets.isNotEmpty()) {
                        packIntoRows(allAssets, availableWidth = width, targetRowHeight = height, spacing = GRID_SPACING_DP)
                    } else emptyList()
                    _state.update {
                        it.copy(
                            assets = allAssets,
                            rows = rows,
                            hasMore = hasMore,
                            isLoadingMore = false
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(isLoadingMore = false)
                    }
                }
            )
        }
    }

    private fun repackRows() {
        val current = _state.value
        if (current.availableWidth <= 0f || current.assets.isEmpty()) return
        val rows = packIntoRows(
            assets = current.assets,
            availableWidth = current.availableWidth,
            targetRowHeight = current.targetRowHeight,
            spacing = GRID_SPACING_DP
        )
        _state.update { it.copy(rows = rows) }
    }
}
