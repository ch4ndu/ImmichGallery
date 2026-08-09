package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.udnahc.immichgallery.ui.component.JustifiedPhotoRow
import com.udnahc.immichgallery.ui.component.PhotoOverlayHost
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.model.asTextOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.desktopGridZoom
import com.udnahc.immichgallery.ui.util.ensureReturnSourceVisible
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.explore_favorites_description
import immichgallery.composeapp.generated.resources.explore_memories_description
import immichgallery.composeapp.generated.resources.favorites
import immichgallery.composeapp.generated.resources.ic_favorite
import immichgallery.composeapp.generated.resources.ic_memories
import immichgallery.composeapp.generated.resources.load_more
import immichgallery.composeapp.generated.resources.memories
import immichgallery.composeapp.generated.resources.search_hint
import immichgallery.composeapp.generated.resources.search_no_results
import immichgallery.composeapp.generated.resources.search_placeholder
import immichgallery.composeapp.generated.resources.search_type_filename
import immichgallery.composeapp.generated.resources.search_type_smart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchScreen(
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    onFavoritesClick: () -> Unit = {},
    onMemoriesClick: () -> Unit = {},
    onOverlayActiveChange: (Boolean) -> Unit = {},
    viewModel: SearchViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val dismissScope = rememberCoroutineScope()

    // Scroll back to last viewed asset when returning from detail
    LaunchedEffect(viewModel.lastViewedAssetId) {
        val rowIndex = viewModel.getDisplayItemIndexForReturn() ?: return@LaunchedEffect
        listState.ensureReturnSourceVisible(rowIndex)
    }

    PhotoOverlayHost(
        initialIndexKey = state.results,
        onOverlayActiveChange = onOverlayActiveChange,
        resolveInitialIndex = { id ->
            state.results.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        },
        content = { showOverlay, transitionAssetId, hiddenAssetId, onPhotoClick ->
            SearchContent(
                state = state,
                showOverlay = showOverlay,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
                onQueryChange = viewModel::updateQuery,
                onSearchTypeChange = viewModel::updateSearchType,
                onSearch = viewModel::search,
                onPhotoClick = onPhotoClick,
                onSetAvailableWidth = viewModel::setAvailableWidth,
                onSetAvailableViewportHeight = viewModel::setAvailableViewportHeight,
                onSetTargetRowHeight = viewModel::setTargetRowHeight,
                onLoadMore = viewModel::loadMore,
                onFavoritesClick = onFavoritesClick,
                onMemoriesClick = onMemoriesClick,
                listState = listState,
                sharedTransitionScope = this,
            )
        },
        overlay = { initialIndex, onDismissHost, onCurrentAssetChanged, onStlTransitionActiveChanged, sharedTransitionScope ->
            StaticPhotoOverlay(
                assets = state.results,
                initialIndex = initialIndex,
                apiKey = viewModel.apiKey,
                getAssetDetail = viewModel::getAssetDetail,
                getAssetStack = viewModel::getAssetStack,
                onPersonClick = onPersonClick,
                onDismiss = { currentAssetId ->
                    dismissScope.launch {
                        viewModel.lastViewedAssetId = currentAssetId
                        viewModel.getDisplayItemIndexForReturn()
                            ?.let { listState.ensureReturnSourceVisible(it) }
                        onDismissHost(currentAssetId)
                    }
                },
                onCurrentAssetChanged = onCurrentAssetChanged,
                onStlTransitionActiveChanged = onStlTransitionActiveChanged,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this,
            )
        }
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchContent(
    state: SearchState,
    showOverlay: Boolean = false,
    transitionAssetId: String? = null,
    hiddenAssetId: String? = null,
    onQueryChange: (String) -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onSearch: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onSetAvailableWidth: (Float) -> Unit,
    onSetAvailableViewportHeight: (Float) -> Unit = {},
    onSetTargetRowHeight: (Float) -> Unit = {},
    onLoadMore: () -> Unit,
    onFavoritesClick: () -> Unit = {},
    onMemoriesClick: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    sharedTransitionScope: SharedTransitionScope? = null,
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
        SearchControls(
            visible = !showOverlay,
            query = state.query,
            searchType = state.searchType,
            onQueryChange = onQueryChange,
            onSearchTypeChange = onSearchTypeChange,
            onSearch = onSearch
        )

        SearchResultContent(
            state = state,
            statusBarPadding = statusBarPadding,
            listState = listState,
            transitionAssetId = transitionAssetId,
            hiddenAssetId = hiddenAssetId,
            onPhotoClick = onPhotoClick,
            onSetAvailableWidth = onSetAvailableWidth,
            onSetAvailableViewportHeight = onSetAvailableViewportHeight,
            onSetTargetRowHeight = onSetTargetRowHeight,
            onLoadMore = onLoadMore,
            onFavoritesClick = onFavoritesClick,
            onMemoriesClick = onMemoriesClick,
            sharedTransitionScope = sharedTransitionScope
        )
    }
}

@Composable
private fun SearchControls(
    visible: Boolean,
    query: String,
    searchType: SearchType,
    onQueryChange: (String) -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onSearch: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = systemBarFadeIn,
        exit = systemBarFadeOut,
    ) {
        Column {
            Spacer(modifier = Modifier.height(Dimens.mediumSpacing))

            OutlinedTextField(
                value = query,
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
                    selected = searchType == SearchType.SMART,
                    onClick = { onSearchTypeChange(SearchType.SMART) },
                    label = { Text(stringResource(Res.string.search_type_smart)) }
                )
                FilterChip(
                    selected = searchType == SearchType.METADATA,
                    onClick = { onSearchTypeChange(SearchType.METADATA) },
                    label = { Text(stringResource(Res.string.search_type_filename)) }
                )
            }

            Spacer(modifier = Modifier.height(Dimens.mediumSpacing))
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SearchResultContent(
    state: SearchState,
    statusBarPadding: Dp,
    listState: LazyListState,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    onPhotoClick: (String) -> Unit,
    onSetAvailableWidth: (Float) -> Unit,
    onSetAvailableViewportHeight: (Float) -> Unit,
    onSetTargetRowHeight: (Float) -> Unit,
    onLoadMore: () -> Unit,
    onFavoritesClick: () -> Unit,
    onMemoriesClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?
) {
    when {
        state.isLoading -> SearchLoadingState()
        state.error != null -> SearchMessageState(
            text = state.error.asTextOrNull().orEmpty(),
            isError = true
        )
        state.hasSearched && state.results.isEmpty() && state.nextPage == null -> SearchMessageState(
            text = stringResource(Res.string.search_no_results)
        )
        state.results.isNotEmpty() || state.nextPage != null -> SearchResultsGrid(
            state = state,
            statusBarPadding = statusBarPadding,
            listState = listState,
            transitionAssetId = transitionAssetId,
            hiddenAssetId = hiddenAssetId,
            onPhotoClick = onPhotoClick,
            onSetAvailableWidth = onSetAvailableWidth,
            onSetAvailableViewportHeight = onSetAvailableViewportHeight,
            onSetTargetRowHeight = onSetTargetRowHeight,
            onLoadMore = onLoadMore,
            sharedTransitionScope = sharedTransitionScope
        )
        shouldShowExploreLanding(state) -> ExploreLanding(
            onFavoritesClick = onFavoritesClick,
            onMemoriesClick = onMemoriesClick
        )
        else -> SearchMessageState(text = stringResource(Res.string.search_hint))
    }
}

internal fun shouldShowExploreLanding(state: SearchState): Boolean =
    state.query.isBlank() && !state.hasSearched

@Composable
private fun ExploreLanding(
    onFavoritesClick: () -> Unit,
    onMemoriesClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = Dimens.largeSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimens.largeSpacing)
    ) {
        ExploreCollectionCard(
            title = stringResource(Res.string.favorites),
            description = stringResource(Res.string.explore_favorites_description),
            icon = Res.drawable.ic_favorite,
            onClick = onFavoritesClick
        )
        ExploreCollectionCard(
            title = stringResource(Res.string.memories),
            description = stringResource(Res.string.explore_memories_description),
            icon = Res.drawable.ic_memories,
            onClick = onMemoriesClick
        )
    }
}

@Composable
private fun ExploreCollectionCard(
    title: String,
    description: String,
    icon: org.jetbrains.compose.resources.DrawableResource,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.exploreCardHeight)
            .semantics { contentDescription = title }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.largeSpacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.largeSpacing)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(Dimens.exploreCardIconSize)
            )
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchLoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SearchMessageState(
    text: String,
    isError: Boolean = false
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SearchResultsGrid(
    state: SearchState,
    statusBarPadding: Dp,
    listState: LazyListState,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    onPhotoClick: (String) -> Unit,
    onSetAvailableWidth: (Float) -> Unit,
    onSetAvailableViewportHeight: (Float) -> Unit,
    onSetTargetRowHeight: (Float) -> Unit,
    onLoadMore: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?
) {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(listState, focusManager) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { it }
            .collect { focusManager.clearFocus() }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 3
        }
    }
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    var lastAutomaticLoadKey by remember(state.activeRequestGeneration) {
        mutableStateOf<SearchAutoLoadKey?>(null)
    }
    val automaticLoadKey = state.nextPage?.let { page ->
        SearchAutoLoadKey(state.activeRequestGeneration, page)
    }
    LaunchedEffect(shouldLoadMore, automaticLoadKey, state.isLoadingMore) {
        if (
            shouldAutomaticallyLoadSearchPage(
                shouldLoadMore = shouldLoadMore,
                isLoadingMore = state.isLoadingMore,
                candidate = automaticLoadKey,
                lastAttempt = lastAutomaticLoadKey
            )
        ) {
            lastAutomaticLoadKey = automaticLoadKey
            latestOnLoadMore()
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val widthDp = with(density) { constraints.maxWidth.toDp() }
        val visibleGridHeight = (maxHeight - Dimens.bottomBarHeight - navBarPadding)
            .value
            .coerceAtLeast(0f)
        LaunchedEffect(widthDp, visibleGridHeight) {
            onSetAvailableWidth(widthDp.value)
            onSetAvailableViewportHeight(visibleGridHeight)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoomRowHeight(
                    state.targetRowHeight,
                    state.rowHeightBounds,
                    onSetTargetRowHeight
                )
                .desktopGridZoom(
                    state.targetRowHeight,
                    state.rowHeightBounds,
                    onSetTargetRowHeight
                )
        ) {
            ScrollbarOverlay(
                listState = listState,
                topPadding = statusBarPadding + Dimens.topBarHeight,
                bottomPadding = Dimens.bottomBarHeight + navBarPadding
            ) {
                SearchResultsList(
                    state = state,
                    listState = listState,
                    navBarPadding = navBarPadding,
                    transitionAssetId = transitionAssetId,
                    hiddenAssetId = hiddenAssetId,
                    onPhotoClick = onPhotoClick,
                    onLoadMore = onLoadMore,
                    sharedTransitionScope = sharedTransitionScope
                )
            }
        }
    }
}

internal data class SearchAutoLoadKey(
    val requestGeneration: Long,
    val page: Int
)

internal fun shouldAutomaticallyLoadSearchPage(
    shouldLoadMore: Boolean,
    isLoadingMore: Boolean,
    candidate: SearchAutoLoadKey?,
    lastAttempt: SearchAutoLoadKey?
): Boolean = shouldLoadMore &&
        !isLoadingMore &&
        candidate != null &&
        candidate != lastAttempt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SearchResultsList(
    state: SearchState,
    listState: LazyListState,
    navBarPadding: Dp,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    onPhotoClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?
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
            key = { _, row -> row.gridKey },
            contentType = { _, _ -> "search_photo_row" }
        ) { _, row ->
            JustifiedPhotoRow(
                row = row,
                spacing = Dimens.gridSpacing,
                onPhotoClick = onPhotoClick,
                sharedTransitionScope = sharedTransitionScope,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
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
        } else if (state.nextPage != null) {
            item(key = "load_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.mediumSpacing),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = onLoadMore) {
                        Text(stringResource(Res.string.load_more))
                    }
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
