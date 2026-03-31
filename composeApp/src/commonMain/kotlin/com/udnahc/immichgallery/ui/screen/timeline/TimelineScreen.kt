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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.PlaceholderCell
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.ThumbnailCell
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.timeline_failed_tap_retry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private const val ZOOM_STEP_THRESHOLD = 1.3f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelineScreen(
    groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    onOverlayActiveChanged: (Boolean) -> Unit = {},
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: TimelineViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val apiKey = viewModel.apiKey
    var selectedAssetId by remember { mutableStateOf<String?>(null) }
    var lastSelectedAssetId by remember { mutableStateOf<String?>(null) }
    if (selectedAssetId != null) lastSelectedAssetId = selectedAssetId
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(groupSize) {
        viewModel.setGroupSize(groupSize)
    }

    LaunchedEffect(selectedAssetId) {
        onOverlayActiveChanged(selectedAssetId != null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = selectedAssetId == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TimelineContent(
                    state = state,
                    gridState = gridState,
                    onFirstVisibleItemChanged = viewModel::onFirstVisibleItemChanged,
                    onGridColumnsChanged = viewModel::setGridColumns,
                    onRetryBucket = viewModel::retryBucket,
                    onPhotoClick = { assetId -> selectedAssetId = assetId },
                    onRetry = viewModel::loadBuckets,
                    labelProvider = viewModel.labelProvider,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedVisibility
                )
            }

            AnimatedVisibility(
                visible = selectedAssetId != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val assetId = lastSelectedAssetId ?: return@AnimatedVisibility
                val initialIndex = viewModel.getGlobalPhotoIndex(assetId) ?: 0
                TimelinePhotoOverlay(
                    timelineState = viewModel.state,
                    initialIndex = initialIndex,
                    apiKey = apiKey,
                    getAssetFileName = viewModel::getAssetFileName,
                    getAssetDetail = viewModel::getAssetDetail,
                    onBucketNeeded = viewModel::loadBucketAssets,
                    onPersonClick = onPersonClick,
                    onDismiss = { currentAssetId ->
                        if (currentAssetId != null) {
                            val displayIndex = viewModel.getDisplayItemIndex(currentAssetId)
                            if (displayIndex != null) {
                                val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
                                if (displayIndex !in visible) {
                                    coroutineScope.launch { gridState.scrollToItem(displayIndex) }
                                }
                            }
                        }
                        selectedAssetId = null
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedVisibility
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelineContent(
    state: TimelineState,
    gridState: LazyGridState = rememberLazyGridState(),
    onFirstVisibleItemChanged: (Int) -> Unit,
    onGridColumnsChanged: (Int) -> Unit = {},
    onRetryBucket: (String) -> Unit,
    onPhotoClick: (assetId: String) -> Unit,
    onRetry: () -> Unit,
    labelProvider: (Float) -> String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val displayItems = state.displayItems
    val gridColumns = state.gridColumns

    LoadingErrorContent(
        isLoading = state.isLoading && displayItems.isEmpty(),
        error = if (displayItems.isEmpty()) state.error else null,
        onRetry = onRetry
    ) {
        key(state.groupSize) {
            // Pinch-to-zoom state
            var zoomAccumulator by remember { mutableFloatStateOf(1f) }

            // Visibility: pass first visible item index to ViewModel
            LaunchedEffect(Unit) {
                snapshotFlow { gridState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collectLatest { onFirstVisibleItemChanged(it) }
            }

            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBarPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            Box(
                modifier = Modifier.fillMaxSize().pointerInput(gridColumns) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var previousDistance = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val dist = (pressed[0].position - pressed[1].position)
                                    .getDistance()
                                if (previousDistance > 0f && dist > 0f) {
                                    val zoom = dist / previousDistance
                                    zoomAccumulator *= zoom
                                    if (zoomAccumulator > ZOOM_STEP_THRESHOLD &&
                                        gridColumns > MIN_GRID_COLUMNS
                                    ) {
                                        onGridColumnsChanged(gridColumns - 1)
                                        zoomAccumulator = 1f
                                    } else if (zoomAccumulator < 1f / ZOOM_STEP_THRESHOLD &&
                                        gridColumns < MAX_GRID_COLUMNS
                                    ) {
                                        onGridColumnsChanged(gridColumns + 1)
                                        zoomAccumulator = 1f
                                    }
                                }
                                previousDistance = dist
                                event.changes.forEach { it.consume() }
                            } else {
                                previousDistance = 0f
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            ) {
                ScrollbarOverlay(
                    gridState = gridState,
                    topPadding = statusBarPadding + Dimens.topBarHeight + Dimens.sectionHeaderHeight,
                    bottomPadding = Dimens.bottomBarHeight + navBarPadding,
                    labelProvider = labelProvider,
                    yearMarkers = state.yearMarkers
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.gridSpacing),
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
                            span = { index ->
                                GridItemSpan(
                                    if (displayItems[index].isFullSpan) maxLineSpan else 1
                                )
                            }
                        ) { index ->
                            when (val item = displayItems[index]) {
                                is HeaderItem -> SectionHeader(label = item.label)
                                is PhotoItem -> ThumbnailCell(
                                    asset = item.asset,
                                    onClick = { onPhotoClick(item.asset.id) },
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                is PlaceholderItem -> PlaceholderCell()
                                is ErrorItem -> ErrorCell(
                                    onRetry = { onRetryBucket(item.timeBucket) }
                                )
                            }
                        }
                    }
                }

                // Sticky header overlay
                StickyHeaderOverlay(
                    gridState = gridState,
                    displayItems = displayItems,
                    statusBarPadding = statusBarPadding
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.sectionHeaderHeight)
            .padding(horizontal = Dimens.sectionHeaderPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
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
    gridState: LazyGridState,
    displayItems: List<TimelineDisplayItem>,
    statusBarPadding: Dp
) {
    val label by remember(displayItems) {
        derivedStateOf {
            displayItems.getOrNull(gridState.firstVisibleItemIndex)?.sectionLabel
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
    val gridState = rememberLazyGridState()
    StickyHeaderOverlay(
        gridState = gridState,
        displayItems = emptyList(),
        statusBarPadding = 0.dp
    )
}
