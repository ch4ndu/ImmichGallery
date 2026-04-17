package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.ErrorBanner
import com.udnahc.immichgallery.ui.component.GroupSizeDropdown
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.SectionHeader
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    onPhotoClick: (assetId: String) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    viewModel: AlbumDetailViewModel = koinViewModel { parametersOf(albumId) }
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Scroll back to last viewed asset when returning from detail
    LaunchedEffect(viewModel.lastViewedAssetId) {
        val assetId = viewModel.lastViewedAssetId ?: return@LaunchedEffect
        val itemIndex = state.displayItems.indexOfFirst { item ->
            item is AlbumRowItem && item.row.photos.any { it.asset.id == assetId }
        }
        if (itemIndex >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == itemIndex }
            if (!isVisible) {
                listState.scrollToItem(itemIndex)
            }
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusBarPadding + Dimens.topBarHeight
    val contentBottomPadding = navBarPadding

    Box(modifier = Modifier.fillMaxSize()) {
        AlbumDetailContent(
            state = state,
            onPhotoClick = onPhotoClick,
            onRetry = viewModel::refreshAll,
            onDismissBanner = viewModel::dismissBannerError,
            onAvailableWidthChanged = viewModel::setAvailableWidth,
            onTargetRowHeightChanged = viewModel::setTargetRowHeight,
            contentTopPadding = contentTopPadding,
            contentBottomPadding = contentBottomPadding,
            listState = listState,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )

        DetailTopBar(
            title = state.albumName,
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailContent(
    state: AlbumDetailState,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissBanner: () -> Unit = {},
    onAvailableWidthChanged: (Float) -> Unit = {},
    onTargetRowHeightChanged: (Float) -> Unit = {},
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    LoadingErrorContent(
        isLoading = (state.isBuilding || state.isLoading) && state.assets.isEmpty(),
        error = if (state.assets.isEmpty()) state.error else null,
        onRetry = onRetry,
        loadingText = if (state.isBuilding) "Preparing album, please wait..." else null
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoomRowHeight(state.targetRowHeight, onTargetRowHeightChanged)
        ) {
            val widthDp = maxWidth.value
            LaunchedEffect(widthDp) {
                onAvailableWidthChanged(widthDp)
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
                        key = { displayItems[it].key },
                        contentType = { displayItems[it]::class }
                    ) { index ->
                        when (val item = displayItems[index]) {
                            is AlbumHeaderItem -> SectionHeader(label = item.label)
                            is AlbumRowItem -> JustifiedPhotoRow(
                                row = item.row,
                                spacing = Dimens.gridSpacing,
                                onPhotoClick = onPhotoClick,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
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
