package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
    private val albumId: String
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailState())
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    init {
        loadAlbumDetail()
    }

    fun loadAlbumDetail() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, error = null) }
            getAlbumDetailUseCase(albumId).fold(
                onSuccess = { detail ->
                    _state.update {
                        it.copy(albumName = detail.name, assets = detail.assets, isLoading = false)
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load album") }
                }
            )
        }
    }
}
