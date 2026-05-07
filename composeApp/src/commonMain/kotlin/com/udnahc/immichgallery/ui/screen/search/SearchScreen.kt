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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.udnahc.immichgallery.ui.component.PhotoOverlayHost
import com.udnahc.immichgallery.ui.component.PhotoOverlaySourcePosition
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.model.asTextOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.desktopGridZoom
import com.udnahc.immichgallery.ui.util.pinchToZoomRowHeight
import com.udnahc.immichgallery.ui.util.prepareOverlayDismissSource
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.search_no_results
import immichgallery.composeapp.generated.resources.search_placeholder
import immichgallery.composeapp.generated.resources.search_hint
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
    val sourcePositions = remember { mutableStateMapOf<String, PhotoOverlaySourcePosition>() }
    var preparedDismissReturnKey by remember { mutableStateOf<String?>(null) }

    // Scroll back to last viewed asset when returning from detail
    LaunchedEffect(viewModel.lastViewedAssetId) {
        val rowIndex = viewModel.getDisplayItemIndexForReturn() ?: return@LaunchedEffect
            val info = listState.layoutInfo
            val visibleItem = info.visibleItemsInfo.firstOrNull { it.index == rowIndex }
            if (preparedDismissReturnKey == viewModel.lastViewedAssetId && visibleItem != null) {
                preparedDismissReturnKey = null
                return@LaunchedEffect
            }
            val fullyVisible = visibleItem != null &&
                visibleItem.offset >= info.viewportStartOffset &&
                (visibleItem.offset + visibleItem.size) <= info.viewportEndOffset
            if (!fullyVisible) {
                listState.scrollToItem(rowIndex)
            }
    }

    PhotoOverlayHost(
        initialIndexKey = state.results,
        onOverlayActiveChange = onOverlayActiveChange,
        resolveInitialIndex = { id -> state.results.indexOfFirst { it.id == id }.takeIf { it >= 0 } },
        prepareDismissSource = prepareDismissSource@ { context ->
            val assetId = context.assetId ?: return@prepareDismissSource
            viewModel.lastViewedAssetId = assetId
            preparedDismissReturnKey = assetId
            sourcePositions.remove(assetId)
            listState.prepareOverlayDismissSource(
                displayIndex = viewModel.getDisplayItemIndexForReturn(),
                isSourceReady = { sourcePositions[assetId]?.generation == context.sourceGeneration },
                clearSourceReady = { sourcePositions.remove(assetId) },
            )
        },
        onActiveSourcePositioned = { position ->
            sourcePositions[position.assetId] = position
        },
        content = { showOverlay, transitionAssetId, hiddenAssetId, activeSourceGeneration, onActiveSourcePositioned, onPhotoClick ->
            SearchContent(
                state = state,
                showOverlay = showOverlay,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
                activeSourceGeneration = activeSourceGeneration,
                onActiveSourcePositioned = onActiveSourcePositioned,
                onQueryChange = viewModel::updateQuery,
                onSearchTypeChange = viewModel::updateSearchType,
                onSearch = viewModel::search,
                onPhotoClick = onPhotoClick,
                onSetAvailableWidth = viewModel::setAvailableWidth,
                onSetAvailableViewportHeight = viewModel::setAvailableViewportHeight,
                onSetTargetRowHeight = viewModel::setTargetRowHeight,
                onLoadMore = viewModel::loadMore,
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
                    onPersonClick = onPersonClick,
                    onDismiss = onDismissHost,
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
    activeSourceGeneration: Int = 0,
    onActiveSourcePositioned: ((PhotoOverlaySourcePosition) -> Unit)? = null,
    onQueryChange: (String) -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onSearch: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onSetAvailableWidth: (Float) -> Unit,
    onSetAvailableViewportHeight: (Float) -> Unit = {},
    onSetTargetRowHeight: (Float) -> Unit = {},
    onLoadMore: () -> Unit,
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
        // Search field + filter chips hide when the detail overlay is shown
        // so they don't leak through the transparent scrim during drag-to-dismiss.
        AnimatedVisibility(
            visible = !showOverlay,
            enter = systemBarFadeIn,
            exit = systemBarFadeOut,
        ) {
            Column {
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
            }
        }

        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error.asTextOrNull().orEmpty(), color = MaterialTheme.colorScheme.error)
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

                LaunchedEffect(listState, focusManager) {
                    snapshotFlow { listState.isScrollInProgress }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect { focusManager.clearFocus() }
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
                val latestOnLoadMore by rememberUpdatedState(onLoadMore)
                LaunchedEffect(shouldLoadMore) {
                    if (shouldLoadMore) latestOnLoadMore()
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
                            .pinchToZoomRowHeight(state.targetRowHeight, state.rowHeightBounds, onSetTargetRowHeight)
                            .desktopGridZoom(state.targetRowHeight, state.rowHeightBounds, onSetTargetRowHeight)
                    ) {
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
                                        transitionAssetId = transitionAssetId,
                                        hiddenAssetId = hiddenAssetId,
                                        activeSourceGeneration = activeSourceGeneration,
                                        onActiveSourcePositioned = onActiveSourcePositioned,
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
