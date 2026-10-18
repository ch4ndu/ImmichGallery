package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.buildTimelineDisplayIndex
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals

class TimelineEstimateReplacementUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var items by mutableStateOf<List<TimelineDisplayItem>>(emptyList())
    private val listState = LazyListState()

    @Test
    fun shrinkingClippedEstimateKeepsVisibleBandAtSamePixelOffset() {
        items = listOf(header("h_0"), estimate("est_a", 900f, 0, 30), band("mr_0_0_4", 0, 4, 260f), band("mr_0_4_4", 4, 4, 300f)) +
                filler()
        setContent()
        scrollTo(index = 1, offset = 2200)
        val bandBefore = offsetOf("mr_0_0_4")

        items = listOf(header("h_0"), estimate("est_b", 410.37f, 0, 30), band("mr_0_0_4", 0, 4, 260f), band("mr_0_4_4", 4, 4, 300f)) +
                filler()
        compose.waitForIdle()

        assertEquals(bandBefore, offsetOf("mr_0_0_4"))
    }

    @Test
    fun estimateOnlyViewportKeepsDistanceFromBucketHeaderWhenBandsArrive() {
        val estimates = (0 until 6).map { estimate("pl_$it", 600f, it * 10, 10) }
        val bands = (0 until 9).map { band("mr_1_${it * 7}", it * 7, 7, 377.31f + it * 9.7f) }
        items = listOf(header("h_0")) + estimates + listOf(header("h_1", bucket = 1))
        setContent()
        // The test window is 768 px tall; stay within the three-viewport cap so the header anchor is used.
        scrollTo(index = 2, offset = 120)
        val before = compose.runOnIdle { headerDistancePx(items) }

        items = listOf(header("h_0")) + bands + listOf(header("h_1", bucket = 1))
        compose.waitForIdle()

        assertEquals(before, compose.runOnIdle { headerDistancePx(items) })
    }

    private fun setContent() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(DENSITY)) {
                val reconciler = remember { TimelineScrollAnchorReconciler() }
                val displayIndex = remember(items) {
                    buildTimelineDisplayIndex(items).copy(bucketIdentityByIndex = listOf("b0#1#MONTH#4", "b1#1#MONTH#4"))
                }
                Box(Modifier.height(700.dp)) {
                    TimelineLazyList(
                        state = listState,
                        displayItems = items,
                        sectionLabelsByKey = emptyMap(),
                        statusBarPadding = 0.dp,
                        navBarPadding = 0.dp,
                        transitionAssetId = null,
                        hiddenAssetId = null,
                        onPhotoClick = {},
                        onRetryBucket = {},
                        sharedTransitionScope = null
                    )
                    TimelineScrollEffects(
                        listState = listState,
                        displayIndex = displayIndex,
                        scrollAnchorReconciler = reconciler,
                        onVisibleBucketsChanged = { _, _ -> },
                        onScrollInProgressChanged = {}
                    )
                }
            }
        }
    }

    private fun scrollTo(index: Int, offset: Int) {
        compose.runOnIdle { runBlocking { listState.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }

    private fun offsetOf(key: String): Int = compose.runOnIdle {
        listState.layoutInfo.visibleItemsInfo.first { it.key == key }.offset
    }

    // Distance from the offscreen bucket header's top to the viewport top, using LazyColumn's per-item rounding.
    private fun headerDistancePx(current: List<TimelineDisplayItem>): Int {
        val first = listState.layoutInfo.visibleItemsInfo.first()
        val spacingPx = (2 * DENSITY).roundToInt()
        val between = (0 until first.index).sumOf { index ->
            (heightDp(current[index]) * DENSITY).roundToInt() + spacingPx
        }
        return between - first.offset
    }

    private fun heightDp(item: TimelineDisplayItem): Float = when (item) {
        is HeaderItem -> 48f
        is PlaceholderItem -> item.estimatedHeight
        is MosaicBandItem -> item.bandHeight
        else -> 0f
    }

    private fun filler() = (0 until 8).map { band("mr_0_filler_$it", 100 + it * 4, 4, 280f) }

    private fun header(key: String, bucket: Int = 0) = HeaderItem(key, bucket, "b$bucket|month", key)

    private fun estimate(key: String, height: Float, start: Int, count: Int) = PlaceholderItem(
        gridKey = key,
        bucketIndex = 0,
        sectionLabel = "b0|month",
        estimatedHeight = height,
        sourceStartIndex = start,
        sourceCount = count,
        sourceIsBucketOrdinal = true
    )

    private fun band(key: String, start: Int, count: Int, height: Float) = MosaicBandItem(
        gridKey = key,
        bucketIndex = 0,
        sectionLabel = "b0|month",
        tiles = emptyList(),
        bandHeight = height,
        sourceStartIndex = start,
        sourceCount = count
    )

    private companion object {
        const val DENSITY = 2.75f
    }
}
