package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import com.udnahc.immichgallery.LocalAppActive
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.ui.component.AssetDetailSheet
import com.udnahc.immichgallery.ui.component.ViewerFilmstrip
import com.udnahc.immichgallery.ui.component.ViewerFilmstripControls
import com.udnahc.immichgallery.ui.component.AssetPage
import com.udnahc.immichgallery.ui.component.rememberSlideshowBinding
import com.udnahc.immichgallery.ui.component.BindSlideshowPager
import com.udnahc.immichgallery.ui.component.SlideshowFadeLayer
import com.udnahc.immichgallery.ui.component.SlideshowEmptyViewer
import com.udnahc.immichgallery.ui.component.SlideshowChrome
import com.udnahc.immichgallery.ui.component.DetailBottomHandle
import com.udnahc.immichgallery.ui.component.DetailTopBarOverlay
import com.udnahc.immichgallery.ui.component.PhotoDragTransform
import com.udnahc.immichgallery.ui.component.SlideshowOptionsDialog
import com.udnahc.immichgallery.ui.component.StackAvailability
import com.udnahc.immichgallery.ui.component.StackSessionOverlay
import com.udnahc.immichgallery.ui.component.ViewerImagePrefetchCandidates
import com.udnahc.immichgallery.ui.component.rememberStackSessionController
import com.udnahc.immichgallery.ui.util.LocalViewerFullscreen
import com.udnahc.immichgallery.ui.util.LocalViewerFullscreenSession
import com.udnahc.immichgallery.ui.util.rememberViewerFullscreenSession
import com.udnahc.immichgallery.ui.util.DragToDismissState
import com.udnahc.immichgallery.ui.util.PhotoDismissMotion
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.desktopDetailShortcuts
import com.udnahc.immichgallery.ui.util.dragToDismiss
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

private data class TimelinePagerObservation(
    val state: TimelineOverlayState,
    val projectionKey: TimelinePagerProjectionKey,
    val settledPage: Int,
    val isScrollInProgress: Boolean
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelinePhotoOverlay(
    timelineState: StateFlow<TimelineOverlayState>,
    initialIndex: Int,
    apiKey: String,
    getAssetFileName: suspend (assetId: String, fallback: String) -> Result<String>,
    getAssetDetail: suspend (String) -> Result<AssetDetail>,
    getAssetStack: suspend (String) -> Result<AssetStackLookup>,
    assetProvider: (String) -> List<Asset>?,
    onBucketNeeded: (timeBucket: String) -> Unit,
    onRetryBucket: (timeBucket: String) -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: (currentAssetId: String?, currentBucket: String?) -> Unit,
    onCurrentAssetChanged: (String) -> Unit = {},
    onStlTransitionActiveChanged: (Boolean) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onShowInTimeline: ((date: LocalDate, currentAssetId: String?, currentBucket: String?) -> Unit)? = null
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

    val state by timelineState.collectAsState()
    val latestState by rememberUpdatedState(state)
    val latestAssetProvider by rememberUpdatedState(assetProvider)
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
                sharedTransitionScope?.isTransitionActive != true,
        onBucketNeeded = onBucketNeeded, onRetryBucket = onRetryBucket
    )
    val playback by slideshow.uiState.collectAsState()
    val progress = remember(slideshow) { slideshow.state.map { it.motionProgress }.distinctUntilChanged() }.collectAsState(0f)
    val slideshowConfig = playback.config
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val dismissBase: (String?, String?) -> Unit = { assetId, bucket ->
        slideshow.send { it.stop() }
        stackController.close()
        fullscreenSession.close()
        latestOnDismiss(assetId, bucket)
    }
    val fileNameCache = remember { mutableStateMapOf<String, String>() }

    val isSlideshow = slideshowConfig != null
    DisposableEffect(fullscreenSession, isSlideshow, slideshowConfig?.fullscreen) {
        val lease = if (slideshowConfig?.fullscreen == true) fullscreenSession.enterSlideshow() else null
        onDispose { lease?.release() }
    }


    val onTap: () -> Unit = {
        if (isSlideshow) slideshow.send { it.toggleControls() } else showTopBar = !showTopBar
    }
    LaunchedEffect(isSlideshow) { if (!isSlideshow) showTopBar = true }
    LaunchedEffect(state) { slideshow.source { timelineSlideshowSource(state, assetProvider) } }

    val totalPages = state.pageIndex.totalPages

    if (totalPages == 0) {
        SlideshowEmptyViewer(slideshow, playback) { dismissBase(null, null) }
        return
    }

    val clampedInitial = initialIndex.coerceIn(0, totalPages - 1)

    val pagerState = rememberPagerState(
        initialPage = clampedInitial
    ) { totalPages }
    var retainedPagerAnchor by remember {
        mutableStateOf(
            captureTimelinePagerAnchor(
                page = clampedInitial,
                state = state,
                assetProvider = assetProvider
            )
        )
    }
    var reconciledProjectionKey by remember {
        mutableStateOf(timelinePagerProjectionKey(state))
    }

    // Force the pager to snap to the target page on first composition. Inside
    // SharedTransitionLayout's LookaheadScope, the pager's scroll offset can
    // briefly not reflect initialPage during the first measure pass, which makes
    // the detail-side page X appear to slide in from the right.
    LaunchedEffect(Unit) {
        if (pagerState.currentPage != clampedInitial) {
            pagerState.scrollToPage(clampedInitial)
        }
    }

    LaunchedEffect(totalPages) {
        if (pagerState.currentPage >= totalPages) {
            pagerState.scrollToPage((totalPages - 1).coerceAtLeast(0))
        }
    }

    val latestAssetChangedCallback by rememberUpdatedState(onCurrentAssetChanged)
    val latestOnBucketNeeded by rememberUpdatedState(onBucketNeeded)
    // One owner reconciles settled identity with changing Timeline projection.
    // A projection update is handled before the numeric settled page is
    // accepted, preventing insert/remove/materialization changes from silently
    // transferring the viewer to whichever asset inherited that page number.
    LaunchedEffect(pagerState) {
        snapshotFlow {
            TimelinePagerObservation(
                state = latestState,
                projectionKey = timelinePagerProjectionKey(latestState),
                settledPage = pagerState.settledPage,
                isScrollInProgress = pagerState.isScrollInProgress
            )
        }
            .distinctUntilChanged()
            .collect { observation ->
                if (observation.isScrollInProgress) return@collect
                val projectionChanged = observation.projectionKey != reconciledProjectionKey
                val retained = retainedPagerAnchor
                if (projectionChanged && retained != null) {
                    when (val resolution = reconcileTimelinePagerAnchor(
                        anchor = retained,
                        state = observation.state,
                        assetProvider = latestAssetProvider
                    )) {
                        is TimelinePagerAnchorResolution.Found -> {
                            if (pagerState.settledPage != resolution.pageIndex) {
                                pagerState.scrollToPage(resolution.pageIndex)
                            }
                            val changed = retainedPagerAnchor?.materializedAssetId() !=
                                    resolution.anchor.assetId
                            retainedPagerAnchor = resolution.anchor
                            if (changed) latestAssetChangedCallback(resolution.anchor.assetId)
                        }

                        is TimelinePagerAnchorResolution.Pending -> {
                            if (pagerState.settledPage != resolution.pageIndex) {
                                pagerState.scrollToPage(resolution.pageIndex)
                            }
                            retainedPagerAnchor = resolution.anchor
                            latestOnBucketNeeded(resolution.timeBucket)
                        }

                        TimelinePagerAnchorResolution.Empty -> {
                            retainedPagerAnchor = null
                        }
                    }
                    reconciledProjectionKey = observation.projectionKey
                    return@collect
                }

                captureTimelinePagerAnchor(
                    page = observation.settledPage,
                    state = observation.state,
                    assetProvider = latestAssetProvider
                )?.let { captured ->
                    val changed = captured is TimelinePagerAnchor.Materialized &&
                            retainedPagerAnchor?.materializedAssetId() != captured.assetId
                    retainedPagerAnchor = captured
                    if (changed) {
                        latestAssetChangedCallback(captured.assetId)
                    }
                }
                reconciledProjectionKey = observation.projectionKey
            }
    }

    // Neighboring unloaded buckets are skipped until materialized. The shared
    // owner filters videos, bounds requests to the viewport, and cancels work
    // displaced from the settled current ±1 neighborhood.
    ViewerImagePrefetchCandidates(
        assets = listOf(
            pagerState.settledPage - 1,
            pagerState.settledPage,
            pagerState.settledPage + 1
        ).mapNotNull { page -> resolvePageAsset(page, state, assetProvider) } + listOfNotNull(playback.upcomingAsset)
    )

    // Bucket of the settled page — used by the dismiss callbacks to report
    // where the user landed. The bucket-load kick itself lives in the per-page
    // LaunchedEffect inside the pager body (covers pre-composed neighbor pages
    // too); no separate kick needed here.
    val currentBucketKey = resolvePageBucket(pagerState.settledPage, state)
    val currentAsset = resolvePageAsset(
        pagerState.settledPage, state, assetProvider
    )
    val showDateInTimeline: ((LocalDate) -> Unit)? = onShowInTimeline?.let { showDate ->
        { date ->
            showDetailSheet = false
            showSlideshowDialog = false
            slideshow.send { it.stop() }
            stackController.dismissSession()
            stackController.close()
            fullscreenSession.close()
            showDate(date, currentAsset?.id, currentBucketKey)
        }
    }



    LaunchedEffect(pagerState.settledPage, currentAsset?.id) {
        stackController.onSettledAssetChanged(currentAsset?.id)
    }

    // `Modifier.pointerInput(enabled)` caches its block — any lambda captured
    // there freezes `currentAsset`/`currentBucketKey` at the recomposition
    // when the block was last launched (when `enabled` last flipped). On a
    // drag-to-dismiss after the user has paged deep into the overlay, that
    // stale capture reports the CLICKED photo's id and bucket instead of the
    // currently-viewed one — so the overlay return target lands on the wrong cell
    // and the shared-element exit has no source bounds. BackHandler wraps
    // its onBack in rememberUpdatedState internally, which is why back-button
    // dismiss works fine; we apply the same pattern here for parity.
    val latestCurrentAssetId by rememberUpdatedState(currentAsset?.id)
    val latestCurrentBucketKey by rememberUpdatedState(currentBucketKey)

    val slideshowTransition = BindSlideshowPager(slideshow, playback, pagerState, currentAsset)

    PlatformBackHandler(enabled = !isCoveredByStack, onBack = {
        if (isSlideshow) slideshow.send { it.stop() }
        else if (!fullscreenSession.exitFullscreen()) dismissBase(currentAsset?.id, currentBucketKey)
    })

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

    // Cache file names. Capture the asset id at launch so after the suspending
    // fetch returns, the cache write is keyed by the fetched id even if the
    // pager has advanced — structured cancellation makes the suspend throw on
    // key change anyway, but the explicit capture makes the invariant obvious.
    LaunchedEffect(currentAsset?.id) {
        val asset = currentAsset ?: return@LaunchedEffect
        val assetId = asset.id
        if (asset.fileName.isNotEmpty()) {
            fileNameCache[assetId] = asset.fileName
            return@LaunchedEffect
        }
        if (fileNameCache.containsKey(assetId)) return@LaunchedEffect
        getAssetFileName(assetId, asset.fileName).onSuccess { name ->
            fileNameCache[assetId] = name
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
    LaunchedEffect(currentAsset?.id) { isCurrentPageZoomed = false }

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
                    dismissBase(latestCurrentAssetId, latestCurrentBucketKey)
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
                    else if (!pagerState.isScrollInProgress) scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(totalPages - 1)) }
                },
                onDismiss = {
                    if (isSlideshow) slideshow.send { it.stop() } else if (!fullscreenSession.exitFullscreen()) dismissBase(latestCurrentAssetId, latestCurrentBucketKey)
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
        val normalFooterVisible = showTopBar && !isSlideshow && !isCoveredByStack && !showDetailSheet && !showSlideshowDialog &&
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
            key = { page ->
                timelinePagerPageKey(page, state, assetProvider).toSaveablePagerKey()
            }
        ) { page ->
            val asset = resolvePageAsset(page, state, assetProvider)
            val isSettledPage = pagerState.settledPage == page

            // Trigger loading for this page's bucket
            LaunchedEffect(page) {
                snapshotFlow {
                    resolvePageBucket(page, latestState)
                        ?.takeUnless { bucket -> latestAssetProvider(bucket) != null }
                }
                    .mapNotNull { it }
                    .distinctUntilChanged()
                    .collect { bucket -> latestOnBucketNeeded(bucket) }
            }

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
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        SlideshowFadeLayer(slideshowTransition)

        DetailTopBarOverlay(
            showTopBar = showTopBar && !isSlideshow && !isCoveredByStack,
            title = displayFileName,
            onBack = { dismissBase(currentAsset?.id, currentBucketKey) },
            onInfo = { showDetailSheet = true },
            onToggleFullscreen = if (fullscreen.isSupported) { { fullscreenSession.toggle() } } else null,
            isFullscreen = fullscreen.isFullscreen,
            onSlideshow = if (totalPages > 1) {
                { showSlideshowDialog = true }
            } else null,
            stackCount = currentAsset?.stackCount ?: 0,
            stackAvailability = currentAsset?.id
                ?.let(stackController.state::availability)
                ?: StackAvailability.Unknown,
            onStackMenuOpened = currentAsset?.let { asset ->
                { stackController.preflight(asset) }
            },
            onViewStack = currentAsset?.let { asset ->
                {
                    showSlideshowDialog = false
                    showDetailSheet = false
                    stackController.openSession(asset.id)
                }
            },
            onRetryStackCheck = currentAsset?.let { asset ->
                { stackController.retryPreflight(asset.id) }
            }
        )

        if (normalFooterVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .onSizeChanged { bottomChromeHeightPx = it.height }
            ) {
                if (filmstripExpanded) ViewerFilmstrip(
                    totalPages = totalPages, currentPage = pagerState.settledPage,
                    pageKey = { page -> timelineFilmstripPageKey(page, state) },
                    assetForPage = { page -> resolvePageAsset(page, state, assetProvider) },
                    onPageSelected = { page -> scope.launch { pagerState.scrollToPage(page) } },
                    onVisiblePagesChanged = { pages ->
                        pages.mapNotNull { resolvePageBucket(it, state) }.distinct()
                            .filter { assetProvider(it) == null }.forEach(onBucketNeeded)
                    },
                )
                ViewerFilmstripControls(totalPages = totalPages, currentPage = pagerState.settledPage,
                    expanded = filmstripExpanded, onExpandedChange = { filmstripExpanded = it })
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
                    dismissBase(null, null)
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
                        dismissBase(null, null)
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

private fun resolvePageAsset(
    pageIndex: Int,
    state: TimelineOverlayState,
    assetProvider: (String) -> List<Asset>?
): Asset? {
    val bucketIndex = state.pageIndex.pageToBucketIndex(pageIndex) ?: return null
    val bucket = state.buckets.getOrNull(bucketIndex) ?: return null
    val bucketStart = state.pageIndex.bucketStartPages.getOrNull(bucketIndex) ?: return null
    val localIndex = pageIndex - bucketStart
    return assetProvider(bucket.timeBucket)?.getOrNull(localIndex)
}

private fun resolvePageBucket(
    pageIndex: Int,
    state: TimelineOverlayState
): String? {
    val bucketIndex = state.pageIndex.pageToBucketIndex(pageIndex) ?: return null
    return state.buckets.getOrNull(bucketIndex)?.timeBucket
}
