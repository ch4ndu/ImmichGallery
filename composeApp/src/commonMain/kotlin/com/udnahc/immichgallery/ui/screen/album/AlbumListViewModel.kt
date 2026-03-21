package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

@Immutable
data class AlbumListState(
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class AlbumListViewModel(
    private val getAlbumsUseCase: GetAlbumsUseCase
) : ViewModel() {

    private val log = logging()
    private val _state = MutableStateFlow(AlbumListState())
    val state: StateFlow<AlbumListState> = _state.asStateFlow()

    init {
        loadAlbums()
    }

    fun loadAlbums() {
        viewModelScope.launch(Dispatchers.IO) {
            log.d { "Loading albums..." }
            _state.update { it.copy(isLoading = true, error = null) }
            getAlbumsUseCase().fold(
                onSuccess = { albums ->
                    log.d { "Loaded ${albums.size} albums" }
                    _state.update { it.copy(albums = albums, isLoading = false) }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to load albums" }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Failed to load albums"
                        )
                    }
                }
            )
        }
    }
}
