package com.udnahc.immichgallery.ui.screen.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.component.ThumbnailCell
import com.udnahc.immichgallery.ui.theme.Dimens
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.back
import immichgallery.composeapp.generated.resources.ic_back
import immichgallery.composeapp.generated.resources.retry
import immichgallery.composeapp.generated.resources.unknown
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private const val GRID_COLUMNS = 3

@Composable
fun PersonDetailScreen(
    personId: String,
    personName: String,
    onBack: () -> Unit,
    viewModel: PersonDetailViewModel = koinViewModel { parametersOf(personId) }
) {
    val state by viewModel.state.collectAsState()
    val unknownLabel = stringResource(Res.string.unknown)
    val serverConfigRepository: ServerConfigRepository = koinInject()
    val apiKey = remember { serverConfigRepository.getApiKey() }
    var selectedPhotoIndex by remember { mutableStateOf<Int?>(null) }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusBarPadding + Dimens.topBarHeight
    val contentBottomPadding = navBarPadding
    val barColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f)

    Box(modifier = Modifier.fillMaxSize()) {
        PersonDetailContent(
            state = state,
            onPhotoClick = { _, index -> selectedPhotoIndex = index },
            onRetry = viewModel::loadAssets,
            contentTopPadding = contentTopPadding,
            contentBottomPadding = contentBottomPadding
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .statusBarsPadding()
                .height(Dimens.topBarHeight)
                .padding(start = Dimens.smallSpacing, end = Dimens.screenPadding)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    painterResource(Res.drawable.ic_back),
                    contentDescription = stringResource(Res.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = personName.ifBlank { unknownLabel },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Dimens.topBarHeight)
            )
        }

        selectedPhotoIndex?.let { index ->
            StaticPhotoOverlay(
                assets = state.assets,
                initialIndex = index,
                apiKey = apiKey,
                onDismiss = { selectedPhotoIndex = null }
            )
        }
    }
}

@Composable
fun PersonDetailContent(
    state: PersonDetailState,
    onPhotoClick: (List<Asset>, Int) -> Unit,
    onRetry: () -> Unit,
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp = 0.dp
) {
    when {
        state.isLoading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.error != null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
                }
            }
        }
        else -> {
            val rows = remember(state.assets) { state.assets.chunked(GRID_COLUMNS) }
            val listState = rememberLazyListState()
            ScrollbarOverlay(listState = listState, topPadding = contentTopPadding, bottomPadding = contentBottomPadding) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = contentTopPadding, bottom = contentBottomPadding)
                ) {
                    items(rows, key = { it.first().id }) { row ->
                        PhotoRow(row, state.assets, onPhotoClick)
                    }
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
private fun PersonDetailContentPreview() {
    val sampleAsset = Asset(
        id = "1", type = AssetType.IMAGE, fileName = "photo.jpg",
        createdAt = "", thumbnailUrl = "", originalUrl = ""
    )
    PersonDetailContent(
        state = PersonDetailState(assets = listOf(sampleAsset)),
        onPhotoClick = { _, _ -> },
        onRetry = {},
        contentTopPadding = 0.dp,
        contentBottomPadding = 0.dp
    )
}
