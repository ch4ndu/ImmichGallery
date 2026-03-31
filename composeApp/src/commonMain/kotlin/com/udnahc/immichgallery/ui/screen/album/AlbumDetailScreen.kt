package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.udnahc.immichgallery.ui.theme.GRID_COLUMNS
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.PhotoRow
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: AlbumDetailViewModel = koinViewModel { parametersOf(albumId) }
) {
    val state by viewModel.state.collectAsState()
    val apiKey = viewModel.apiKey
    var selectedPhotoIndex by remember { mutableStateOf<Int?>(null) }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusBarPadding + Dimens.topBarHeight
    val contentBottomPadding = navBarPadding

    Box(modifier = Modifier.fillMaxSize()) {
        AlbumDetailContent(
            state = state,
            onPhotoClick = { _, index -> selectedPhotoIndex = index },
            onRetry = viewModel::loadAlbumDetail,
            contentTopPadding = contentTopPadding,
            contentBottomPadding = contentBottomPadding
        )

        DetailTopBar(title = state.albumName, onBack = onBack)

        selectedPhotoIndex?.let { index ->
            StaticPhotoOverlay(
                assets = state.assets,
                initialIndex = index,
                apiKey = apiKey,
                getAssetDetail = viewModel::getAssetDetail,
                onPersonClick = onPersonClick,
                onDismiss = { selectedPhotoIndex = null }
            )
        }
    }
}

@Composable
fun AlbumDetailContent(
    state: AlbumDetailState,
    onPhotoClick: (List<Asset>, Int) -> Unit,
    onRetry: () -> Unit,
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp = 0.dp
) {
    LoadingErrorContent(
        isLoading = state.isLoading,
        error = state.error,
        onRetry = onRetry
    ) {
        val rows = remember(state.assets) { state.assets.chunked(GRID_COLUMNS) }
        val listState = rememberLazyListState()
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
                items(rows, key = { it.first().id }) { row ->
                    PhotoRow(row, state.assets, onPhotoClick)
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
        onPhotoClick = { _, _ -> },
        onRetry = {},
        contentTopPadding = 0.dp,
        contentBottomPadding = 0.dp
    )
}
