package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.GeometryAlignedRange
import com.udnahc.immichgallery.domain.model.GeometryAlignedSection
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketSkeleton
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class TimelineSkeletonReplacementUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacingSkeletonPlaceholdersWithBandsKeepsViewportPixelStable() {
        val skeletons = (0 until BUCKETS).map(::skeleton)
        var items by mutableStateOf(displayItems(skeletons, readyBuckets = emptySet()))
        val listState = LazyListState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.75f)) {
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
                }
            }
        }
        compose.runOnIdle { runBlocking { listState.scrollToItem(index = 40, scrollOffset = 157) } }
        compose.waitForIdle()
        val before = compose.runOnIdle { visibleSnapshot(listState) }

        // Buckets above, at, and below the viewport become ready in one publication.
        items = displayItems(skeletons, readyBuckets = setOf(0, 1, 2, 3))
        compose.waitForIdle()

        assertEquals(before, compose.runOnIdle { visibleSnapshot(listState) })
    }

    private fun visibleSnapshot(state: LazyListState): List<Triple<Any, Int, Int>> =
        state.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) }

    private fun displayItems(
        skeletons: List<TimelineMosaicBucketSkeleton>,
        readyBuckets: Set<Int>
    ): List<TimelineDisplayItem> = skeletons.flatMapIndexed { bucketIndex, skeleton ->
        val timeBucket = skeleton.timeBucket
        listOf(HeaderItem("h_$timeBucket", bucketIndex, "$timeBucket|month", timeBucket)) +
                if (bucketIndex in readyBuckets) {
                    skeleton.sections.flatMap { section ->
                        timelineGeometryAlignedItems(
                            timeBucket = timeBucket,
                            sectionKey = section.sectionKey,
                            bucketIndex = bucketIndex,
                            sectionLabel = section.sectionKey,
                            aligned = GeometryAlignedSection(
                                section.ranges.map { range -> GeometryAlignedRange(range, band(bucketIndex, range)) }
                            )
                        ).let { sectionItems ->
                            listOf(
                                HeaderItem(
                                    timelineDayHeaderGridKey(timeBucket, section.sectionKey),
                                    bucketIndex,
                                    section.sectionKey,
                                    section.sectionKey
                                )
                            ) + sectionItems
                        }
                    }
                } else {
                    timelineSkeletonBucketItems(timeBucket, bucketIndex, TimelineGroupSize.DAY, skeleton)
                }
    }

    private fun band(bucketIndex: Int, range: MosaicSectionGeometryRange) = MosaicBandItem(
        gridKey = "engine",
        bucketIndex = bucketIndex,
        sectionLabel = "s",
        tiles = emptyList(),
        bandHeight = range.height,
        sourceStartIndex = range.sourceStartIndex,
        sourceCount = range.sourceCount
    )

    private fun skeleton(bucketIndex: Int): TimelineMosaicBucketSkeleton {
        val timeBucket = "2026-${(12 - bucketIndex).toString().padStart(2, '0')}"
        val sections = (0 until 3).map { day ->
            val ranges = (0 until 6).map { band ->
                // Fractional heights exercise per-item px rounding at a non-integer density.
                MosaicSectionGeometryRange(band * 4, 4, 97.37f + band * 13.11f + day * 3.3f)
            }
            TimelineMosaicGeometrySummary(
                timeBucket = timeBucket,
                sectionKey = "$timeBucket|day|$timeBucket-${(28 - day).toString().padStart(2, '0')}",
                placeholderHeight = 0f,
                displayItemCount = ranges.size,
                ranges = ranges
            )
        }
        return TimelineMosaicBucketSkeleton(timeBucket, assetCount = 72, placeholderHeight = 0f, sections = sections)
    }

    private companion object {
        const val BUCKETS = 6
    }
}
