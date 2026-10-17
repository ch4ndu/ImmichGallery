package com.udnahc.immichgallery.ui.screen.favorites

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.data.model.UserResponse
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetStackUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetCurrentUserUseCase
import com.udnahc.immichgallery.domain.usecase.search.FavoritesFailureKind
import com.udnahc.immichgallery.domain.usecase.search.FavoritesLoadException
import com.udnahc.immichgallery.domain.usecase.search.FavoritesLoadOutcome
import com.udnahc.immichgallery.domain.usecase.search.GetFavoriteAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.ui.model.FavoritesUiMessage
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class FavoritesState(
    val assets: List<Asset> = emptyList(),
    val assetIds: List<String> = assets.map(Asset::id),
    val rows: List<RowItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: FavoritesUiMessage? = null,
    val loadMoreError: FavoritesUiMessage? = null,
    val nextPage: Int? = null,
    val consumedPages: Set<Int> = emptySet(),
    val firstUnconsumedPage: Int? = null,
    val ownerId: String? = null,
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    internal val activeRequestGeneration: Long = 0L
)

class FavoritesViewModel internal constructor(
    private val loadCurrentUser: suspend () -> Result<UserResponse>,
    private val loadFavorites: suspend (String, Int, Set<Int>) -> Result<FavoritesLoadOutcome>,
    val apiKey: String,
    private val loadAssetDetail: suspend (String) -> Result<AssetDetail>,
    private val loadAssetStack: suspend (String) -> Result<AssetStackLookup>,
    hasSavedTargetRowHeight: Boolean,
    initialTargetRowHeight: Float,
    private val saveTargetRowHeight: (Float) -> Unit,
    private val requestDispatcher: CoroutineDispatcher = Dispatchers.IO,
    layoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val rowPacker: suspend (FavoritesState) -> List<RowItem> = { snapshot ->
        packFavoritesRows(snapshot)
    }
) : ViewModel() {

    constructor(
        getCurrentUserUseCase: GetCurrentUserUseCase,
        getFavoriteAssetsUseCase: GetFavoriteAssetsUseCase,
        getApiKeyUseCase: GetApiKeyUseCase,
        getAssetDetailUseCase: GetAssetDetailUseCase,
        getAssetStackUseCase: GetAssetStackUseCase,
        getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
        setTargetRowHeightAction: SetTargetRowHeightAction
    ) : this(
        loadCurrentUser = { getCurrentUserUseCase() },
        loadFavorites = { ownerId, page, consumedPages ->
            getFavoriteAssetsUseCase(ownerId, page, consumedPages)
        },
        apiKey = getApiKeyUseCase(),
        loadAssetDetail = getAssetDetailUseCase::invoke,
        loadAssetStack = getAssetStackUseCase::invoke,
        hasSavedTargetRowHeight = getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.FAVORITES),
        initialTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.FAVORITES),
        saveTargetRowHeight = { height ->
            setTargetRowHeightAction(RowHeightScope.FAVORITES, height)
        }
    )

    private var hasSavedTargetRowHeight = hasSavedTargetRowHeight
    private var savedTargetRowHeight = initialTargetRowHeight
    private var availableViewportHeight = 0f
    private var requestGeneration = 0L
    private var initialLoadJob: Job? = null
    private var loadMoreJob: Job? = null
    private val layoutRunner = PhotoGridLayoutRunner(viewModelScope, layoutDispatcher)
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope, layoutDispatcher)
    private val _state = MutableStateFlow(
        FavoritesState(targetRowHeight = initialTargetRowHeight)
    )
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    var lastViewedAssetId: String? by mutableStateOf(null)

    init {
        retry()
    }

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        loadAssetDetail(assetId)

    suspend fun getAssetStack(assetId: String): Result<AssetStackLookup> =
        loadAssetStack(assetId)

    fun retry() {
        invalidateRequests()
        val generation = requestGeneration
        val snapshot = _state.value
        _state.update {
            it.copy(
                assets = emptyList(),
                assetIds = emptyList(),
                rows = emptyList(),
                isLoading = true,
                isLoadingMore = false,
                error = null,
                loadMoreError = null,
                nextPage = null,
                activeRequestGeneration = generation
            )
        }
        initialLoadJob = viewModelScope.launch(requestDispatcher) {
            val ownerId = snapshot.ownerId ?: loadOwnerId(generation) ?: return@launch
            val startingPage = snapshot.firstUnconsumedPage ?: 1
            val consumedPages = snapshot.consumedPages
                .takeIf { snapshot.ownerId != null }
                .orEmpty()
            publishInitialFavorites(ownerId, startingPage, consumedPages, generation)
        }
    }

    private suspend fun loadOwnerId(generation: Long): String? {
        val result = loadCurrentUser()
        val user = result.getOrElse { error ->
            updateForGeneration(generation) {
                it.copy(
                    isLoading = false,
                    error = error.toFavoritesUiMessage(),
                    firstUnconsumedPage = 1
                )
            }
            return null
        }
        val ownerId = user.id.takeIf(String::isNotBlank)
        if (ownerId == null) {
            updateForGeneration(generation) {
                it.copy(
                    isLoading = false,
                    error = FavoritesUiMessage.LoadFailed,
                    firstUnconsumedPage = 1
                )
            }
            return null
        }
        val published = updateForGeneration(generation) { it.copy(ownerId = ownerId) }
        return ownerId.takeIf { published }
    }

    private suspend fun publishInitialFavorites(
        ownerId: String,
        startingPage: Int,
        consumedPages: Set<Int>,
        generation: Long
    ) {
        loadFavorites(ownerId, startingPage, consumedPages).fold(
            onSuccess = { outcome ->
                val assets = outcome.assets.distinctBy(Asset::id)
                val assetIds = assets.map(Asset::id)
                val published = updateForGeneration(generation) {
                    it.copy(
                        assets = assets,
                        assetIds = assetIds,
                        isLoading = false,
                        error = null,
                        nextPage = outcome.nextPage,
                        consumedPages = outcome.consumedPages,
                        firstUnconsumedPage = null
                    )
                }
                if (published) scheduleRows()
            },
            onFailure = { error ->
                val failure = error as? FavoritesLoadException
                updateForGeneration(generation) {
                    it.copy(
                        isLoading = false,
                        error = error.toFavoritesUiMessage(),
                        consumedPages = failure?.consumedPages ?: it.consumedPages,
                        firstUnconsumedPage = failure?.firstUnconsumedPage ?: startingPage
                    )
                }
            }
        )
    }

    fun loadMore() {
        val previous = _state.value
        val ownerId = previous.ownerId ?: return
        val page = previous.nextPage ?: return
        if (previous.isLoadingMore || previous.isLoading) return
        if (page in previous.consumedPages) {
            _state.compareAndSet(previous, previous.copy(nextPage = null))
            return
        }
        if (!_state.compareAndSet(
                previous,
                previous.copy(isLoadingMore = true, loadMoreError = null)
            )
        ) {
            return
        }

        val generation = previous.activeRequestGeneration
        loadMoreJob = viewModelScope.launch(requestDispatcher) {
            loadFavorites(ownerId, page, previous.consumedPages).fold(
                onSuccess = { outcome ->
                    val published = updateForGeneration(generation) {
                        val assets = (it.assets + outcome.assets).distinctBy(Asset::id)
                        it.copy(
                            assets = assets,
                            assetIds = assets.map(Asset::id),
                            isLoadingMore = false,
                            loadMoreError = null,
                            nextPage = outcome.nextPage,
                            consumedPages = outcome.consumedPages,
                            firstUnconsumedPage = null
                        )
                    }
                    if (published) scheduleRows()
                },
                onFailure = { error ->
                    val failure = error as? FavoritesLoadException
                    updateForGeneration(generation) {
                        it.copy(
                            isLoadingMore = false,
                            loadMoreError = error.toFavoritesUiMessage(),
                            nextPage = failure?.firstUnconsumedPage ?: page,
                            consumedPages = failure?.consumedPages ?: it.consumedPages,
                            firstUnconsumedPage = failure?.firstUnconsumedPage ?: page
                        )
                    }
                }
            )
        }
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _state.value.availableWidth) return
        _state.update { current ->
            current.copy(
                availableWidth = widthDp,
                targetRowHeight = targetRowHeightForConfig(widthDp, current.rowHeightBounds)
            )
        }
        scheduleRows()
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
        scheduleRows()
    }

    fun setTargetRowHeight(height: Float) {
        val clamped = _state.value.rowHeightBounds.clamp(height)
        if (clamped == _state.value.targetRowHeight) return
        hasSavedTargetRowHeight = true
        savedTargetRowHeight = clamped
        rowHeightPersistenceRunner.launch(debounce = true) {
            saveTargetRowHeight(clamped)
        }
        _state.update { it.copy(targetRowHeight = clamped) }
        scheduleRows(debounce = true)
    }

    fun getDisplayItemIndexForReturn(): Int? =
        lastViewedAssetId?.let { assetId ->
            _state.value.rows.indexOfFirst { row ->
                row.photos.any { it.asset.id == assetId }
            }.takeIf { it >= 0 }
        }

    private fun targetRowHeightForConfig(
        availableWidth: Float,
        bounds: RowHeightBounds
    ): Float {
        val preferred = if (hasSavedTargetRowHeight) {
            savedTargetRowHeight
        } else {
            defaultTargetRowHeightForWidth(availableWidth)
        }
        return bounds.clamp(preferred)
    }

    private fun invalidateRequests() {
        initialLoadJob?.cancel()
        loadMoreJob?.cancel()
        layoutRunner.cancel()
        requestGeneration += 1L
    }

    private fun updateForGeneration(
        generation: Long,
        transform: (FavoritesState) -> FavoritesState
    ): Boolean {
        while (true) {
            val current = _state.value
            if (current.activeRequestGeneration != generation) return false
            if (_state.compareAndSet(current, transform(current))) return true
        }
    }

    private fun scheduleRows(debounce: Boolean = false) {
        layoutRunner.launch(debounce = debounce) { layoutGeneration ->
            val snapshot = _state.value
            val rows = rowPacker(snapshot)
            if (layoutRunner.isCurrent(layoutGeneration)) {
                updateForGeneration(snapshot.activeRequestGeneration) { current ->
                    current.copy(rows = rows)
                }
            }
        }
    }
}

private fun packFavoritesRows(snapshot: FavoritesState): List<RowItem> =
    if (snapshot.assets.isNotEmpty() && snapshot.availableWidth > 0f) {
        packIntoRows(
            assets = snapshot.assets,
            availableWidth = snapshot.availableWidth,
            targetRowHeight = snapshot.targetRowHeight,
            spacing = GRID_SPACING_DP,
            maxRowHeight = snapshot.rowHeightBounds.max
        )
    } else {
        emptyList()
    }

internal fun Throwable.toFavoritesUiMessage(): FavoritesUiMessage {
    val failureKind = (this as? FavoritesLoadException)?.kind
    if (failureKind == FavoritesFailureKind.PERMISSION_DENIED) {
        return FavoritesUiMessage.PermissionDenied
    }
    val requestError = (this as? FavoritesLoadException)?.cause ?: this
    return if (
        requestError is ClientRequestException &&
        (requestError.response.status == HttpStatusCode.Unauthorized ||
                requestError.response.status == HttpStatusCode.Forbidden)
    ) {
        FavoritesUiMessage.PermissionDenied
    } else {
        FavoritesUiMessage.LoadFailed
    }
}
