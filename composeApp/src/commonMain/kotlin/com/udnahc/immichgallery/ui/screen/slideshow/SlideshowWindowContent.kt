package com.udnahc.immichgallery.ui.screen.slideshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.SlideshowWindowRequest
import com.udnahc.immichgallery.domain.model.SlideshowWindowSource
import com.udnahc.immichgallery.ui.component.*
import com.udnahc.immichgallery.ui.screen.timeline.TimelinePhotoOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import immichgallery.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun SlideshowWindowContent(
    request: SlideshowWindowRequest,
    viewModel: SlideshowWindowViewModel,
    eligible: Boolean,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val windowLaunch = rememberSlideshowWindowLauncher(viewModel, request.stamp)
    CompositionLocalProvider(
        LocalAppActive provides (eligible && state != SlideshowWindowState.Closed),
        LocalSlideshowUsesAppForeground provides false
    ) {
        when (val current = state) {
            is SlideshowWindowState.Ready -> {
                val source = request.source
                if (source is SlideshowWindowSource.Timeline) {
                    TimelinePhotoOverlay(
                        timelineState = viewModel.timeline,
                        initialIndex = current.initialIndex,
                        apiKey = viewModel.apiKey,
                        getAssetFileName = viewModel::getFileName,
                        getAssetDetail = viewModel::getDetail,
                        getAssetStack = viewModel::getStack,
                        assetProvider = viewModel::assetsForBucket,
                        onBucketNeeded = { viewModel.requestBucket(it) },
                        onRetryBucket = { viewModel.requestBucket(it, retry = true) },
                        onPersonClick = null,
                        onDismiss = { _, _ -> onClose() },
                        autoStart = request.config,
                        slideshowWindowSource = source,
                        windowLaunch = windowLaunch
                    )
                } else {
                    StaticPhotoOverlay(
                        assets = current.assets,
                        initialIndex = current.initialIndex,
                        apiKey = viewModel.apiKey,
                        getAssetDetail = viewModel::getDetail,
                        getAssetStack = viewModel::getStack,
                        onPersonClick = null,
                        onDismiss = { onClose() },
                        autoStart = request.config,
                        slideshowWindowSource = source,
                        windowLaunch = windowLaunch
                    )
                }
            }
            SlideshowWindowState.Closed -> Unit
            else -> {
                PlatformBackHandler(enabled = true, onBack = onClose)
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(Dimens.largeSpacing),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
                    ) {
                        if (current == SlideshowWindowState.Loading) CircularProgressIndicator(color = Color.White)
                        val message = when ((current as? SlideshowWindowState.Failed)?.reason) {
                            SlideshowWindowFailure.LOAD -> Res.string.slideshow_window_failed
                            SlideshowWindowFailure.EMPTY -> Res.string.slideshow_window_empty
                            SlideshowWindowFailure.MISSING_ASSET -> Res.string.slideshow_window_missing_asset
                            SlideshowWindowFailure.CACHE_UNAVAILABLE -> Res.string.slideshow_window_cache_unavailable
                            SlideshowWindowFailure.ALL_VIDEOS -> Res.string.slideshow_all_videos
                            null -> Res.string.slideshow_window_loading
                        }
                        Text(stringResource(message), color = Color.White)
                        if (current is SlideshowWindowState.Failed && current.reason != SlideshowWindowFailure.EMPTY) {
                            Button(onClick = viewModel::retry) { Text(stringResource(Res.string.retry)) }
                        }
                        Button(onClick = onClose) { Text(stringResource(Res.string.slideshow_window_close)) }
                    }
                }
            }
        }
    }
}
