package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.ui.component.AssetDetailSheet
import com.udnahc.immichgallery.ui.component.AssetPage
import com.udnahc.immichgallery.ui.component.DetailBottomHandle
import com.udnahc.immichgallery.ui.component.DetailTopBarOverlay
import com.udnahc.immichgallery.ui.util.DragToDismissState
import com.udnahc.immichgallery.ui.util.PhotoDismissMotion
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.dragToDismiss
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelinePhotoOverlay(
    timelineState: StateFlow<TimelineState>,
    initialIndex: Int,
    apiKey: String,
    getAssetFileName: suspend (assetId: String, fallback: String) -> Result<String>,
    getAssetDetail: suspend (String) -> Result<AssetDetail>,
    assetCache: SnapshotStateMap<String, List<Asset>>,
    loadBucket: suspend (timeBucket: String) -> Unit,
    onBucketNeeded: (timeBucket: String) -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: (currentAssetId: String?, currentBucket: String?) -> Unit,
    onCurrentAssetChanged: (String) -> Unit = {},
    onStlTransitionActiveChanged: (Boolean) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Forward the STL's actual animation-active state to the screen so the
    // screen can flip `overlayAnimActive` false only when the bounds animation
    // has truly completed — instead of after a fixed delay that can truncate
    // really-tall-image animations.
    val stlActive = sharedTransitionScope?.isTransitionActive ?: false
    LaunchedEffect(stlActive) { onStlTransitionActiveChanged(stlActive) }

    val state by timelineState.collectAsState()
    var showTopBar by remember { mutableStateOf(true) }
    val onTap = remember { { showTopBar = !showTopBar } }
    var showDetailSheet by remember { mutableStateOf(false) }
    val fileNameCache = remember { mutableStateMapOf<String, String>() }

    val totalPages = remember(state.buckets) {
        state.buckets.sumOf { it.count }
    }

    if (totalPages == 0) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val clampedInitial = initialIndex.coerceIn(0, totalPages - 1)

    val pagerState = rememberPagerState(
        initialPage = clampedInitial
    ) { totalPages }

    // Force the pager to snap to the target page on first composition. Inside
    // SharedTransitionLayout's LookaheadScope, the pager's scroll offset can
    // briefly not reflect initialPage during the first measure pass, which makes
    // the detail-side page X appear to slide in from the right.
    LaunchedEffect(Unit) {
        if (pagerState.currentPage != clampedInitial) {
            pagerState.scrollToPage(clampedInitial)
        }
    }

    // Report the currently-viewed asset to the parent so the grid can hide it.
    // mapNotNull filters out settled pages whose bucket hasn't loaded yet —
    // otherwise we'd briefly signal null and the grid would flash every cell in.
    LaunchedEffect(pagerState, state.buckets, assetCache) {
        snapshotFlow { pagerState.settledPage }
            .mapNotNull { resolvePageAsset(it, state.buckets, assetCache)?.id }
            .distinctUntilChanged()
            .collect(onCurrentAssetChanged)
    }

    // Prefetch current ±1 full images into Coil's cache. Neighboring pages
    // whose bucket hasn't loaded yet are silently skipped — they'll prefetch
    // whenever their bucket arrives and the pager settles near them.
    val prefetchContext = LocalPlatformContext.current
    val imageLoader = remember(prefetchContext) { SingletonImageLoader.get(prefetchContext) }
    LaunchedEffect(pagerState, state.buckets, assetCache) {
        snapshotFlow { pagerState.settledPage }
            .collect { page ->
                listOf(page - 1, page, page + 1).forEach { idx ->
                    val asset = resolvePageAsset(idx, state.buckets, assetCache)
                        ?: return@forEach
                    imageLoader.enqueue(
                        ImageRequest.Builder(prefetchContext)
                            .data(asset.originalUrl)
                            .build()
                    )
                }
            }
    }

    // Load bucket assets into the shared cache as needed
    val currentBucketKey = resolvePageBucket(pagerState.settledPage, state.buckets)
    LaunchedEffect(currentBucketKey) {
        if (currentBucketKey != null && !assetCache.containsKey(currentBucketKey)) {
            onBucketNeeded(currentBucketKey)
            loadBucket(currentBucketKey)
        }
    }

    val currentAsset = resolvePageAsset(
        pagerState.settledPage, state.buckets, assetCache
    )

    PlatformBackHandler(enabled = true, onBack = {
        onDismiss(currentAsset?.id, currentBucketKey)
    })

    // Cache file names
    LaunchedEffect(currentAsset?.id) {
        val asset = currentAsset ?: return@LaunchedEffect
        if (asset.fileName.isNotEmpty()) {
            fileNameCache[asset.id] = asset.fileName
            return@LaunchedEffect
        }
        if (fileNameCache.containsKey(asset.id)) return@LaunchedEffect
        getAssetFileName(asset.id, asset.fileName).onSuccess { name ->
            fileNameCache[asset.id] = name
        }
    }

    val resolvedFileName = currentAsset?.let {
        it.fileName.ifEmpty { fileNameCache[it.id] ?: "" }
    } ?: ""
    // Keep the last non-empty title visible while an async filename fetch is
    // in flight — avoids the top bar blanking out between pager pages.
    var lastDisplayedFileName by remember { mutableStateOf("") }
    LaunchedEffect(resolvedFileName) {
        if (resolvedFileName.isNotEmpty()) lastDisplayedFileName = resolvedFileName
    }
    val displayFileName = resolvedFileName.ifEmpty { lastDisplayedFileName }

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

    val gestureEnabled = !showDetailSheet
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
                onDismiss = { onDismiss(currentAsset?.id, currentBucketKey) },
                onOpenDetailSheet = { showDetailSheet = true },
            )
            // Swallow taps that fall in letterbox dead zones so they don't
            // reach the grid composed beneath the overlay.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> page }
        ) { page ->
            val asset = resolvePageAsset(page, state.buckets, assetCache)
            val isSettledPage = pagerState.settledPage == page

            // Trigger loading for this page's bucket
            LaunchedEffect(page) {
                val bucket = resolvePageBucket(page, state.buckets)
                if (bucket != null && !assetCache.containsKey(bucket)) {
                    onBucketNeeded(bucket)
                    loadBucket(bucket)
                }
            }

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
                if (asset != null) {
                    AssetPage(
                        asset = asset,
                        apiKey = apiKey,
                        isCurrentPage = isSettledPage,
                        onTap = onTap,
                        sharedTransitionScope = if (isSettledPage) sharedTransitionScope else null,
                        animatedVisibilityScope = if (isSettledPage) animatedVisibilityScope else null,
                        pageTransform = transformForPage,
                        isDragging = isSettledPage && dragState.isActive,
                        onZoomStateChanged = { zoomed -> isCurrentPageZoomed = zoomed },
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        DetailTopBarOverlay(
            showTopBar = showTopBar,
            title = displayFileName,
            onBack = { onDismiss(currentAsset?.id, currentBucketKey) },
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

        if (showDetailSheet && currentAsset != null) {
            AssetDetailSheet(
                assetId = currentAsset.id,
                getAssetDetail = getAssetDetail,
                onPersonClick = { personId, personName ->
                    showDetailSheet = false
                    onDismiss(null, null)
                    onPersonClick(personId, personName)
                },
                onDismiss = { showDetailSheet = false }
            )
        }
    }
}

private fun resolvePageAsset(
    pageIndex: Int,
    buckets: List<TimelineBucket>,
    bucketAssets: Map<String, List<Asset>>
): Asset? {
    var offset = 0
    for (bucket in buckets) {
        if (pageIndex < offset + bucket.count) {
            val localIndex = pageIndex - offset
            return bucketAssets[bucket.timeBucket]?.getOrNull(localIndex)
        }
        offset += bucket.count
    }
    return null
}

private fun resolvePageBucket(
    pageIndex: Int,
    buckets: List<TimelineBucket>
): String? {
    var offset = 0
    for (bucket in buckets) {
        if (pageIndex < offset + bucket.count) return bucket.timeBucket
        offset += bucket.count
    }
    return null
}
