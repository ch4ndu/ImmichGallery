package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.auth.DirectSessionOperationRunner
import com.udnahc.immichgallery.domain.action.auth.SessionOperationRunner
import com.udnahc.immichgallery.domain.action.detail.UpsertDetailMosaicArtifactsAction
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
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
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetStackUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.detail.GetDetailMosaicArtifactsUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.ui.model.ConnectionUiMessage
import com.udnahc.immichgallery.ui.screen.detail.PhotoGridDetailLayoutCoordinator
import com.udnahc.immichgallery.ui.screen.detail.PhotoGridDetailLayoutSnapshot
import com.udnahc.immichgallery.ui.util.DetailSyncGate
import com.udnahc.immichgallery.ui.util.MosaicConfigPreparationCoordinator
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import com.udnahc.immichgallery.ui.util.SyncActivityKey
import com.udnahc.immichgallery.ui.util.SyncActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val isSyncing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int? = 1
)

class AlbumDetailViewModel(
    private val getAlbumDetailUseCase: GetAlbumDetailUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getAssetStackUseCase: GetAssetStackUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction,
    private val mosaicConfigPreparationCoordinator: MosaicConfigPreparationCoordinator,
    private val mosaicWorkScheduler: MosaicWorkScheduler,
    private val getDetailMosaicArtifactsUseCase: GetDetailMosaicArtifactsUseCase,
    private val upsertDetailMosaicArtifactsAction: UpsertDetailMosaicArtifactsAction,
    private val syncActivityTracker: SyncActivityTracker,
    private val albumId: String,
    private val sessionOperationRunner: SessionOperationRunner = DirectSessionOperationRunner
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()

    private val log = logging("AlbumDetailViewModel")
    private var hasSavedTargetRowHeight =
        getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.ALBUM_DETAIL)
    private var savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.ALBUM_DETAIL)
    private val mosaicOwnerKey = "album:$albumId"
    private var syncJob: Job? = null
    private var loadMoreJob: Job? = null
    private var albumOrder: String = "desc"
    private val detailSyncGate = DetailSyncGate()
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
        mosaicConfigPreparationCoordinator = mosaicConfigPreparationCoordinator,
        mosaicWorkScheduler = mosaicWorkScheduler,
        getState = { _state.value },
        updateState = { transform -> _state.update(transform) },
        snapshotOf = AlbumDetailState::layoutSnapshot,
        withAvailableWidth = { state, width, target ->
            state.copy(
                availableWidth = width,
                targetRowHeight = target
            )
        },
        withViewportHeight = { state, target, bounds ->
            state.copy(
                targetRowHeight = target,
                rowHeightBounds = bounds
            )
        },
        withTargetRowHeight = { state, target -> state.copy(targetRowHeight = target) },
        withGroupSize = { state, groupSize -> state.copy(groupSize = groupSize) },
        withViewConfig = { state, viewConfig -> state.copy(viewConfig = viewConfig) },
        withDisplayItems = { state, items -> state.withDisplayItems(items) },
        persistTargetRowHeight = { scope, height -> setTargetRowHeightAction(scope, height) },
        persistViewConfig = setViewConfigAction::invoke,
        cacheOwnerType = DetailMosaicCacheOwnerType.ALBUM,
        cacheOwnerId = albumId,
        isPersistentCacheComplete = { _state.value.hasMore == false },
        readPersistentArtifacts = getDetailMosaicArtifactsUseCase::invoke,
        upsertPersistentArtifacts = upsertDetailMosaicArtifactsAction::invoke,
        sessionOperationRunner = sessionOperationRunner
    )

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    suspend fun getAssetStack(assetId: String): Result<AssetStackLookup> =
        getAssetStackUseCase(assetId)

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
                log.d { "Room emitted <redacted> assets for album <redacted>" }
                _state.update { it.copy(assets = assets) }
                handleAssetEmission(assets)
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
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

    suspend fun prepareMosaicViewConfig(config: ViewConfig): Result<Unit> =
        withContext(Dispatchers.Default) {
            syncActivityTracker.track(SyncActivityKey.MosaicPreparation) {
                val normalized = config.normalized
                if (!normalized.cacheMosaicResults || !normalized.mosaicEnabled) {
                    return@track Result.success(Unit)
                }
                mosaicConfigPreparationCoordinator.cancelObsoleteWork()
                syncJob?.takeIf { it.isActive }?.join()
                loadMoreJob?.takeIf { it.isActive }?.join()
                val fullSyncResult = detailSyncGate.run {
                    if (!_state.value.hasMore) {
                        null
                    } else {
                        val hasCachedAssets = getAlbumDetailUseCase.hasCachedAssets(albumId)
                        _state.update {
                            if (hasCachedAssets) {
                                it.copy(isSyncing = true, bannerError = null)
                            } else {
                                it.copy(isBuilding = true, error = null)
                            }
                        }
                        val result = getAlbumDetailUseCase.syncAll(albumId)
                        result.onSuccess { syncResult ->
                            val assets = getAlbumDetailUseCase.getAlbumAssets(albumId)
                            val lastSync = getAlbumDetailUseCase.getLastSyncedAt(albumId)
                            albumOrder = syncResult.order
                            _state.update {
                                it.copy(
                                    albumName = syncResult.albumName.ifBlank { it.albumName },
                                    assets = assets,
                                    hasMore = false,
                                    nextPage = null,
                                    isBuilding = false,
                                    isSyncing = false,
                                    isLoadingMore = false,
                                    lastSyncedAt = lastSync
                                )
                            }
                            handleSyncedContentChange(syncResult.changed)
                        }.onFailure {
                            _state.update { state ->
                                state.copy(isBuilding = false, isSyncing = false)
                            }
                        }
                        result
                    }
                }
                if (fullSyncResult?.isFailure == true) {
                    return@track Result.failure(
                        fullSyncResult.exceptionOrNull()
                            ?: IllegalStateException("Album detail full sync failed")
                    )
                }
                return@track layoutCoordinator.prepareMosaicViewConfig(normalized)
            }
        }

    fun setVisibleBucketIndexes(indexes: List<Int>) {
        layoutCoordinator.setVisibleBucketIndexes(indexes)
    }

    fun setScrollInProgress(inProgress: Boolean) {
        layoutCoordinator.setScrollInProgress(inProgress)
    }

    fun refreshAll() {
        syncFromServer()
    }

    fun loadMore() {
        val previous = _state.value
        if (syncJob?.isActive == true || previous.isSyncing || previous.isBuilding) return
        if (previous.isLoadingMore || !previous.hasMore) return

        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            detailSyncGate.run {
                val current = _state.value
                if (current.isSyncing || current.isBuilding || current.isLoadingMore || !current.hasMore) {
                    return@run
                }
                val page = current.nextPage ?: return@run
                if (!_state.compareAndSet(current, current.copy(isLoadingMore = true))) return@run

                getAlbumDetailUseCase.syncPage(albumId, page, albumOrder).fold(
                    onSuccess = { result ->
                        val lastSync = if (!result.hasMore) {
                            getAlbumDetailUseCase.getLastSyncedAt(albumId)
                        } else {
                            null
                        }
                        albumOrder = result.order
                        _state.update {
                            it.copy(
                                albumName = result.albumName.ifBlank { it.albumName },
                                hasMore = result.hasMore,
                                nextPage = result.nextPage,
                                isLoadingMore = false,
                                lastSyncedAt = lastSync ?: it.lastSyncedAt
                            )
                        }
                        handleSyncedContentChange(result.changed)
                    },
                    onFailure = {
                        _state.update { it.copy(isLoadingMore = false) }
                    }
                )
            }
        }
    }

    fun dismissBannerError() {
        _state.update { it.copy(bannerError = null) }
    }

    override fun onCleared() {
        layoutCoordinator.onCleared()
        super.onCleared()
    }

    private fun syncFromServer() {
        if (syncJob?.isActive == true || _state.value.isLoadingMore || loadMoreJob?.isActive == true) return
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            syncActivityTracker.track(SyncActivityKey.AlbumDetail(albumId)) {
                detailSyncGate.run {
                    val hasCachedAssets = getAlbumDetailUseCase.hasCachedAssets(albumId)

                    if (!hasCachedAssets) {
                        _state.update { it.copy(isBuilding = true, error = null) }
                    } else {
                        _state.update { it.copy(isSyncing = true, bannerError = null) }
                    }

                    val lastSync = getAlbumDetailUseCase.getLastSyncedAt(albumId)
                    _state.update { it.copy(lastSyncedAt = lastSync) }

                    getAlbumDetailUseCase.syncPage(albumId, page = 1).fold(
                        onSuccess = { result ->
                            val completedAt = if (!result.hasMore) {
                                getAlbumDetailUseCase.getLastSyncedAt(albumId)
                            } else {
                                null
                            }
                            log.d { "Synced album detail for <redacted>" }
                            albumOrder = result.order
                            // Network success and grid-visible content changes are
                            // separate signals. Album-name-only refreshes update the
                            // title while keeping the current packed rows/Mosaic work.
                            handleSyncedContentChange(result.changed)
                            _state.update {
                                it.copy(
                                    albumName = result.albumName,
                                    hasMore = result.hasMore,
                                    nextPage = result.nextPage,
                                    isBuilding = false,
                                    isSyncing = false,
                                    error = null,
                                    lastSyncedAt = completedAt ?: it.lastSyncedAt
                                )
                            }
                        },
                        onFailure = { e ->
                            log.e { "Failed to sync album detail for <redacted>" }
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
