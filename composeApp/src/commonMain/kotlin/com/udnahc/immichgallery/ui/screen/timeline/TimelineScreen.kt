package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.ui.component.ErrorBanner
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.SectionHeader
import com.udnahc.immichgallery.ui.component.SuccessBanner
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.loading_timeline
import immichgallery.composeapp.generated.resources.timeline_failed_tap_retry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelineScreen(
    groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    onRefreshCallback: ((() -> Unit)?) -> Unit = {},
    onSyncingState: (Boolean) -> Unit = {},
    onOverlayActiveChange: (Boolean) -> Unit = {},
    viewModel: TimelineViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isBuilding by viewModel.isBuilding.collectAsState()
    val buildError by viewModel.buildError.collectAsState()
    val listState = rememberLazyListState()

    var selectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSelectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (selectedAssetId != null) lastSelectedAssetId = selectedAssetId

    var overlayInitialIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedAssetId) {
        val id = selectedAssetId
        overlayInitialIndex = if (id != null) viewModel.getGlobalPhotoIndex(id) ?: 0 else null
    }
    val showOverlay = selectedAssetId != null && overlayInitialIndex != null

    LaunchedEffect(showOverlay) { onOverlayActiveChange(showOverlay) }
    PlatformBackHandler(enabled = showOverlay) { selectedAssetId = null }

    // Report refresh callback and syncing state to parent
    LaunchedEffect(Unit) { onRefreshCallback { viewModel.refreshAll() } }
    LaunchedEffect(state.isSyncing, isBuilding) { onSyncingState(state.isSyncing || isBuilding) }

    LaunchedEffect(groupSize) {
        viewModel.setGroupSize(groupSize)
    }

    // Scroll back to last viewed asset (or its bucket placeholder) on return from detail
    LaunchedEffect(viewModel.lastViewedAssetId, viewModel.lastViewedBucket) {
        val displayIndex = viewModel.getDisplayItemIndexForReturn(
            viewModel.lastViewedAssetId,
            viewModel.lastViewedBucket
        ) ?: return@LaunchedEffect
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == displayIndex }
        if (!isVisible) {
            listState.scrollToItem(displayIndex)
        }
    }

    // First-launch building screen (bypasses debounced state pipeline)
    if (isBuilding) {
        LoadingErrorContent(
            isLoading = true,
            error = null,
            onRetry = viewModel::refreshAll,
            loadingText = stringResource(Res.string.loading_timeline)
        ) {}
        return
    }
    if (buildError != null) {
        LoadingErrorContent(
            isLoading = false,
            error = buildError,
            onRetry = viewModel::refreshAll
        ) {}
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TimelineContent(
                    state = state,
                    listState = listState,
                    onFirstVisibleItemChanged = viewModel::onFirstVisibleItemChanged,
                    onTargetRowHeightChanged = viewModel::setTargetRowHeight,
                    onAvailableWidthChanged = viewModel::setAvailableWidth,
                    onRetryBucket = viewModel::retryBucket,
                    onPhotoClick = remember { { assetId: String -> selectedAssetId = assetId } },
                    onRetry = viewModel::refreshAll,
                    onDismissBannerError = viewModel::dismissBannerError,
                    onDismissBannerSuccess = viewModel::dismissBannerSuccess,
                    labelProvider = viewModel.labelProvider,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedVisibility,
                )
            }

            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val assetId = lastSelectedAssetId ?: return@AnimatedVisibility
                val initialIndex = overlayInitialIndex ?: 0
                TimelinePhotoOverlay(
                    timelineState = viewModel.state,
                    initialIndex = initialIndex,
                    apiKey = viewModel.apiKey,
                    getAssetFileName = viewModel::getAssetFileName,
                    getAssetDetail = viewModel::getAssetDetail,
                    getAssetsForBucket = viewModel::getAssetsForBucket,
                    onBucketNeeded = viewModel::loadBucketAssets,
                    onPersonClick = onPersonClick,
                    onDismiss = { currentAssetId, currentBucket ->
                        viewModel.lastViewedAssetId = currentAssetId
                        viewModel.lastViewedBucket = currentBucket
                        selectedAssetId = null
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedVisibility,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelineContent(
    state: TimelineState,
    listState: LazyListState = rememberLazyListState(),
    onFirstVisibleItemChanged: (Int) -> Unit,
    onTargetRowHeightChanged: (Float) -> Unit = {},
    onAvailableWidthChanged: (Float) -> Unit = {},
    onRetryBucket: (String) -> Unit,
    onPhotoClick: (assetId: String) -> Unit,
    onRetry: () -> Unit,
    onDismissBannerError: () -> Unit = {},
    onDismissBannerSuccess: () -> Unit = {},
    labelProvider: (Float) -> String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val displayItems = state.displayItems
    val targetRowHeight = state.targetRowHeight

    LoadingErrorContent(
        isLoading = state.isLoading && displayItems.isEmpty(),
        error = if (displayItems.isEmpty()) state.error else null,
        onRetry = onRetry
    ) {
        key(state.groupSize) {
            // Visibility: prefetch nearby buckets only when scroll settles
            @OptIn(FlowPreview::class)
            LaunchedEffect(Unit) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .debounce(300)
                    .collectLatest { onFirstVisibleItemChanged(it) }
            }

            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBarPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToZoomRowHeight(targetRowHeight, onTargetRowHeightChanged)
            ) {
                // Report available width to ViewModel for row packing
                LaunchedEffect(maxWidth) {
                    onAvailableWidthChanged(maxWidth.value)
                }

                ScrollbarOverlay(
                    listState = listState,
                    topPadding = statusBarPadding + Dimens.topBarHeight + Dimens.sectionHeaderHeight,
                    bottomPadding = Dimens.bottomBarHeight + navBarPadding,
                    labelProvider = labelProvider,
                    yearMarkers = state.yearMarkers
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Dimens.gridSpacing),
                        contentPadding = remember(statusBarPadding, navBarPadding) {
                            PaddingValues(
                                top = statusBarPadding + Dimens.topBarHeight,
                                bottom = Dimens.bottomBarHeight + navBarPadding
                            )
                        }
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
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                is PlaceholderItem -> PlaceholderRow(
                                    estimatedHeight = item.estimatedHeight
                                )
                                is ErrorItem -> ErrorCell(
                                    onRetry = { onRetryBucket(item.timeBucket) }
                                )
                                is PhotoItem -> { /* Should not appear at top level with row packing */ }
                            }
                        }
                    }
                }

                // Sticky header overlay
                StickyHeaderOverlay(
                    listState = listState,
                    displayItems = displayItems,
                    statusBarPadding = statusBarPadding
                )

                // Banner overlays
                val bannerModifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = Dimens.topBarHeight + Dimens.sectionHeaderHeight)
                if (state.bannerError != null) {
                    ErrorBanner(
                        message = state.bannerError,
                        lastSyncedAt = state.lastSyncedAt,
                        onDismiss = onDismissBannerError,
                        modifier = bannerModifier
                    )
                } else if (state.bannerSuccess != null) {
                    SuccessBanner(
                        message = state.bannerSuccess,
                        onDismiss = onDismissBannerSuccess,
                        modifier = bannerModifier
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderRow(estimatedHeight: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(estimatedHeight.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
private fun ErrorCell(onRetry: () -> Unit) {
    val failedText = stringResource(Res.string.timeline_failed_tap_retry)
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.sectionHeaderHeight)
            .clickable(onClick = onRetry),
        contentAlignment = Alignment.Center
    ) {
        Text(
            failedText,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StickyHeaderOverlay(
    listState: LazyListState,
    displayItems: List<TimelineDisplayItem>,
    statusBarPadding: Dp
) {
    val label by remember(displayItems) {
        derivedStateOf {
            displayItems.getOrNull(listState.firstVisibleItemIndex)?.sectionLabel
        }
    }

    if (label != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                .statusBarsPadding()
                .padding(top = Dimens.topBarHeight)
                .height(Dimens.sectionHeaderHeight)
                .padding(horizontal = Dimens.sectionHeaderPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = label!!,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Preview
@Composable
private fun SectionHeaderPreview() {
    SectionHeader(label = "March 2026")
}

@Preview
@Composable
private fun ErrorCellPreview() {
    ErrorCell(onRetry = {})
}

@Preview
@Composable
private fun StickyHeaderOverlayPreview() {
    val listState = rememberLazyListState()
    StickyHeaderOverlay(
        listState = listState,
        displayItems = emptyList(),
        statusBarPadding = 0.dp
    )
}
