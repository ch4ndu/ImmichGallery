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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import com.udnahc.immichgallery.data.model.ImmichPaginationCompatibilityException
import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.AssetGroupDisplayLabel
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
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.date_group_day
import immichgallery.composeapp.generated.resources.date_group_month
import immichgallery.composeapp.generated.resources.date_month_april
import immichgallery.composeapp.generated.resources.date_month_august
import immichgallery.composeapp.generated.resources.date_month_december
import immichgallery.composeapp.generated.resources.date_month_february
import immichgallery.composeapp.generated.resources.date_month_january
import immichgallery.composeapp.generated.resources.date_month_july
import immichgallery.composeapp.generated.resources.date_month_june
import immichgallery.composeapp.generated.resources.date_month_march
import immichgallery.composeapp.generated.resources.date_month_may
import immichgallery.composeapp.generated.resources.date_month_november
import immichgallery.composeapp.generated.resources.date_month_october
import immichgallery.composeapp.generated.resources.date_month_september
import immichgallery.composeapp.generated.resources.load_more
import immichgallery.composeapp.generated.resources.timeline_unknown_date
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource

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
            PhotoGridDetailMetricsReporter(
                widthDp = widthDp,
                visibleGridHeight = visibleGridHeight,
                onAvailableWidthChanged = onAvailableWidthChanged,
                onAvailableViewportHeightChanged = onAvailableViewportHeightChanged
            )

            VisibleBucketReporter(
                listState = listState,
                displayIndex = displayIndex,
                onVisibleBucketIndexesChanged = onVisibleBucketIndexesChanged
            )
            ScrollInProgressReporter(
                listState = listState,
                onScrollInProgressChanged = onScrollInProgressChanged
            )

            PhotoGridDetailSurface(
                displayItems = displayItems,
                viewConfig = viewConfig,
                targetRowHeight = targetRowHeight,
                rowHeightBounds = rowHeightBounds,
                contentTopPadding = contentTopPadding,
                contentBottomPadding = contentBottomPadding,
                listState = listState,
                isLoadingMore = isLoadingMore,
                onLoadMore = onLoadMore,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
                bannerMessage = bannerMessage,
                lastSyncedAt = lastSyncedAt,
                onDismissBanner = onDismissBanner,
                onTargetRowHeightChanged = onTargetRowHeightChanged,
                onPhotoClick = onPhotoClick,
                sharedTransitionScope = sharedTransitionScope
            )
        }
    }
}

@Composable
private fun PhotoGridDetailMetricsReporter(
    widthDp: Float,
    visibleGridHeight: Float,
    onAvailableWidthChanged: (Float) -> Unit,
    onAvailableViewportHeightChanged: (Float) -> Unit
) {
    LaunchedEffect(widthDp, visibleGridHeight) {
        onAvailableWidthChanged(widthDp)
        onAvailableViewportHeightChanged(visibleGridHeight)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PhotoGridDetailSurface(
    displayItems: List<PhotoGridDisplayItem>,
    viewConfig: ViewConfig,
    targetRowHeight: Float,
    rowHeightBounds: RowHeightBounds,
    contentTopPadding: Dp,
    contentBottomPadding: Dp,
    listState: LazyListState,
    isLoadingMore: Boolean,
    onLoadMore: (() -> Unit)?,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    bannerMessage: String?,
    lastSyncedAt: Long?,
    onDismissBanner: () -> Unit,
    onTargetRowHeightChanged: (Float) -> Unit,
    onPhotoClick: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope?
) {
    val gridModifier = Modifier.fillMaxSize().let { base ->
        if (viewConfig.mosaicEnabled &&
            (viewConfig.disableZoomWhenMosaicEnabled || viewConfig.cacheMosaicResults)
        ) {
            base
        } else {
            base
                .pinchToZoomRowHeight(
                    targetRowHeight,
                    rowHeightBounds,
                    onTargetRowHeightChanged
                )
                .desktopGridZoom(targetRowHeight, rowHeightBounds, onTargetRowHeightChanged)
        }
    }

    Box(modifier = gridModifier) {
        ScrollbarOverlay(
            listState = listState,
            topPadding = contentTopPadding,
            bottomPadding = contentBottomPadding
        ) {
            PhotoGridDetailLazyList(
                displayItems = displayItems,
                listState = listState,
                contentTopPadding = contentTopPadding,
                contentBottomPadding = contentBottomPadding,
                isLoadingMore = isLoadingMore,
                onLoadMore = onLoadMore,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
                onPhotoClick = onPhotoClick,
                sharedTransitionScope = sharedTransitionScope
            )
        }

        PhotoGridDetailBanner(
            message = bannerMessage,
            lastSyncedAt = lastSyncedAt,
            contentTopPadding = contentTopPadding,
            onDismiss = onDismissBanner,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PhotoGridDetailLazyList(
    displayItems: List<PhotoGridDisplayItem>,
    listState: LazyListState,
    contentTopPadding: Dp,
    contentBottomPadding: Dp,
    isLoadingMore: Boolean,
    onLoadMore: (() -> Unit)?,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    onPhotoClick: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope?
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

@Composable
private fun PhotoGridDetailBanner(
    message: String?,
    lastSyncedAt: Long?,
    contentTopPadding: Dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message != null) {
        ErrorBanner(
            message = message,
            lastSyncedAt = lastSyncedAt,
            onDismiss = onDismiss,
            modifier = modifier.padding(top = contentTopPadding)
        )
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
        is HeaderItem -> SectionHeader(
            label = assetGroupDisplayLabelText(item.assetGroupDisplayLabel, item.label)
        )
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
private fun assetGroupDisplayLabelText(
    label: AssetGroupDisplayLabel?,
    fallback: String
): String = when (label) {
    is AssetGroupDisplayLabel.Month -> stringResource(
        Res.string.date_group_month,
        localizedMonthName(label.monthNumber),
        label.year
    )

    is AssetGroupDisplayLabel.Day -> stringResource(
        Res.string.date_group_day,
        label.date.day,
        localizedMonthName(label.date.month.ordinal + 1),
        label.date.year
    )

    AssetGroupDisplayLabel.UnknownDate -> stringResource(Res.string.timeline_unknown_date)
    null -> fallback
}

@Composable
internal fun localizedMonthName(monthNumber: Int): String = stringResource(
    when (monthNumber) {
        1 -> Res.string.date_month_january
        2 -> Res.string.date_month_february
        3 -> Res.string.date_month_march
        4 -> Res.string.date_month_april
        5 -> Res.string.date_month_may
        6 -> Res.string.date_month_june
        7 -> Res.string.date_month_july
        8 -> Res.string.date_month_august
        9 -> Res.string.date_month_september
        10 -> Res.string.date_month_october
        11 -> Res.string.date_month_november
        else -> Res.string.date_month_december
    }
)

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
    item(key = "load_more_footer") {
        LoadMoreFooter(
            listState = listState,
            isLoadingMore = isLoadingMore,
            onLoadMore = onLoadMore
        )
    }
}

@Composable
private fun LoadMoreFooter(
    listState: LazyListState,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, isLoadingMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            canAutoLoadMore(
                hasMore = true,
                isLoadingMore = isLoadingMore,
                canScrollBackward = listState.canScrollBackward,
                lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                totalItemsCount = info.totalItemsCount
            )
        }
            .distinctUntilChanged()
            .collect { shouldLoadMore ->
                if (shouldLoadMore) latestOnLoadMore()
            }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.screenPadding),
        contentAlignment = Alignment.Center
    ) {
        if (canManuallyLoadMore(hasMore = true, isLoadingMore = isLoadingMore)) {
            Button(onClick = onLoadMore) {
                Text(stringResource(Res.string.load_more))
            }
        } else {
            CircularProgressIndicator()
        }
    }
}

internal fun canAutoLoadMore(
    hasMore: Boolean,
    isLoadingMore: Boolean,
    canScrollBackward: Boolean,
    lastVisibleIndex: Int,
    totalItemsCount: Int
): Boolean =
    hasMore &&
            !isLoadingMore &&
            canScrollBackward &&
            totalItemsCount > 0 &&
            lastVisibleIndex >= totalItemsCount - 3

internal fun canManuallyLoadMore(hasMore: Boolean, isLoadingMore: Boolean): Boolean =
    hasMore && !isLoadingMore

internal data class DetailPaginationCursor(
    val hasMore: Boolean,
    val nextPage: Int?
)

internal fun detailPaginationCursorAfterFailure(
    hasMore: Boolean,
    nextPage: Int?,
    failure: Throwable
): DetailPaginationCursor =
    if (failure is ImmichPaginationCompatibilityException) {
        DetailPaginationCursor(hasMore = false, nextPage = null)
    } else {
        DetailPaginationCursor(hasMore = hasMore, nextPage = nextPage)
    }
