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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.model.nextSlideshowPage
import com.udnahc.immichgallery.ui.component.AssetDetailSheet
import com.udnahc.immichgallery.ui.component.AssetPage
import com.udnahc.immichgallery.ui.component.DetailBottomHandle
import com.udnahc.immichgallery.ui.component.DetailTopBarOverlay
import com.udnahc.immichgallery.ui.component.PhotoDragTransform
import com.udnahc.immichgallery.ui.component.SlideshowOptionsDialog
import com.udnahc.immichgallery.ui.component.StackAvailability
import com.udnahc.immichgallery.ui.component.StackSessionOverlay
import com.udnahc.immichgallery.ui.component.ViewerImagePrefetchCandidates
import com.udnahc.immichgallery.ui.component.rememberStackSessionController
import com.udnahc.immichgallery.ui.component.shouldAdvanceSlideshowAfterDelay
import com.udnahc.immichgallery.ui.util.DragToDismissState
import com.udnahc.immichgallery.ui.util.PhotoDismissMotion
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.desktopDetailShortcuts
import com.udnahc.immichgallery.ui.util.dragToDismiss
import com.udnahc.immichgallery.ui.util.rememberScreenWakeLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

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
    var showTopBar by remember { mutableStateOf(true) }
    var slideshowConfig by remember { mutableStateOf<SlideshowConfig?>(null) }
    var showSlideshowDialog by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    val stackController = rememberStackSessionController(getAssetStack)
    val stackSession = stackController.state.session
    val isCoveredByStack = stackSession != null
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val dismissBase: (String?, String?) -> Unit = { assetId, bucket ->
        stackController.close()
        latestOnDismiss(assetId, bucket)
    }
    val fileNameCache = remember { mutableStateMapOf<String, String>() }

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

    val wakeLock = rememberScreenWakeLock()
    LaunchedEffect(isSlideshow) {
        if (isSlideshow) wakeLock.acquire() else wakeLock.release()
    }
    DisposableEffect(Unit) { onDispose { wakeLock.release() } }

    val totalPages = state.pageIndex.totalPages

    if (totalPages == 0) {
        Box(Modifier.fillMaxSize().background(Color.Black))
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
        ).mapNotNull { page -> resolvePageAsset(page, state, assetProvider) }
    )

    // Bucket of the settled page — used by the dismiss callbacks to report
    // where the user landed. The bucket-load kick itself lives in the per-page
    // LaunchedEffect inside the pager body (covers pre-composed neighbor pages
    // too); no separate kick needed here.
    val currentBucketKey = resolvePageBucket(pagerState.settledPage, state)

    val currentAsset = resolvePageAsset(
        pagerState.settledPage, state, assetProvider
    )

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

    // Auto-advance slideshow. Keyed on settledPage so the timer resets whenever
    // the page changes — including manual left/right arrow advances.
    LaunchedEffect(slideshowConfig, pagerState.settledPage, totalPages, isCoveredByStack) {
        if (isCoveredByStack) return@LaunchedEffect
        val config = slideshowConfig ?: return@LaunchedEffect
        if (totalPages <= 1) return@LaunchedEffect
        val startingPage = pagerState.settledPage
        val startingTotalPages = totalPages
        delay(config.durationSeconds * 1000L)
        if (!shouldAdvanceSlideshowAfterDelay(
                startingConfig = config,
                currentConfig = slideshowConfig,
                startingPage = startingPage,
                currentSettledPage = pagerState.settledPage,
                startingPageCount = startingTotalPages,
                currentPageCount = latestState.pageIndex.totalPages,
                isCoveredByStack = stackController.state.session != null
            )
        ) {
            return@LaunchedEffect
        }
        val next = nextSlideshowPage(
            order = config.order,
            current = startingPage,
            total = startingTotalPages,
            forward = true,
        )
        pagerState.animateScrollToPage(next)
    }

    PlatformBackHandler(enabled = !isCoveredByStack, onBack = {
        dismissBase(currentAsset?.id, currentBucketKey)
    })

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
                    slideshowConfig = null
                    dismissBase(latestCurrentAssetId, latestCurrentBucketKey)
                },
                onOpenDetailSheet = { showDetailSheet = true },
            )
            // Swallow taps that fall in letterbox dead zones so they don't
            // reach the grid composed beneath the overlay.
            .pointerInput(Unit) { detectTapGestures { } }
            .desktopDetailShortcuts(
                enabled = !showDetailSheet && !showSlideshowDialog && !isCoveredByStack,
                onPrev = {
                    if (!pagerState.isScrollInProgress) {
                        val config = slideshowConfig
                        val target = if (config != null) {
                            nextSlideshowPage(
                                config.order,
                                pagerState.currentPage,
                                totalPages,
                                forward = false
                            )
                        } else {
                            (pagerState.currentPage - 1).coerceAtLeast(0)
                        }
                        scope.launch { pagerState.animateScrollToPage(target) }
                    }
                },
                onNext = {
                    if (!pagerState.isScrollInProgress) {
                        val config = slideshowConfig
                        val target = if (config != null) {
                            nextSlideshowPage(
                                config.order,
                                pagerState.currentPage,
                                totalPages,
                                forward = true
                            )
                        } else {
                            (pagerState.currentPage + 1).coerceAtMost(totalPages - 1)
                        }
                        scope.launch { pagerState.animateScrollToPage(target) }
                    }
                },
                onDismiss = {
                    slideshowConfig = null
                    dismissBase(latestCurrentAssetId, latestCurrentBucketKey)
                },
                onToggleSlideshow = {
                    if (isSlideshow) slideshowConfig = null
                    else if (totalPages > 1) showSlideshowDialog = true
                },
                onInfo = { showDetailSheet = true },
            )
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !isCoveredByStack,
            key = { page -> timelinePagerPageKey(page, state, assetProvider) }
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
                        isCurrentPage = isSettledPage && !isCoveredByStack,
                        isSlideshow = isSlideshow,
                        slideshowConfig = slideshowConfig,
                        onTap = onTap,
                        sharedTransitionScope = if (isSettledPage) sharedTransitionScope else null,
                        animatedVisibilityScope = if (isSettledPage) animatedVisibilityScope else null,
                        dragTransform = photoDragTransform,
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
            onBack = { dismissBase(currentAsset?.id, currentBucketKey) },
            onInfo = { showDetailSheet = true },
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
                    slideshowConfig = null
                    showSlideshowDialog = false
                    showDetailSheet = false
                    stackController.openSession(asset.id)
                }
            },
            onRetryStackCheck = currentAsset?.let { asset ->
                { stackController.retryPreflight(asset.id) }
            }
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
                    dismissBase(null, null)
                    onPersonClick(personId, personName)
                },
                onDismiss = { showDetailSheet = false }
            )
        }

        if (stackSession != null) {
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
                onRetry = stackController::retrySession
            )
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
