package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
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
    getAssetsForBucket: suspend (timeBucket: String) -> List<Asset>,
    onBucketNeeded: (timeBucket: String) -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: (currentAssetId: String?, currentBucket: String?) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val state by timelineState.collectAsState()
    var showTopBar by remember { mutableStateOf(true) }
    val onTap = remember { { showTopBar = !showTopBar } }
    var showDetailSheet by remember { mutableStateOf(false) }
    val fileNameCache = remember { mutableStateMapOf<String, String>() }

    // Cache loaded bucket assets for the pager
    val bucketAssetsCache = remember { mutableStateMapOf<String, List<Asset>>() }

    val totalPages = remember(state.buckets) {
        state.buckets.sumOf { it.count }
    }

    if (totalPages == 0) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, totalPages - 1)
    ) { totalPages }

    // Load bucket assets into local cache as needed
    val currentBucketKey = resolvePageBucket(pagerState.settledPage, state.buckets)
    LaunchedEffect(currentBucketKey) {
        if (currentBucketKey != null && !bucketAssetsCache.containsKey(currentBucketKey)) {
            onBucketNeeded(currentBucketKey)
            val assets = getAssetsForBucket(currentBucketKey)
            if (assets.isNotEmpty()) {
                bucketAssetsCache[currentBucketKey] = assets
            }
        }
    }

    val currentAsset = resolvePageAsset(
        pagerState.settledPage, state.buckets, bucketAssetsCache
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

    val displayFileName = currentAsset?.let {
        it.fileName.ifEmpty { fileNameCache[it.id] ?: "" }
    } ?: ""

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
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> page }
        ) { page ->
            val asset = resolvePageAsset(page, state.buckets, bucketAssetsCache)
            val isSettledPage = pagerState.settledPage == page

            // Trigger loading for this page's bucket
            LaunchedEffect(page) {
                val bucket = resolvePageBucket(page, state.buckets)
                if (bucket != null && !bucketAssetsCache.containsKey(bucket)) {
                    onBucketNeeded(bucket)
                    val assets = getAssetsForBucket(bucket)
                    if (assets.isNotEmpty()) {
                        bucketAssetsCache[bucket] = assets
                    }
                }
            }

            val transformForPage = if (isSettledPage) {
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
