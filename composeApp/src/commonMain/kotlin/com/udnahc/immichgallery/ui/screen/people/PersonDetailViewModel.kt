package com.udnahc.immichgallery.ui.screen.people

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.detail.UpsertDetailMosaicArtifactsAction
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
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
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.detail.GetDetailMosaicArtifactsUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsPageUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.ui.model.ConnectionUiMessage
import com.udnahc.immichgallery.ui.screen.detail.PhotoGridDetailLayoutCoordinator
import com.udnahc.immichgallery.ui.screen.detail.PhotoGridDetailLayoutSnapshot
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lighthousegames.logging.logging

@Immutable
data class PersonDetailState(
    val assets: List<Asset> = emptyList(),
    val displayItems: List<PhotoGridDisplayItem> = emptyList(),
    val displayIndex: TimelineDisplayIndex = TimelineDisplayIndex(),
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    val groupSize: GroupSize = GroupSize.MONTH,
    val viewConfig: ViewConfig = ViewConfig(),
    val nextPage: Int = 1,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: ConnectionUiMessage? = null,
    val bannerError: ConnectionUiMessage? = null,
    val lastSyncedAt: Long? = null,
    val isBuilding: Boolean = false,
    val isSyncing: Boolean = false
)

enum class PersonDetailSnackbarMessage {
    SyncInProgress
}

class PersonDetailViewModel(
    private val getPersonAssetsUseCase: GetPersonAssetsUseCase,
    private val getPersonAssetsPageUseCase: GetPersonAssetsPageUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction,
    private val mosaicWorkScheduler: MosaicWorkScheduler,
    private val getDetailMosaicArtifactsUseCase: GetDetailMosaicArtifactsUseCase,
    private val upsertDetailMosaicArtifactsAction: UpsertDetailMosaicArtifactsAction,
    private val personId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()
    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging("PersonDetailViewModel")
    private var hasSavedTargetRowHeight = getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.PERSON_DETAIL)
    private var savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.PERSON_DETAIL)
    private val mosaicOwnerKey = "person:$personId"
    private var syncJob: Job? = null
    private var loadMoreJob: Job? = null
    private val _snackbarEvents = MutableSharedFlow<PersonDetailSnackbarMessage>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<PersonDetailSnackbarMessage> = _snackbarEvents.asSharedFlow()
    private val _state = MutableStateFlow(
        PersonDetailState(
            targetRowHeight = savedTargetRowHeight,
            viewConfig = getViewConfigUseCase()
        )
    )
    val state: StateFlow<PersonDetailState> = _state.asStateFlow()
    private val layoutCoordinator = PhotoGridDetailLayoutCoordinator(
        scope = viewModelScope,
        hasSavedTargetRowHeight = hasSavedTargetRowHeight,
        savedTargetRowHeight = savedTargetRowHeight,
        targetRowHeightScope = RowHeightScope.PERSON_DETAIL,
        ownerKey = mosaicOwnerKey,
        logTag = "PersonDetailViewModel",
        mosaicWorkScheduler = mosaicWorkScheduler,
        getState = { _state.value },
        updateState = { transform -> _state.update(transform) },
        snapshotOf = PersonDetailState::layoutSnapshot,
        withAvailableWidth = { state, width, target -> state.copy(availableWidth = width, targetRowHeight = target) },
        withViewportHeight = { state, target, bounds -> state.copy(targetRowHeight = target, rowHeightBounds = bounds) },
        withTargetRowHeight = { state, target -> state.copy(targetRowHeight = target) },
        withGroupSize = { state, groupSize -> state.copy(groupSize = groupSize) },
        withViewConfig = { state, viewConfig -> state.copy(viewConfig = viewConfig) },
        withDisplayItems = { state, items -> state.withDisplayItems(items) },
        persistTargetRowHeight = { scope, height -> setTargetRowHeightAction(scope, height) },
        persistViewConfig = setViewConfigAction::invoke,
        cacheOwnerType = DetailMosaicCacheOwnerType.PERSON,
        cacheOwnerId = personId,
        isPersistentCacheComplete = { _state.value.hasMore == false },
        readPersistentArtifacts = getDetailMosaicArtifactsUseCase::invoke,
        upsertPersistentArtifacts = upsertDetailMosaicArtifactsAction::invoke
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
            getPersonAssetsUseCase.observe(personId).collect { assets ->
                log.d { "Room emitted ${assets.size} assets for person $personId" }
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

    suspend fun prepareMosaicViewConfig(config: ViewConfig): Result<Unit> = withContext(Dispatchers.Default) {
        val normalized = config.normalized
        if (!normalized.cacheMosaicResults || !normalized.mosaicEnabled) return@withContext Result.success(Unit)
        if (_state.value.hasMore) {
            val result = getPersonAssetsUseCase.syncAll(personId)
                .onFailure { return@withContext Result.failure(it) }
                .getOrNull()
            val assets = getPersonAssetsUseCase.getAssets(personId)
            _state.update {
                it.copy(
                    assets = assets,
                    hasMore = false,
                    nextPage = 1,
                    isLoadingMore = false
                )
            }
            handleSyncedContentChange(result?.changed == true)
        }
        return@withContext layoutCoordinator.prepareMosaicViewConfig(normalized)
    }

    fun setVisibleBucketIndexes(indexes: List<Int>) {
        layoutCoordinator.setVisibleBucketIndexes(indexes)
    }

    fun setScrollInProgress(inProgress: Boolean) {
        layoutCoordinator.setScrollInProgress(inProgress)
    }

    fun loadMore() {
        // Atomic claim: only the caller that flips isLoadingMore from false to
        // true proceeds. Without compareAndSet, two near-simultaneous near-end
        // triggers could both observe isLoadingMore=false before either wrote
        // it, and both would launch duplicate requests — and both would try
        // to advance nextPage, skipping or refetching pages.
        val prev = _state.value
        if (syncJob?.isActive == true || prev.isSyncing || prev.isBuilding) {
            emitSyncInProgressSnackbar()
            return
        }
        if (prev.isLoadingMore || !prev.hasMore) return
        if (!_state.compareAndSet(prev, prev.copy(isLoadingMore = true))) return

        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            val page = _state.value.nextPage
            getPersonAssetsPageUseCase(personId, page = page).fold(
                onSuccess = { result ->
                    _state.update {
                        it.copy(
                            hasMore = result.hasMore,
                            nextPage = page + 1,
                            isLoadingMore = false
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
        if (syncJob?.isActive == true || _state.value.isLoadingMore || loadMoreJob?.isActive == true) return
        _state.update { it.copy(nextPage = 1) }
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val hasCachedAssets = getPersonAssetsUseCase.hasCachedAssets(personId)

            if (!hasCachedAssets) {
                _state.update { it.copy(isBuilding = true, error = null) }
            } else {
                _state.update { it.copy(isSyncing = true, bannerError = null) }
            }

            val lastSync = getPersonAssetsUseCase.getLastSyncedAt(personId)
            _state.update { it.copy(lastSyncedAt = lastSync) }

            if (!hasCachedAssets) {
                log.d { "First launch — syncing all assets for person $personId..." }
                getPersonAssetsUseCase.syncAll(personId).fold(
                    onSuccess = { result ->
                        _state.update {
                            it.copy(
                                hasMore = false,
                                isBuilding = false,
                                isSyncing = false,
                                error = null
                            )
                        }
                        handleSyncedContentChange(result.changed)
                    },
                    onFailure = { e ->
                        log.e(e) { "Failed to sync person assets for $personId" }
                        _state.update {
                            it.copy(
                                isBuilding = false,
                                isSyncing = false,
                                error = ConnectionUiMessage.NoConnectionToServer
                            )
                        }
                    }
                )
            } else {
                getPersonAssetsPageUseCase(personId, page = 1).fold(
                    onSuccess = { result ->
                        _state.update {
                            it.copy(
                                hasMore = result.hasMore,
                                nextPage = 2,
                                isSyncing = false,
                                error = null
                            )
                        }
                        handleSyncedContentChange(result.changed)
                    },
                    onFailure = { e ->
                        log.e(e) { "Failed to sync person assets for $personId" }
                        _state.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = ConnectionUiMessage.CannotConnectToServer
                            )
                        }
                    }
                )
            }
        }
    }

    private fun handleAssetEmission(assets: List<Asset>) {
        layoutCoordinator.handleAssetEmission(assets)
    }

    private fun handleSyncedContentChange(changed: Boolean) {
        layoutCoordinator.handleSyncedContentChange(changed)
    }

    private fun emitSyncInProgressSnackbar() {
        _snackbarEvents.tryEmit(PersonDetailSnackbarMessage.SyncInProgress)
    }
}

private fun PersonDetailState.withDisplayItems(items: List<PhotoGridDisplayItem>): PersonDetailState =
    copy(
        displayItems = items,
        displayIndex = buildPhotoGridDisplayIndex(items)
    )

private fun PersonDetailState.layoutSnapshot(): PhotoGridDetailLayoutSnapshot =
    PhotoGridDetailLayoutSnapshot(
        assets = assets,
        availableWidth = availableWidth,
        targetRowHeight = targetRowHeight,
        rowHeightBounds = rowHeightBounds,
        groupSize = groupSize,
        viewConfig = viewConfig,
        displayItems = displayItems
    )
