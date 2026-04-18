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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.model.SlideshowOrder
import com.udnahc.immichgallery.ui.util.DragToDismissState
import com.udnahc.immichgallery.ui.util.PhotoDismissMotion
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.dragToDismiss
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
    var slideshowConfig by remember { mutableStateOf<SlideshowConfig?>(null) }
    var showSlideshowDialog by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }

    val isSlideshow = slideshowConfig != null

    val onTap: () -> Unit = remember {
        {
            if (slideshowConfig != null) {
                slideshowConfig = null
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

    val clampedInitial = initialIndex.coerceIn(0, (assets.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = clampedInitial
    ) { assets.size }

    // Force pager to snap to initialPage on first composition. In LookaheadScope
    // from SharedTransitionLayout, the pager's scroll offset can transiently be 0
    // before snapping, producing a visible slide-in from the right.
    LaunchedEffect(Unit) {
        if (pagerState.currentPage != clampedInitial) {
            pagerState.scrollToPage(clampedInitial)
        }
    }

    // Auto-advance slideshow — keyed on config so it restarts on config change
    LaunchedEffect(slideshowConfig) {
        val config = slideshowConfig ?: return@LaunchedEffect
        if (assets.size <= 1) return@LaunchedEffect
        val delayMs = config.durationSeconds * 1000L
        while (true) {
            kotlinx.coroutines.delay(delayMs)
            if (slideshowConfig == null) break
            val next = if (config.order == SlideshowOrder.RANDOM) {
                var target = (0 until assets.size).random()
                // Avoid staying on the same page
                while (target == pagerState.settledPage && assets.size > 1) {
                    target = (0 until assets.size).random()
                }
                target
            } else {
                (pagerState.settledPage + 1) % assets.size
            }
            pagerState.animateScrollToPage(next)
        }
    }

    PlatformBackHandler(enabled = true, onBack = {
        onDismiss(assets.getOrNull(pagerState.settledPage)?.id)
    })

    // Slideshow options dialog
    if (showSlideshowDialog) {
        SlideshowOptionsDialog(
            onConfirm = { config ->
                showSlideshowDialog = false
                slideshowConfig = config
                showTopBar = false
            },
            onDismiss = { showSlideshowDialog = false }
        )
    }

    val density = LocalDensity.current
    val dismissThresholdPx = remember(density) {
        with(density) { PhotoDismissMotion.dismissThreshold.toPx() }
    }
    val flickVelocityPx = remember(density) {
        with(density) { PhotoDismissMotion.flickVelocity.toPx() }
    }
    val scope = rememberCoroutineScope()
    val dragState = remember(scope) {
        DragToDismissState(
            scope = scope,
            dismissThresholdPx = dismissThresholdPx,
            flickVelocityPx = flickVelocityPx,
            exitSpec = PhotoDismissMotion.exitSpec,
            snapBackSpec = PhotoDismissMotion.snapBackSpec,
        )
    }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.settledPage) { isCurrentPageZoomed = false }

    val gestureEnabled = !showDetailSheet && !isSlideshow
    LaunchedEffect(gestureEnabled) {
        if (!gestureEnabled && dragState.isActive) dragState.cancel()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = dragState.scrimAlpha))
            .dragToDismiss(
                state = dragState,
                enabled = gestureEnabled,
                isZoomed = { isCurrentPageZoomed },
                dismissThresholdPx = dismissThresholdPx,
                flickVelocityPx = flickVelocityPx,
                onDismiss = {
                    slideshowConfig = null
                    onDismiss(assets.getOrNull(pagerState.settledPage)?.id)
                },
                onOpenDetailSheet = { showDetailSheet = true },
            )
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> assets[page].id }
        ) { page ->
            val asset = assets[page]
            val isSettledPage = pagerState.settledPage == page
            // Only apply graphicsLayer while a drag is actually in flight —
            // an always-on identity layer adds a compositing boundary that
            // interacts badly with sharedBounds promotion/demotion.
            val transformForPage = if (isSettledPage && dragState.isActive) {
                Modifier.graphicsLayer {
                    scaleX = dragState.scale
                    scaleY = dragState.scale
                    translationX = dragState.translation.x
                    translationY = dragState.translation.y
                    transformOrigin = dragState.pivot
                }
            } else Modifier
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AssetPage(
                    asset = asset,
                    apiKey = apiKey,
                    isCurrentPage = isSettledPage,
                    isSlideshow = isSlideshow,
                    slideshowConfig = slideshowConfig,
                    onTap = onTap,
                    sharedTransitionScope = if (isSettledPage) sharedTransitionScope else null,
                    animatedVisibilityScope = if (isSettledPage) animatedVisibilityScope else null,
                    pageTransform = transformForPage,
                    isDragging = isSettledPage && dragState.isActive,
                    onZoomStateChanged = { zoomed -> isCurrentPageZoomed = zoomed },
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
                { showSlideshowDialog = true }
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
