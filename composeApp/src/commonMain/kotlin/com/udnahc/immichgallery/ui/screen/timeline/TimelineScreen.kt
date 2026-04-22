package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.udnahc.immichgallery.ui.util.LocalPhotoBoundsTween
import com.udnahc.immichgallery.ui.util.PHOTO_TRANSITION_DURATION_MS
import com.udnahc.immichgallery.ui.util.photoTransitionFadeIn
import com.udnahc.immichgallery.ui.util.photoTransitionFadeOut
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import kotlinx.coroutines.delay
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.loading_timeline
import immichgallery.composeapp.generated.resources.timeline_failed_tap_retry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.lighthousegames.logging.logging
import org.koin.compose.viewmodel.koinViewModel

// TODO(diagnostic): logging for enter-animation-breaks-after-deep-scroll bug.
// Remove once mechanism confirmed.
private val diagLog = logging("TimelineEnterDiag")
private fun diagNow(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()


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

    // Monotonic counter bumped on every overlay open (including same-asset
    // re-taps). Used as the key() for SharedTransitionLayout so STL's internal
    // sharedElements map is disposed+remounted on every open — without this the
    // `initialMfrOffset` carries drift between transitions and the shared
    // element flies to `2 × grid_Y` instead of the real cell position.
    // MUST update synchronously in the composition body (not in a
    // LaunchedEffect); otherwise the epoch increment lands in the same frame
    // as overlayInitialIndex, so the STL remount coincides with showOverlay
    // flipping true — AnimatedVisibility mounts already-visible and the enter
    // animation snaps instead of running.
    var selectionEpoch by rememberSaveable { mutableStateOf(0) }
    var prevSelectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (prevSelectedAssetId == null && selectedAssetId != null) {
        selectionEpoch++
        // DIAGNOSTIC: mark the instant of the tap-induced STL remount key bump.
        diagLog.d { "t=${diagNow()} TAP selectedAssetId=$selectedAssetId selectionEpoch=$selectionEpoch" }
    }
    prevSelectedAssetId = selectedAssetId

    // Follows the pager's current page; drives which grid cell is hidden.
    var currentViewedAssetId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedAssetId) { currentViewedAssetId = selectedAssetId }

    // Forwarded from the overlay: STL reports whether its bounds animation is
    // actually running. Used below to flip `overlayAnimActive` false only when
    // the animation has truly settled — instead of a fixed delay that can
    // truncate long/large animations.
    var stlTransitionActive by remember { mutableStateOf(false) }

    // True while an open/dismiss animation is in flight. Drives the dynamic
    // BoundsTransform: tween for open/dismiss, snap for mid-overlay pager swipes.
    var overlayAnimActive by remember { mutableStateOf(false) }
    LaunchedEffect(selectedAssetId) {
        overlayAnimActive = true
        // Minimum window: give the animation time to actually start so
        // `stlTransitionActive` has had a chance to go true.
        delay(PHOTO_TRANSITION_DURATION_MS.toLong())
        // Then wait for the STL to report the animation is truly done.
        snapshotFlow { stlTransitionActive }.first { !it }
        overlayAnimActive = false
    }

    var overlayInitialIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedAssetId) {
        val id = selectedAssetId
        if (id != null) {
            val t0 = diagNow()
            diagLog.d { "t=$t0 INDEX_FETCH_START id=$id" }
            // Sync pre-populate: copy the bucket containing this asset from the
            // VM's in-memory cache to the overlay cache, so AssetPage (with its
            // sharedBounds destination) renders on frame one of the overlay.
            viewModel.prepareOverlayForAsset(id)
            val idx = viewModel.getGlobalPhotoIndex(id) ?: 0
            overlayInitialIndex = idx
            diagLog.d { "t=${diagNow()} INDEX_FETCH_DONE id=$id idx=$idx elapsed=${diagNow() - t0}ms" }
        } else {
            overlayInitialIndex = null
        }
    }
    val showOverlay = selectedAssetId != null && overlayInitialIndex != null
    LaunchedEffect(showOverlay) {
        // DIAGNOSTIC: shows when the gate that flips AV.visible true actually
        // flips — useful as a T0 marker for the pager-settle race.
        diagLog.d { "t=${diagNow()} SHOW_OVERLAY=$showOverlay" }
    }

    LaunchedEffect(showOverlay) { onOverlayActiveChange(showOverlay) }
    PlatformBackHandler(enabled = showOverlay) { selectedAssetId = null }

    // Report refresh callback and syncing state to parent
    LaunchedEffect(Unit) { onRefreshCallback { viewModel.refreshAll() } }
    LaunchedEffect(state.isSyncing, isBuilding) { onSyncingState(state.isSyncing || isBuilding) }

    LaunchedEffect(groupSize) {
        viewModel.setGroupSize(groupSize)
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
        CompositionLocalProvider(LocalPhotoBoundsTween provides overlayAnimActive) {
        androidx.compose.runtime.key(selectionEpoch) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            // Grid is no longer wrapped in its own AnimatedVisibility — it stays
            // composed behind the overlay so drag-to-dismiss reveals it.
            // Per-cell AVs in ThumbnailCell drive the shared-element animation.
            TimelineContent(
                state = state,
                listState = listState,
                showOverlay = showOverlay,
                hiddenAssetId = currentViewedAssetId,
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
            )

            AnimatedVisibility(
                visible = showOverlay,
                enter = photoTransitionFadeIn,
                exit = photoTransitionFadeOut,
            ) {
                val assetId = lastSelectedAssetId ?: return@AnimatedVisibility
                val initialIndex = overlayInitialIndex ?: 0
                TimelinePhotoOverlay(
                    timelineState = viewModel.state,
                    initialIndex = initialIndex,
                    apiKey = viewModel.apiKey,
                    getAssetFileName = viewModel::getAssetFileName,
                    getAssetDetail = viewModel::getAssetDetail,
                    assetCache = viewModel.overlayAssetCache,
                    loadBucket = viewModel::loadBucketForOverlay,
                    onBucketNeeded = viewModel::loadBucketAssets,
                    onPersonClick = onPersonClick,
                    onDismiss = { currentAssetId, currentBucket ->
                        viewModel.lastViewedAssetId = currentAssetId
                        viewModel.lastViewedBucket = currentBucket
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
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelineContent(
    state: TimelineState,
    listState: LazyListState = rememberLazyListState(),
    showOverlay: Boolean = false,
    hiddenAssetId: String? = null,
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
