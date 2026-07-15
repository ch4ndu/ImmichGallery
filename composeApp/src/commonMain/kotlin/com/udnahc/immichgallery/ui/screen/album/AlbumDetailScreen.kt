package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.PhotoGridDetailActions
import com.udnahc.immichgallery.ui.component.PhotoGridDetailContent
import com.udnahc.immichgallery.ui.component.PhotoOverlayHost
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.model.asText
import com.udnahc.immichgallery.ui.model.asTextOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.ensureReturnSourceVisible
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.loading_album
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: AlbumDetailViewModel = koinViewModel { parametersOf(albumId) }
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val dismissScope = rememberCoroutineScope()
    var entryResetApplied by remember { mutableStateOf(false) }

    DisposableEffect(viewModel) {
        viewModel.activateForegroundMosaic()
        onDispose { viewModel.deactivateForegroundMosaic() }
    }

    // This is intentionally not saveable: returning to this destination starts
    // at the top even when Navigation restores a previous LazyListState.
    LaunchedEffect(state.displayItems.isNotEmpty()) {
        if (!entryResetApplied && state.displayItems.isNotEmpty()) {
            listState.scrollToItem(0)
            entryResetApplied = true
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusBarPadding + Dimens.topBarHeight
    val contentBottomPadding = navBarPadding

    PhotoOverlayHost(
        initialIndexKey = state.assets,
        onOverlayDismissed = { assetId ->
            assetId?.let { id ->
                state.displayIndex.assetDisplayIndexById[id]?.let { itemIndex ->
                    dismissScope.launch { listState.ensureReturnSourceVisible(itemIndex) }
                }
            }
        },
        resolveInitialIndex = { id ->
            state.assets.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        },
        content = { _, transitionAssetId, hiddenAssetId, onPhotoClick ->
            AlbumDetailContent(
                state = state,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
                onPhotoClick = onPhotoClick,
                onRetry = viewModel::refreshAll,
                onDismissBanner = viewModel::dismissBannerError,
                onLoadMore = viewModel::loadMore,
                onAvailableWidthChanged = viewModel::setAvailableWidth,
                onAvailableViewportHeightChanged = viewModel::setAvailableViewportHeight,
                onVisibleBucketIndexesChanged = viewModel::setVisibleBucketIndexes,
                onScrollInProgressChanged = viewModel::setScrollInProgress,
                onTargetRowHeightChanged = viewModel::setTargetRowHeight,
                contentTopPadding = contentTopPadding,
                contentBottomPadding = contentBottomPadding,
                listState = listState,
                sharedTransitionScope = this,
            )
        },
        overlay = { initialIndex, onDismissHost, onCurrentAssetChanged, onStlTransitionActiveChanged, sharedTransitionScope ->
            StaticPhotoOverlay(
                assets = state.assets,
                initialIndex = initialIndex,
                apiKey = viewModel.apiKey,
                getAssetDetail = viewModel::getAssetDetail,
                onPersonClick = onPersonClick,
                onDismiss = onDismissHost,
                onCurrentAssetChanged = onCurrentAssetChanged,
                onStlTransitionActiveChanged = onStlTransitionActiveChanged,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this,
            )
        },
        chrome = { showOverlay ->
            AnimatedVisibility(
                visible = !showOverlay,
                enter = systemBarFadeIn,
                exit = systemBarFadeOut,
            ) {
                DetailTopBar(
                    title = state.albumName,
                    onBack = onBack,
                    trailingContent = {
                        PhotoGridDetailActions(
                            groupSize = state.groupSize,
                            viewConfig = state.viewConfig,
                            onGroupSizeSelected = viewModel::setGroupSize,
                            onViewConfigChanged = viewModel::setViewConfig,
                            onPrepareViewConfig = viewModel::prepareMosaicViewConfig
                        )
                    }
                )
            }
        }
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailContent(
    state: AlbumDetailState,
    transitionAssetId: String? = null,
    hiddenAssetId: String? = null,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissBanner: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onAvailableWidthChanged: (Float) -> Unit = {},
    onAvailableViewportHeightChanged: (Float) -> Unit = {},
    onVisibleBucketIndexesChanged: (List<Int>) -> Unit = {},
    onScrollInProgressChanged: (Boolean) -> Unit = {},
    onTargetRowHeightChanged: (Float) -> Unit = {},
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    PhotoGridDetailContent(
        isLoading = state.isLoading,
        isBuilding = state.isBuilding,
        assetsEmpty = state.assets.isEmpty(),
        errorMessage = state.error.asTextOrNull(),
        loadingText = if (state.isBuilding) stringResource(Res.string.loading_album) else null,
        displayItems = state.displayItems,
        displayIndex = state.displayIndex,
        bannerMessage = state.bannerError?.asText(),
        lastSyncedAt = state.lastSyncedAt,
        transitionAssetId = transitionAssetId,
        hiddenAssetId = hiddenAssetId,
        targetRowHeight = state.targetRowHeight,
        rowHeightBounds = state.rowHeightBounds,
        viewConfig = state.viewConfig,
        onPhotoClick = onPhotoClick,
        onRetry = onRetry,
        onDismissBanner = onDismissBanner,
        onAvailableWidthChanged = onAvailableWidthChanged,
        onAvailableViewportHeightChanged = onAvailableViewportHeightChanged,
        onVisibleBucketIndexesChanged = onVisibleBucketIndexesChanged,
        onScrollInProgressChanged = onScrollInProgressChanged,
        onTargetRowHeightChanged = onTargetRowHeightChanged,
        contentTopPadding = contentTopPadding,
        contentBottomPadding = contentBottomPadding,
        listState = listState,
        isLoadingMore = state.isLoadingMore,
        onLoadMore = if (state.hasMore) onLoadMore else null,
        sharedTransitionScope = sharedTransitionScope,
    )
}
