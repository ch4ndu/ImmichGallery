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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    var selectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSelectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (selectedAssetId != null) lastSelectedAssetId = selectedAssetId

    var currentViewedAssetId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedAssetId) { currentViewedAssetId = selectedAssetId }

    // Pre-compute the pager's starting index before showing the overlay so the
    // grid cell's AV-exit and the overlay's AV-enter both commit in the same
    // frame — avoids a one-frame stutter at the start of the shared-element
    // transition (matches TimelineScreen's gating pattern).
    var overlayInitialIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedAssetId, state.assets) {
        val id = selectedAssetId
        overlayInitialIndex = if (id != null) {
            state.assets.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
        } else null
    }
    val showOverlay = selectedAssetId != null && overlayInitialIndex != null

    var overlayAnimActive by remember { mutableStateOf(false) }
    LaunchedEffect(selectedAssetId) {
        overlayAnimActive = true
        delay(PHOTO_TRANSITION_DURATION_MS.toLong())
        overlayAnimActive = false
    }

    PlatformBackHandler(enabled = showOverlay) { selectedAssetId = null }

    // Scroll back to last viewed asset when returning from detail
    LaunchedEffect(viewModel.lastViewedAssetId) {
        val assetId = viewModel.lastViewedAssetId ?: return@LaunchedEffect
        val itemIndex = state.displayItems.indexOfFirst { item ->
            item is PersonRowItem && item.row.photos.any { it.asset.id == assetId }
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
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            PersonDetailContent(
                state = state,
                hiddenAssetId = currentViewedAssetId,
                onPhotoClick = remember { { id: String -> selectedAssetId = id } },
                onRetry = viewModel::refreshAll,
                onDismissBanner = viewModel::dismissBannerError,
                onLoadMore = viewModel::loadMore,
                onAvailableWidth = viewModel::setAvailableWidth,
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
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedVisibility,
                )
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
                }
            )
        }
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
        loadingText = if (state.isBuilding) stringResource(Res.string.loading_photos) else null
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoomRowHeight(state.targetRowHeight, onTargetRowHeightChanged)
        ) {
            val widthDp = maxWidth
            LaunchedEffect(widthDp) {
                onAvailableWidth(widthDp.value)
            }

            val shouldLoadMore by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val totalItems = info.totalItemsCount
                    totalItems > 0 && lastVisible >= totalItems - 3
                }
            }
            LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore) onLoadMore()
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
                        key = { displayItems[it].key },
                        contentType = { displayItems[it]::class }
                    ) { index ->
                        when (val item = displayItems[index]) {
                            is PersonHeaderItem -> SectionHeader(label = item.label)
                            is PersonRowItem -> JustifiedPhotoRow(
                                row = item.row,
                                spacing = Dimens.gridSpacing,
                                onPhotoClick = onPhotoClick,
                                sharedTransitionScope = sharedTransitionScope,
                                hiddenAssetId = hiddenAssetId,
                            )
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
