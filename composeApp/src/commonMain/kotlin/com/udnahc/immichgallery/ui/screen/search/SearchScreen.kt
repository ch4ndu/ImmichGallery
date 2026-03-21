package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.component.ThumbnailCell
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.search_hint
import immichgallery.composeapp.generated.resources.search_no_results
import immichgallery.composeapp.generated.resources.search_placeholder
import immichgallery.composeapp.generated.resources.search_type_filename
import immichgallery.composeapp.generated.resources.search_type_smart
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private const val GRID_COLUMNS = 3

@Composable
fun SearchScreen(
    onOverlayActiveChanged: (Boolean) -> Unit = {},
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: SearchViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val apiKey = viewModel.apiKey
    var selectedPhotoIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(selectedPhotoIndex) {
        onOverlayActiveChanged(selectedPhotoIndex != null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SearchContent(
            state = state,
            onQueryChange = viewModel::updateQuery,
            onSearchTypeChange = viewModel::updateSearchType,
            onSearch = viewModel::search,
            onPhotoClick = { _, index -> selectedPhotoIndex = index }
        )

        selectedPhotoIndex?.let { index ->
            val getAssetDetailUseCase: GetAssetDetailUseCase = koinInject()
            StaticPhotoOverlay(
                assets = state.results,
                initialIndex = index,
                apiKey = apiKey,
                getAssetDetailUseCase = getAssetDetailUseCase,
                onPersonClick = onPersonClick,
                onDismiss = { selectedPhotoIndex = null }
            )
        }
    }
}

@Composable
fun SearchContent(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onSearch: () -> Unit,
    onPhotoClick: (List<Asset>, Int) -> Unit
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .padding(horizontal = Dimens.screenPadding)
            .padding(top = statusBarPadding + Dimens.topBarHeight)
    ) {
        Spacer(modifier = Modifier.height(Dimens.mediumSpacing))

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(Res.string.search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Dimens.mediumSpacing))

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {
            FilterChip(
                selected = state.searchType == SearchType.SMART,
                onClick = { onSearchTypeChange(SearchType.SMART) },
                label = { Text(stringResource(Res.string.search_type_smart)) }
            )
            FilterChip(
                selected = state.searchType == SearchType.METADATA,
                onClick = { onSearchTypeChange(SearchType.METADATA) },
                label = { Text(stringResource(Res.string.search_type_filename)) }
            )
        }

        Spacer(modifier = Modifier.height(Dimens.mediumSpacing))

        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                }
            }

            state.hasSearched && state.results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Res.string.search_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.results.isNotEmpty() -> {
                val rows = remember(state.results) { state.results.chunked(GRID_COLUMNS) }
                val navBarPadding =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val listState = rememberLazyListState()
                LaunchedEffect(listState.isScrollInProgress) {
                    if (listState.isScrollInProgress) focusManager.clearFocus()
                }
                ScrollbarOverlay(
                    listState = listState,
                    topPadding = statusBarPadding + Dimens.topBarHeight,
                    bottomPadding = Dimens.bottomBarHeight + navBarPadding
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = Dimens.bottomBarHeight + navBarPadding)
                    ) {
                        items(rows, key = { it.first().id }) { row ->
                            PhotoRow(row, state.results, onPhotoClick)
                        }
                    }
                }
            }

            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Res.string.search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoRow(
    assets: List<Asset>,
    allAssets: List<Asset>,
    onPhotoClick: (List<Asset>, Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gridSpacing)
    ) {
        assets.forEach { asset ->
            val onClick = remember(asset.id) {
                {
                    val index = allAssets.indexOf(asset)
                    onPhotoClick(allAssets, index)
                }
            }
            ThumbnailCell(
                asset = asset,
                onClick = onClick,
                modifier = Modifier.weight(1f)
            )
        }
        repeat(GRID_COLUMNS - assets.size) {
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Preview
@Composable
private fun PhotoRowPreview() {
    val sampleAssets = listOf(
        Asset(
            id = "1", type = AssetType.IMAGE, fileName = "photo1.jpg",
            createdAt = "", thumbnailUrl = "", originalUrl = ""
        ),
        Asset(
            id = "2", type = AssetType.IMAGE, fileName = "photo2.jpg",
            createdAt = "", thumbnailUrl = "", originalUrl = ""
        )
    )
    PhotoRow(assets = sampleAssets, allAssets = sampleAssets, onPhotoClick = { _, _ -> })
}

@Preview
@Composable
private fun SearchContentPreview() {
    SearchContent(
        state = SearchState(),
        onQueryChange = {},
        onSearchTypeChange = {},
        onSearch = {},
        onPhotoClick = { _, _ -> }
    )
}
