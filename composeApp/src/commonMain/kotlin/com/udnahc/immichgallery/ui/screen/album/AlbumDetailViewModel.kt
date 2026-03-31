package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class AlbumDetailState(
    val albumName: String = "",
    val assets: List<Asset> = emptyList(),
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rows: List<RowItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class AlbumDetailViewModel(
    private val getAlbumDetailUseCase: GetAlbumDetailUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val albumId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    private val _state = MutableStateFlow(AlbumDetailState())
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    init {
        loadAlbumDetail()
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

    fun loadAlbumDetail() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, error = null) }
            getAlbumDetailUseCase(albumId).fold(
                onSuccess = { detail ->
                    val width = _state.value.availableWidth
                    val height = _state.value.targetRowHeight
                    val rows = if (width > 0f && detail.assets.isNotEmpty()) {
                        packIntoRows(detail.assets, availableWidth = width, targetRowHeight = height, spacing = GRID_SPACING_DP)
                    } else emptyList()
                    _state.update {
                        it.copy(
                            albumName = detail.name,
                            assets = detail.assets,
                            rows = rows,
                            isLoading = false
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Failed to load album"
                        )
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
