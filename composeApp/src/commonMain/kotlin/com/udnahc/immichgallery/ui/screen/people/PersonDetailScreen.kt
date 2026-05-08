package com.udnahc.immichgallery.ui.screen.people

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.PhotoGridDetailActions
import com.udnahc.immichgallery.ui.component.PhotoGridDetailContent
import com.udnahc.immichgallery.ui.component.PhotoOverlayHost
import com.udnahc.immichgallery.ui.component.PhotoOverlaySourcePosition
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.model.asText
import com.udnahc.immichgallery.ui.model.asTextOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.prepareOverlayDismissSource
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import androidx.compose.ui.tooling.preview.Preview
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.loading_photos
import immichgallery.composeapp.generated.resources.unknown
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PersonDetailScreen(
    personId: String,
    personName: String,
    onBack: () -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: PersonDetailViewModel = koinViewModel { parametersOf(personId) }
) {
    val state by viewModel.state.collectAsState()
    val unknownLabel = stringResource(Res.string.unknown)
    val listState = rememberLazyListState()
    val sourcePositions = remember { mutableStateMapOf<String, PhotoOverlaySourcePosition>() }
    var preparedDismissReturnKey by remember { mutableStateOf<String?>(null) }

    DisposableEffect(viewModel) {
        viewModel.activateForegroundMosaic()
        onDispose { viewModel.deactivateForegroundMosaic() }
    }

    // Scroll back to last viewed asset when returning from detail
    LaunchedEffect(viewModel.lastViewedAssetId) {
        val itemIndex = viewModel.getDisplayItemIndexForReturn() ?: return@LaunchedEffect
        if (itemIndex >= 0) {
            val info = listState.layoutInfo
            val visibleItem = info.visibleItemsInfo.firstOrNull { it.index == itemIndex }
            if (preparedDismissReturnKey == viewModel.lastViewedAssetId && visibleItem != null) {
                preparedDismissReturnKey = null
                return@LaunchedEffect
            }
            val fullyVisible = visibleItem != null &&
                visibleItem.offset >= info.viewportStartOffset &&
                (visibleItem.offset + visibleItem.size) <= info.viewportEndOffset
            if (!fullyVisible) {
                listState.scrollToItem(itemIndex)
            }
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusBarPadding + Dimens.topBarHeight
    val contentBottomPadding = navBarPadding

    PhotoOverlayHost(
        initialIndexKey = state.assets,
        resolveInitialIndex = { id -> state.assets.indexOfFirst { it.id == id }.takeIf { it >= 0 } },
        prepareDismissSource = prepareDismissSource@ { context ->
            val assetId = context.assetId ?: return@prepareDismissSource
            viewModel.lastViewedAssetId = assetId
            preparedDismissReturnKey = assetId
            sourcePositions.remove(assetId)
            listState.prepareOverlayDismissSource(
                displayIndex = viewModel.getDisplayItemIndexForReturn(),
                isSourceReady = { sourcePositions[assetId]?.generation == context.sourceGeneration },
                clearSourceReady = { sourcePositions.remove(assetId) },
            )
        },
        onActiveSourcePositioned = { position ->
            sourcePositions[position.assetId] = position
        },
        content = { _, transitionAssetId, sourcePositionAssetId, hiddenAssetId, activeSourceGeneration, onActiveSourcePositioned, onPhotoClick ->
            PersonDetailContent(
                state = state,
                transitionAssetId = transitionAssetId,
                sourcePositionAssetId = sourcePositionAssetId,
                hiddenAssetId = hiddenAssetId,
                activeSourceGeneration = activeSourceGeneration,
                onActiveSourcePositioned = onActiveSourcePositioned,
                onPhotoClick = onPhotoClick,
                onRetry = viewModel::refreshAll,
                onDismissBanner = viewModel::dismissBannerError,
                onLoadMore = viewModel::loadMore,
                onAvailableWidth = viewModel::setAvailableWidth,
                onAvailableViewportHeight = viewModel::setAvailableViewportHeight,
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
                    title = personName.ifBlank { unknownLabel },
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
fun PersonDetailContent(
    state: PersonDetailState,
    transitionAssetId: String? = null,
    sourcePositionAssetId: String? = null,
    hiddenAssetId: String? = null,
    activeSourceGeneration: Int = 0,
    onActiveSourcePositioned: ((PhotoOverlaySourcePosition) -> Unit)? = null,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissBanner: () -> Unit = {},
    onLoadMore: () -> Unit,
    onAvailableWidth: (Float) -> Unit,
    onAvailableViewportHeight: (Float) -> Unit = {},
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
        loadingText = if (state.isBuilding) stringResource(Res.string.loading_photos) else null,
        displayItems = state.displayItems,
        displayIndex = state.displayIndex,
        bannerMessage = state.bannerError?.asText(),
        lastSyncedAt = state.lastSyncedAt,
        transitionAssetId = transitionAssetId,
        sourcePositionAssetId = sourcePositionAssetId,
        hiddenAssetId = hiddenAssetId,
        activeSourceGeneration = activeSourceGeneration,
        onActiveSourcePositioned = onActiveSourcePositioned,
        targetRowHeight = state.targetRowHeight,
        rowHeightBounds = state.rowHeightBounds,
        viewConfig = state.viewConfig,
        onPhotoClick = onPhotoClick,
        onRetry = onRetry,
        onDismissBanner = onDismissBanner,
        onAvailableWidthChanged = onAvailableWidth,
        onAvailableViewportHeightChanged = onAvailableViewportHeight,
        onVisibleBucketIndexesChanged = onVisibleBucketIndexesChanged,
        onScrollInProgressChanged = onScrollInProgressChanged,
        onTargetRowHeightChanged = onTargetRowHeightChanged,
        contentTopPadding = contentTopPadding,
        contentBottomPadding = contentBottomPadding,
        listState = listState,
        isLoadingMore = state.isLoadingMore,
        onLoadMore = onLoadMore,
        sharedTransitionScope = sharedTransitionScope,
    )
}

@Preview
@Composable
private fun PersonDetailContentPreview() {
    PersonDetailContent(
        state = PersonDetailState(isLoading = true),
        onPhotoClick = {},
        onRetry = {},
        onLoadMore = {},
        onAvailableWidth = {},
    )
}
