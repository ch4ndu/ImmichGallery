package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class TimelineScrollTarget(
    val bucketIndex: Int,
    val displayIndex: Int
)

@Immutable
data class TimelineDisplayIndex(
    val bucketByDisplayIndex: List<Int> = emptyList(),
    val displayIndexesByBucket: Map<Int, List<Int>> = emptyMap()
)

fun buildTimelineDisplayIndex(displayItems: List<TimelineDisplayItem>): TimelineDisplayIndex {
    return buildPhotoGridDisplayIndex(displayItems)
}

fun buildPhotoGridDisplayIndex(displayItems: List<PhotoGridDisplayItem>): TimelineDisplayIndex {
    if (displayItems.isEmpty()) return TimelineDisplayIndex()
    val bucketByDisplayIndex = ArrayList<Int>(displayItems.size)
    val mutableDisplayIndexesByBucket = linkedMapOf<Int, MutableList<Int>>()
    displayItems.forEachIndexed { index, item ->
        bucketByDisplayIndex.add(item.bucketIndex)
        mutableDisplayIndexesByBucket.getOrPut(item.bucketIndex) { mutableListOf() }.add(index)
    }
    return TimelineDisplayIndex(
        bucketByDisplayIndex = bucketByDisplayIndex,
        displayIndexesByBucket = mutableDisplayIndexesByBucket
    )
}

fun visibleBucketIndexesForDisplayIndexes(
    displayItems: List<TimelineDisplayItem>,
    visibleItemIndexes: List<Int>
): List<Int> =
    visibleItemIndexes
        .mapNotNull { index -> displayItems.getOrNull(index)?.bucketIndex }
        .distinct()

fun visibleBucketIndexesForDisplayIndexes(
    displayIndex: TimelineDisplayIndex,
    visibleItemIndexes: List<Int>
): List<Int> =
    visibleItemIndexes
        .mapNotNull { index -> displayIndex.bucketByDisplayIndex.getOrNull(index) }
        .distinct()

fun timelineScrollTargetForFraction(
    pageIndex: TimelinePageIndex,
    displayItems: List<TimelineDisplayItem>,
    fraction: Float
): TimelineScrollTarget? {
    if (pageIndex.totalPages <= 0 || displayItems.isEmpty()) return null
    val page = pageForTimelineFraction(pageIndex, fraction) ?: return null
    val bucketIndex = pageIndex.pageToBucketIndex(page) ?: return null
    val bucketStart = pageIndex.bucketStartPages.getOrElse(bucketIndex) { 0 }
    val bucketCount = pageIndex.bucketPageCounts.getOrElse(bucketIndex) { 0 }.coerceAtLeast(1)
    val bucketFraction = ((page - bucketStart).toFloat() / bucketCount).coerceIn(0f, 1f)
    val displayIndex = estimatedDisplayIndexForBucketFraction(displayItems, bucketIndex, bucketFraction)
        ?: return null
    return TimelineScrollTarget(bucketIndex, displayIndex)
}

fun timelineScrollTargetForFraction(
    pageIndex: TimelinePageIndex,
    displayIndex: TimelineDisplayIndex,
    fraction: Float
): TimelineScrollTarget? {
    if (pageIndex.totalPages <= 0 || displayIndex.bucketByDisplayIndex.isEmpty()) return null
    val page = pageForTimelineFraction(pageIndex, fraction) ?: return null
    val bucketIndex = pageIndex.pageToBucketIndex(page) ?: return null
    val bucketStart = pageIndex.bucketStartPages.getOrElse(bucketIndex) { 0 }
    val bucketCount = pageIndex.bucketPageCounts.getOrElse(bucketIndex) { 0 }.coerceAtLeast(1)
    val bucketFraction = ((page - bucketStart).toFloat() / bucketCount).coerceIn(0f, 1f)
    val targetDisplayIndex = estimatedDisplayIndexForBucketFraction(displayIndex, bucketIndex, bucketFraction)
        ?: return null
    return TimelineScrollTarget(bucketIndex, targetDisplayIndex)
}

fun bucketIndexForTimelineFraction(
    pageIndex: TimelinePageIndex,
    fraction: Float
): Int? =
    pageForTimelineFraction(pageIndex, fraction)
        ?.let(pageIndex::pageToBucketIndex)

fun timelineScrollFractionForDisplayIndex(
    pageIndex: TimelinePageIndex,
    displayItems: List<TimelineDisplayItem>,
    displayIndex: Int
): Float? {
    if (pageIndex.totalPages <= 0) return null
    val item = displayItems.getOrNull(displayIndex) ?: return null
    val bucketIndex = item.bucketIndex
    val bucketStart = pageIndex.bucketStartPages.getOrNull(bucketIndex) ?: return null
    val bucketCount = pageIndex.bucketPageCounts.getOrNull(bucketIndex)?.coerceAtLeast(1) ?: return null
    val bucketDisplayIndexes = displayItems.indices
        .filter { index -> displayItems[index].bucketIndex == bucketIndex }
    if (bucketDisplayIndexes.isEmpty()) return null
    val bucketDisplayOffset = bucketDisplayIndexes.indexOf(displayIndex)
        .takeIf { it >= 0 }
        ?: return null
    val bucketFraction = bucketDisplayOffset.toFloat() / bucketDisplayIndexes.size
    val page = bucketStart + bucketCount * bucketFraction
    return (page / pageIndex.totalPages.toFloat()).coerceIn(0f, 1f)
}

fun timelineScrollFractionForDisplayIndex(
    pageIndex: TimelinePageIndex,
    timelineDisplayIndex: TimelineDisplayIndex,
    displayIndex: Int
): Float? {
    if (pageIndex.totalPages <= 0) return null
    val bucketIndex = timelineDisplayIndex.bucketByDisplayIndex.getOrNull(displayIndex) ?: return null
    val bucketStart = pageIndex.bucketStartPages.getOrNull(bucketIndex) ?: return null
    val bucketCount = pageIndex.bucketPageCounts.getOrNull(bucketIndex)?.coerceAtLeast(1) ?: return null
    val bucketDisplayIndexes = timelineDisplayIndex.displayIndexesByBucket[bucketIndex].orEmpty()
    if (bucketDisplayIndexes.isEmpty()) return null
    val bucketDisplayOffset = bucketDisplayIndexes.indexOf(displayIndex)
        .takeIf { it >= 0 }
        ?: return null
    val bucketFraction = bucketDisplayOffset.toFloat() / bucketDisplayIndexes.size
    val page = bucketStart + bucketCount * bucketFraction
    return (page / pageIndex.totalPages.toFloat()).coerceIn(0f, 1f)
}

private fun pageForTimelineFraction(
    pageIndex: TimelinePageIndex,
    fraction: Float
): Int? {
    if (pageIndex.totalPages <= 0) return null
    return (fraction.coerceIn(0f, 1f) * pageIndex.totalPages)
        .toInt()
        .coerceIn(0, pageIndex.totalPages - 1)
}

fun estimatedDisplayIndexForBucketFraction(
    displayItems: List<TimelineDisplayItem>,
    bucketIndex: Int,
    bucketFraction: Float
): Int? {
    val indexes = displayItems.indices
        .filter { index -> displayItems[index].bucketIndex == bucketIndex }
    if (indexes.isEmpty()) return null
    val targetOffset = (bucketFraction.coerceIn(0f, 1f) * indexes.size)
        .toInt()
        .coerceIn(0, indexes.lastIndex)
    return indexes[targetOffset]
}

fun estimatedDisplayIndexForBucketFraction(
    displayIndex: TimelineDisplayIndex,
    bucketIndex: Int,
    bucketFraction: Float
): Int? {
    val indexes = displayIndex.displayIndexesByBucket[bucketIndex].orEmpty()
    if (indexes.isEmpty()) return null
    val targetOffset = (bucketFraction.coerceIn(0f, 1f) * indexes.size)
        .toInt()
        .coerceIn(0, indexes.lastIndex)
    return indexes[targetOffset]
}

fun prioritizedTimelineBucketIndexes(
    bucketCount: Int,
    targetBucketIndex: Int?,
    visibleBucketIndexes: List<Int>,
    nearbyRadius: Int
): List<Int> {
    if (bucketCount <= 0) return emptyList()
    val ordered = LinkedHashSet<Int>()

    fun addIfValid(index: Int) {
        if (index in 0 until bucketCount) ordered.add(index)
    }

    targetBucketIndex?.let(::addIfValid)
    visibleBucketIndexes.forEach(::addIfValid)

    val seeds = buildList {
        targetBucketIndex?.let { add(it) }
        addAll(visibleBucketIndexes)
    }
    for (seed in seeds) {
        for (offset in -nearbyRadius..nearbyRadius) {
            addIfValid(seed + offset)
        }
    }

    for (index in 0 until bucketCount) {
        ordered.add(index)
    }
    return ordered.toList()
}
