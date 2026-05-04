package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildPhotoGridDisplayIndex
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.ui.model.ConnectionUiMessage
import com.udnahc.immichgallery.ui.screen.detail.PhotoGridDetailLayoutCoordinator
import com.udnahc.immichgallery.ui.screen.detail.PhotoGridDetailLayoutSnapshot
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

@Immutable
data class AlbumDetailState(
    val albumName: String = "",
    val assets: List<Asset> = emptyList(),
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    val groupSize: GroupSize = GroupSize.MONTH,
    val viewConfig: ViewConfig = ViewConfig(),
    val displayItems: List<PhotoGridDisplayItem> = emptyList(),
    val displayIndex: TimelineDisplayIndex = TimelineDisplayIndex(),
    val isLoading: Boolean = false,
    val error: ConnectionUiMessage? = null,
    val bannerError: ConnectionUiMessage? = null,
    val lastSyncedAt: Long? = null,
    val isBuilding: Boolean = false,
    val isSyncing: Boolean = false
)

class AlbumDetailViewModel(
    private val getAlbumDetailUseCase: GetAlbumDetailUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction,
    private val mosaicWorkScheduler: MosaicWorkScheduler,
    private val albumId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()
    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging("AlbumDetailViewModel")
    private var hasSavedTargetRowHeight = getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.ALBUM_DETAIL)
    private var savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.ALBUM_DETAIL)
    private val mosaicOwnerKey = "album:$albumId"
    private var syncJob: Job? = null
    private val _state = MutableStateFlow(
        AlbumDetailState(
            targetRowHeight = savedTargetRowHeight,
            viewConfig = getViewConfigUseCase()
        )
    )
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()
    private val layoutCoordinator = PhotoGridDetailLayoutCoordinator(
        scope = viewModelScope,
        hasSavedTargetRowHeight = hasSavedTargetRowHeight,
        savedTargetRowHeight = savedTargetRowHeight,
        targetRowHeightScope = RowHeightScope.ALBUM_DETAIL,
        ownerKey = mosaicOwnerKey,
        logTag = "AlbumDetailViewModel",
        mosaicWorkScheduler = mosaicWorkScheduler,
        getState = { _state.value },
        updateState = { transform -> _state.update(transform) },
        snapshotOf = AlbumDetailState::layoutSnapshot,
        withAvailableWidth = { state, width, target -> state.copy(availableWidth = width, targetRowHeight = target) },
        withViewportHeight = { state, target, bounds -> state.copy(targetRowHeight = target, rowHeightBounds = bounds) },
        withTargetRowHeight = { state, target -> state.copy(targetRowHeight = target) },
        withGroupSize = { state, groupSize -> state.copy(groupSize = groupSize) },
        withViewConfig = { state, viewConfig -> state.copy(viewConfig = viewConfig) },
        withDisplayItems = { state, items -> state.withDisplayItems(items) },
        persistTargetRowHeight = { scope, height -> setTargetRowHeightAction(scope, height) },
        persistViewConfig = setViewConfigAction::invoke
    )

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    fun activateForegroundMosaic() {
        layoutCoordinator.activateForegroundMosaic()
    }

    fun deactivateForegroundMosaic() {
        layoutCoordinator.deactivateForegroundMosaic()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cachedName = getAlbumDetailUseCase.getCachedAlbumName(albumId)
            if (cachedName != null) {
                _state.update { it.copy(albumName = cachedName) }
            }

            getAlbumDetailUseCase.observeAssets(albumId).collect { assets ->
                log.d { "Room emitted ${assets.size} assets for album $albumId" }
                _state.update { it.copy(assets = assets) }
                handleAssetEmission(assets)
            }
        }

        viewModelScope.launch {
            getViewConfigUseCase.observe().collect { saved ->
                val config = saved.normalized
                if (config != _state.value.viewConfig) {
                    layoutCoordinator.onViewConfigObserved(config)
                }
            }
        }

        syncFromServer()
    }

    fun setAvailableWidth(widthDp: Float) {
        layoutCoordinator.setAvailableWidth(widthDp)
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        layoutCoordinator.setAvailableViewportHeight(heightDp)
    }

    fun setTargetRowHeight(height: Float) {
        layoutCoordinator.setTargetRowHeight(height)
    }

    fun setGroupSize(size: GroupSize) {
        layoutCoordinator.setGroupSize(size)
    }

    fun setViewConfig(config: ViewConfig) {
        layoutCoordinator.setViewConfig(config)
    }

    fun setVisibleBucketIndexes(indexes: List<Int>) {
        layoutCoordinator.setVisibleBucketIndexes(indexes)
    }

    fun refreshAll() {
        syncFromServer()
    }

    fun dismissBannerError() {
        _state.update { it.copy(bannerError = null) }
    }

    fun getDisplayItemIndexForReturn(): Int? =
        lastViewedAssetId?.let { assetId ->
            _state.value.displayIndex.assetDisplayIndexById[assetId]
        }

    override fun onCleared() {
        layoutCoordinator.onCleared()
        super.onCleared()
    }

    private fun syncFromServer() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val hasCachedAssets = getAlbumDetailUseCase.hasCachedAssets(albumId)

            if (!hasCachedAssets) {
                _state.update { it.copy(isBuilding = true, error = null) }
            } else {
                _state.update { it.copy(isSyncing = true, bannerError = null) }
            }

            val lastSync = getAlbumDetailUseCase.getLastSyncedAt(albumId)
            _state.update { it.copy(lastSyncedAt = lastSync) }

            getAlbumDetailUseCase.sync(albumId).fold(
                onSuccess = { result ->
                    log.d { "Synced album detail for $albumId" }
                    // Network success and grid-visible content changes are
                    // separate signals. Album-name-only refreshes update the
                    // title while keeping the current packed rows/Mosaic work.
                    handleSyncedContentChange(result.changed)
                    _state.update {
                        it.copy(
                            albumName = result.albumName,
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
                                error = ConnectionUiMessage.NoConnectionToServer
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = ConnectionUiMessage.CannotConnectToServer
                            )
                        }
                    }
                }
            )
        }
    }

    private fun handleAssetEmission(assets: List<Asset>) {
        layoutCoordinator.handleAssetEmission(assets)
    }

    private fun handleSyncedContentChange(changed: Boolean) {
        layoutCoordinator.handleSyncedContentChange(changed)
    }

}

private fun AlbumDetailState.withDisplayItems(items: List<PhotoGridDisplayItem>): AlbumDetailState =
    copy(
        displayItems = items,
        displayIndex = buildPhotoGridDisplayIndex(items)
    )

private fun AlbumDetailState.layoutSnapshot(): PhotoGridDetailLayoutSnapshot =
    PhotoGridDetailLayoutSnapshot(
        assets = assets,
        availableWidth = availableWidth,
        targetRowHeight = targetRowHeight,
        rowHeightBounds = rowHeightBounds,
        groupSize = groupSize,
        viewConfig = viewConfig,
        displayItems = displayItems
    )
