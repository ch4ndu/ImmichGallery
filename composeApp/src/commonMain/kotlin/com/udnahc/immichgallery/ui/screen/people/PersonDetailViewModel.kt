package com.udnahc.immichgallery.ui.screen.people

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
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.groupAssets
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsPageUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
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
sealed interface PersonDisplayItem {
    val key: String
}

@Immutable
data class PersonHeaderItem(val label: String) : PersonDisplayItem {
    override val key: String get() = "header_$label"
}

@Immutable
data class PersonRowItem(val row: RowItem) : PersonDisplayItem {
    override val key: String get() = row.gridKey
}

@Immutable
data class PersonDetailState(
    val assets: List<Asset> = emptyList(),
    val displayItems: List<PersonDisplayItem> = emptyList(),
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    val groupSize: GroupSize = GroupSize.MONTH,
    val nextPage: Int = 1,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val bannerError: String? = null,
    val lastSyncedAt: Long? = null,
    val isBuilding: Boolean = false,
    val isSyncing: Boolean = false
)

class PersonDetailViewModel(
    private val getPersonAssetsUseCase: GetPersonAssetsUseCase,
    private val getPersonAssetsPageUseCase: GetPersonAssetsPageUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val personId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()
    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging()
    private var savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.PERSON_DETAIL)
    private var availableViewportHeight = 0f
    private val _state = MutableStateFlow(PersonDetailState(targetRowHeight = savedTargetRowHeight))
    val state: StateFlow<PersonDetailState> = _state.asStateFlow()

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            getPersonAssetsUseCase.observe(personId).collect { assets ->
                log.d { "Room emitted ${assets.size} assets for person $personId" }
                val snapshot = _state.value
                val items = withContext(Dispatchers.Default) {
                    buildDisplayItems(
                        assets,
                        snapshot.groupSize,
                        snapshot.availableWidth,
                        snapshot.targetRowHeight,
                        snapshot.rowHeightBounds.max
                    )
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
                val items = buildDisplayItems(
                    current.assets,
                    current.groupSize,
                    widthDp,
                    current.targetRowHeight,
                    current.rowHeightBounds.max
                )
                current.copy(availableWidth = widthDp, displayItems = items)
            }
        }
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        if (heightDp == availableViewportHeight) return
        availableViewportHeight = heightDp
        val bounds = rowHeightBoundsForViewport(heightDp)
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { current ->
                val targetHeight = bounds.clamp(savedTargetRowHeight)
                val items = buildDisplayItems(
                    current.assets,
                    current.groupSize,
                    current.availableWidth,
                    targetHeight,
                    bounds.max
                )
                current.copy(
                    targetRowHeight = targetHeight,
                    rowHeightBounds = bounds,
                    displayItems = items
                )
            }
        }
    }

    fun setTargetRowHeight(height: Float) {
        val clamped = _state.value.rowHeightBounds.clamp(height)
        if (clamped == _state.value.targetRowHeight) return
        savedTargetRowHeight = clamped
        setTargetRowHeightAction(RowHeightScope.PERSON_DETAIL, clamped)
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { current ->
                val items = buildDisplayItems(
                    current.assets,
                    current.groupSize,
                    current.availableWidth,
                    clamped,
                    current.rowHeightBounds.max
                )
                current.copy(targetRowHeight = clamped, displayItems = items)
            }
        }
    }

    fun setGroupSize(size: GroupSize) {
        if (size == _state.value.groupSize) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { current ->
                val items = buildDisplayItems(
                    current.assets,
                    size,
                    current.availableWidth,
                    current.targetRowHeight,
                    current.rowHeightBounds.max
                )
                current.copy(groupSize = size, displayItems = items)
            }
        }
    }

    fun loadMore() {
        // Atomic claim: only the caller that flips isLoadingMore from false to
        // true proceeds. Without compareAndSet, two near-simultaneous near-end
        // triggers could both observe isLoadingMore=false before either wrote
        // it, and both would launch duplicate requests — and both would try
        // to advance nextPage, skipping or refetching pages.
        val prev = _state.value
        if (prev.isLoadingMore || !prev.hasMore) return
        if (!_state.compareAndSet(prev, prev.copy(isLoadingMore = true))) return

        viewModelScope.launch(Dispatchers.IO) {
            val page = _state.value.nextPage
            getPersonAssetsPageUseCase(personId, page = page).fold(
                onSuccess = { hasMore ->
                    _state.update {
                        it.copy(
                            hasMore = hasMore,
                            nextPage = page + 1,
                            isLoadingMore = false
                        )
                    }
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

    private fun buildDisplayItems(
        assets: List<Asset>,
        groupSize: GroupSize,
        availableWidth: Float,
        targetRowHeight: Float,
        maxRowHeight: Float
    ): List<PersonDisplayItem> {
        if (assets.isEmpty() || availableWidth <= 0f) return emptyList()
        val groups = groupAssets(assets, groupSize)
        val items = mutableListOf<PersonDisplayItem>()
        for (group in groups) {
            if (group.label.isNotEmpty()) {
                items.add(PersonHeaderItem(group.label))
            }
            val rows = packIntoRows(
                group.assets,
                availableWidth = availableWidth,
                targetRowHeight = targetRowHeight,
                spacing = GRID_SPACING_DP,
                maxRowHeight = maxRowHeight
            )
            for (row in rows) {
                items.add(PersonRowItem(row))
            }
        }
        return items
    }

    private fun syncFromServer() {
        _state.update { it.copy(nextPage = 1) }
        viewModelScope.launch(Dispatchers.IO) {
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
                    onSuccess = {
                        _state.update {
                            it.copy(
                                hasMore = false,
                                isBuilding = false,
                                isSyncing = false,
                                error = null
                            )
                        }
                    },
                    onFailure = { e ->
                        log.e(e) { "Failed to sync person assets for $personId" }
                        _state.update {
                            it.copy(
                                isBuilding = false,
                                isSyncing = false,
                                error = "No connection to server"
                            )
                        }
                    }
                )
            } else {
                getPersonAssetsPageUseCase(personId, page = 1).fold(
                    onSuccess = { hasMore ->
                        _state.update {
                            it.copy(
                                hasMore = hasMore,
                                nextPage = 2,
                                isSyncing = false,
                                error = null
                            )
                        }
                    },
                    onFailure = { e ->
                        log.e(e) { "Failed to sync person assets for $personId" }
                        _state.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = "Cannot connect to server"
                            )
                        }
                    }
                )
            }
        }
    }
}
