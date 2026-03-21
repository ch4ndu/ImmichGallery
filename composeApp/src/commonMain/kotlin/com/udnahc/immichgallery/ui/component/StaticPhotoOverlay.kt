package com.udnahc.immichgallery.ui.component

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
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.ui.util.PlatformBackHandler

@Composable
fun StaticPhotoOverlay(
    assets: List<Asset>,
    initialIndex: Int,
    apiKey: String,
    getAssetDetailUseCase: GetAssetDetailUseCase,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: () -> Unit
) {
    PlatformBackHandler(enabled = true, onBack = onDismiss)

    var showTopBar by remember { mutableStateOf(true) }
    var showDetailSheet by remember { mutableStateOf(false) }

    if (assets.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (assets.size - 1).coerceAtLeast(0))
    ) { assets.size }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> assets[page].id }
        ) { page ->
            val asset = assets[page]
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AssetPage(
                    asset = asset,
                    apiKey = apiKey,
                    isCurrentPage = pagerState.settledPage == page,
                    onTap = { showTopBar = !showTopBar }
                )
            }
        }

        val currentAsset = assets[pagerState.settledPage]
        DetailTopBarOverlay(
            showTopBar = showTopBar,
            title = currentAsset.fileName,
            onBack = onDismiss,
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
                getAssetDetailUseCase = getAssetDetailUseCase,
                onPersonClick = { personId, personName ->
                    showDetailSheet = false
                    onDismiss()
                    onPersonClick(personId, personName)
                },
                onDismiss = { showDetailSheet = false }
            )
        }
    }
}
