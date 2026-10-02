package com.udnahc.immichgallery.ui.screen.favorites

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.PhotoOverlayHost
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlaySourceIdentity
import com.udnahc.immichgallery.ui.component.resolveStaticPhotoOverlayInitialIndex
import com.udnahc.immichgallery.ui.model.asText
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.desktopGridZoom
import com.udnahc.immichgallery.ui.util.ensureReturnSourceVisible
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.favorites
import immichgallery.composeapp.generated.resources.favorites_empty
import immichgallery.composeapp.generated.resources.load_more
import immichgallery.composeapp.generated.resources.loading_favorites
import immichgallery.composeapp.generated.resources.retry
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: FavoritesViewModel = koinViewModel(),
    onShowInTimeline: ((LocalDate) -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val overlaySource = StaticPhotoOverlaySourceIdentity(
        ownerKey = "favorites:${state.ownerId.orEmpty()}:${state.activeRequestGeneration}",
        assetIds = state.assets.map { it.id },
        isPending = state.isLoading ||
            state.isLoadingMore ||
            state.nextPage != null ||
            state.firstUnconsumedPage != null
    )

    LaunchedEffect(viewModel.lastViewedAssetId) {
        val rowIndex = viewModel.getDisplayItemIndexForReturn() ?: return@LaunchedEffect
        listState.ensureReturnSourceVisible(rowIndex)
    }

    PhotoOverlayHost(
        initialIndexKey = overlaySource,
        onPrepareOverlayDismiss = { currentAssetId ->
            viewModel.lastViewedAssetId = currentAssetId
            viewModel.getDisplayItemIndexForReturn()
                ?.let { listState.ensureReturnSourceVisible(it) }
        },
        resolveInitialIndex = { assetId ->
            state.assets.indexOfFirst { it.id == assetId }.takeIf { it >= 0 }
        },
        resolveInitialIndexState = { assetId ->
            resolveStaticPhotoOverlayInitialIndex(overlaySource, assetId)
        },
        content = { _, transitionAssetId, hiddenAssetId, onPhotoClick ->
            FavoritesContent(
                state = state,
                listState = listState,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
                onPhotoClick = onPhotoClick,
                onRetry = viewModel::retry,
                onLoadMore = viewModel::loadMore,
                onSetAvailableWidth = viewModel::setAvailableWidth,
                onSetAvailableViewportHeight = viewModel::setAvailableViewportHeight,
                onSetTargetRowHeight = viewModel::setTargetRowHeight,
                sharedTransitionScope = this
            )
        },
        overlay = { initialIndex, onDismissHost, onCurrentAssetChanged, onStlTransitionActiveChanged, sharedTransitionScope ->
            StaticPhotoOverlay(
                assets = state.assets,
                initialIndex = initialIndex,
                apiKey = viewModel.apiKey,
                getAssetDetail = viewModel::getAssetDetail,
                getAssetStack = viewModel::getAssetStack,
                onPersonClick = onPersonClick,
                onDismiss = onDismissHost,
                onShowInTimeline = onShowInTimeline?.let { showDate ->
                    { date, currentAssetId ->
                        onDismissHost.dismiss(currentAssetId) { showDate(date) }
                    }
                },
                onCurrentAssetChanged = onCurrentAssetChanged,
                onStlTransitionActiveChanged = onStlTransitionActiveChanged,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this
            )
        },
        chrome = { showOverlay ->
            AnimatedVisibility(
                visible = !showOverlay,
                enter = systemBarFadeIn,
                exit = systemBarFadeOut
            ) {
                DetailTopBar(
                    title = stringResource(Res.string.favorites),
                    onBack = onBack
                )
            }
        }
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FavoritesContent(
    state: FavoritesState,
    listState: LazyListState,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSetAvailableWidth: (Float) -> Unit,
    onSetAvailableViewportHeight: (Float) -> Unit,
    onSetTargetRowHeight: (Float) -> Unit,
    sharedTransitionScope: SharedTransitionScope?
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusBarPadding + Dimens.topBarHeight

    when {
        state.isLoading -> FavoritesLoadingState(contentTopPadding)
        state.error != null -> FavoritesErrorState(
            message = state.error.asText(),
            contentTopPadding = contentTopPadding,
            onRetry = onRetry
        )
        state.assets.isEmpty() && state.nextPage == null -> FavoritesMessageState(
            message = stringResource(Res.string.favorites_empty),
            contentTopPadding = contentTopPadding
        )
        else -> FavoritesGrid(
            state = state,
            listState = listState,
            contentTopPadding = contentTopPadding,
            contentBottomPadding = navigationBarPadding,
            transitionAssetId = transitionAssetId,
            hiddenAssetId = hiddenAssetId,
            onPhotoClick = onPhotoClick,
            onLoadMore = onLoadMore,
            onSetAvailableWidth = onSetAvailableWidth,
            onSetAvailableViewportHeight = onSetAvailableViewportHeight,
            onSetTargetRowHeight = onSetTargetRowHeight,
            sharedTransitionScope = sharedTransitionScope
        )
    }
}

@Composable
private fun FavoritesLoadingState(contentTopPadding: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentTopPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
        ) {
            CircularProgressIndicator()
            Text(stringResource(Res.string.loading_favorites))
        }
    }
}

@Composable
private fun FavoritesErrorState(
    message: String,
    contentTopPadding: Dp,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentTopPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
        ) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) {
                Text(stringResource(Res.string.retry))
            }
        }
    }
}

@Composable
private fun FavoritesMessageState(
    message: String,
    contentTopPadding: Dp
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentTopPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FavoritesGrid(
    state: FavoritesState,
    listState: LazyListState,
    contentTopPadding: Dp,
    contentBottomPadding: Dp,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    onPhotoClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    onSetAvailableWidth: (Float) -> Unit,
    onSetAvailableViewportHeight: (Float) -> Unit,
    onSetTargetRowHeight: (Float) -> Unit,
    sharedTransitionScope: SharedTransitionScope?
) {
    val density = LocalDensity.current
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            layoutInfo.totalItemsCount > 0 && lastVisible >= layoutInfo.totalItemsCount - 3
        }
    }
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    var lastAutomaticLoadKey by remember(state.activeRequestGeneration) {
        mutableStateOf<FavoritesAutoLoadKey?>(null)
    }
    val automaticLoadKey = state.nextPage
        ?.takeIf { state.loadMoreError == null }
        ?.let { page ->
            FavoritesAutoLoadKey(state.activeRequestGeneration, page)
        }
    LaunchedEffect(shouldLoadMore, automaticLoadKey, state.isLoadingMore) {
        if (
            shouldAutomaticallyLoadFavoritesPage(
                shouldLoadMore = shouldLoadMore,
                isLoadingMore = state.isLoadingMore,
                candidate = automaticLoadKey,
                lastAttempt = lastAutomaticLoadKey
            )
        ) {
            lastAutomaticLoadKey = automaticLoadKey
            latestOnLoadMore()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.screenPadding)
    ) {
        val widthDp = with(density) { constraints.maxWidth.toDp() }
        val visibleHeight = (maxHeight - contentTopPadding - contentBottomPadding)
            .value
            .coerceAtLeast(0f)
        LaunchedEffect(widthDp, visibleHeight) {
            onSetAvailableWidth(widthDp.value)
            onSetAvailableViewportHeight(visibleHeight)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoomRowHeight(
                    state.targetRowHeight,
                    state.rowHeightBounds,
                    onSetTargetRowHeight
                )
                .desktopGridZoom(
                    state.targetRowHeight,
                    state.rowHeightBounds,
                    onSetTargetRowHeight
                )
        ) {
            ScrollbarOverlay(
                listState = listState,
                topPadding = contentTopPadding,
                bottomPadding = contentBottomPadding
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = contentTopPadding,
                        bottom = contentBottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.gridSpacing)
                ) {
                    items(
                        items = state.rows,
                        key = { it.gridKey },
                        contentType = { "favorites_photo_row" }
                    ) { row ->
                        JustifiedPhotoRow(
                            row = row,
                            spacing = Dimens.gridSpacing,
                            onPhotoClick = onPhotoClick,
                            sharedTransitionScope = sharedTransitionScope,
                            transitionAssetId = transitionAssetId,
                            hiddenAssetId = hiddenAssetId
                        )
                    }
                    if (state.isLoadingMore) {
                        item(key = "favorites_loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Dimens.mediumSpacing),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(Dimens.iconSize))
                            }
                        }
                    } else if (state.nextPage != null) {
                        item(key = "favorites_load_more") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Dimens.mediumSpacing),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
                            ) {
                                state.loadMoreError?.let { message ->
                                    Text(
                                        text = message.asText(),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Button(onClick = onLoadMore) {
                                    Text(stringResource(Res.string.load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class FavoritesAutoLoadKey(
    val requestGeneration: Long,
    val page: Int
)

internal fun shouldAutomaticallyLoadFavoritesPage(
    shouldLoadMore: Boolean,
    isLoadingMore: Boolean,
    candidate: FavoritesAutoLoadKey?,
    lastAttempt: FavoritesAutoLoadKey?
): Boolean = shouldLoadMore &&
        !isLoadingMore &&
        candidate != null &&
        candidate != lastAttempt
