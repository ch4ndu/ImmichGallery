package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.paging.compose.collectAsLazyPagingItems
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.ThumbnailCell
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.retry
import immichgallery.composeapp.generated.resources.timeline_failed_tap_retry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private const val GRID_COLUMNS = 3
private const val HEADER_KEY_PREFIX = "header_"

@Composable
fun TimelineScreen(
    onOverlayActiveChanged: (Boolean) -> Unit = {},
    viewModel: TimelineViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val pagingItems = viewModel.assetsPaging.collectAsLazyPagingItems()
    val serverConfigRepository: ServerConfigRepository = koinInject()
    val apiKey = remember { serverConfigRepository.getApiKey() }
    var selectedPhotoIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(selectedPhotoIndex) {
        onOverlayActiveChanged(selectedPhotoIndex != null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TimelineContent(
            state = state,
            pagingItemCount = pagingItems.itemCount,
            pagingItemProvider = { index -> pagingItems[index] },
            onVisibleBucketsChanged = viewModel::onVisibleBucketsChanged,
            onRetryBucket = viewModel::retryBucket,
            onPhotoClick = { flatIndex -> selectedPhotoIndex = flatIndex },
            onRetry = viewModel::loadBuckets,
            labelProvider = viewModel.labelProvider
        )

        selectedPhotoIndex?.let { index ->
            val getAssetFileNameUseCase: GetAssetFileNameUseCase = koinInject()
            TimelinePhotoOverlay(
                pagingItems = pagingItems,
                initialIndex = index,
                apiKey = apiKey,
                getAssetFileNameUseCase = getAssetFileNameUseCase,
                onDismiss = { selectedPhotoIndex = null }
            )
        }
    }
}

@Composable
fun TimelineContent(
    state: TimelineState,
    pagingItemCount: Int,
    pagingItemProvider: (Int) -> Asset?,
    onVisibleBucketsChanged: (Set<String>) -> Unit,
    onRetryBucket: (String) -> Unit,
    onPhotoClick: (Int) -> Unit,
    onRetry: () -> Unit,
    labelProvider: (Float) -> String?
) {
    when {
        state.isLoading && state.buckets.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        state.error != null && state.buckets.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
                }
            }
        }

        else -> {
            val gridState = rememberLazyGridState()
            val buckets = state.buckets
            val loadedBuckets = state.loadedBuckets
            val failedBuckets = state.failedBuckets
            val cumulativeOffsets = state.cumulativeOffsets

            // Detect visible buckets from both header and asset items
            val visibleBucketKeys by remember {
                derivedStateOf {
                    val bucketKeys = mutableSetOf<String>()
                    for (item in gridState.layoutInfo.visibleItemsInfo) {
                        val key = item.key as? String ?: continue
                        if (key.startsWith(HEADER_KEY_PREFIX)) {
                            bucketKeys.add(key.removePrefix(HEADER_KEY_PREFIX))
                        } else {
                            // Asset keys: "{timeBucket}_{idx}"
                            val timeBucket = key.substringBeforeLast("_", "")
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

            Box(modifier = Modifier.fillMaxSize()) {
                ScrollbarOverlay(
                    gridState = gridState,
                    topPadding = statusBarPadding + Dimens.topBarHeight + Dimens.sectionHeaderHeight,
                    bottomPadding = Dimens.bottomBarHeight + navBarPadding,
                    labelProvider = labelProvider
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(GRID_COLUMNS),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.gridSpacing),
                        verticalArrangement = Arrangement.spacedBy(Dimens.gridSpacing),
                        contentPadding = PaddingValues(
                            bottom = Dimens.bottomBarHeight + navBarPadding
                        )
                    ) {
                        buckets.forEachIndexed { bucketIndex, bucket ->
                            // Full-width header
                            item(
                                key = "$HEADER_KEY_PREFIX${bucket.timeBucket}",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                BucketHeader(bucket)
                            }

                            val isFailed = failedBuckets.contains(bucket.timeBucket)
                            val isLoaded = loadedBuckets.contains(bucket.timeBucket)

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
                                // Loaded bucket: map to paging data
                                val flatOffset = cumulativeOffsets[bucketIndex]
                                items(
                                    count = bucket.count,
                                    key = { idx -> "${bucket.timeBucket}_$idx" }
                                ) { idx ->
                                    val pagingIndex = flatOffset + idx
                                    val asset = if (pagingIndex < pagingItemCount) {
                                        pagingItemProvider(pagingIndex)
                                    } else {
                                        null
                                    }
                                    if (asset != null) {
                                        val onClick = remember(pagingIndex) {
                                            { onPhotoClick(pagingIndex) }
                                        }
                                        ThumbnailCell(
                                            asset = asset,
                                            onClick = onClick
                                        )
                                    } else {
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
            // Find the last header that has scrolled to or past the top
            var lastHeaderLabel: String? = null
            for (item in visibleItems) {
                val key = item.key as? String ?: continue
                if (key.startsWith(HEADER_KEY_PREFIX)) {
                    val timeBucket = key.removePrefix(HEADER_KEY_PREFIX)
                    lastHeaderLabel = bucketLabelMap[timeBucket]
                    if (lastHeaderLabel != null) break
                }
            }
            // If no header is visible in the viewport, find which bucket the first visible item belongs to
            if (lastHeaderLabel == null && visibleItems.isNotEmpty()) {
                val firstKey = visibleItems.first().key as? String
                if (firstKey != null && !firstKey.startsWith(HEADER_KEY_PREFIX)) {
                    // Parse bucket timeBucket from the asset key format: "{timeBucket}_{idx}"
                    val timeBucket = firstKey.substringBeforeLast("_", "")
                    if (timeBucket.isNotEmpty()) {
                        lastHeaderLabel = bucketLabelMap[timeBucket]
                    }
                }
            }
            lastHeaderLabel
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

@Composable
private fun PlaceholderCell(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
