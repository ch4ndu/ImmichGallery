package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.ui.util.PlatformBackHandler

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StaticPhotoOverlay(
    assets: List<Asset>,
    initialIndex: Int,
    apiKey: String,
    getAssetDetail: suspend (String) -> Result<AssetDetail>,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: (currentAssetId: String?) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    var showTopBar by remember { mutableStateOf(true) }
    val onTap = remember { { showTopBar = !showTopBar } }
    var showDetailSheet by remember { mutableStateOf(false) }

    if (assets.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (assets.size - 1).coerceAtLeast(0))
    ) { assets.size }

    PlatformBackHandler(enabled = true, onBack = {
        onDismiss(assets.getOrNull(pagerState.settledPage)?.id)
    })

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> assets[page].id }
        ) { page ->
            val asset = assets[page]
            val isSettledPage = pagerState.settledPage == page
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AssetPage(
                    asset = asset,
                    apiKey = apiKey,
                    isCurrentPage = isSettledPage,
                    onTap = onTap,
                    sharedTransitionScope = if (isSettledPage) sharedTransitionScope else null,
                    animatedVisibilityScope = if (isSettledPage) animatedVisibilityScope else null
                )
            }
        }

        val currentAsset = assets[pagerState.settledPage]
        DetailTopBarOverlay(
            showTopBar = showTopBar,
            title = currentAsset.fileName,
            onBack = { onDismiss(currentAsset.id) },
            onDownload = {},
            onShare = {},
            onInfo = { showDetailSheet = true }
        )

        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            DetailBottomHandle(
                visible = showTopBar,
                onClick = { showDetailSheet = true }
            )
        }

        if (showDetailSheet) {
            AssetDetailSheet(
                assetId = currentAsset.id,
                getAssetDetail = getAssetDetail,
                onPersonClick = { personId, personName ->
                    showDetailSheet = false
                    onDismiss(null)
                    onPersonClick(personId, personName)
                },
                onDismiss = { showDetailSheet = false }
            )
        }
    }
}
