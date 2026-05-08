package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.udnahc.immichgallery.ui.util.LocalPhotoBoundsTween
import com.udnahc.immichgallery.ui.util.PHOTO_TRANSITION_DURATION_MS
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.photoTransitionFadeIn
import com.udnahc.immichgallery.ui.util.photoTransitionFadeOut
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoOverlayHost(
    modifier: Modifier = Modifier,
    initialIndexKey: Any? = Unit,
    onOverlayActiveChange: (Boolean) -> Unit = {},
    resolveInitialIndex: suspend (assetId: String) -> Int?,
    prepareDismissSource: suspend (PhotoOverlayDismissContext) -> Unit = {},
    onActiveSourcePositioned: ((PhotoOverlaySourcePosition) -> Unit)? = null,
    content: @Composable SharedTransitionScope.(
        showOverlay: Boolean,
        transitionAssetId: String?,
        hiddenAssetId: String?,
        activeSourceGeneration: Int,
        onActiveSourcePositioned: ((PhotoOverlaySourcePosition) -> Unit)?,
        onPhotoClick: (String) -> Unit
    ) -> Unit,
    overlay: @Composable AnimatedVisibilityScope.(
        initialIndex: Int,
        onDismissHost: (PhotoOverlayDismissContext) -> Unit,
        onCurrentAssetChanged: (String) -> Unit,
        onStlTransitionActiveChanged: (Boolean) -> Unit,
        sharedTransitionScope: SharedTransitionScope
    ) -> Unit,
    chrome: @Composable BoxScope.(showOverlay: Boolean) -> Unit = {}
) {
    var selectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSelectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (selectedAssetId != null) lastSelectedAssetId = selectedAssetId

    // Remount SharedTransitionLayout on every open so same-asset re-taps do
    // not reuse stale shared-element bounds from the previous transition.
    var selectionEpoch by rememberSaveable { mutableStateOf(0) }
    var prevSelectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (prevSelectedAssetId == null && selectedAssetId != null) {
        selectionEpoch++
    }
    prevSelectedAssetId = selectedAssetId

    var currentViewedAssetId by remember { mutableStateOf<String?>(null) }
    var overlayInitialIndex by remember { mutableStateOf<Int?>(null) }
    var lastOverlayInitialIndex by remember { mutableStateOf<Int?>(null) }
    var transitionAssetIdForGrid by remember { mutableStateOf<String?>(null) }
    var dismissPrepActive by remember { mutableStateOf(false) }
    var dismissInProgress by remember { mutableStateOf(false) }
    var dismissGeneration by remember { mutableStateOf(0) }
    var activeSourceGeneration by remember { mutableStateOf(0) }
    LaunchedEffect(selectedAssetId, initialIndexKey) {
        val id = selectedAssetId
        if (id != null) {
            val resolvedIndex = resolveInitialIndex(id) ?: 0
            overlayInitialIndex = resolvedIndex
            lastOverlayInitialIndex = resolvedIndex
            currentViewedAssetId = id
        } else {
            currentViewedAssetId = null
            overlayInitialIndex = null
            dismissPrepActive = false
            dismissInProgress = false
        }
    }
    val overlayVisible = selectedAssetId != null && overlayInitialIndex != null
    // Dismiss must reveal the grid-side shared-element source in the same
    // composition that starts AnimatedVisibility exit; otherwise the overlay
    // image appears to pause while the grid cell is still hidden.
    val hiddenAssetIdForGrid = if (overlayVisible && !dismissPrepActive) currentViewedAssetId else null

    var stlTransitionActive by remember { mutableStateOf(false) }
    var overlayAnimActive by remember { mutableStateOf(false) }
    LaunchedEffect(selectedAssetId, selectionEpoch) {
        if (selectedAssetId == null && lastSelectedAssetId == null) {
            overlayAnimActive = false
            return@LaunchedEffect
        }
        overlayAnimActive = true
        delay(PHOTO_TRANSITION_DURATION_MS.toLong())
        snapshotFlow { stlTransitionActive }.first { !it }
        overlayAnimActive = false
    }
    LaunchedEffect(selectedAssetId, overlayVisible, overlayAnimActive) {
        if (selectedAssetId == null && !overlayVisible && !overlayAnimActive) {
            transitionAssetIdForGrid = null
        }
    }

    LaunchedEffect(overlayVisible) { onOverlayActiveChange(overlayVisible) }
    val dismissScope = rememberCoroutineScope()
    fun requestDismiss(context: PhotoOverlayDismissContext) {
        if (dismissInProgress) return
        val assetId = context.assetId ?: currentViewedAssetId ?: lastSelectedAssetId
        if (assetId == null) {
            overlayAnimActive = true
            overlayInitialIndex = null
            selectedAssetId = null
            return
        }
        dismissGeneration += 1
        activeSourceGeneration += 1
        val generation = dismissGeneration
        val enrichedContext = context.copy(
            assetId = assetId,
            sourceGeneration = activeSourceGeneration,
        )
        dismissInProgress = true
        dismissPrepActive = true
        overlayAnimActive = true
        transitionAssetIdForGrid = assetId
        dismissScope.launch {
            try {
                prepareDismissSource(enrichedContext)
            } finally {
                if (dismissGeneration == generation) {
                    overlayInitialIndex = null
                    selectedAssetId = null
                    dismissPrepActive = false
                    dismissInProgress = false
                }
            }
        }
    }

    PlatformBackHandler(enabled = overlayVisible) {
        requestDismiss(
            PhotoOverlayDismissContext(
                assetId = currentViewedAssetId ?: lastSelectedAssetId,
                mode = PhotoOverlayDismissMode.Back,
            )
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // During dismiss source prep the grid-side source is revealed while the
        // overlay is still visible. Keep the shared-element tween off for that
        // overlap so the detail node does not animate back to its steady
        // centered bounds before the actual exit starts.
        val boundsTweenActive = overlayAnimActive && !dismissPrepActive
        CompositionLocalProvider(LocalPhotoBoundsTween provides boundsTweenActive) {
            androidx.compose.runtime.key(selectionEpoch) {
                SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                    content(
                        overlayVisible,
                        transitionAssetIdForGrid,
                        hiddenAssetIdForGrid,
                        activeSourceGeneration,
                        onActiveSourcePositioned,
                        remember {
                            { id: String ->
                                overlayInitialIndex = null
                                overlayAnimActive = true
                                dismissPrepActive = false
                                dismissInProgress = false
                                transitionAssetIdForGrid = id
                                selectedAssetId = id
                            }
                        }
                    )

                    AnimatedVisibility(
                        visible = overlayVisible,
                        enter = photoTransitionFadeIn,
                        exit = photoTransitionFadeOut,
                    ) {
                        lastSelectedAssetId ?: return@AnimatedVisibility
                        overlay(
                            overlayInitialIndex ?: lastOverlayInitialIndex ?: 0,
                            ::requestDismiss,
                            { id ->
                                if (overlayVisible && !dismissInProgress) {
                                    currentViewedAssetId = id
                                    transitionAssetIdForGrid = id
                                }
                            },
                            { active -> stlTransitionActive = active },
                            this@SharedTransitionLayout
                        )
                    }
                }
            }
        }

        chrome(overlayVisible)
    }
}
