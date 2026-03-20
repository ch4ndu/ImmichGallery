package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.paging.compose.LazyPagingItems
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.ui.component.AssetPage
import com.udnahc.immichgallery.ui.component.DetailTopBarOverlay
import com.udnahc.immichgallery.ui.util.PlatformBackHandler

@Composable
fun TimelinePhotoOverlay(
    pagingItems: LazyPagingItems<Asset>,
    initialIndex: Int,
    apiKey: String,
    getAssetFileNameUseCase: GetAssetFileNameUseCase,
    onDismiss: () -> Unit
) {
    PlatformBackHandler(enabled = true, onBack = onDismiss)

    var showTopBar by remember { mutableStateOf(true) }
    val fileNameCache = remember { mutableStateMapOf<String, String>() }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (pagingItems.itemCount - 1).coerceAtLeast(0))
    ) { pagingItems.itemCount }

    val currentAsset = if (pagingItems.itemCount > 0) pagingItems[pagerState.settledPage] else null

    LaunchedEffect(currentAsset?.id) {
        val asset = currentAsset ?: return@LaunchedEffect
        if (asset.fileName.isNotEmpty()) {
            fileNameCache[asset.id] = asset.fileName
            return@LaunchedEffect
        }
        if (fileNameCache.containsKey(asset.id)) return@LaunchedEffect
        getAssetFileNameUseCase(asset.id, asset.fileName).onSuccess { name ->
            fileNameCache[asset.id] = name
        }
    }

    val displayFileName = currentAsset?.let {
        it.fileName.ifEmpty { fileNameCache[it.id] ?: "" }
    } ?: ""

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        if (pagingItems.itemCount > 0) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { page -> pagingItems.peek(page)?.id ?: page }
            ) { page ->
                val asset = pagingItems[page]
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (asset != null) {
                        AssetPage(
                            asset = asset,
                            apiKey = apiKey,
                            isCurrentPage = pagerState.settledPage == page,
                            onTap = { showTopBar = !showTopBar }
                        )
                    }
                }
            }
        }

        DetailTopBarOverlay(
            showTopBar = showTopBar,
            title = displayFileName,
            onBack = onDismiss,
            onDownload = {},
            onShare = {},
            onInfo = {}
        )
    }
}
