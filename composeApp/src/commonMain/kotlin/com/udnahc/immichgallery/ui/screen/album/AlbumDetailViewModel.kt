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
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildMosaicAssignments
import com.udnahc.immichgallery.domain.model.buildPhotoGridItemsWithMosaic
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.groupAssets
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecFor
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction,
    private val albumId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()
    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging("AlbumDetailViewModel")
    private var hasSavedTargetRowHeight = getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.ALBUM_DETAIL)
    private var savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.ALBUM_DETAIL)
    private var availableViewportHeight = 0f
    private val layoutRunner = PhotoGridLayoutRunner(viewModelScope)
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope)
    private val _state = MutableStateFlow(
        AlbumDetailState(
            targetRowHeight = savedTargetRowHeight,
            viewConfig = getViewConfigUseCase()
        )
    )
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
                _state.update { it.copy(assets = assets) }
                scheduleDisplayItems()
            }
        }

        viewModelScope.launch {
            getViewConfigUseCase.observe().collect { saved ->
                val config = saved.normalized
                if (config != _state.value.viewConfig) {
                    _state.update { it.copy(viewConfig = config) }
                    scheduleDisplayItems()
                }
            }
        }

        syncFromServer()
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _state.value.availableWidth) return
        _state.update { current ->
            current.copy(
                availableWidth = widthDp,
                targetRowHeight = targetRowHeightForConfig(widthDp, current.rowHeightBounds)
            )
        }
        scheduleDisplayItems()
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        if (heightDp == availableViewportHeight) return
        availableViewportHeight = heightDp
        val bounds = rowHeightBoundsForViewport(heightDp)
        _state.update { current ->
            current.copy(
                targetRowHeight = targetRowHeightForConfig(current.availableWidth, bounds),
                rowHeightBounds = bounds
            )
        }
        scheduleDisplayItems()
    }

    fun setTargetRowHeight(height: Float) {
        val clamped = _state.value.rowHeightBounds.clamp(height)
        if (clamped == _state.value.targetRowHeight) return
        hasSavedTargetRowHeight = true
        savedTargetRowHeight = clamped
        rowHeightPersistenceRunner.launch(debounce = true) {
            setTargetRowHeightAction(RowHeightScope.ALBUM_DETAIL, clamped)
        }
        _state.update { it.copy(targetRowHeight = clamped) }
        scheduleDisplayItems(debounce = true)
    }

    private fun targetRowHeightForConfig(availableWidth: Float, bounds: RowHeightBounds): Float {
        val preferred = if (hasSavedTargetRowHeight) {
            savedTargetRowHeight
        } else {
            defaultTargetRowHeightForWidth(availableWidth)
        }
        return bounds.clamp(preferred)
    }

    fun setGroupSize(size: GroupSize) {
        if (size == _state.value.groupSize) return
        _state.update { it.copy(groupSize = size) }
        scheduleDisplayItems()
    }

    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        if (normalized == _state.value.viewConfig) return
        setViewConfigAction(normalized)
        _state.update { it.copy(viewConfig = normalized) }
        scheduleDisplayItems()
    }

    fun refreshAll() {
        syncFromServer()
    }

    fun dismissBannerError() {
        _state.update { it.copy(bannerError = null) }
    }

    private fun scheduleDisplayItems(debounce: Boolean = false) {
        layoutRunner.launch(debounce = debounce) { generation ->
            val snapshot = _state.value
            val items = buildDisplayItems(
                snapshot.assets,
                snapshot.groupSize,
                snapshot.availableWidth,
                snapshot.targetRowHeight,
                snapshot.rowHeightBounds.max,
                snapshot.viewConfig
            )
            if (layoutRunner.isCurrent(generation)) {
                _state.update { current -> current.copy(displayItems = items) }
            }
        }
    }

    private fun buildDisplayItems(
        assets: List<Asset>,
        groupSize: GroupSize,
        availableWidth: Float,
        targetRowHeight: Float,
        maxRowHeight: Float,
        viewConfig: ViewConfig
    ): List<PhotoGridDisplayItem> {
        if (assets.isEmpty() || availableWidth <= 0f) return emptyList()
        val groups = groupAssets(assets, groupSize)
        val items = mutableListOf<PhotoGridDisplayItem>()
        val mosaicLayoutSpec = mosaicLayoutSpecFor(availableWidth, targetRowHeight)
        for ((index, group) in groups.withIndex()) {
            if (group.label.isNotEmpty()) {
                items.add(HeaderItem(
                    gridKey = "h_${index}_${group.label}",
                    bucketIndex = index,
                    sectionLabel = group.label,
                    label = group.label
                ))
            }
            val layoutSpec = mosaicLayoutSpec
            if (viewConfig.mosaicEnabled && layoutSpec != null) {
                val assignments = buildMosaicAssignments(
                    assets = group.assets,
                    layoutSpec = layoutSpec,
                    spacing = GRID_SPACING_DP,
                    enabledFamilies = viewConfig.mosaicFamilies
                )
                logMosaicAssignmentStats(group.label, group.assets.size, assignments.sumOf { it.sourceCount }, assignments.size)
                items.addAll(
                    buildPhotoGridItemsWithMosaic(
                        assets = group.assets,
                        assignments = assignments,
                        bucketIndex = index,
                        sectionLabel = group.label,
                        layoutSpec = layoutSpec,
                        spacing = GRID_SPACING_DP,
                        maxRowHeight = maxRowHeight,
                        promoteWideImages = false,
                        minCompleteRowPhotos = 2
                    )
                )
            } else {
                items.addAll(
                    packIntoRows(
                        group.assets,
                        bucketIndex = index,
                        sectionLabel = group.label,
                        availableWidth = availableWidth,
                        targetRowHeight = if (viewConfig.mosaicEnabled && layoutSpec != null) {
                            layoutSpec.cellHeight
                        } else {
                            targetRowHeight
                        },
                        spacing = GRID_SPACING_DP,
                        maxRowHeight = maxRowHeight,
                        promoteWideImages = !viewConfig.mosaicEnabled,
                        minCompleteRowPhotos = if (viewConfig.mosaicEnabled) 2 else 1
                    )
                )
            }
        }
        return items
    }

    private fun logMosaicAssignmentStats(
        sectionLabel: String,
        assetCount: Int,
        assignedAssetCount: Int,
        assignmentCount: Int
    ) {
        log.d {
            "Mosaic assignments section=$sectionLabel assets=$assetCount " +
                "bands=$assignmentCount assignedAssets=$assignedAssetCount " +
                "fallbackOrSkippedAssets=${assetCount - assignedAssetCount}"
        }
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
