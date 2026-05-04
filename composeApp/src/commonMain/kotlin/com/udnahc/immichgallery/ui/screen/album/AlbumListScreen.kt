package com.udnahc.immichgallery.ui.screen.album

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import com.udnahc.immichgallery.LocalAppActive
import androidx.compose.foundation.layout.statusBarsPadding
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.model.DEFAULT_GRID_COLUMN_COUNT
import com.udnahc.immichgallery.ui.component.ErrorBanner
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.model.UiMessage
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.timeline_cannot_connect
import immichgallery.composeapp.generated.resources.timeline_no_connection
import immichgallery.composeapp.generated.resources.items_count
import immichgallery.composeapp.generated.resources.loading_albums
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private const val THUMBNAIL_DECODE_SIZE = 256

@Composable
fun AlbumListScreen(
    onAlbumClick: (String) -> Unit,
    onRefreshCallback: ((() -> Unit)?) -> Unit = {},
    onSyncingState: (Boolean) -> Unit = {},
    viewModel: AlbumListViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { onRefreshCallback { viewModel.refreshAll() } }
    LaunchedEffect(state.isSyncing, state.isBuilding) { onSyncingState(state.isSyncing || state.isBuilding) }

    AlbumListContent(
        state = state,
        onAlbumClick = onAlbumClick,
        onRetry = viewModel::refreshAll,
        onDismissBanner = viewModel::dismissBannerError
    )
}

@Composable
fun AlbumListContent(
    state: AlbumListState,
    onAlbumClick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissBanner: () -> Unit = {}
) {
    LoadingErrorContent(
        isLoading = (state.isBuilding || state.isLoading) && state.albums.isEmpty(),
        error = if (state.albums.isEmpty()) state.error.asTextOrNull() else null,
        onRetry = onRetry,
        loadingText = if (state.isBuilding) stringResource(Res.string.loading_albums) else null
    ) {
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarPadding =
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val gridState = rememberLazyGridState()
        Box(modifier = Modifier.fillMaxSize()) {
            ScrollbarOverlay(
                gridState = gridState,
                topPadding = statusBarPadding + Dimens.topBarHeight,
                bottomPadding = Dimens.bottomBarHeight + navBarPadding
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(DEFAULT_GRID_COLUMN_COUNT),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = Dimens.cardPadding,
                        end = Dimens.cardPadding,
                        top = statusBarPadding + Dimens.topBarHeight + Dimens.cardPadding,
                        bottom = Dimens.bottomBarHeight + navBarPadding
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.cardPadding),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.albums, key = { it.id }) { album ->
                        val onClick = remember(album.id) { { onAlbumClick(album.id) } }
                        AlbumCard(album = album, onClick = onClick)
                    }
                }
            }

            val bannerError = state.bannerError
            if (bannerError != null) {
                ErrorBanner(
                    message = bannerError.asText(),
                    lastSyncedAt = state.lastSyncedAt,
                    onDismiss = onDismissBanner,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = Dimens.topBarHeight)
                )
            }
        }
    }
}

@Composable
private fun UiMessage?.asTextOrNull(): String? = this?.asText()

@Composable
private fun UiMessage.asText(): String =
    stringResource(
        when (this) {
            UiMessage.NoConnectionToServer -> Res.string.timeline_no_connection
            UiMessage.CannotConnectToServer -> Res.string.timeline_cannot_connect
            else -> Res.string.timeline_cannot_connect
        }
    )

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column {
            if (LocalAppActive.current) {
                val painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(album.thumbnailUrl)
                        .precision(Precision.EXACT)
                        .size(Size(THUMBNAIL_DECODE_SIZE, THUMBNAIL_DECODE_SIZE))
                        .build()
                )
                Image(
                    painter = painter,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            Column(modifier = Modifier.padding(Dimens.cardPadding)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    text = stringResource(Res.string.items_count, album.assetCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview
@Composable
private fun AlbumListContentPreview() {
    AlbumListContent(
        state = AlbumListState(
            albums = listOf(
                Album("1", "Vacation", 42, null),
                Album("2", "Family", 15, null)
            )
        ),
        onAlbumClick = {},
        onRetry = {}
    )
}
