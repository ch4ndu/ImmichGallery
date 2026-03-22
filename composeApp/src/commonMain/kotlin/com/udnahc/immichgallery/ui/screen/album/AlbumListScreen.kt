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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import coil3.compose.AsyncImage
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.items_count
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AlbumListScreen(
    onAlbumClick: (String) -> Unit,
    viewModel: AlbumListViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    AlbumListContent(
        state = state,
        onAlbumClick = onAlbumClick,
        onRetry = viewModel::loadAlbums
    )
}

@Composable
fun AlbumListContent(
    state: AlbumListState,
    onAlbumClick: (String) -> Unit,
    onRetry: () -> Unit
) {
    LoadingErrorContent(
        isLoading = state.isLoading && state.albums.isEmpty(),
        error = if (state.albums.isEmpty()) state.error else null,
        onRetry = onRetry
    ) {
            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBarPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val gridState = rememberLazyGridState()
            ScrollbarOverlay(
                gridState = gridState,
                topPadding = statusBarPadding + Dimens.topBarHeight,
                bottomPadding = Dimens.bottomBarHeight + navBarPadding
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
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
                        AlbumCard(album = album, onClick = { onAlbumClick(album.id) })
                    }
                }
            }
    }
}

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
                AsyncImage(
                    model = album.thumbnailUrl,
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
