package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: AlbumDetailViewModel = koinViewModel { parametersOf(albumId) }
) {
    val state by viewModel.state.collectAsState()
    val apiKey = viewModel.apiKey
    var selectedAssetId by remember { mutableStateOf<String?>(null) }
    var lastSelectedAssetId by remember { mutableStateOf<String?>(null) }
    if (selectedAssetId != null) lastSelectedAssetId = selectedAssetId
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusBarPadding + Dimens.topBarHeight
    val contentBottomPadding = navBarPadding

    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = selectedAssetId == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                AlbumDetailContent(
                    state = state,
                    onPhotoClick = remember { { assetId: String -> selectedAssetId = assetId } },
                    onRetry = viewModel::loadAlbumDetail,
                    onAvailableWidthChanged = viewModel::setAvailableWidth,
                    onTargetRowHeightChanged = viewModel::setTargetRowHeight,
                    contentTopPadding = contentTopPadding,
                    contentBottomPadding = contentBottomPadding,
                    listState = listState,
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
                val initialIndex = state.assets.indexOfFirst { it.id == assetId }
                    .coerceAtLeast(0)
                StaticPhotoOverlay(
                    assets = state.assets,
                    initialIndex = initialIndex,
                    apiKey = apiKey,
                    getAssetDetail = viewModel::getAssetDetail,
                    onPersonClick = onPersonClick,
                    onDismiss = { currentAssetId ->
                        if (currentAssetId != null) {
                            val rowIndex = state.rows.indexOfFirst { row ->
                                row.photos.any { it.asset.id == currentAssetId }
                            }
                            if (rowIndex >= 0) {
                                val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == rowIndex }
                                if (!isVisible) {
                                    coroutineScope.launch { listState.scrollToItem(rowIndex) }
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

        AnimatedVisibility(
            visible = selectedAssetId == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            DetailTopBar(title = state.albumName, onBack = onBack)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailContent(
    state: AlbumDetailState,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onAvailableWidthChanged: (Float) -> Unit = {},
    onTargetRowHeightChanged: (Float) -> Unit = {},
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    LoadingErrorContent(
        isLoading = state.isLoading,
        error = state.error,
        onRetry = onRetry
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

            ScrollbarOverlay(
                listState = listState,
                topPadding = contentTopPadding,
                bottomPadding = contentBottomPadding
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = remember(contentTopPadding, contentBottomPadding) {
                        PaddingValues(
                            top = contentTopPadding,
                            bottom = contentBottomPadding
                        )
                    }
                ) {
                    itemsIndexed(
                        items = state.rows,
                        key = { _, row -> row.gridKey }
                    ) { _, row ->
                        JustifiedPhotoRow(
                            row = row,
                            spacing = Dimens.gridSpacing,
                            onPhotoClick = onPhotoClick,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun AlbumDetailContentPreview() {
    val sampleAsset = Asset(
        id = "1", type = AssetType.IMAGE, fileName = "photo.jpg",
        createdAt = "", thumbnailUrl = "", originalUrl = ""
    )
    AlbumDetailContent(
        state = AlbumDetailState(
            albumName = "Vacation",
            assets = listOf(sampleAsset)
        ),
        onPhotoClick = {},
        onRetry = {},
        contentTopPadding = 0.dp,
        contentBottomPadding = 0.dp
    )
}
