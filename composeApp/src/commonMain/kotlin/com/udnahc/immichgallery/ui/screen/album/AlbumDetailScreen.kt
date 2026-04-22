package com.udnahc.immichgallery.ui.screen.album

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.ErrorBanner
import com.udnahc.immichgallery.ui.component.GroupSizeDropdown
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.SectionHeader
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.LocalPhotoBoundsTween
import com.udnahc.immichgallery.ui.util.PHOTO_TRANSITION_DURATION_MS
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.photoTransitionFadeIn
import com.udnahc.immichgallery.ui.util.photoTransitionFadeOut
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.loading_album
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
    // same snapshot. Otherwise the grid cell's AV can flip hidden in one
    // frame while the overlay mounts in a later frame; with
    // `ExitTransition.None` on the cell's per-cell AV, the source
    // sharedElement disposes before the target registers and the enter
    // animation degrades to a plain alpha crossfade.
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
    LaunchedEffect(selectedAssetId) {
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
            item is AlbumRowItem && item.row.photos.any { it.asset.id == assetId }
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
            AlbumDetailContent(
                state = state,
                hiddenAssetId = currentViewedAssetId,
                onPhotoClick = remember { { id: String -> selectedAssetId = id } },
                onRetry = viewModel::refreshAll,
                onDismissBanner = viewModel::dismissBannerError,
                onAvailableWidthChanged = viewModel::setAvailableWidth,
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
                title = state.albumName,
                onBack = onBack,
                trailingContent = {
                    GroupSizeDropdown(
                        selected = state.groupSize,
                        onSelected = viewModel::setGroupSize
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailContent(
    state: AlbumDetailState,
    hiddenAssetId: String? = null,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissBanner: () -> Unit = {},
    onAvailableWidthChanged: (Float) -> Unit = {},
    onTargetRowHeightChanged: (Float) -> Unit = {},
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    LoadingErrorContent(
        isLoading = (state.isBuilding || state.isLoading) && state.assets.isEmpty(),
        error = if (state.assets.isEmpty()) state.error else null,
        onRetry = onRetry,
        loadingText = if (state.isBuilding) stringResource(Res.string.loading_album) else null
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoomRowHeight(state.targetRowHeight, onTargetRowHeightChanged)
        ) {
            val widthDp = maxWidth.value
            LaunchedEffect(widthDp) {
                onAvailableWidthChanged(widthDp)
            }

            val displayItems = state.displayItems

            ScrollbarOverlay(
                listState = listState,
                topPadding = contentTopPadding,
                bottomPadding = contentBottomPadding
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.gridSpacing),
                    contentPadding = remember(contentTopPadding, contentBottomPadding) {
                        PaddingValues(
                            top = contentTopPadding,
                            bottom = contentBottomPadding
                        )
                    }
                ) {
                    items(
                        count = displayItems.size,
                        key = { displayItems[it].key },
                        contentType = { displayItems[it]::class }
                    ) { index ->
                        when (val item = displayItems[index]) {
                            is AlbumHeaderItem -> SectionHeader(label = item.label)
                            is AlbumRowItem -> JustifiedPhotoRow(
                                row = item.row,
                                spacing = Dimens.gridSpacing,
                                onPhotoClick = onPhotoClick,
                                sharedTransitionScope = sharedTransitionScope,
                                hiddenAssetId = hiddenAssetId,
                            )
                        }
                    }
                }
            }

            if (state.bannerError != null) {
                ErrorBanner(
                    message = state.bannerError,
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
