package com.udnahc.immichgallery.ui.screen.timeline

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.ui.component.AssetDetailSheet
import com.udnahc.immichgallery.ui.component.AssetPage
import com.udnahc.immichgallery.ui.component.DetailBottomHandle
import com.udnahc.immichgallery.ui.component.DetailTopBarOverlay
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TimelinePhotoOverlay(
    timelineState: StateFlow<TimelineState>,
    initialIndex: Int,
    apiKey: String,
    getAssetFileNameUseCase: GetAssetFileNameUseCase,
    getAssetDetailUseCase: GetAssetDetailUseCase,
    onBucketNeeded: (timeBucket: String) -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: () -> Unit
) {
    PlatformBackHandler(enabled = true, onBack = onDismiss)

    val state by timelineState.collectAsState()
    var showTopBar by remember { mutableStateOf(true) }
    var showDetailSheet by remember { mutableStateOf(false) }
    val fileNameCache = remember { mutableStateMapOf<String, String>() }

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

    val currentAsset = resolvePageAsset(
        pagerState.settledPage, state.buckets, state.bucketAssets
    )

    // Trigger bucket loading for the current page
    val currentBucket = resolvePageBucket(pagerState.settledPage, state.buckets)
    LaunchedEffect(currentBucket) {
        if (currentBucket != null && !state.loadedBuckets.contains(currentBucket)) {
            onBucketNeeded(currentBucket)
        }
    }

    // Cache file names
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> page }
        ) { page ->
            val asset = resolvePageAsset(page, state.buckets, state.bucketAssets)
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
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        DetailTopBarOverlay(
            showTopBar = showTopBar,
            title = displayFileName,
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

        if (showDetailSheet && currentAsset != null) {
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
