package com.udnahc.immichgallery.ui.screen.memories

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.udnahc.immichgallery.domain.model.Memory
import com.udnahc.immichgallery.ui.component.DetailTopBar
import com.udnahc.immichgallery.ui.component.PhotoOverlayHost
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.component.ThumbnailCell
import com.udnahc.immichgallery.ui.model.asText
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.PhotoOverlayReturnTarget
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.items_count
import immichgallery.composeapp.generated.resources.loading_memories
import immichgallery.composeapp.generated.resources.memories
import immichgallery.composeapp.generated.resources.memories_empty
import immichgallery.composeapp.generated.resources.retry
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MemoriesScreen(
    onBack: () -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit = { _, _ -> },
    viewModel: MemoriesViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val selectedMemory = state.memories.firstOrNull { it.id == state.selectedMemoryId }

    PhotoOverlayHost(
        initialIndexKey = selectedMemory?.id,
        returnTarget = PhotoOverlayReturnTarget.ORIGIN_ASSET,
        resolveInitialIndex = { assetId ->
            selectedMemory?.assets?.indexOfFirst { it.id == assetId }?.takeIf { it >= 0 }
        },
        content = { _, transitionAssetId, hiddenAssetId, onPhotoClick ->
            MemoriesContent(
                state = state,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId,
                onMemoryClick = { memory ->
                    viewModel.selectMemory(memory.id)
                    onPhotoClick(memory.cover.id)
                },
                onRetry = viewModel::retry,
                sharedTransitionScope = this
            )
        },
        overlay = { initialIndex, onDismissHost, onCurrentAssetChanged, onStlTransitionActiveChanged, sharedTransitionScope ->
            val memory = selectedMemory ?: return@PhotoOverlayHost
            StaticPhotoOverlay(
                assets = memory.assets,
                initialIndex = initialIndex,
                apiKey = viewModel.apiKey,
                getAssetDetail = viewModel::getAssetDetail,
                getAssetStack = viewModel::getAssetStack,
                sharedElementAssetId = memory.cover.id,
                showRemainingAssetCount = true,
                onPersonClick = onPersonClick,
                onDismiss = onDismissHost,
                onCurrentAssetChanged = onCurrentAssetChanged,
                onStlTransitionActiveChanged = onStlTransitionActiveChanged,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this
            )
        },
        chrome = { showOverlay ->
            AnimatedVisibility(
                visible = !showOverlay,
                enter = systemBarFadeIn,
                exit = systemBarFadeOut
            ) {
                DetailTopBar(
                    title = stringResource(Res.string.memories),
                    onBack = onBack
                )
            }
        }
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MemoriesContent(
    state: MemoriesState,
    transitionAssetId: String?,
    hiddenAssetId: String?,
    onMemoryClick: (Memory) -> Unit,
    onRetry: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusBarPadding + Dimens.topBarHeight

    when {
        state.isLoading -> MemoriesStatus(
            message = stringResource(Res.string.loading_memories),
            topPadding = contentTopPadding,
            loading = true
        )
        state.error != null -> MemoriesStatus(
            message = state.error.asText(),
            topPadding = contentTopPadding,
            loading = false,
            onRetry = onRetry
        )
        state.memories.isEmpty() -> MemoriesStatus(
            message = stringResource(Res.string.memories_empty),
            topPadding = contentTopPadding,
            loading = false
        )
        else -> {
            val listState = rememberLazyListState()
            ScrollbarOverlay(
                listState = listState,
                topPadding = contentTopPadding,
                bottomPadding = navigationBarPadding
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.screenPadding,
                        end = Dimens.screenPadding,
                        top = contentTopPadding + Dimens.cardPadding,
                        bottom = navigationBarPadding + Dimens.cardPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.largeSpacing)
                ) {
                    items(state.memories, key = Memory::id) { memory ->
                        val onClick = remember(memory.id, onMemoryClick) {
                            { onMemoryClick(memory) }
                        }
                        MemoryCard(
                            memory = memory,
                            onClick = onClick,
                            sharedTransitionScope = sharedTransitionScope,
                            transitionAssetId = transitionAssetId,
                            hiddenAssetId = hiddenAssetId
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MemoryCard(
    memory: Memory,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    transitionAssetId: String?,
    hiddenAssetId: String?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.memoryCardHeight)
    ) {
        Box(Modifier.fillMaxSize()) {
            ThumbnailCell(
                asset = memory.cover,
                onClick = onClick,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                sharedTransitionScope = sharedTransitionScope,
                transitionAssetId = transitionAssetId,
                hiddenAssetId = hiddenAssetId
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Dimens.cardPadding),
                color = Color.Black.copy(alpha = 0.65f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(Dimens.cardPadding)) {
                    Text(
                        text = memory.year.toString(),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(Res.string.items_count, memory.assets.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoriesStatus(
    message: String,
    topPadding: androidx.compose.ui.unit.Dp,
    loading: Boolean,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
        ) {
            if (loading) CircularProgressIndicator()
            Text(
                text = message,
                color = if (onRetry == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text(stringResource(Res.string.retry))
                }
            }
        }
    }
}
