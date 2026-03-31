package com.udnahc.immichgallery.ui.screen.people

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
import com.udnahc.immichgallery.ui.component.LoadingErrorContent
import com.udnahc.immichgallery.ui.component.PhotoRow
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.theme.GRID_COLUMNS
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.unknown
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PersonDetailScreen(
    personId: String,
    personName: String,
    onBack: () -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: PersonDetailViewModel = koinViewModel { parametersOf(personId) }
) {
    val state by viewModel.state.collectAsState()
    val unknownLabel = stringResource(Res.string.unknown)
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
                PersonDetailContent(
                    state = state,
                    onPhotoClick = { assetId -> selectedAssetId = assetId },
                    onRetry = viewModel::loadAssets,
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
                            val rowIndex = state.assets.indexOfFirst { it.id == currentAssetId } / GRID_COLUMNS
                            if (rowIndex >= 0) {
                                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                                if (rowIndex !in visible) {
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

        DetailTopBar(
            title = personName.ifBlank { unknownLabel },
            onBack = onBack
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PersonDetailContent(
    state: PersonDetailState,
    onPhotoClick: (String) -> Unit,
    onRetry: () -> Unit,
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
        val rows = remember(state.assets) { state.assets.chunked(GRID_COLUMNS) }
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
                    PhotoRow(
                        row,
                        state.assets,
                        onPhotoClick = { _, index ->
                            val asset = state.assets.getOrNull(index)
                            if (asset != null) onPhotoClick(asset.id)
                        },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun PersonDetailContentPreview() {
    val sampleAsset = Asset(
        id = "1", type = AssetType.IMAGE, fileName = "photo.jpg",
        createdAt = "", thumbnailUrl = "", originalUrl = ""
    )
    PersonDetailContent(
        state = PersonDetailState(
            assets = listOf(sampleAsset)
        ),
        onPhotoClick = {},
        onRetry = {},
        contentTopPadding = 0.dp,
        contentBottomPadding = 0.dp
    )
}
