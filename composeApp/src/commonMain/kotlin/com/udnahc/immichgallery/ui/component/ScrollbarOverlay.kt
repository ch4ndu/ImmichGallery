package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridLayoutInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.ui.screen.timeline.YearMarker
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.excludeFromSystemGestures
import com.udnahc.immichgallery.ui.util.rememberHapticFeedback
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.ic_scroll_arrows
import immichgallery.composeapp.generated.resources.scroll_drag
import immichgallery.composeapp.generated.resources.scrollbar_thumb
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val SCROLL_FRACTION_STEP = 0.005f
private const val MIN_ITEMS_FOR_SCROLLBAR = 10
private const val YEAR_MARKER_EDGE_PADDING = 24f
private const val YEAR_MARKER_MIN_SPACING_DP = 28f
private const val HANDLE_EDGE_PADDING = 8f
private const val BUBBLE_ALPHA = 0.9f

@Composable
fun ScrollbarOverlay(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    topPadding: Dp,
    bottomPadding: Dp,
    estimatedItemCount: Int? = null,
    scrollFractionProvider: (() -> Float)? = null,
    onScrollToFraction: ((Float) -> Unit)? = null,
    onDragStarted: () -> Unit = {},
    onDragStopped: (Float) -> Unit = {},
    labelProvider: ((Float) -> String?)? = null,
    yearMarkers: List<YearMarker> = emptyList(),
    content: @Composable () -> Unit
) {
    val scrollFraction by remember {
        derivedStateOf {
            val raw = scrollFractionProvider?.invoke()
                ?: listState.layoutInfo.scrollFraction(estimatedItemCount)
            (raw / SCROLL_FRACTION_STEP).toInt() * SCROLL_FRACTION_STEP
        }
    }

    val totalItems by remember(estimatedItemCount) {
        derivedStateOf { estimatedItemCount ?: listState.layoutInfo.totalItemsCount }
    }
    val showScrollbar by remember(estimatedItemCount) {
        derivedStateOf { (estimatedItemCount ?: listState.layoutInfo.totalItemsCount) >= MIN_ITEMS_FOR_SCROLLBAR }
    }

    val coroutineScope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    ScrollbarLayout(
        scrollFraction = scrollFraction,
        showScrollbar = showScrollbar,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        labelProvider = labelProvider,
        yearMarkers = yearMarkers,
        onDragFraction = onScrollToFraction ?: { fraction ->
            val targetIndex = dragFractionToIndex(fraction, totalItems)
            scrollJob?.cancel()
            scrollJob = coroutineScope.launch { listState.scrollToItem(targetIndex) }
            Unit
        },
        onDragStarted = onDragStarted,
        onDragStopped = onDragStopped,
        modifier = modifier,
        content = content
    )
}

@Composable
fun ScrollbarOverlay(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    topPadding: Dp,
    bottomPadding: Dp,
    labelProvider: ((Float) -> String?)? = null,
    yearMarkers: List<YearMarker> = emptyList(),
    content: @Composable () -> Unit
) {
    val scrollFraction by remember {
        derivedStateOf {
            val fraction = gridState.layoutInfo.scrollFraction()
            (fraction / SCROLL_FRACTION_STEP).toInt() * SCROLL_FRACTION_STEP
        }
    }

    val totalItems by remember { derivedStateOf { gridState.layoutInfo.totalItemsCount } }
    val showScrollbar by remember { derivedStateOf { totalItems >= MIN_ITEMS_FOR_SCROLLBAR } }

    val coroutineScope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    ScrollbarLayout(
        scrollFraction = scrollFraction,
        showScrollbar = showScrollbar,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        labelProvider = labelProvider,
        yearMarkers = yearMarkers,
        onDragFraction = { fraction ->
            val targetIndex = dragFractionToIndex(fraction, totalItems)
            scrollJob?.cancel()
            scrollJob = coroutineScope.launch { gridState.scrollToItem(targetIndex) }
        },
        onDragStarted = {},
        onDragStopped = {},
        modifier = modifier,
        content = content
    )
}

@Composable
fun ScrollbarOverlay(
    staggeredGridState: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    topPadding: Dp,
    bottomPadding: Dp,
    labelProvider: ((Float) -> String?)? = null,
    yearMarkers: List<YearMarker> = emptyList(),
    content: @Composable () -> Unit
) {
    val scrollFraction by remember {
        derivedStateOf {
            val fraction = staggeredGridState.layoutInfo.scrollFraction()
            (fraction / SCROLL_FRACTION_STEP).toInt() * SCROLL_FRACTION_STEP
        }
    }

    val totalItems by remember { derivedStateOf { staggeredGridState.layoutInfo.totalItemsCount } }
    val showScrollbar by remember { derivedStateOf { totalItems >= MIN_ITEMS_FOR_SCROLLBAR } }

    val coroutineScope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    ScrollbarLayout(
        scrollFraction = scrollFraction,
        showScrollbar = showScrollbar,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        labelProvider = labelProvider,
        yearMarkers = yearMarkers,
        onDragFraction = { fraction ->
            val targetIndex = dragFractionToIndex(fraction, totalItems)
            scrollJob?.cancel()
            scrollJob = coroutineScope.launch { staggeredGridState.scrollToItem(targetIndex) }
        },
        onDragStarted = {},
        onDragStopped = {},
        modifier = modifier,
        content = content
    )
}

@Composable
private fun ScrollbarLayout(
    scrollFraction: Float,
    showScrollbar: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    labelProvider: ((Float) -> String?)?,
    yearMarkers: List<YearMarker>,
    onDragFraction: (Float) -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()

        if (showScrollbar) {
            ScrollbarHandle(
                scrollFraction = scrollFraction,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
                labelProvider = labelProvider,
                yearMarkers = yearMarkers,
                onDragFraction = onDragFraction,
                onDragStarted = onDragStarted,
                onDragStopped = onDragStopped,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun ScrollbarHandle(
    scrollFraction: Float,
    topPadding: Dp,
    bottomPadding: Dp,
    labelProvider: ((Float) -> String?)?,
    yearMarkers: List<YearMarker>,
    onDragFraction: (Float) -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptics = rememberHapticFeedback()
    val thumbDescription = stringResource(Res.string.scrollbar_thumb)
    val dragDescription = stringResource(Res.string.scroll_drag)

    var trackHeightPx by remember { mutableStateOf(0) }
    // Pre-compute pixel values once (stable across recompositions for same Dp inputs)
    val topPaddingPx = remember(topPadding) { with(density) { topPadding.toPx() } }
    val bottomPaddingPx = remember(bottomPadding) { with(density) { bottomPadding.toPx() } }
    val handleSizePx = remember { with(density) { Dimens.scrollbarHandleSize.toPx() } }
    val handleEdgePaddingPx = remember { with(density) { HANDLE_EDGE_PADDING.dp.toPx() } }
    val yearMarkerEdgePaddingPx = remember { with(density) { YEAR_MARKER_EDGE_PADDING.dp.toPx() } }
    val handleClipOffsetPx =
        remember { with(density) { Dimens.scrollbarHandleClipOffset.toPx() }.toInt() }
    val bubbleVerticalOffsetPx =
        remember { with(density) { Dimens.scrollbarBubbleVerticalOffset.toPx() }.toInt() }

    // Drag state
    var dragFraction by remember { mutableFloatStateOf(scrollFraction) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(scrollFraction, isDragging) {
        if (!isDragging) dragFraction = scrollFraction
    }

    val currentFraction = if (isDragging) dragFraction else scrollFraction

    // Handle position
    val handleTop = topPaddingPx + handleEdgePaddingPx
    val handleBottom = bottomPaddingPx + handleEdgePaddingPx
    val rawHandleOffsetPx =
        remember(trackHeightPx, currentFraction, handleSizePx, handleTop, handleBottom) {
            val availableTrack = trackHeightPx - handleSizePx - handleTop - handleBottom
            val maxOffset = (trackHeightPx - handleSizePx - handleBottom).coerceAtLeast(handleTop)
            (handleTop + availableTrack * currentFraction).coerceIn(handleTop, maxOffset).toInt()
        }
    // Use Animatable so we can snapTo (instant) on first layout and animateTo (spring) on scroll
    val handleAnimatable = remember { Animatable(0, Int.VectorConverter) }
    var hasSnappedToLayout by remember { mutableStateOf(false) }
    LaunchedEffect(rawHandleOffsetPx, trackHeightPx) {
        if (trackHeightPx == 0) return@LaunchedEffect
        if (!hasSnappedToLayout) {
            // First valid layout — snap instantly, no animation
            handleAnimatable.snapTo(rawHandleOffsetPx)
            hasSnappedToLayout = true
        } else if (isDragging) {
            handleAnimatable.snapTo(rawHandleOffsetPx)
        } else {
            handleAnimatable.animateTo(
                rawHandleOffsetPx,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }
    val handleOffsetPx = if (isDragging) rawHandleOffsetPx else handleAnimatable.value

    // Label + haptics
    val label = if (isDragging && labelProvider != null) labelProvider(currentFraction) else null
    var previousLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(label) {
        if (isDragging && label != null && label != previousLabel && previousLabel != null) {
            haptics.performTick()
        }
        previousLabel = label
    }

    val draggableState = rememberDraggableState { delta ->
        val availableTrack = trackHeightPx - handleSizePx - handleTop - handleBottom
        if (availableTrack > 0) {
            val currentOffset = availableTrack * dragFraction
            val newOffset = (currentOffset + delta).coerceIn(0f, availableTrack)
            dragFraction = newOffset / availableTrack
            onDragFraction(dragFraction)
        }
    }

    val handleColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val handleIconColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bubbleBackground = MaterialTheme.colorScheme.inverseSurface.copy(alpha = BUBBLE_ALPHA)
    val bubbleTextColor = MaterialTheme.colorScheme.inverseOnSurface
    val yearLabelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Filter out overlapping year markers — skip markers too close to the previous one
    val yearMarkerMinSpacingPx = remember { with(density) { YEAR_MARKER_MIN_SPACING_DP.dp.toPx() } }
    val visibleMarkers = remember(
        yearMarkers,
        trackHeightPx,
        topPaddingPx,
        bottomPaddingPx,
        yearMarkerEdgePaddingPx
    ) {
        if (trackHeightPx == 0) return@remember yearMarkers
        val markerTop = topPaddingPx + yearMarkerEdgePaddingPx
        val markerBottom = bottomPaddingPx + yearMarkerEdgePaddingPx
        val availableTrack = trackHeightPx - markerTop - markerBottom
        if (availableTrack <= 0) return@remember emptyList()

        val result = mutableListOf<YearMarker>()
        var lastOffsetPx = -yearMarkerMinSpacingPx
        for (marker in yearMarkers) {
            val offsetPx = markerTop + availableTrack * marker.fraction
            if (offsetPx - lastOffsetPx >= yearMarkerMinSpacingPx) {
                result.add(marker)
                lastOffsetPx = offsetPx
            }
        }
        result
    }

    // Container for the scrollbar area
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(Dimens.scrollbarTouchTargetWidth + Dimens.scrollbarYearLabelWidth)
            .clipToBounds()
            .onSizeChanged { trackHeightPx = it.height }
            .semantics { contentDescription = thumbDescription }
    ) {
        // Year markers along the right edge — small pill badges
        visibleMarkers.forEach { marker ->
            key(marker.year) {
                val markerOffsetPx = remember(
                    trackHeightPx,
                    marker.fraction,
                    topPaddingPx,
                    bottomPaddingPx,
                    yearMarkerEdgePaddingPx
                ) {
                    val markerTop = topPaddingPx + yearMarkerEdgePaddingPx
                    val markerBottom = bottomPaddingPx + yearMarkerEdgePaddingPx
                    val availableTrack = trackHeightPx - markerTop - markerBottom
                    val maxOffset = (trackHeightPx - markerBottom).coerceAtLeast(markerTop)
                    (markerTop + availableTrack * marker.fraction).coerceIn(markerTop, maxOffset)
                        .toInt()
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, markerOffsetPx) }
                        .padding(end = Dimens.scrollbarYearLabelPadding)
                        .clip(RoundedCornerShape(Dimens.scrollbarYearLabelCornerRadius))
                        .background(yearLabelColor.copy(alpha = 0.12f))
                        .padding(
                            horizontal = Dimens.scrollbarYearLabelPaddingHorizontal,
                            vertical = Dimens.scrollbarYearLabelPaddingVertical
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = marker.year,
                        style = MaterialTheme.typography.labelSmall,
                        color = yearLabelColor.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
        }

        // Circle handle
        Surface(
            shape = CircleShape,
            color = handleColor,
            shadowElevation = Dimens.scrollbarHandleElevation,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(x = handleClipOffsetPx, y = handleOffsetPx) }
                .size(Dimens.scrollbarHandleSize)
                .excludeFromSystemGestures()
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    startDragImmediately = true,
                    onDragStarted = {
                        isDragging = true
                        onDragStarted()
                    },
                    onDragStopped = {
                        isDragging = false
                        onDragStopped(dragFraction)
                    }
                )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_scroll_arrows),
                    contentDescription = dragDescription,
                    tint = handleIconColor,
                    modifier = Modifier
                        .size(Dimens.scrollbarHandleIconSize)
                        .offset(x = -(Dimens.scrollbarHandleClipOffset / 4))
                )
            }
        }

        // Date bubble — slides in from right when dragging
        AnimatedVisibility(
            visible = label != null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val bubbleCenterOffset =
                        handleOffsetPx + (handleSizePx / 2).toInt() - bubbleVerticalOffsetPx
                    IntOffset(0, bubbleCenterOffset)
                }
                .padding(
                    end = Dimens.scrollbarHandleSize - Dimens.scrollbarHandleClipOffset + Dimens.scrollbarBubbleGap + Dimens.scrollbarBubbleVerticalOffset
                ),
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ) + fadeIn(animationSpec = tween(150)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(200)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.scrollbarBubbleCornerRadius))
                    .background(bubbleBackground)
                    .padding(
                        horizontal = Dimens.scrollbarBubblePadding,
                        vertical = Dimens.smallSpacing
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = bubbleTextColor,
                    maxLines = 1
                )
            }
        }
    }
}

// --- Scroll fraction helpers ---

private fun LazyListLayoutInfo.scrollFraction(estimatedItemCount: Int?): Float {
    val total = estimatedItemCount ?: totalItemsCount
    if (total == 0) return 0f
    val firstVisible = visibleItemsInfo.firstOrNull() ?: return 0f
    val itemFraction = firstVisible.index.toFloat() / total
    val pixelFraction = if (firstVisible.size > 0) {
        -firstVisible.offset.toFloat() / firstVisible.size / total
    } else 0f
    return (itemFraction + pixelFraction).coerceIn(0f, 1f)
}

private fun LazyGridLayoutInfo.scrollFraction(): Float {
    if (totalItemsCount == 0) return 0f
    val firstVisible = visibleItemsInfo.firstOrNull() ?: return 0f
    val itemFraction = firstVisible.index.toFloat() / totalItemsCount
    val pixelFraction = if (firstVisible.size.height > 0) {
        -firstVisible.offset.y.toFloat() / firstVisible.size.height / totalItemsCount
    } else 0f
    return (itemFraction + pixelFraction).coerceIn(0f, 1f)
}

private fun LazyStaggeredGridLayoutInfo.scrollFraction(): Float {
    if (totalItemsCount == 0) return 0f
    val firstVisible = visibleItemsInfo.firstOrNull() ?: return 0f
    val itemFraction = firstVisible.index.toFloat() / totalItemsCount
    val pixelFraction = if (firstVisible.size.height > 0) {
        -firstVisible.offset.y.toFloat() / firstVisible.size.height / totalItemsCount
    } else 0f
    return (itemFraction + pixelFraction).coerceIn(0f, 1f)
}

private fun dragFractionToIndex(
    fraction: Float,
    totalItems: Int
): Int {
    return (fraction * totalItems).toInt().coerceIn(0, (totalItems - 1).coerceAtLeast(0))
}

// --- Preview ---

@Preview
@Composable
private fun ScrollbarOverlayPreview() {
    val listState = rememberLazyListState()
    ScrollbarOverlay(
        listState = listState,
        topPadding = 0.dp,
        bottomPadding = 0.dp,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(state = listState) {
            items(100) { index ->
                Text(
                    text = "Item $index",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.screenPadding)
                )
            }
        }
    }
}
