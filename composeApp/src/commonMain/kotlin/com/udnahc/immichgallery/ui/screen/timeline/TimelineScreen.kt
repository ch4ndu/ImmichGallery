package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.TIMELINE_MOSAIC_COMPACT_COLUMN_COUNT
import com.udnahc.immichgallery.domain.model.TIMELINE_MOSAIC_LARGE_COLUMN_COUNT
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineScrollTarget
import com.udnahc.immichgallery.domain.model.TimelineScrollbarTargetTracker
import com.udnahc.immichgallery.domain.model.timelineScrollFractionForDisplayIndex
import com.udnahc.immichgallery.domain.model.visibleBucketIndexesForDisplayIndexes
import com.udnahc.immichgallery.ui.component.ErrorBanner
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.MosaicPhotoBand
import com.udnahc.immichgallery.ui.component.PhotoOverlayHost
import com.udnahc.immichgallery.ui.component.PlaceholderRow
import com.udnahc.immichgallery.ui.component.SectionHeader
import com.udnahc.immichgallery.ui.component.SuccessBanner
import com.udnahc.immichgallery.ui.model.asText
import com.udnahc.immichgallery.ui.util.desktopGridZoom
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.loading_timeline
import immichgallery.composeapp.generated.resources.timeline_failed_tap_retry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val LARGE_SCREEN_MOSAIC_WIDTH = 840.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelineScreen(
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    onRefreshCallback: ((() -> Unit)?) -> Unit = {},
    onSyncingState: (Boolean) -> Unit = {},
    onColdSyncBlockingState: (Boolean) -> Unit = {},
    onOverlayActiveChange: (Boolean) -> Unit = {},
    viewModel: TimelineViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isBuilding by viewModel.isBuilding.collectAsState()
    val buildError by viewModel.buildError.collectAsState()
    val listState = rememberLazyListState()

    // Report refresh callback and syncing state to parent
    LaunchedEffect(Unit) { onRefreshCallback { viewModel.refreshAll() } }
    LaunchedEffect(state.isSyncing, isBuilding) { onSyncingState(state.isSyncing || isBuilding) }
    LaunchedEffect(isBuilding) { onColdSyncBlockingState(isBuilding) }
    DisposableEffect(Unit) {
        onDispose { onColdSyncBlockingState(false) }
    }

    // Scroll back to last viewed asset (or its bucket placeholder) on return from detail.
    // Uses fully-visible check (offset within viewport bounds) rather than a loose
    // "any pixel peeking" test — otherwise rows partially clipped under the top bar
    // or overscroll area count as visible and the scroll is skipped, leaving the
    // user on a row that's mostly offscreen.
    LaunchedEffect(viewModel.lastViewedAssetId, viewModel.lastViewedBucket) {
        val displayIndex = viewModel.getDisplayItemIndexForReturn(
            viewModel.lastViewedAssetId,
            viewModel.lastViewedBucket
        ) ?: return@LaunchedEffect
        val info = listState.layoutInfo
        val visibleItem = info.visibleItemsInfo.firstOrNull { it.index == displayIndex }
        val fullyVisible = visibleItem != null &&
            visibleItem.offset >= info.viewportStartOffset &&
            (visibleItem.offset + visibleItem.size) <= info.viewportEndOffset
        if (!fullyVisible) {
            listState.scrollToItem(displayIndex)
        }
    }

    // First-launch building screen (bypasses debounced state pipeline)
    if (isBuilding) {
        TimelineColdSyncLoading(
            loadingText = stringResource(Res.string.loading_timeline),
            onAvailableWidthChanged = viewModel::setAvailableWidth,
            onAvailableViewportHeightChanged = viewModel::setAvailableViewportHeight,
            onMosaicColumnCountChanged = viewModel::setMosaicColumnCount
        )
        return
    }
    if (buildError != null) {
        LoadingErrorContent(
            isLoading = false,
            error = buildError?.asText(),
            onRetry = viewModel::refreshAll
        ) {}
        return
    }

    PhotoOverlayHost(
        onOverlayActiveChange = onOverlayActiveChange,
        resolveInitialIndex = { assetId -> viewModel.getGlobalPhotoIndex(assetId) },
        content = { showOverlay, hiddenAssetId, onPhotoClick ->
            // Grid is no longer wrapped in its own AnimatedVisibility — it stays
            // composed behind the overlay so drag-to-dismiss reveals it.
            // Per-cell AVs in ThumbnailCell drive the shared-element animation.
            TimelineContent(
                state = state,
                listState = listState,
                showOverlay = showOverlay,
                hiddenAssetId = hiddenAssetId,
                onVisibleBucketsChanged = viewModel::onVisibleBucketsChanged,
                onViewportBucketTargeted = viewModel::onViewportBucketTargeted,
                scrollTargetForFraction = viewModel::scrollTargetForFraction,
                onTargetRowHeightChanged = viewModel::setTargetRowHeight,
                onMosaicColumnCountChanged = viewModel::setMosaicColumnCount,
                onAvailableWidthChanged = viewModel::setAvailableWidth,
                onAvailableViewportHeightChanged = viewModel::setAvailableViewportHeight,
                onRetryBucket = viewModel::retryBucket,
                onPhotoClick = onPhotoClick,
                onRetry = viewModel::refreshAll,
                onDismissBannerError = viewModel::dismissBannerError,
                onDismissBannerSuccess = viewModel::dismissBannerSuccess,
                labelProvider = viewModel.labelProvider,
                sharedTransitionScope = this,
            )
        },
        overlay = { initialIndex, onDismissHost, onCurrentAssetChanged, onStlTransitionActiveChanged, sharedTransitionScope ->
                TimelinePhotoOverlay(
                    timelineState = viewModel.overlayState,
                    initialIndex = initialIndex,
                    apiKey = viewModel.apiKey,
                    getAssetFileName = viewModel::getAssetFileName,
                    getAssetDetail = viewModel::getAssetDetail,
                    assetCache = viewModel.bucketAssetsCache,
                    onBucketNeeded = viewModel::loadBucketAssets,
                    onPersonClick = onPersonClick,
                    onDismiss = { currentAssetId, currentBucket ->
                        viewModel.lastViewedAssetId = currentAssetId
                        viewModel.lastViewedBucket = currentBucket
                        onDismissHost(currentAssetId)
                    },
                    onCurrentAssetChanged = onCurrentAssetChanged,
                    onStlTransitionActiveChanged = onStlTransitionActiveChanged,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                )
        }
    )
}

@Composable
private fun TimelineColdSyncLoading(
    loadingText: String,
    onAvailableWidthChanged: (Float) -> Unit,
    onAvailableViewportHeightChanged: (Float) -> Unit,
    onMosaicColumnCountChanged: (Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val timelineMosaicColumnCount = if (maxWidth >= LARGE_SCREEN_MOSAIC_WIDTH) {
            TIMELINE_MOSAIC_LARGE_COLUMN_COUNT
        } else {
            TIMELINE_MOSAIC_COMPACT_COLUMN_COUNT
        }
        LaunchedEffect(maxWidth, maxHeight, timelineMosaicColumnCount) {
            onAvailableWidthChanged(maxWidth.value)
            onAvailableViewportHeightChanged(maxHeight.value)
            onMosaicColumnCountChanged(timelineMosaicColumnCount)
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(Dimens.largeSpacing))
                Text(
                    text = loadingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    showOverlay: Boolean = false,
    hiddenAssetId: String? = null,
    onVisibleBucketsChanged: (List<Int>, TimelineBucketTargetReason) -> Unit,
    onViewportBucketTargeted: (Int, TimelineBucketTargetReason) -> Unit,
    scrollTargetForFraction: (Float) -> TimelineScrollTarget?,
    onTargetRowHeightChanged: (Float) -> Unit = {},
    onMosaicColumnCountChanged: (Int) -> Unit = {},
    onAvailableWidthChanged: (Float) -> Unit = {},
    onAvailableViewportHeightChanged: (Float) -> Unit = {},
    onRetryBucket: (String) -> Unit,
    onPhotoClick: (assetId: String) -> Unit,
    onRetry: () -> Unit,
    onDismissBannerError: () -> Unit = {},
    onDismissBannerSuccess: () -> Unit = {},
    labelProvider: (Float) -> String?,
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    val displayItems = state.displayItems
    val targetRowHeight = state.targetRowHeight
    val latestDisplayIndex by rememberUpdatedState(state.displayIndex)
    val latestPageIndex by rememberUpdatedState(state.pageIndex)

    LoadingErrorContent(
        isLoading = state.isLoading && displayItems.isEmpty(),
        error = if (displayItems.isEmpty()) state.error else null,
        onRetry = onRetry
    ) {
        key(state.groupSize) {
            LaunchedEffect(listState) {
                snapshotFlow {
                    visibleBucketIndexesForDisplayIndexes(
                        latestDisplayIndex,
                        listState.layoutInfo.visibleItemsInfo.map { it.index }
                    )
                }
                    .distinctUntilChanged()
                    .collectLatest { buckets ->
                        onVisibleBucketsChanged(buckets, TimelineBucketTargetReason.VisibleScroll)
                    }
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.isScrollInProgress }
                    .distinctUntilChanged()
                    .collectLatest { isScrollInProgress ->
                        if (!isScrollInProgress) {
                            val buckets = visibleBucketIndexesForDisplayIndexes(
                                latestDisplayIndex,
                                listState.layoutInfo.visibleItemsInfo.map { it.index }
                            )
                            onVisibleBucketsChanged(buckets, TimelineBucketTargetReason.ScrollSettled)
                        }
                    }
            }

            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBarPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                val fullGridHeight = maxHeight.value.coerceAtLeast(0f)
                // Report available width to ViewModel for row packing
                val timelineMosaicColumnCount = if (maxWidth >= LARGE_SCREEN_MOSAIC_WIDTH) {
                    TIMELINE_MOSAIC_LARGE_COLUMN_COUNT
                } else {
                    TIMELINE_MOSAIC_COMPACT_COLUMN_COUNT
                }
                LaunchedEffect(maxWidth, fullGridHeight, timelineMosaicColumnCount) {
                    onAvailableWidthChanged(maxWidth.value)
                    onAvailableViewportHeightChanged(fullGridHeight)
                    onMosaicColumnCountChanged(timelineMosaicColumnCount)
                }

                val gridModifier = Modifier.fillMaxSize().let { base ->
                    if (state.viewConfig.mosaicEnabled) {
                        base
                    } else {
                        base
                            .pinchToZoomRowHeight(targetRowHeight, state.rowHeightBounds, onTargetRowHeightChanged)
                            .desktopGridZoom(targetRowHeight, state.rowHeightBounds, onTargetRowHeightChanged)
                    }
                }

                Box(
                    modifier = gridModifier
                ) {
                    val coroutineScope = rememberCoroutineScope()
                    val scrollbarScrollJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
                    val scrollbarTargetTracker = remember { TimelineScrollbarTargetTracker() }

                    fun scrollToFractionTarget(fraction: Float, commit: Boolean) {
                        val target = scrollTargetForFraction(fraction) ?: return
                        scrollbarScrollJob[0]?.cancel()
                        scrollbarScrollJob[0] = coroutineScope.launch {
                            listState.scrollToItem(target.displayIndex)
                        }
                        if (commit) {
                            if (scrollbarTargetTracker.shouldNotifyDragStop(target.bucketIndex)) {
                                onViewportBucketTargeted(target.bucketIndex, TimelineBucketTargetReason.ScrollbarStop)
                            }
                        } else if (scrollbarTargetTracker.shouldNotifyDragTarget(target.bucketIndex)) {
                            onViewportBucketTargeted(target.bucketIndex, TimelineBucketTargetReason.ScrollbarDrag)
                        }
                    }

                    ScrollbarOverlay(
                        listState = listState,
                        topPadding = statusBarPadding + Dimens.topBarHeight + Dimens.sectionHeaderHeight,
                        bottomPadding = Dimens.bottomBarHeight + navBarPadding,
                        scrollFractionProvider = {
                            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                            if (firstVisibleIndex == null) {
                                0f
                            } else {
                                timelineScrollFractionForDisplayIndex(
                                    pageIndex = latestPageIndex,
                                    timelineDisplayIndex = latestDisplayIndex,
                                    displayIndex = firstVisibleIndex
                                ) ?: 0f
                            }
                        },
                        onScrollToFraction = { fraction -> scrollToFractionTarget(fraction, commit = false) },
                        onDragStarted = { scrollbarTargetTracker.onDragStarted() },
                        onDragStopped = { fraction -> scrollToFractionTarget(fraction, commit = true) },
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
                                    is ErrorItem -> ErrorCell(
                                        onRetry = { onRetryBucket(item.timeBucket) }
                                    )
                                    is PhotoItem -> { /* Should not appear at top level with row packing */ }
                                }
                            }
                        }
                    }

                    // Sticky header overlay — hidden when the detail overlay is
                    // shown so it doesn't peek through the transparent scrim
                    // during drag-to-dismiss or overlap the overlay's top bar.
                    AnimatedVisibility(
                        visible = !showOverlay,
                        enter = systemBarFadeIn,
                        exit = systemBarFadeOut,
                    ) {
                        StickyHeaderOverlay(
                            listState = listState,
                            displayItems = displayItems,
                            statusBarPadding = statusBarPadding
                        )
                    }

                    // Banner overlays — same gating as sticky header.
                    AnimatedVisibility(
                        visible = !showOverlay,
                        enter = systemBarFadeIn,
                        exit = systemBarFadeOut,
                        modifier = Modifier.align(Alignment.TopCenter),
                    ) {
                        val bannerModifier = Modifier
                            .statusBarsPadding()
                            .padding(top = Dimens.topBarHeight + Dimens.sectionHeaderHeight)
                        val bannerError = state.bannerError
                        val bannerSuccess = state.bannerSuccess
                        if (bannerError != null) {
                            ErrorBanner(
                                message = bannerError.asText(),
                                lastSyncedAt = state.lastSyncedAt,
                                onDismiss = onDismissBannerError,
                                modifier = bannerModifier
                            )
                        } else if (bannerSuccess != null) {
                            SuccessBanner(
                                message = bannerSuccess.asText(),
                                onDismiss = onDismissBannerSuccess,
                                modifier = bannerModifier
                            )
                        }
                    }
                }
            }
        }
    }
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

    val currentLabel = label
    if (currentLabel != null) {
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
                text = currentLabel,
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
