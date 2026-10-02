package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.asset_position
import immichgallery.composeapp.generated.resources.ic_play
import immichgallery.composeapp.generated.resources.viewer_filmstrip_hide
import immichgallery.composeapp.generated.resources.viewer_filmstrip_placeholder
import immichgallery.composeapp.generated.resources.viewer_filmstrip_show
import immichgallery.composeapp.generated.resources.viewer_filmstrip_unloaded
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val FILMSTRIP_THUMBNAIL_DECODE_SIZE = 256
private const val FILMSTRIP_BACKGROUND_ALPHA = 0.45f

/**
 * [assetForPage] is a read-only lookup: only visible slots resolve thumbnails.
 * [pageKey] must be unique and stable across lazy materialization (for example,
 * Timeline bucket/offset rather than switching a placeholder key to an asset ID).
 * The owner gates slideshow/drag/transition/coverage and applies safe-area padding.
 */
@Composable
internal fun ViewerFilmstrip(
    totalPages: Int,
    currentPage: Int,
    pageKey: (Int) -> Any,
    assetForPage: (Int) -> Asset?,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onVisiblePagesChanged: (List<Int>) -> Unit = {}
) {
    if (totalPages <= 0) return
    val selectedPage = currentPage.coerceIn(0, totalPages - 1)
    val selectedKey = pageKey(selectedPage)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedPage)
    val latestTotalPages by rememberUpdatedState(totalPages)
    val latestOnPageSelected by rememberUpdatedState(onPageSelected)
    val latestOnVisiblePagesChanged by rememberUpdatedState(onVisiblePagesChanged)
    val appActive = LocalAppActive.current
    val latestAppActive by rememberUpdatedState(appActive)
    val visiblePages by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.asSequence()
                .filter { it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset }
                .map { it.index }
                .toSet()
        }
    }

    LaunchedEffect(listState, totalPages, selectedPage, selectedKey) {
        val layout = listState.layoutInfo
        val fullyVisiblePages = layout.visibleItemsInfo.filter {
            it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset
        }.map { it.index }
        viewerFilmstripScrollTarget(totalPages, selectedPage, fullyVisiblePages)?.let {
            // A direct jump avoids traversing and loading all intervening buckets.
            listState.requestScrollToItem(it)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            if (latestAppActive) {
                layout.visibleItemsInfo.filter {
                    it.index in 0 until latestTotalPages &&
                        it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset
                }.map { it.index to it.key }
            } else {
                emptyList()
            }
        }.collect { visibleItems ->
            // Keys also change when a source reorders at unchanged indexes.
            latestOnVisiblePagesChanged(visibleItems.map { it.first })
        }
    }
    DisposableEffect(listState) {
        onDispose { latestOnVisiblePagesChanged(emptyList()) }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.viewerFilmstripHeight)
            .background(Color.Black.copy(alpha = FILMSTRIP_BACKGROUND_ALPHA))
            .selectableGroup(),
        contentPadding = PaddingValues(horizontal = Dimens.screenPadding, vertical = Dimens.cardPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
    ) {
        items(count = totalPages, key = pageKey, contentType = { "viewer-thumbnail" }) { page ->
            val asset = if (appActive && page in visiblePages) assetForPage(page) else null
            ViewerFilmstripThumbnail(
                asset = asset,
                page = page,
                totalPages = totalPages,
                selected = page == selectedPage,
                onClick = {
                    if (page in 0 until latestTotalPages) latestOnPageSelected(page)
                }
            )
        }
    }
}

/** Keep this mounted when collapsed; Memories sets [showPosition] to false. */
@Composable
internal fun ViewerFilmstripControls(
    totalPages: Int,
    currentPage: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showPosition: Boolean = true
) {
    if (totalPages <= 0) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = FILMSTRIP_BACKGROUND_ALPHA))
            .padding(horizontal = Dimens.screenPadding),
        horizontalArrangement = if (showPosition) Arrangement.SpaceBetween else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showPosition) {
            Text(
                text = stringResource(Res.string.asset_position, currentPage.coerceIn(0, totalPages - 1) + 1, totalPages),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
        }
        TextButton(
            onClick = { onExpandedChange(!expanded) },
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
        ) {
            Text(stringResource(if (expanded) Res.string.viewer_filmstrip_hide else Res.string.viewer_filmstrip_show))
        }
    }
}

@Composable
private fun ViewerFilmstripThumbnail(
    asset: Asset?,
    page: Int,
    totalPages: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val position = stringResource(Res.string.asset_position, page + 1, totalPages)
    val description = if (asset == null) {
        stringResource(Res.string.viewer_filmstrip_unloaded, position)
    } else {
        position
    }
    Box(
        modifier = Modifier
            .size(Dimens.viewerFilmstripHeight - Dimens.cardPadding * 2)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (asset == null) {
            Text(
                text = stringResource(Res.string.viewer_filmstrip_placeholder, page + 1),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clearAndSetSemantics { }
            )
        } else {
            val context = LocalPlatformContext.current
            val request = remember(context, asset.thumbnailUrl, asset.thumbnailCacheKey) {
                ImageRequest.Builder(context)
                    .data(asset.thumbnailUrl)
                    .size(Size(FILMSTRIP_THUMBNAIL_DECODE_SIZE, FILMSTRIP_THUMBNAIL_DECODE_SIZE))
                    .precision(Precision.EXACT)
                    .memoryCacheKey(asset.thumbnailCacheKey)
                    .diskCacheKey(asset.thumbnailCacheKey)
                    .build()
            }
            Image(
                painter = rememberAsyncImagePainter(model = request),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (asset.type == AssetType.VIDEO) {
                Icon(
                    painter = painterResource(Res.drawable.ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(Dimens.iconSize)
                )
            }
        }
    }
}

internal fun viewerFilmstripScrollTarget(
    totalPages: Int,
    currentPage: Int,
    fullyVisiblePages: Collection<Int>
): Int? {
    if (totalPages <= 0) return null
    val selectedPage = currentPage.coerceIn(0, totalPages - 1)
    return selectedPage.takeUnless { it in fullyVisiblePages }
}
