package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import com.udnahc.immichgallery.LocalAppActive
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.LocalViewerFullscreen
import com.udnahc.immichgallery.ui.util.LocalViewerFullscreenSession
import com.udnahc.immichgallery.ui.util.rememberViewerFullscreenSession
import com.udnahc.immichgallery.ui.util.DragToDismissState
import com.udnahc.immichgallery.ui.util.PhotoDismissMotion
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.desktopDetailShortcuts
import com.udnahc.immichgallery.ui.util.dragToDismiss
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.asset_position
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private const val PAGE_INDICATOR_BACKGROUND_ALPHA = 0.45f
private const val INACTIVE_PAGE_DOT_ALPHA = 0.55f

internal fun allPageDotsFit(
    totalPages: Int,
    availableWidthPx: Int,
    density: Density
): Boolean {
    if (totalPages <= 0 || availableWidthPx <= 0) {
        return false
    }
    val dotSlotPx = with(density) { Dimens.pageIndicatorActiveDotSize.roundToPx() }.toLong()
    val gapPx = with(density) { Dimens.smallSpacing.roundToPx() }.toLong()
    val paddingEdgePx = with(density) { Dimens.mediumSpacing.roundToPx() }.toLong()

    val afterPaddingPx = availableWidthPx.toLong() - paddingEdgePx * 2L
    if (afterPaddingPx < 0L) return false
    val afterDotsPx = afterPaddingPx - dotSlotPx * totalPages.toLong()
    if (afterDotsPx < 0L) return false
    return gapPx * (totalPages - 1).toLong() <= afterDotsPx
}

internal fun pageIndicatorPosition(
    currentPage: Int,
    totalPages: Int,
    currentPageOffsetFraction: Float
): Float {
    if (totalPages <= 0) return 0f
    val offset = currentPageOffsetFraction.takeIf(Float::isFinite) ?: 0f
    return (currentPage.toFloat() + offset).coerceIn(0f, (totalPages - 1).toFloat())
}

internal fun pageDotActiveness(
    page: Int,
    totalPages: Int,
    pagePosition: Float
): Float {
    if (page !in 0 until totalPages || !pagePosition.isFinite()) return 0f
    val clampedPosition = pagePosition.coerceIn(0f, (totalPages - 1).toFloat())
    return (1f - abs(page - clampedPosition)).coerceIn(0f, 1f)
}

internal fun pageIndicatorProgress(
    totalPages: Int,
    pagePosition: Float
): Float {
    if (totalPages <= 0 || !pagePosition.isFinite()) return 0f
    val clampedPosition = pagePosition.coerceIn(0f, (totalPages - 1).toFloat())
    return ((clampedPosition + 1f) / totalPages).coerceIn(0f, 1f)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StaticPhotoOverlay(
    assets: List<Asset>,
    initialIndex: Int,
    apiKey: String,
    getAssetDetail: suspend (String) -> Result<AssetDetail>,
    getAssetStack: suspend (String) -> Result<AssetStackLookup>,
    sharedElementAssetId: String? = null,
    stackBrowsingEnabled: Boolean = true,
    showPageIndicator: Boolean = false,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: (currentAssetId: String?) -> Unit,
    onCurrentAssetChanged: (String) -> Unit = {},
    onStlTransitionActiveChanged: (Boolean) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onShowInTimeline: ((date: LocalDate, currentAssetId: String?) -> Unit)? = null
) {
    // Forward the STL's actual animation-active state to the screen so the
    // screen can flip `overlayAnimActive` false only when the bounds animation
    // has truly completed — instead of after a fixed delay that can truncate
    // really-tall-image animations. distinctUntilChanged prevents duplicate
    // callback fires when snapshot observers emit the same value.
    val latestStlCallback by rememberUpdatedState(onStlTransitionActiveChanged)
    LaunchedEffect(sharedTransitionScope) {
        snapshotFlow { sharedTransitionScope?.isTransitionActive ?: false }
            .distinctUntilChanged()
            .collect { latestStlCallback(it) }
    }

    val fullscreen = LocalViewerFullscreen.current
    val fullscreenSession = rememberViewerFullscreenSession()
    var filmstripExpanded by remember { mutableStateOf(false) }
    var showTopBar by remember { mutableStateOf(true) }
    var showSlideshowDialog by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    val stackController = rememberStackSessionController(getAssetStack)
    val stackSession = stackController.state.session
    val isCoveredByStack = stackSession != null
    val slideshow = rememberSlideshowBinding(
        environmentActive = fullscreen.isWindowVisibleAndNotMinimized && LocalAppActive.current && !isCoveredByStack && !showDetailSheet && !showSlideshowDialog &&
                sharedTransitionScope?.isTransitionActive != true
    )
    val playback by slideshow.uiState.collectAsState()
    val progress = remember(slideshow) { slideshow.state.map { it.motionProgress }.distinctUntilChanged() }.collectAsState(0f)
    val slideshowConfig = playback.config
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val dismissBase: (String?) -> Unit = { assetId ->
        slideshow.send { it.stop() }
        stackController.close()
        fullscreenSession.close()
        latestOnDismiss(assetId)
    }

    val isSlideshow = slideshowConfig != null
    DisposableEffect(fullscreenSession, isSlideshow, slideshowConfig?.fullscreen) {
        val lease = if (slideshowConfig?.fullscreen == true) fullscreenSession.enterSlideshow() else null
        onDispose { lease?.release() }
    }


    val onTap: () -> Unit = {
        if (isSlideshow) slideshow.send { it.toggleControls() } else showTopBar = !showTopBar
    }
    LaunchedEffect(isSlideshow) { if (!isSlideshow) showTopBar = true }
    LaunchedEffect(assets) { slideshow.source { staticSlideshowSource(assets) } }

    if (assets.isEmpty()) {
        SlideshowEmptyViewer(slideshow, playback) { dismissBase(null) }
        return
    }

    val clampedInitial = initialIndex.coerceIn(0, (assets.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = clampedInitial
    ) { assets.size }
    val latestAssets by rememberUpdatedState(assets)
    val currentAsset = assets.getOrNull(pagerState.settledPage)
    val showDateInTimeline: ((LocalDate) -> Unit)? = onShowInTimeline?.let { showDate ->
        { date ->
            showDetailSheet = false
            showSlideshowDialog = false
            slideshow.send { it.stop() }
            stackController.dismissSession()
            stackController.close()
            fullscreenSession.close()
            showDate(date, currentAsset?.id)
        }
    }


    LaunchedEffect(currentAsset?.id) {
        stackController.onSettledAssetChanged(currentAsset?.id)
    }

    // Force pager to snap to initialPage on first composition. In LookaheadScope
    // from SharedTransitionLayout, the pager's scroll offset can transiently be 0
    // before snapping, producing a visible slide-in from the right.
    LaunchedEffect(Unit) {
        if (pagerState.currentPage != clampedInitial) {
            pagerState.scrollToPage(clampedInitial)
        }
    }

    LaunchedEffect(assets.size) {
        if (assets.isNotEmpty() && pagerState.currentPage >= assets.size) {
            pagerState.scrollToPage(assets.lastIndex)
        }
    }

    // Report the currently-viewed asset to the parent so the grid can hide it.
    // Keep the collector stable, but read the latest asset list so Room/search
    // updates don't leave the grid hiding or prefetching an old page.
    val latestAssetChangedCallback by rememberUpdatedState(onCurrentAssetChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { latestAssets.getOrNull(pagerState.settledPage)?.id }
            .mapNotNull { it }
            .distinctUntilChanged()
            .collect { latestAssetChangedCallback(it) }
    }

    ViewerImagePrefetch(assets = assets, settledPage = pagerState.settledPage, upcoming = playback.upcomingAsset)

    val slideshowTransition = BindSlideshowPager(slideshow, playback, pagerState, currentAsset)

    PlatformBackHandler(enabled = !isCoveredByStack, onBack = {
        if (isSlideshow) slideshow.send { it.stop() }
        else if (!fullscreenSession.exitFullscreen()) dismissBase(assets.getOrNull(pagerState.settledPage)?.id)
    })

    // Slideshow options dialog
    if (showSlideshowDialog) {
        SlideshowOptionsDialog(
            onConfirm = { config ->
                showSlideshowDialog = false
                slideshow.send { it.start(config, pagerState.settledPage) }
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
    val dragState = remember(scope, dismissThresholdPx, flickVelocityPx) {
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

    val gestureEnabled = !showDetailSheet && !isSlideshow && !isCoveredByStack
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
                    slideshow.send { it.stop() }
                    dismissBase(assets.getOrNull(pagerState.settledPage)?.id)
                },
                onOpenDetailSheet = { showDetailSheet = true },
            )
            // Swallow taps that fall in letterbox dead zones so they don't
            // reach the grid composed beneath the overlay.
            .pointerInput(isSlideshow) { detectTapGestures { if (isSlideshow) onTap() } }
            .desktopDetailShortcuts(
                enabled = !showDetailSheet && !showSlideshowDialog && !isCoveredByStack,
                slideshowActive = isSlideshow,
                onPrev = {
                    if (isSlideshow) slideshow.send { it.previous() }
                    else if (!pagerState.isScrollInProgress) scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                },
                onNext = {
                    if (isSlideshow) slideshow.send { it.next() }
                    else if (!pagerState.isScrollInProgress) scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(assets.lastIndex)) }
                },
                onDismiss = {
                    if (isSlideshow) slideshow.send { it.stop() } else if (!fullscreenSession.exitFullscreen()) dismissBase(assets.getOrNull(pagerState.settledPage)?.id)
                },
                onToggleSlideshow = {
                    if (isSlideshow) slideshow.send { it.togglePause() }
                    else showSlideshowDialog = true
                },
                onInfo = { showDetailSheet = true },
                onToggleFullscreen = if (fullscreen.isSupported) { { fullscreenSession.toggle() } } else null,
            )
    ) {
        var bottomChromeHeightPx by remember { mutableIntStateOf(0) }
        val normalFooterVisible = !isSlideshow && !isCoveredByStack && !showDetailSheet && !showSlideshowDialog &&
                !dragState.isActive && sharedTransitionScope?.isTransitionActive != true
        val bottomChromePadding = if (normalFooterVisible) {
            with(density) { bottomChromeHeightPx.toDp() }
        } else {
            0.dp
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !isCoveredByStack && !isSlideshow,
            key = { page -> assets.getOrNull(page)?.id ?: "missing_$page" }
        ) { page ->
            val asset = assets.getOrNull(page)
            val isSettledPage = pagerState.settledPage == page
            val photoDragTransform = if (isSettledPage && dragState.isActive) {
                PhotoDragTransform(
                    active = true,
                    scale = dragState.scale,
                    translation = dragState.translation,
                    startPosition = dragState.startPosition,
                    dismissCommitted = dragState.dismissCommitted,
                )
            } else PhotoDragTransform.Idle
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (asset != null) {
                    AssetPage(
                        asset = asset,
                        sharedElementAssetId = sharedElementAssetId ?: asset.id,
                        apiKey = apiKey,
                        isCurrentPage = isSettledPage && !isCoveredByStack && !showDetailSheet && !showSlideshowDialog && LocalAppActive.current,
                        isSlideshow = isSlideshow,
                        slideshowConfig = slideshowConfig,
                        playback = playback.takeIf { isSettledPage && it.asset?.id == asset.id },
                        slideshowProgress = { progress.value },
                        onSlideshowEvent = { request, event -> slideshow.mediaEvent(request, event) },
                        onTap = onTap,
                        sharedTransitionScope = if (isSettledPage) sharedTransitionScope else null,
                        animatedVisibilityScope = if (isSettledPage) animatedVisibilityScope else null,
                        dragTransform = photoDragTransform,
                        isDragging = isSettledPage && dragState.isActive,
                        onZoomStateChanged = { zoomed -> isCurrentPageZoomed = zoomed },
                        bottomChromePadding = bottomChromePadding,
                    )
                }
            }
        }

        SlideshowFadeLayer(slideshowTransition)

        DetailTopBarOverlay(
            showTopBar = showTopBar && !isSlideshow && !isCoveredByStack,
            title = currentAsset?.fileName.orEmpty(),
            onBack = { dismissBase(currentAsset?.id) },
            onInfo = { showDetailSheet = true },
            onToggleFullscreen = if (fullscreen.isSupported) { { fullscreenSession.toggle() } } else null,
            isFullscreen = fullscreen.isFullscreen,
            onSlideshow = if (assets.size > 1) {
                { showSlideshowDialog = true }
            } else null,
            stackCount = currentAsset?.stackCount ?: 0,
            stackAvailability = currentAsset?.id
                ?.let(stackController.state::availability)
                ?: StackAvailability.Unknown,
            onStackMenuOpened = if (stackBrowsingEnabled && currentAsset != null) {
                { stackController.preflight(currentAsset) }
            } else null,
            onViewStack = if (stackBrowsingEnabled && currentAsset != null) {
                {
                    showSlideshowDialog = false
                    showDetailSheet = false
                    stackController.openSession(currentAsset.id)
                }
            } else null,
            onRetryStackCheck = if (stackBrowsingEnabled && currentAsset != null) {
                { stackController.retryPreflight(currentAsset.id) }
            } else null
        )

        if (normalFooterVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .onSizeChanged { bottomChromeHeightPx = it.height }
            ) {
                if (showPageIndicator && assets.size > 1) PhotoPageIndicator(
                    currentPage = pagerState.currentPage,
                    currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                    settledPage = pagerState.settledPage, totalPages = assets.size,
                    modifier = Modifier.padding(vertical = Dimens.smallSpacing))
                if (showTopBar) {
                    if (filmstripExpanded) ViewerFilmstrip(
                        totalPages = assets.size, currentPage = pagerState.settledPage,
                        pageKey = { page -> assets[page].id },
                        assetForPage = { page -> assets.getOrNull(page) },
                        onPageSelected = { page -> scope.launch { pagerState.scrollToPage(page) } },
                    )
                    ViewerFilmstripControls(totalPages = assets.size, currentPage = pagerState.settledPage,
                        expanded = filmstripExpanded, onExpandedChange = { filmstripExpanded = it }, showPosition = !showPageIndicator)
                }
                DetailBottomHandle(visible = showTopBar, onClick = { showDetailSheet = true })
            }
        }

        if (isSlideshow && !isCoveredByStack) {
            SlideshowChrome(slideshow, playback, getAssetDetail,
                onInfo = { showDetailSheet = true }, modifier = Modifier.align(Alignment.BottomCenter), fullscreenSession = fullscreenSession)
        }

        if (showDetailSheet && currentAsset != null) {
            AssetDetailSheet(
                assetId = currentAsset.id,
                getAssetDetail = getAssetDetail,
                onPersonClick = { personId, personName ->
                    showDetailSheet = false
                    dismissBase(currentAsset.id)
                    onPersonClick(personId, personName)
                },
                captureDateTime = currentAsset.createdAt,
                onShowInTimeline = showDateInTimeline,
                onDismiss = { showDetailSheet = false }
            )
        }

        if (stackSession != null) {
            CompositionLocalProvider(LocalViewerFullscreenSession provides fullscreenSession) {
                StackSessionOverlay(
                    session = stackSession,
                    apiKey = apiKey,
                    getAssetDetail = getAssetDetail,
                    getAssetStack = getAssetStack,
                    onPersonClick = { personId, personName ->
                        stackController.dismissSession()
                        dismissBase(currentAsset?.id)
                        onPersonClick(personId, personName)
                    },
                    onDismiss = stackController::dismissSession,
                    onRetry = stackController::retrySession,
                    onShowInTimeline = showDateInTimeline
                )
            }
        }
    }
}

@Composable
private fun PhotoPageIndicator(
    currentPage: Int,
    currentPageOffsetFraction: Float,
    settledPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val clampedSettledPage = settledPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    val pagePosition = pageIndicatorPosition(
        currentPage = currentPage,
        totalPages = totalPages,
        currentPageOffsetFraction = currentPageOffsetFraction
    )
    val positionDescription = stringResource(
        Res.string.asset_position,
        clampedSettledPage + 1,
        totalPages
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        if (allPageDotsFit(totalPages, constraints.maxWidth, density)) {
            Row(
                modifier = Modifier
                    .semantics { contentDescription = positionDescription }
                    .background(
                        color = Color.Black.copy(alpha = PAGE_INDICATOR_BACKGROUND_ALPHA),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(
                        horizontal = Dimens.mediumSpacing,
                        vertical = Dimens.smallSpacing
                    ),
                horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(totalPages) { page ->
                    val activeness = pageDotActiveness(page, totalPages, pagePosition)
                    val dotSize = lerp(
                        Dimens.pageIndicatorDotSize,
                        Dimens.pageIndicatorActiveDotSize,
                        activeness
                    )
                    Box(
                        modifier = Modifier.size(Dimens.pageIndicatorActiveDotSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(dotSize)
                                .background(
                                    color = Color.White.copy(
                                        alpha = INACTIVE_PAGE_DOT_ALPHA +
                                            (1f - INACTIVE_PAGE_DOT_ALPHA) * activeness
                                    ),
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }
        } else {
            val progress = pageIndicatorProgress(totalPages, pagePosition)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = positionDescription }
                    .background(
                        color = Color.Black.copy(alpha = PAGE_INDICATOR_BACKGROUND_ALPHA),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(
                        horizontal = Dimens.mediumSpacing,
                        vertical = Dimens.smallSpacing
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.pageIndicatorProgressHeight)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = INACTIVE_PAGE_DOT_ALPHA))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(Color.White)
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val dividerWidth = Dimens.pageIndicatorSegmentDividerWidth.toPx()
                        repeat(totalPages - 1) { index ->
                            val x = size.width * (index + 1) / totalPages
                            drawLine(
                                color = Color.Black,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = dividerWidth
                            )
                        }
                    }
                }
            }
        }
    }
}
