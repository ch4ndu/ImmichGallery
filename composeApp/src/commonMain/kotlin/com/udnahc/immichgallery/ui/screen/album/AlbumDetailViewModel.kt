package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
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

    fun loadAlbumDetail() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, error = null) }
            getAlbumDetailUseCase(albumId).fold(
                onSuccess = { detail ->
                    _state.update {
                        it.copy(
                            albumName = detail.name,
                            assets = detail.assets,
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
}
