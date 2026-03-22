package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineBucket
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
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private const val HEADER_KEY_PREFIX = "header_"
private const val ZOOM_STEP_THRESHOLD = 1.3f

@Composable
fun TimelineScreen(
    groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    onOverlayActiveChanged: (Boolean) -> Unit = {},
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: TimelineViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val apiKey = viewModel.apiKey
    var selectedPhotoIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(groupSize) {
        viewModel.setGroupSize(groupSize)
    }

    LaunchedEffect(selectedPhotoIndex) {
        onOverlayActiveChanged(selectedPhotoIndex != null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TimelineContent(
            state = state,
            groupSize = groupSize,
            gridColumns = state.gridColumns,
            onGridColumnsChanged = viewModel::setGridColumns,
            onVisibleBucketsChanged = viewModel::onVisibleBucketsChanged,
            onRetryBucket = viewModel::retryBucket,
            onPhotoClick = { assetId ->
                selectedPhotoIndex = viewModel.getGlobalPhotoIndex(assetId)
            },
            onRetry = viewModel::loadBuckets,
            labelProvider = viewModel.labelProvider
        )

        val index = selectedPhotoIndex
        if (index != null) {
            TimelinePhotoOverlay(
                timelineState = viewModel.state,
                initialIndex = index,
                apiKey = apiKey,
                getAssetFileNameUseCase = viewModel.getAssetFileNameUseCase,
                getAssetDetailUseCase = viewModel.getAssetDetailUseCase,
                onBucketNeeded = viewModel::loadBucketAssets,
                onPersonClick = onPersonClick,
                onDismiss = { selectedPhotoIndex = null }
            )
        }
    }
}

@Composable
fun TimelineContent(
    state: TimelineState,
    groupSize: TimelineGroupSize = TimelineGroupSize.MONTH,
    gridColumns: Int = 3,
    onGridColumnsChanged: (Int) -> Unit = {},
    onVisibleBucketsChanged: (Set<String>) -> Unit,
    onRetryBucket: (String) -> Unit,
    onPhotoClick: (assetId: String) -> Unit,
    onRetry: () -> Unit,
    labelProvider: (Float) -> String?
) {
    LoadingErrorContent(
        isLoading = state.isLoading && state.buckets.isEmpty(),
        error = if (state.buckets.isEmpty()) state.error else null,
        onRetry = onRetry
    ) {
        key(groupSize) {
            val gridState = rememberLazyGridState()
            val buckets = state.buckets
            val loadedBuckets = state.loadedBuckets
            val failedBuckets = state.failedBuckets

            // Pinch-to-zoom state
            var zoomAccumulator by remember { mutableFloatStateOf(1f) }

            // Detect visible buckets from both header and asset items
            val visibleBucketKeys by remember {
                derivedStateOf {
                    val bucketKeys = mutableSetOf<String>()
                    for (item in gridState.layoutInfo.visibleItemsInfo) {
                        val key = item.key as? String ?: continue
                        if (key.startsWith(HEADER_KEY_PREFIX)) {
                            bucketKeys.add(key.removePrefix(HEADER_KEY_PREFIX))
                        } else {
                            // Asset keys: "{timeBucket}:{assetId}" or "{timeBucket}_{idx}"
                            val timeBucket = key.substringBefore("|", "").ifEmpty {
                                key.substringBeforeLast("_", "")
                            }
                            if (timeBucket.isNotEmpty()) {
                                bucketKeys.add(timeBucket)
                            }
                        }
                    }
                    bucketKeys as Set<String>
                }
            }

            LaunchedEffect(Unit) {
                snapshotFlow { visibleBucketKeys }
                    .distinctUntilChanged()
                    .collectLatest { onVisibleBucketsChanged(it) }
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
                        contentPadding = PaddingValues(
                            bottom = Dimens.bottomBarHeight + navBarPadding
                        )
                    ) {
                        buckets.forEachIndexed { _, bucket ->
                            val isFailed = failedBuckets.contains(bucket.timeBucket)
                            val isLoaded = loadedBuckets.contains(bucket.timeBucket)

                            // Skip monthly header in DAY mode for loaded buckets
                            // (day sub-headers provide the date context)
                            val isDayLoaded = groupSize == TimelineGroupSize.DAY && isLoaded
                            if (!isDayLoaded) {
                                item(
                                    key = "$HEADER_KEY_PREFIX${bucket.timeBucket}",
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    BucketHeader(bucket)
                                }
                            }

                            if (isFailed) {
                                // Show a single full-span failed row
                                item(
                                    key = "failed_${bucket.timeBucket}",
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    val failedText =
                                        stringResource(Res.string.timeline_failed_tap_retry)
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(Dimens.sectionHeaderHeight)
                                            .clickable { onRetryBucket(bucket.timeBucket) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            failedText,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            } else if (!isLoaded) {
                                // Unloaded bucket: show placeholders without touching paging
                                items(
                                    count = bucket.count,
                                    key = { idx -> "${bucket.timeBucket}_$idx" }
                                ) {
                                    PlaceholderCell()
                                }
                            } else {
                                // Loaded bucket: render from bucketAssets map
                                val assets = state.bucketAssets[bucket.timeBucket]
                                if (assets != null) {
                                    val dayGroupList = if (groupSize == TimelineGroupSize.DAY) {
                                        state.dayGroups[bucket.timeBucket]
                                    } else null

                                    if (!dayGroupList.isNullOrEmpty()) {
                                        // DAY mode: render day sub-headers + assets
                                        dayGroupList.forEach { dayGroup ->
                                            item(
                                                key = "${bucket.timeBucket}_day_${dayGroup.label}",
                                                span = { GridItemSpan(maxLineSpan) }
                                            ) {
                                                BucketHeader(
                                                    TimelineBucket(dayGroup.label, "", 0)
                                                )
                                            }
                                            items(
                                                count = dayGroup.assets.size,
                                                key = { idx ->
                                                    "${bucket.timeBucket}|${dayGroup.assets[idx].id}"
                                                }
                                            ) { idx ->
                                                val asset = dayGroup.assets[idx]
                                                ThumbnailCell(
                                                    asset = asset,
                                                    onClick = { onPhotoClick(asset.id) }
                                                )
                                            }
                                        }
                                    } else {
                                        // MONTH mode: render flat
                                        items(
                                            count = assets.size,
                                            key = { idx ->
                                                "${bucket.timeBucket}|${assets[idx].id}"
                                            }
                                        ) { idx ->
                                            val asset = assets[idx]
                                            ThumbnailCell(
                                                asset = asset,
                                                onClick = { onPhotoClick(asset.id) }
                                            )
                                        }
                                    }
                                } else {
                                    // Fallback: bucket marked loaded but assets not yet in map
                                    items(
                                        count = bucket.count,
                                        key = { idx -> "${bucket.timeBucket}_$idx" }
                                    ) {
                                        PlaceholderCell()
                                    }
                                }
                            }
                        }
                    }
                }

                // Overlay sticky header
                StickyHeaderOverlay(
                    gridState = gridState,
                    bucketLabelMap = state.bucketLabelMap,
                    statusBarPadding = statusBarPadding
                )
            }
        }
    }
}

@Composable
private fun BucketHeader(bucket: TimelineBucket) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
            .padding(top = statusBarPadding + Dimens.topBarHeight)
            .height(Dimens.sectionHeaderHeight)
            .padding(horizontal = Dimens.sectionHeaderPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = bucket.displayLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun StickyHeaderOverlay(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    bucketLabelMap: Map<String, String>,
    statusBarPadding: androidx.compose.ui.unit.Dp
) {
    val currentBucketLabel by remember(bucketLabelMap) {
        derivedStateOf {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null
            val firstKey = visibleItems.first().key as? String ?: return@derivedStateOf null
            val timeBucket = if (firstKey.startsWith(HEADER_KEY_PREFIX)) {
                firstKey.removePrefix(HEADER_KEY_PREFIX)
            } else {
                // Asset keys: "{timeBucket}|{assetId}" or "{timeBucket}_{idx}"
                firstKey.substringBefore("|", "").ifEmpty {
                    firstKey.substringBeforeLast("_", "")
                }
            }
            if (timeBucket.isNotEmpty()) bucketLabelMap[timeBucket] else null
        }
    }

    if (currentBucketLabel != null) {
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
                text = currentBucketLabel!!,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Preview
@Composable
private fun BucketHeaderPreview() {
    BucketHeader(
        bucket = TimelineBucket(
            displayLabel = "March 2026",
            timeBucket = "2026-03-01",
            count = 42
        )
    )
}

@Preview
@Composable
private fun StickyHeaderOverlayPreview() {
    val gridState = rememberLazyGridState()
    StickyHeaderOverlay(
        gridState = gridState,
        bucketLabelMap = mapOf("2026-03" to "March 2026"),
        statusBarPadding = 0.dp
    )
}
