package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.scrollbar_thumb

private const val SCROLL_FRACTION_STEP = 0.005f
private const val BUBBLE_ALPHA = 0.9f
private const val AUTO_HIDE_DELAY_MS = 1500L
private const val MIN_ITEMS_FOR_SCROLLBAR = 10

@Composable
fun ScrollbarOverlay(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    topPadding: Dp,
    bottomPadding: Dp,
    estimatedItemCount: Int? = null,
    scrollFractionProvider: (() -> Float)? = null,
    onScrollToFraction: ((Float) -> Unit)? = null,
    labelProvider: ((Float) -> String?)? = null,
    content: @Composable () -> Unit
) {
    val scrollFraction by remember {
        derivedStateOf {
            val raw = scrollFractionProvider?.invoke()
                ?: listState.layoutInfo.scrollFraction(estimatedItemCount)
            (raw / SCROLL_FRACTION_STEP).toInt() * SCROLL_FRACTION_STEP
        }
    }

    val totalItems = estimatedItemCount ?: listState.layoutInfo.totalItemsCount
    val showScrollbar = totalItems >= MIN_ITEMS_FOR_SCROLLBAR

    val coroutineScope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    ScrollbarLayout(
        scrollFraction = scrollFraction,
        isScrollInProgress = listState.isScrollInProgress,
        showScrollbar = showScrollbar,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        labelProvider = labelProvider,
        onDragFraction = onScrollToFraction ?: { fraction ->
            val targetIndex = dragFractionToIndex(fraction, totalItems)
            scrollJob?.cancel()
            scrollJob = coroutineScope.launch { listState.scrollToItem(targetIndex) }
            Unit
        },
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
    content: @Composable () -> Unit
) {
    val scrollFraction by remember {
        derivedStateOf {
            val fraction = gridState.layoutInfo.scrollFraction()
            (fraction / SCROLL_FRACTION_STEP).toInt() * SCROLL_FRACTION_STEP
        }
    }

    val totalItems = gridState.layoutInfo.totalItemsCount
    val showScrollbar = totalItems >= MIN_ITEMS_FOR_SCROLLBAR

    val coroutineScope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    ScrollbarLayout(
        scrollFraction = scrollFraction,
        isScrollInProgress = gridState.isScrollInProgress,
        showScrollbar = showScrollbar,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        labelProvider = labelProvider,
        onDragFraction = { fraction ->
            val targetIndex = dragFractionToIndex(fraction, totalItems)
            scrollJob?.cancel()
            scrollJob = coroutineScope.launch { gridState.scrollToItem(targetIndex) }
        },
        modifier = modifier,
        content = content
    )
}

@Composable
private fun ScrollbarLayout(
    scrollFraction: Float,
    isScrollInProgress: Boolean,
    showScrollbar: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    labelProvider: ((Float) -> String?)?,
    onDragFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    // Show when scrolling, hide after delay when stopped
    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            isVisible = true
        } else {
            delay(AUTO_HIDE_DELAY_MS)
            isVisible = false
        }
    }

    val thumbVisible = isVisible && showScrollbar

    Box(modifier = modifier) {
        content()

        if (showScrollbar) {
            ScrollbarThumb(
                scrollFraction = scrollFraction,
                thumbVisible = thumbVisible,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
                labelProvider = labelProvider,
                onDragFraction = onDragFraction,
                onDragStarted = { isDragging = true },
                onDragStopped = {
                    isDragging = false
                    isVisible = true
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

private val BubbleShape = RoundedCornerShape(50)

@Composable
private fun ScrollbarThumb(
    scrollFraction: Float,
    thumbVisible: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    labelProvider: ((Float) -> String?)?,
    onDragFraction: (Float) -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val thumbDescription = stringResource(Res.string.scrollbar_thumb)
    var trackHeightPx by remember { mutableStateOf(0) }
    val handleHeightPx = with(density) { Dimens.scrollbarHandleHeight.toPx() }
    val topPaddingPx = with(density) { topPadding.toPx() }
    val bottomPaddingPx = with(density) { bottomPadding.toPx() }

    // Track drag fraction independently so it updates immediately during drag
    var dragFraction by remember { mutableFloatStateOf(scrollFraction) }
    var isDragging by remember { mutableStateOf(false) }

    // Sync with scroll fraction when NOT dragging
    LaunchedEffect(scrollFraction, isDragging) {
        if (!isDragging) {
            dragFraction = scrollFraction
        }
    }

    val currentFraction = if (isDragging) dragFraction else scrollFraction

    val thumbOffsetPx = remember(trackHeightPx, currentFraction, handleHeightPx, topPaddingPx, bottomPaddingPx) {
        val availableTrack = trackHeightPx - handleHeightPx - topPaddingPx - bottomPaddingPx
        (topPaddingPx + availableTrack * currentFraction).coerceAtLeast(topPaddingPx).toInt()
    }

    val label = if (isDragging && labelProvider != null) {
        labelProvider(currentFraction)
    } else null

    val draggableState = rememberDraggableState { delta ->
        val availableTrack = trackHeightPx - handleHeightPx - topPaddingPx - bottomPaddingPx
        if (availableTrack > 0) {
            val currentOffset = availableTrack * dragFraction
            val newOffset = (currentOffset + delta).coerceIn(0f, availableTrack)
            dragFraction = newOffset / availableTrack
            onDragFraction(dragFraction)
        }
    }

    // Touch target fills full height — topPadding is handled via offset math
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(Dimens.scrollbarTouchTargetWidth)
            .padding(end = Dimens.scrollbarEndPadding)
            .onSizeChanged { trackHeightPx = it.height }
            .then(
                if (thumbVisible) {
                    Modifier.draggable(
                        state = draggableState,
                        orientation = Orientation.Vertical,
                        startDragImmediately = true,
                        onDragStarted = {
                            isDragging = true
                            onDragStarted()
                        },
                        onDragStopped = {
                            isDragging = false
                            onDragStopped()
                        }
                    )
                } else {
                    Modifier
                }
            )
            .semantics { contentDescription = thumbDescription },
        contentAlignment = Alignment.TopEnd
    ) {
        // Only the visual thumb fades in/out — the draggable area stays composed
        AnimatedVisibility(
            visible = thumbVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .offset { IntOffset(0, thumbOffsetPx) }
                    .height(Dimens.scrollbarBubbleHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Label bubble — expands horizontally when dragging with a label
                AnimatedVisibility(
                    visible = label != null,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(BubbleShape)
                            .background(
                                MaterialTheme.colorScheme.inverseSurface.copy(alpha = BUBBLE_ALPHA)
                            )
                            .padding(
                                horizontal = Dimens.scrollbarBubblePadding,
                                vertical = Dimens.smallSpacing
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            maxLines = 1
                        )
                    }
                }

                // Teardrop handle — always visible when thumb is shown
                Box(
                    modifier = Modifier
                        .width(Dimens.scrollbarHandleWidth)
                        .height(Dimens.scrollbarHandleHeight)
                        .clip(RoundedCornerShape(Dimens.scrollbarHandleWidth / 2))
                        .background(Color(0xFFFF5F68))
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

private fun dragFractionToIndex(fraction: Float, totalItems: Int): Int {
    return (fraction * totalItems).toInt().coerceIn(0, (totalItems - 1).coerceAtLeast(0))
}

// --- Preview ---

@Preview
@Composable
private fun ScrollbarOverlayPreview() {
    val listState = rememberLazyListState()
    ScrollbarOverlay(listState = listState, topPadding = 0.dp, bottomPadding = 0.dp, modifier = Modifier.fillMaxSize()) {
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
