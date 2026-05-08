package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.visibleBucketIndexesForDisplayIndexes
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.desktopGridZoom
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoGridDetailContent(
    isLoading: Boolean,
    isBuilding: Boolean,
    assetsEmpty: Boolean,
    errorMessage: String?,
    loadingText: String?,
    displayItems: List<PhotoGridDisplayItem>,
    displayIndex: TimelineDisplayIndex,
    bannerMessage: String?,
    lastSyncedAt: Long?,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    targetRowHeight: Float,
    rowHeightBounds: RowHeightBounds,
    viewConfig: ViewConfig,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissBanner: () -> Unit,
    onAvailableWidthChanged: (Float) -> Unit,
    onAvailableViewportHeightChanged: (Float) -> Unit,
    onVisibleBucketIndexesChanged: (List<Int>) -> Unit,
    onScrollInProgressChanged: (Boolean) -> Unit = {},
    onTargetRowHeightChanged: (Float) -> Unit,
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    LoadingErrorContent(
        isLoading = (isBuilding || isLoading) && assetsEmpty,
        error = if (assetsEmpty) errorMessage else null,
        onRetry = onRetry,
        loadingText = loadingText
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthDp = maxWidth.value
            val visibleGridHeight = (maxHeight - contentTopPadding - contentBottomPadding)
                .value
                .coerceAtLeast(0f)
            LaunchedEffect(widthDp, visibleGridHeight) {
                onAvailableWidthChanged(widthDp)
                onAvailableViewportHeightChanged(visibleGridHeight)
            }

            VisibleBucketReporter(
                listState = listState,
                displayIndex = displayIndex,
                onVisibleBucketIndexesChanged = onVisibleBucketIndexesChanged
            )
            ScrollInProgressReporter(
                listState = listState,
                onScrollInProgressChanged = onScrollInProgressChanged
            )

            var gridModifier = Modifier.fillMaxSize()
            if (!(viewConfig.mosaicEnabled &&
                        (viewConfig.disableZoomWhenMosaicEnabled || viewConfig.cacheMosaicResults))
            ) {
                gridModifier = gridModifier
                    .pinchToZoomRowHeight(
                        targetRowHeight,
                        rowHeightBounds,
                        onTargetRowHeightChanged
                    )
                    .desktopGridZoom(targetRowHeight, rowHeightBounds, onTargetRowHeightChanged)
            }

            Box(modifier = gridModifier) {
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
                            key = { displayItems[it].gridKey },
                            contentType = { photoGridDisplayItemContentType(displayItems[it]) }
                        ) { index ->
                            PhotoGridDisplayItemRenderer(
                                item = displayItems[index],
                                transitionAssetId = transitionAssetId,
                                hiddenAssetId = hiddenAssetId,
                                onPhotoClick = onPhotoClick,
                                sharedTransitionScope = sharedTransitionScope
                            )
                        }

                        if (onLoadMore != null) {
                            paginationFooter(
                                isLoadingMore = isLoadingMore,
                                onLoadMore = onLoadMore,
                                listState = listState
                            )
                        }
                    }
                }

                if (bannerMessage != null) {
                    ErrorBanner(
                        message = bannerMessage,
                        lastSyncedAt = lastSyncedAt,
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
fun VisibleBucketReporter(
    listState: LazyListState,
    displayIndex: TimelineDisplayIndex,
    onVisibleBucketIndexesChanged: (List<Int>) -> Unit
) {
    val latestDisplayIndex by rememberUpdatedState(displayIndex)
    val latestCallback by rememberUpdatedState(onVisibleBucketIndexesChanged)
    LaunchedEffect(listState) {
        snapshotFlow {
            visibleBucketIndexesForDisplayIndexes(
                latestDisplayIndex,
                listState.layoutInfo.visibleItemsInfo.map { it.index }
            )
        }
            .distinctUntilChanged()
            .collect { indexes -> latestCallback(indexes) }
    }
}

@Composable
fun ScrollInProgressReporter(
    listState: LazyListState,
    onScrollInProgressChanged: (Boolean) -> Unit
) {
    val latestCallback by rememberUpdatedState(onScrollInProgressChanged)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress -> latestCallback(inProgress) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoGridDisplayItemRenderer(
    item: PhotoGridDisplayItem,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    onPhotoClick: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    errorContent: (@Composable (ErrorItem) -> Unit)? = null
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
        )

        is MosaicBandItem -> MosaicPhotoBand(
            band = item,
            onPhotoClick = onPhotoClick,
            sharedTransitionScope = sharedTransitionScope,
            transitionAssetId = transitionAssetId,
            hiddenAssetId = hiddenAssetId,
        )

        is PlaceholderItem -> PlaceholderRow(estimatedHeight = item.estimatedHeight)
        is ErrorItem -> errorContent?.invoke(item)
        is PhotoItem -> Unit
    }
}

@Composable
fun PhotoGridDetailActions(
    groupSize: GroupSize,
    viewConfig: ViewConfig,
    onGroupSizeSelected: (GroupSize) -> Unit,
    onViewConfigChanged: (ViewConfig) -> Unit,
    onPrepareViewConfig: suspend (ViewConfig) -> Result<Unit> = { Result.success(Unit) }
) {
    GroupSizeDropdown(
        selected = groupSize,
        onSelected = onGroupSizeSelected
    )
    MosaicViewConfigIconMenu(
        viewConfig = viewConfig,
        onViewConfigChanged = onViewConfigChanged,
        onPrepareViewConfig = onPrepareViewConfig
    )
}

private fun LazyListScope.paginationFooter(
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState
) {
    item(key = "load_more_trigger") {
        LoadMoreTrigger(
            listState = listState,
            onLoadMore = onLoadMore
        )
    }
    if (isLoadingMore) {
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

@Composable
private fun LoadMoreTrigger(
    listState: LazyListState,
    onLoadMore: () -> Unit
) {
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = info.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 3
        }
            .distinctUntilChanged()
            .collect { shouldLoadMore ->
                if (shouldLoadMore) latestOnLoadMore()
            }
    }
}
