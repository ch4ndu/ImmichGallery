package com.udnahc.immichgallery.ui.screen.people

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.visibleBucketIndexesForDisplayIndexes
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.ErrorBanner
import com.udnahc.immichgallery.ui.component.GroupSizeDropdown
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.MosaicPhotoBand
import com.udnahc.immichgallery.ui.component.MosaicViewConfigIconMenu
import com.udnahc.immichgallery.ui.component.PlaceholderRow
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.SectionHeader
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.model.UiMessage
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.LocalPhotoBoundsTween
import com.udnahc.immichgallery.ui.util.PHOTO_TRANSITION_DURATION_MS
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.photoTransitionFadeIn
import com.udnahc.immichgallery.ui.util.photoTransitionFadeOut
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import com.udnahc.immichgallery.ui.util.desktopGridZoom
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.loading_photos
import immichgallery.composeapp.generated.resources.person_detail_sync_in_progress
import immichgallery.composeapp.generated.resources.timeline_cannot_connect
import immichgallery.composeapp.generated.resources.timeline_no_connection
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
    val snackbarHostState = remember { SnackbarHostState() }
    val syncInProgressMessage = stringResource(Res.string.person_detail_sync_in_progress)

    LaunchedEffect(viewModel, syncInProgressMessage) {
        viewModel.snackbarEvents.collect { message ->
            when (message) {
                PersonDetailSnackbarMessage.SyncInProgress -> {
                    snackbarHostState.showSnackbar(syncInProgressMessage)
                }
            }
        }
    }

    DisposableEffect(viewModel) {
        viewModel.activateForegroundMosaic()
        onDispose { viewModel.deactivateForegroundMosaic() }
    }

    var selectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSelectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (selectedAssetId != null) lastSelectedAssetId = selectedAssetId

    // Bumped on every open so key() below forces a fresh STL mount — fixes the
    // same-asset re-tap Y-doubling that the lastSelectedAssetId key missed.
    // MUST update synchronously in the composition body (not in a
    // LaunchedEffect); otherwise the epoch increment lands in the same frame
    // as overlayInitialIndex, so the STL remount coincides with showOverlay
    // flipping true — AnimatedVisibility mounts already-visible and the enter
    // animation snaps instead of running.
    var selectionEpoch by rememberSaveable { mutableStateOf(0) }
    var prevSelectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (prevSelectedAssetId == null && selectedAssetId != null) {
        selectionEpoch++
    }
    prevSelectedAssetId = selectedAssetId

    // Follows the pager's current page; drives which grid cell is hidden.
    // NOTE: updated inside the same LaunchedEffect as `overlayInitialIndex`
    // below — AFTER `overlayInitialIndex` — so both writes commit in the
    // same snapshot. Otherwise the grid cell's AV can flip hidden before
    // the overlay mounts, the source sharedElement disposes (ExitTransition
    // is None), and the enter animation degrades to a plain alpha crossfade.
    var currentViewedAssetId by remember { mutableStateOf<String?>(null) }

    var overlayInitialIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedAssetId, state.assets) {
        val id = selectedAssetId
        if (id != null) {
            overlayInitialIndex = state.assets.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
            currentViewedAssetId = id
        } else {
            currentViewedAssetId = null
            overlayInitialIndex = null
        }
    }
    val showOverlay = selectedAssetId != null && overlayInitialIndex != null

    // Forwarded from the overlay: STL reports whether its bounds animation is
    // actually running. Used below to flip `overlayAnimActive` false only when
    // the animation has truly settled.
    var stlTransitionActive by remember { mutableStateOf(false) }

    var overlayAnimActive by remember { mutableStateOf(false) }
    // Keyed on selectionEpoch as well so rapid open/close cycles don't let a
    // previous effect's snapshotFlow resolve on a stale `stlTransitionActive`
    // idle state from the prior transition.
    LaunchedEffect(selectedAssetId, selectionEpoch) {
        overlayAnimActive = true
        delay(PHOTO_TRANSITION_DURATION_MS.toLong())
        snapshotFlow { stlTransitionActive }.first { !it }
        overlayAnimActive = false
    }

    PlatformBackHandler(enabled = showOverlay) { selectedAssetId = null }

    // Scroll back to last viewed asset when returning from detail
    LaunchedEffect(viewModel.lastViewedAssetId) {
        val assetId = viewModel.lastViewedAssetId ?: return@LaunchedEffect
        val itemIndex = state.displayItems.indexOfFirst { item ->
            when (item) {
                is RowItem -> item.photos.any { it.asset.id == assetId }
                is MosaicBandItem -> item.tiles.any { it.photo.asset.id == assetId }
                else -> false
            }
        }
        if (itemIndex >= 0) {
            val info = listState.layoutInfo
            val visibleItem = info.visibleItemsInfo.firstOrNull { it.index == itemIndex }
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

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPhotoBoundsTween provides overlayAnimActive) {
        androidx.compose.runtime.key(selectionEpoch) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            PersonDetailContent(
                state = state,
                hiddenAssetId = currentViewedAssetId,
                onPhotoClick = remember { { id: String -> selectedAssetId = id } },
                onRetry = viewModel::refreshAll,
                onDismissBanner = viewModel::dismissBannerError,
                onLoadMore = viewModel::loadMore,
                onAvailableWidth = viewModel::setAvailableWidth,
                onAvailableViewportHeight = viewModel::setAvailableViewportHeight,
                onVisibleBucketIndexesChanged = viewModel::setVisibleBucketIndexes,
                onTargetRowHeightChanged = viewModel::setTargetRowHeight,
                contentTopPadding = contentTopPadding,
                contentBottomPadding = contentBottomPadding,
                listState = listState,
                sharedTransitionScope = this@SharedTransitionLayout,
            )

            AnimatedVisibility(
                visible = showOverlay,
                enter = photoTransitionFadeIn,
                exit = photoTransitionFadeOut,
            ) {
                lastSelectedAssetId ?: return@AnimatedVisibility
                StaticPhotoOverlay(
                    assets = state.assets,
                    initialIndex = overlayInitialIndex ?: 0,
                    apiKey = viewModel.apiKey,
                    getAssetDetail = viewModel::getAssetDetail,
                    onPersonClick = onPersonClick,
                    onDismiss = { currentAssetId ->
                        viewModel.lastViewedAssetId = currentAssetId
                        selectedAssetId = null
                    },
                    onCurrentAssetChanged = { id -> currentViewedAssetId = id },
                    onStlTransitionActiveChanged = { stlTransitionActive = it },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedVisibility,
                )
            }
        }
        }
        }

        AnimatedVisibility(
            visible = !showOverlay,
            enter = systemBarFadeIn,
            exit = systemBarFadeOut,
        ) {
            DetailTopBar(
                title = personName.ifBlank { unknownLabel },
                onBack = onBack,
                trailingContent = {
                    GroupSizeDropdown(
                        selected = state.groupSize,
                        onSelected = viewModel::setGroupSize
                    )
                    MosaicViewConfigIconMenu(
                        viewConfig = state.viewConfig,
                        onViewConfigChanged = viewModel::setViewConfig
                    )
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentBottomPadding + Dimens.mediumSpacing)
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PersonDetailContent(
    state: PersonDetailState,
    hiddenAssetId: String? = null,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissBanner: () -> Unit = {},
    onLoadMore: () -> Unit,
    onAvailableWidth: (Float) -> Unit,
    onAvailableViewportHeight: (Float) -> Unit = {},
    onVisibleBucketIndexesChanged: (List<Int>) -> Unit = {},
    onTargetRowHeightChanged: (Float) -> Unit = {},
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    LoadingErrorContent(
        isLoading = (state.isBuilding || state.isLoading) && state.assets.isEmpty(),
        error = if (state.assets.isEmpty()) state.error.asTextOrNull() else null,
        onRetry = onRetry,
        loadingText = if (state.isBuilding) stringResource(Res.string.loading_photos) else null
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val widthDp = maxWidth
            val visibleGridHeight = (maxHeight - contentTopPadding - contentBottomPadding)
                .value
                .coerceAtLeast(0f)
            LaunchedEffect(widthDp, visibleGridHeight) {
                onAvailableWidth(widthDp.value)
                onAvailableViewportHeight(visibleGridHeight)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToZoomRowHeight(state.targetRowHeight, state.rowHeightBounds, onTargetRowHeightChanged)
                    .desktopGridZoom(state.targetRowHeight, state.rowHeightBounds, onTargetRowHeightChanged)
            ) {
                val shouldLoadMore by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val totalItems = info.totalItemsCount
                        totalItems > 0 && lastVisible >= totalItems - 3
                    }
                }
                val latestOnLoadMore by rememberUpdatedState(onLoadMore)
                LaunchedEffect(shouldLoadMore) {
                    if (shouldLoadMore) latestOnLoadMore()
                }

                val displayItems = state.displayItems
                val latestDisplayIndex by rememberUpdatedState(state.displayIndex)
                LaunchedEffect(listState) {
                    snapshotFlow {
                        visibleBucketIndexesForDisplayIndexes(
                            latestDisplayIndex,
                            listState.layoutInfo.visibleItemsInfo.map { it.index }
                        )
                    }
                        .distinctUntilChanged()
                        .collect { indexes -> onVisibleBucketIndexesChanged(indexes) }
                }

                ScrollbarOverlay(
                    listState = listState,
                    topPadding = contentTopPadding,
                    bottomPadding = contentBottomPadding
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = remember(contentTopPadding, contentBottomPadding) {
                            PaddingValues(
                                top = contentTopPadding,
                                bottom = contentBottomPadding
                            )
                        },
                        verticalArrangement = Arrangement.spacedBy(Dimens.gridSpacing)
                    ) {
                        items(
                            count = displayItems.size,
                            key = { displayItems[it].gridKey },
                            contentType = { displayItems[it]::class }
                        ) { index ->
                            when (val item = displayItems[index]) {
                                is HeaderItem -> SectionHeader(label = item.label)
                                is RowItem -> JustifiedPhotoRow(
                                    row = item,
                                    spacing = Dimens.gridSpacing,
                                    onPhotoClick = onPhotoClick,
                                    sharedTransitionScope = sharedTransitionScope,
                                    hiddenAssetId = hiddenAssetId,
                                )
                                is MosaicBandItem -> MosaicPhotoBand(
                                    band = item,
                                    onPhotoClick = onPhotoClick,
                                    sharedTransitionScope = sharedTransitionScope,
                                    hiddenAssetId = hiddenAssetId,
                                )
                                is PlaceholderItem -> PlaceholderRow(
                                    estimatedHeight = item.estimatedHeight
                                )
                                else -> Unit
                            }
                        }

                        if (state.isLoadingMore) {
                            item(key = "loading_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Dimens.screenPadding),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }

                val bannerError = state.bannerError
                if (bannerError != null) {
                    ErrorBanner(
                        message = bannerError.asText(),
                        lastSyncedAt = state.lastSyncedAt,
                        onDismiss = onDismissBanner,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = contentTopPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun UiMessage?.asTextOrNull(): String? = this?.asText()

@Composable
private fun UiMessage.asText(): String =
    stringResource(
        when (this) {
            UiMessage.NoConnectionToServer -> Res.string.timeline_no_connection
            UiMessage.CannotConnectToServer -> Res.string.timeline_cannot_connect
            else -> Res.string.timeline_cannot_connect
        }
    )

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
