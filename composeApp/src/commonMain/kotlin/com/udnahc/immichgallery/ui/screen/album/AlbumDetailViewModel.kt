package com.udnahc.immichgallery.ui.screen.album

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
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.groupAssets
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
import kotlinx.coroutines.withContext
import org.lighthousegames.logging.logging

/** Display item: either a section header label or a photo row. */
@Immutable
sealed interface AlbumDisplayItem {
    val key: String
}

@Immutable
data class AlbumHeaderItem(val label: String) : AlbumDisplayItem {
    override val key: String get() = "header_$label"
}

@Immutable
data class AlbumRowItem(val row: RowItem) : AlbumDisplayItem {
    override val key: String get() = row.gridKey
}

@Immutable
data class AlbumDetailState(
    val albumName: String = "",
    val assets: List<Asset> = emptyList(),
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val groupSize: GroupSize = GroupSize.MONTH,
    val displayItems: List<AlbumDisplayItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val bannerError: String? = null,
    val lastSyncedAt: Long? = null,
    val isBuilding: Boolean = false,
    val isSyncing: Boolean = false
)

class AlbumDetailViewModel(
    private val getAlbumDetailUseCase: GetAlbumDetailUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val albumId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()
    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging()
    private val _state = MutableStateFlow(AlbumDetailState())
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cachedName = getAlbumDetailUseCase.getCachedAlbumName(albumId)
            if (cachedName != null) {
                _state.update { it.copy(albumName = cachedName) }
            }

            getAlbumDetailUseCase.observeAssets(albumId).collect { assets ->
                log.d { "Room emitted ${assets.size} assets for album $albumId" }
                val snapshot = _state.value
                val items = withContext(Dispatchers.Default) {
                    buildDisplayItems(assets, snapshot.groupSize, snapshot.availableWidth, snapshot.targetRowHeight)
                }
                _state.update { it.copy(assets = assets, displayItems = items) }
            }
        }

        syncFromServer()
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _state.value.availableWidth) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { current ->
                val items = buildDisplayItems(current.assets, current.groupSize, widthDp, current.targetRowHeight)
                current.copy(availableWidth = widthDp, displayItems = items)
            }
        }
    }

    fun setTargetRowHeight(height: Float) {
        if (height == _state.value.targetRowHeight) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { current ->
                val items = buildDisplayItems(current.assets, current.groupSize, current.availableWidth, height)
                current.copy(targetRowHeight = height, displayItems = items)
            }
        }
    }

    fun setGroupSize(size: GroupSize) {
        if (size == _state.value.groupSize) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { current ->
                val items = buildDisplayItems(current.assets, size, current.availableWidth, current.targetRowHeight)
                current.copy(groupSize = size, displayItems = items)
            }
        }
    }

    fun refreshAll() {
        syncFromServer()
    }

    fun dismissBannerError() {
        _state.update { it.copy(bannerError = null) }
    }

    private fun buildDisplayItems(
        assets: List<Asset>,
        groupSize: GroupSize,
        availableWidth: Float,
        targetRowHeight: Float
    ): List<AlbumDisplayItem> {
        if (assets.isEmpty() || availableWidth <= 0f) return emptyList()
        val groups = groupAssets(assets, groupSize)
        val items = mutableListOf<AlbumDisplayItem>()
        for (group in groups) {
            if (group.label.isNotEmpty()) {
                items.add(AlbumHeaderItem(group.label))
            }
            val rows = packIntoRows(group.assets, availableWidth = availableWidth, targetRowHeight = targetRowHeight, spacing = GRID_SPACING_DP)
            for (row in rows) {
                items.add(AlbumRowItem(row))
            }
        }
        return items
    }

    private fun syncFromServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasCachedAssets = getAlbumDetailUseCase.hasCachedAssets(albumId)

            if (!hasCachedAssets) {
                _state.update { it.copy(isBuilding = true, error = null) }
            } else {
                _state.update { it.copy(isSyncing = true, bannerError = null) }
            }

            val lastSync = getAlbumDetailUseCase.getLastSyncedAt(albumId)
            _state.update { it.copy(lastSyncedAt = lastSync) }

            getAlbumDetailUseCase.sync(albumId).fold(
                onSuccess = { albumName ->
                    log.d { "Synced album detail for $albumId" }
                    _state.update {
                        it.copy(
                            albumName = albumName,
                            isBuilding = false,
                            isSyncing = false,
                            error = null
                        )
                    }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to sync album detail for $albumId" }
                    if (!hasCachedAssets) {
                        _state.update {
                            it.copy(
                                isBuilding = false,
                                isSyncing = false,
                                error = "No connection to server"
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = "Cannot connect to server"
                            )
                        }
                    }
                }
            )
        }
    }
}
