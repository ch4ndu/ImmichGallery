package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.photoTransitionFadeIn
import com.udnahc.immichgallery.ui.util.photoTransitionFadeOut
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.search_hint
import immichgallery.composeapp.generated.resources.search_no_results
import immichgallery.composeapp.generated.resources.search_placeholder
import immichgallery.composeapp.generated.resources.search_type_filename
import immichgallery.composeapp.generated.resources.search_type_smart
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchScreen(
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    onOverlayActiveChange: (Boolean) -> Unit = {},
    viewModel: SearchViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    var selectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSelectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (selectedAssetId != null) lastSelectedAssetId = selectedAssetId
    val showOverlay = selectedAssetId != null

    LaunchedEffect(showOverlay) { onOverlayActiveChange(showOverlay) }
    PlatformBackHandler(enabled = showOverlay) { selectedAssetId = null }

    // Scroll back to last viewed asset when returning from detail
    LaunchedEffect(viewModel.lastViewedAssetId) {
        val assetId = viewModel.lastViewedAssetId ?: return@LaunchedEffect
        val rowIndex = state.rows.indexOfFirst { row ->
            row.photos.any { it.asset.id == assetId }
        }
        if (rowIndex >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == rowIndex }
            if (!isVisible) {
                listState.scrollToItem(rowIndex)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !showOverlay,
                enter = photoTransitionFadeIn,
                exit = photoTransitionFadeOut,
            ) {
                SearchContent(
                    state = state,
                    onQueryChange = viewModel::updateQuery,
                    onSearchTypeChange = viewModel::updateSearchType,
                    onSearch = viewModel::search,
                    onPhotoClick = remember { { id: String -> selectedAssetId = id } },
                    onSetAvailableWidth = viewModel::setAvailableWidth,
                    onSetTargetRowHeight = viewModel::setTargetRowHeight,
                    onLoadMore = viewModel::loadMore,
                    listState = listState,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedVisibility,
                )
            }

            AnimatedVisibility(
                visible = showOverlay,
                enter = photoTransitionFadeIn,
                exit = photoTransitionFadeOut,
            ) {
                val assetId = lastSelectedAssetId ?: return@AnimatedVisibility
                val assets = state.results
                val initialIndex = remember(assetId, assets) {
                    assets.indexOfFirst { it.id == assetId }.coerceAtLeast(0)
                }
                StaticPhotoOverlay(
                    assets = assets,
                    initialIndex = initialIndex,
                    apiKey = viewModel.apiKey,
                    getAssetDetail = viewModel::getAssetDetail,
                    onPersonClick = onPersonClick,
                    onDismiss = { currentAssetId ->
                        viewModel.lastViewedAssetId = currentAssetId
                        selectedAssetId = null
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedVisibility,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchContent(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onSearch: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onSetAvailableWidth: (Float) -> Unit,
    onSetTargetRowHeight: (Float) -> Unit = {},
    onLoadMore: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
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
                val navBarPadding =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val density = LocalDensity.current

                LaunchedEffect(listState.isScrollInProgress) {
                    if (listState.isScrollInProgress) focusManager.clearFocus()
                }

                // Load-more detection
                val shouldLoadMore by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val total = info.totalItemsCount
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        total > 0 && lastVisible >= total - 3
                    }
                }
                LaunchedEffect(shouldLoadMore) {
                    if (shouldLoadMore) onLoadMore()
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .pinchToZoomRowHeight(state.targetRowHeight, onSetTargetRowHeight)
                ) {
                    val widthDp = with(density) { constraints.maxWidth.toDp() }
                    LaunchedEffect(widthDp) {
                        onSetAvailableWidth(widthDp.value)
                    }

                    ScrollbarOverlay(
                        listState = listState,
                        topPadding = statusBarPadding + Dimens.topBarHeight,
                        bottomPadding = Dimens.bottomBarHeight + navBarPadding
                    ) {
                        val contentPadding = remember(navBarPadding) {
                            PaddingValues(bottom = Dimens.bottomBarHeight + navBarPadding)
                        }
                        LazyColumn(
                            state = listState,
                            contentPadding = contentPadding,
                            verticalArrangement = Arrangement.spacedBy(Dimens.gridSpacing)
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
                            if (state.isLoadingMore) {
                                item(key = "loading_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = Dimens.mediumSpacing),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(Dimens.iconSize)
                                        )
                                    }
                                }
                            }
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

@Preview
@Composable
private fun SearchContentPreview() {
    SearchContent(
        state = SearchState(),
        onQueryChange = {},
        onSearchTypeChange = {},
        onSearch = {},
        onPhotoClick = {},
        onSetAvailableWidth = {},
        onLoadMore = {}
    )
}
