package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumsUseCase
import com.udnahc.immichgallery.ui.model.UiMessage
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
    val error: UiMessage? = null,
    val bannerError: UiMessage? = null,
    val lastSyncedAt: Long? = null,
    val isBuilding: Boolean = false,
    val isSyncing: Boolean = false
)

class AlbumListViewModel(
    private val getAlbumsUseCase: GetAlbumsUseCase
) : ViewModel() {

    private val log = logging("AlbumListViewModel")
    private val _state = MutableStateFlow(AlbumListState())
    val state: StateFlow<AlbumListState> = _state.asStateFlow()

    init {
        // Observe Room for albums (reactive SSOT)
        viewModelScope.launch(Dispatchers.IO) {
            getAlbumsUseCase.observe().collect { albums ->
                log.d { "Room emitted ${albums.size} albums" }
                _state.update { it.copy(albums = albums) }
            }
        }

        // Initial sync from network
        syncFromServer()
    }

    fun refreshAll() {
        syncFromServer()
    }

    fun dismissBannerError() {
        _state.update { it.copy(bannerError = null) }
    }

    private fun syncFromServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasCachedAlbums = getAlbumsUseCase.hasCachedAlbums()

            if (!hasCachedAlbums) {
                _state.update { it.copy(isBuilding = true, error = null) }
            } else {
                _state.update { it.copy(isSyncing = true, bannerError = null) }
            }

            val lastSync = getAlbumsUseCase.getLastSyncedAt()
            _state.update { it.copy(lastSyncedAt = lastSync) }

            getAlbumsUseCase.sync().fold(
                onSuccess = {
                    log.d { "Synced albums from server" }
                    _state.update { it.copy(isBuilding = false, isSyncing = false, error = null) }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to sync albums from server" }
                    if (!hasCachedAlbums) {
                        _state.update {
                            it.copy(
                                isBuilding = false,
                                isSyncing = false,
                                error = UiMessage.NoConnectionToServer
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = UiMessage.CannotConnectToServer
                            )
                        }
                    }
                }
            )
        }
    }
}
