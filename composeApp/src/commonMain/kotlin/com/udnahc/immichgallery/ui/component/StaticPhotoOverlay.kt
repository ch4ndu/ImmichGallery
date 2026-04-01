package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.rememberScreenWakeLock

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
    var isSlideshow by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }

    val onTap: () -> Unit = remember {
        {
            if (isSlideshow) {
                isSlideshow = false
                showTopBar = true
            } else {
                showTopBar = !showTopBar
            }
        }
    }

    // Wake lock for slideshow mode
    val wakeLock = rememberScreenWakeLock()
    LaunchedEffect(isSlideshow) {
        if (isSlideshow) wakeLock.acquire() else wakeLock.release()
    }
    DisposableEffect(Unit) { onDispose { wakeLock.release() } }

    if (assets.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (assets.size - 1).coerceAtLeast(0))
    ) { assets.size }

    // Auto-advance slideshow — single coroutine that loops
    LaunchedEffect(isSlideshow) {
        if (isSlideshow && assets.size > 1) {
            while (isSlideshow) {
                kotlinx.coroutines.delay(5000)
                if (!isSlideshow) break
                val next = (pagerState.settledPage + 1) % assets.size
                pagerState.animateScrollToPage(next)
            }
        }
    }

    PlatformBackHandler(enabled = true, onBack = {
        onDismiss(assets.getOrNull(pagerState.settledPage)?.id)
    })

    // Vertical swipe gesture state
    var verticalDragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val swipeThresholdPx = remember { with(density) { 100.dp.toPx() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .draggable(
                state = rememberDraggableState { delta -> verticalDragOffset += delta },
                orientation = Orientation.Vertical,
                onDragStopped = {
                    when {
                        verticalDragOffset > swipeThresholdPx -> {
                            isSlideshow = false
                            onDismiss(assets.getOrNull(pagerState.settledPage)?.id)
                        }
                        verticalDragOffset < -swipeThresholdPx && !showDetailSheet -> {
                            showDetailSheet = true
                        }
                    }
                    verticalDragOffset = 0f
                }
            )
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
                    isSlideshow = isSlideshow,
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
            onInfo = { showDetailSheet = true },
            onSlideshow = if (assets.size > 1) {
                { isSlideshow = true; showTopBar = false }
            } else null
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
