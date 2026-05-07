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
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.TimelineScrollTarget
import com.udnahc.immichgallery.domain.model.TimelineScrollbarTargetTracker
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.timelineScrollFractionForDisplayIndex
import com.udnahc.immichgallery.domain.model.visibleBucketIndexesForDisplayIndexes
import com.udnahc.immichgallery.ui.component.ErrorBanner
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.MosaicPhotoBand
import com.udnahc.immichgallery.ui.component.PhotoOverlayHost
import com.udnahc.immichgallery.ui.component.PhotoOverlaySourcePosition
import com.udnahc.immichgallery.ui.component.PlaceholderRow
import com.udnahc.immichgallery.ui.component.photoGridDisplayItemContentType
import com.udnahc.immichgallery.ui.component.SectionHeader
import com.udnahc.immichgallery.ui.component.SuccessBanner
import com.udnahc.immichgallery.ui.model.asText
import com.udnahc.immichgallery.ui.util.desktopGridZoom
import com.udnahc.immichgallery.ui.util.prepareOverlayDismissSource
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

private data class TimelineScrollAnchor(
    val assetId: String,
    val scrollOffset: Int
)

private data class TimelineBucketScrollAnchor(
    val bucketIndex: Int,
    val itemKey: String?,
    val scrollOffset: Int = 0
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelineScreen(
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    onRefreshCallback: ((() -> Unit)?) -> Unit = {},
    onSyncingState: (Boolean) -> Unit = {},
    onColdSyncBlockingState: (Boolean) -> Unit = {},
    onMosaicPrepareCallback: ((suspend (ViewConfig) -> Result<Unit>)?) -> Unit = {},
    onOverlayActiveChange: (Boolean) -> Unit = {},
    viewModel: TimelineViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isBuilding by viewModel.isBuilding.collectAsState()
    val buildError by viewModel.buildError.collectAsState()
    val listState = rememberLazyListState()
    val sourcePositions = remember { mutableStateMapOf<String, PhotoOverlaySourcePosition>() }
    var preparedDismissReturnKey by remember { mutableStateOf<String?>(null) }

    // Report refresh callback and syncing state to parent
    LaunchedEffect(Unit) { onRefreshCallback { viewModel.refreshAll() } }
    LaunchedEffect(state.isSyncing, isBuilding) { onSyncingState(state.isSyncing || isBuilding) }
    LaunchedEffect(isBuilding) { onColdSyncBlockingState(isBuilding) }
    LaunchedEffect(Unit) { onMosaicPrepareCallback(viewModel::prepareMosaicViewConfig) }
    DisposableEffect(Unit) {
        onDispose {
            onColdSyncBlockingState(false)
            onMosaicPrepareCallback(null)
        }
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
        val returnKey = "${viewModel.lastViewedAssetId}|${viewModel.lastViewedBucket}"
        if (preparedDismissReturnKey == returnKey && visibleItem != null) {
            preparedDismissReturnKey = null
            return@LaunchedEffect
        }
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
            mosaicColumnCount = state.viewConfig.mosaicColumnCount,
            onTimelineLayoutMetricsChanged = viewModel::setTimelineLayoutMetrics
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
        prepareDismissSource = prepareDismissSource@ { context ->
            val assetId = context.assetId ?: return@prepareDismissSource
            viewModel.lastViewedAssetId = assetId
            viewModel.lastViewedBucket = context.bucketKey
            preparedDismissReturnKey = "$assetId|${context.bucketKey}"
            sourcePositions.remove(assetId)
            listState.prepareOverlayDismissSource(
                displayIndex = viewModel.getDisplayItemIndexForReturn(assetId, context.bucketKey),
                isSourceReady = { sourcePositions[assetId]?.generation == context.sourceGeneration },
                clearSourceReady = { sourcePositions.remove(assetId) },
            )
        },
        onActiveSourcePositioned = { position ->
            sourcePositions[position.assetId] = position
        },
        content = { showOverlay, transitionAssetId, hiddenAssetId, activeSourceGeneration, onActiveSourcePositioned, onPhotoClick ->
            // Grid is no longer wrapped in its own AnimatedVisibility — it stays
            // composed behind the overlay so drag-to-dismiss reveals it.
            // Per-cell AVs in ThumbnailCell drive the shared-element animation.
            TimelineContent(
                state = state,
                listState = listState,
                showOverlay = showOverlay,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
                activeSourceGeneration = activeSourceGeneration,
                onActiveSourcePositioned = onActiveSourcePositioned,
                onVisibleBucketsChanged = viewModel::onVisibleBucketsChanged,
                onViewportBucketTargeted = viewModel::onViewportBucketTargeted,
                onScrollInProgressChanged = viewModel::onScrollInProgressChanged,
                scrollTargetForFraction = viewModel::scrollTargetForFraction,
                onTargetRowHeightChanged = viewModel::setTargetRowHeight,
                onTimelineLayoutMetricsChanged = viewModel::setTimelineLayoutMetrics,
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
                    onDismiss = onDismissHost,
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
    mosaicColumnCount: Int,
    onTimelineLayoutMetricsChanged: (Float, Float, Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val timelineMosaicColumnCount = mosaicColumnCount
        LaunchedEffect(maxWidth, maxHeight, timelineMosaicColumnCount) {
            onTimelineLayoutMetricsChanged(maxWidth.value, maxHeight.value, timelineMosaicColumnCount)
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
    transitionAssetId: String? = null,
    hiddenAssetId: String? = null,
    activeSourceGeneration: Int = 0,
    onActiveSourcePositioned: ((PhotoOverlaySourcePosition) -> Unit)? = null,
    onVisibleBucketsChanged: (List<Int>, TimelineBucketTargetReason) -> Unit,
    onViewportBucketTargeted: (Int, TimelineBucketTargetReason) -> Unit,
    onScrollInProgressChanged: (Boolean) -> Unit = {},
    scrollTargetForFraction: (Float) -> TimelineScrollTarget?,
    onTargetRowHeightChanged: (Float) -> Unit = {},
    onTimelineLayoutMetricsChanged: (Float, Float, Int) -> Unit = { _, _, _ -> },
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
    val latestDisplayItems by rememberUpdatedState(displayItems)
    val latestDisplayIndex by rememberUpdatedState(state.displayIndex)
    val latestPageIndex by rememberUpdatedState(state.pageIndex)
    var scrollAnchor by remember { mutableStateOf<TimelineScrollAnchor?>(null) }
    var bucketScrollAnchor by remember { mutableStateOf<TimelineBucketScrollAnchor?>(null) }

    LoadingErrorContent(
        isLoading = state.isLoading && displayItems.isEmpty(),
        error = if (displayItems.isEmpty()) state.error else null,
        onRetry = onRetry
    ) {
        key(state.groupSize) {
            LaunchedEffect(listState) {
                snapshotFlow {
                    firstVisibleAssetAnchor(
                        displayItems = latestDisplayItems,
                        visibleItems = listState.layoutInfo.visibleItemsInfo
                    )
                    }
                    .distinctUntilChanged()
                    .collectLatest { anchor ->
                        scrollAnchor = anchor
                        if (anchor != null) {
                            bucketScrollAnchor = null
                        }
                    }
            }
            LaunchedEffect(state.displayIndex) {
                if (listState.isScrollInProgress) return@LaunchedEffect
                val anchor = scrollAnchor
                if (anchor != null) {
                    val targetIndex = latestDisplayIndex.assetDisplayIndexById[anchor.assetId] ?: return@LaunchedEffect
                    val currentAnchor = firstVisibleAssetAnchor(
                        displayItems = latestDisplayItems,
                        visibleItems = listState.layoutInfo.visibleItemsInfo
                    )
                    if (currentAnchor?.assetId != anchor.assetId) {
                        listState.scrollToItem(targetIndex, anchor.scrollOffset)
                    }
                    return@LaunchedEffect
                }
                val bucketAnchor = bucketScrollAnchor ?: return@LaunchedEffect
                val currentFirstIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                val currentFirstBucket = currentFirstIndex?.let { latestDisplayIndex.bucketByDisplayIndex.getOrNull(it) }
                if (currentFirstBucket == bucketAnchor.bucketIndex) return@LaunchedEffect
                val targetIndex = bucketAnchor.itemKey
                    ?.let { itemKey -> latestDisplayItems.indexOfFirst { it.gridKey == itemKey }.takeIf { it >= 0 } }
                    ?: latestDisplayIndex.displayIndexesByBucket[bucketAnchor.bucketIndex]?.firstOrNull()
                    ?: return@LaunchedEffect
                listState.scrollToItem(targetIndex, bucketAnchor.scrollOffset)
            }
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
                        onScrollInProgressChanged(isScrollInProgress)
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
                val timelineMosaicColumnCount = state.viewConfig.mosaicColumnCount
                LaunchedEffect(maxWidth, fullGridHeight, timelineMosaicColumnCount) {
                    onTimelineLayoutMetricsChanged(maxWidth.value, fullGridHeight, timelineMosaicColumnCount)
                }

                val gridModifier = Modifier.fillMaxSize().let { base ->
                    if (state.viewConfig.mosaicEnabled &&
                        (state.viewConfig.disableZoomWhenMosaicEnabled || state.viewConfig.cacheMosaicResults)
                    ) {
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
                    // Route parameter callbacks through rememberUpdatedState so the
                    // remembered scrollbar lambdas below stay identity-stable across
                    // recompositions while still calling the freshest callback. This
                    // stops ScrollbarOverlay from recomposing on every parent
                    // recomposition (which fires often during the warmup wave when
                    // _bucketData / _mosaicStates produce combine emissions).
                    val latestScrollTargetForFraction by rememberUpdatedState(scrollTargetForFraction)
                    val latestOnViewportBucketTargeted by rememberUpdatedState(onViewportBucketTargeted)
                    val scrollToFractionTarget = remember<(Float, Boolean) -> Unit> {
                        { fraction, commit ->
                            val target = latestScrollTargetForFraction(fraction)
                            if (target != null) {
                                scrollAnchor = null
                                bucketScrollAnchor = TimelineBucketScrollAnchor(
                                    bucketIndex = target.bucketIndex,
                                    itemKey = latestDisplayItems.getOrNull(target.displayIndex)?.gridKey
                                )
                                scrollbarScrollJob[0]?.cancel()
                                scrollbarScrollJob[0] = coroutineScope.launch {
                                    listState.scrollToItem(target.displayIndex)
                                }
                                if (commit) {
                                    if (scrollbarTargetTracker.shouldNotifyDragStop(target.bucketIndex)) {
                                        latestOnViewportBucketTargeted(target.bucketIndex, TimelineBucketTargetReason.ScrollbarStop)
                                    }
                                } else if (scrollbarTargetTracker.shouldNotifyDragTarget(target.bucketIndex)) {
                                    latestOnViewportBucketTargeted(target.bucketIndex, TimelineBucketTargetReason.ScrollbarDrag)
                                }
                            }
                        }
                    }
                    val scrollFractionProvider = remember<() -> Float> {
                        {
                            val info = listState.layoutInfo
                            val firstVisibleIndex = info.visibleItemsInfo.firstOrNull()?.index
                            if (firstVisibleIndex == null) {
                                0f
                            } else {
                                // The semantic page-based fraction returns null on
                                // transient warmup state mismatches (LazyColumn's
                                // firstVisibleItemIndex has advanced past the
                                // currently-published TimelineDisplayIndex). Falling
                                // back to 0f made the spring-animated scrollbar
                                // handle warp to the top and back. Use the
                                // LazyColumn-native pixel fraction as a
                                // close-enough approximation in that window — it's
                                // always defined, continuous, and independent of
                                // the lagging snapshot.
                                timelineScrollFractionForDisplayIndex(
                                    pageIndex = latestPageIndex,
                                    timelineDisplayIndex = latestDisplayIndex,
                                    displayIndex = firstVisibleIndex
                                ) ?: nativeListScrollFraction(info)
                            }
                        }
                    }
                    val onScrollToFractionCallback = remember<(Float) -> Unit> {
                        { fraction -> scrollToFractionTarget(fraction, false) }
                    }
                    val onDragStartedCallback = remember<() -> Unit> {
                        { scrollbarTargetTracker.onDragStarted() }
                    }
                    val onDragStoppedCallback = remember<(Float) -> Unit> {
                        { fraction -> scrollToFractionTarget(fraction, true) }
                    }

                    ScrollbarOverlay(
                        listState = listState,
                        topPadding = statusBarPadding + Dimens.topBarHeight + Dimens.sectionHeaderHeight,
                        bottomPadding = Dimens.bottomBarHeight + navBarPadding,
                        scrollFractionProvider = scrollFractionProvider,
                        onScrollToFraction = onScrollToFractionCallback,
                        onDragStarted = onDragStartedCallback,
                        onDragStopped = onDragStoppedCallback,
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
                                items = displayItems,
                                key = { item -> item.gridKey },
                                contentType = ::photoGridDisplayItemContentType
                            ) { item ->
                                TimelineDisplayItemRenderer(
                                    item = item,
                                    transitionAssetId = transitionAssetId,
                                    hiddenAssetId = hiddenAssetId,
                                    activeSourceGeneration = activeSourceGeneration,
                                    onActiveSourcePositioned = onActiveSourcePositioned,
                                    onPhotoClick = onPhotoClick,
                                    onRetryBucket = onRetryBucket,
                                    sharedTransitionScope = sharedTransitionScope
                                )
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
                            displayIndex = state.displayIndex,
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

private fun nativeListScrollFraction(info: LazyListLayoutInfo): Float {
    val total = info.totalItemsCount
    if (total == 0) return 0f
    val first = info.visibleItemsInfo.firstOrNull() ?: return 0f
    val itemFraction = first.index.toFloat() / total
    val pixelFraction = if (first.size > 0) {
        -first.offset.toFloat() / first.size / total
    } else 0f
    return (itemFraction + pixelFraction).coerceIn(0f, 1f)
}

private fun firstVisibleAssetAnchor(
    displayItems: List<TimelineDisplayItem>,
    visibleItems: List<LazyListItemInfo>
): TimelineScrollAnchor? =
    visibleItems
        .asSequence()
        .mapNotNull { itemInfo ->
            displayItems.getOrNull(itemInfo.index)
                ?.firstAssetId()
                ?.let { assetId -> TimelineScrollAnchor(assetId, itemInfo.offset) }
        }
        .firstOrNull()

private fun TimelineDisplayItem.firstAssetId(): String? =
    when (this) {
        is PhotoItem -> asset.id
        is RowItem -> photos.firstOrNull()?.asset?.id
        is MosaicBandItem -> tiles.minByOrNull { it.visualOrder }?.photo?.asset?.id
        is HeaderItem,
        is PlaceholderItem,
        is ErrorItem -> null
    }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TimelineDisplayItemRenderer(
    item: TimelineDisplayItem,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    activeSourceGeneration: Int = 0,
    onActiveSourcePositioned: ((PhotoOverlaySourcePosition) -> Unit)? = null,
    onPhotoClick: (String) -> Unit,
    onRetryBucket: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope?
) {
    when (item) {
        is HeaderItem -> SectionHeader(label = item.label)
        is RowItem -> JustifiedPhotoRow(
            row = item,
            spacing = Dimens.gridSpacing,
            onPhotoClick = onPhotoClick,
            sharedTransitionScope = sharedTransitionScope,
            transitionAssetId = transitionAssetId,
            hiddenAssetId = hiddenAssetId,
            activeSourceGeneration = activeSourceGeneration,
            onActiveSourcePositioned = onActiveSourcePositioned,
        )
        is MosaicBandItem -> MosaicPhotoBand(
            band = item,
            onPhotoClick = onPhotoClick,
            sharedTransitionScope = sharedTransitionScope,
            transitionAssetId = transitionAssetId,
            hiddenAssetId = hiddenAssetId,
            activeSourceGeneration = activeSourceGeneration,
            onActiveSourcePositioned = onActiveSourcePositioned,
        )
        is PlaceholderItem -> PlaceholderRow(estimatedHeight = item.estimatedHeight)
        is ErrorItem -> ErrorCell(onRetry = { onRetryBucket(item.timeBucket) })
        is PhotoItem -> Unit
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
    displayIndex: TimelineDisplayIndex,
    statusBarPadding: Dp
) {
    val latestDisplayIndex by rememberUpdatedState(displayIndex)
    var retainedLabel by remember { mutableStateOf<String?>(null) }
    val label by remember(listState) {
        derivedStateOf {
            latestDisplayIndex.sectionLabelByDisplayIndex.getOrNull(listState.firstVisibleItemIndex)
        }
    }
    LaunchedEffect(label) {
        if (label != null) {
            retainedLabel = label
        }
    }

    val currentLabel = label ?: retainedLabel
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
        displayIndex = TimelineDisplayIndex(),
        statusBarPadding = 0.dp
    )
}
